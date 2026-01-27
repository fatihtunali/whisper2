import Foundation
import AVFoundation
import CoreMedia
import Combine
import WebRTC
import CallKit

/// Call state
enum CallState: Equatable {
    case idle
    case initiating
    case ringing
    case connecting
    case connected
    case reconnecting
    case ended(reason: CallEndReason)
}

/// Active call information
struct ActiveCall {
    let callId: String
    let peerId: String
    let peerName: String?
    let isVideo: Bool
    let isOutgoing: Bool
    var state: CallState
    var startTime: Date?
    var isMuted: Bool = false
    var isSpeakerOn: Bool = false
    var isLocalVideoEnabled: Bool = true
    var isRemoteVideoEnabled: Bool = true
}

/// Service for managing WebRTC calls - CallKit handles all UI
/// This service ONLY manages the WebRTC connection, not any UI state
final class CallService: NSObject, ObservableObject {
    static let shared = CallService()

    // Internal call state (not for UI - CallKit handles UI)
    private var activeCallId: String?
    private var activeCallPeerId: String?
    private var activeCallIsVideo: Bool = false
    private var activeCallIsOutgoing: Bool = false

    // TURN credentials
    private var turnCredentials: TurnCredentialsPayload?

    // Call history (for records only)
    @Published private(set) var callHistory: [CallRecord] = []

    // Store incoming call payload for CallKit answer flow
    private var pendingIncomingCall: CallIncomingPayload?

    // Store outgoing call info for CallKit start flow
    private var pendingOutgoingCall: (peerId: String, isVideo: Bool, callId: String)?

    // WebRTC components
    private var peerConnectionFactory: RTCPeerConnectionFactory?
    private var peerConnection: RTCPeerConnection?
    private var localAudioTrack: RTCAudioTrack?
    private var localVideoTrack: RTCVideoTrack?
    private var remoteAudioTrack: RTCAudioTrack?
    private var remoteVideoTrack: RTCVideoTrack?
    private var videoCapturer: RTCCameraVideoCapturer?

    // Video renderers (set by UI)
    var localVideoRenderer: RTCVideoRenderer?
    var remoteVideoRenderer: RTCVideoRenderer?

    // Services
    private let ws = WebSocketService.shared
    private let auth = AuthService.shared
    private let contacts = ContactsService.shared
    private let crypto = CryptoService.shared
    private let keychain = KeychainService.shared
    private var cancellables = Set<AnyCancellable>()

    // Pending ICE candidates (received before remote description set)
    private var pendingIceCandidates: [RTCIceCandidate] = []

    // CallKit manager
    private(set) var callKitManager: CallKitManager?

    // Audio session state - tracks if CallKit has activated before tracks are ready
    private var isAudioSessionActivated = false

    // Storage
    private let callHistoryStorageKey = "whisper2.call.history"
    private var currentCallStartTime: Date?

    private override init() {
        super.init()
        loadCallHistory()
        setupWebRTC()
        setupMessageHandler()
        setupCallKit()

        // Clean up any stale CallKit state from previous session
        print("CallService init: Cleaning up stale CallKit state")
        callKitManager?.endAllCalls()
    }

    // MARK: - Setup

    private func setupWebRTC() {
        RTCInitializeSSL()

        // Configure RTCAudioSession for CallKit integration
        // CRITICAL: Must set these BEFORE creating any peer connections
        let rtcAudioSession = RTCAudioSession.sharedInstance()
        rtcAudioSession.lockForConfiguration()
        do {
            // Tell RTCAudioSession to use manual audio management (required for CallKit)
            rtcAudioSession.useManualAudio = true
            rtcAudioSession.isAudioEnabled = false  // Will enable when CallKit activates

            // Configure audio session category for voice chat
            // This is set once at startup, CallKit will activate/deactivate as needed
            try rtcAudioSession.setCategory(.playAndRecord,
                                             with: [.allowBluetooth, .allowBluetoothA2DP])
            try rtcAudioSession.setMode(.voiceChat)
            print("RTCAudioSession configured for CallKit: useManualAudio=true, category=playAndRecord, mode=voiceChat")
        } catch {
            print("Failed to configure RTCAudioSession: \(error)")
        }
        rtcAudioSession.unlockForConfiguration()

        let encoderFactory = RTCDefaultVideoEncoderFactory()
        let decoderFactory = RTCDefaultVideoDecoderFactory()

        peerConnectionFactory = RTCPeerConnectionFactory(
            encoderFactory: encoderFactory,
            decoderFactory: decoderFactory
        )
    }

    private func setupCallKit() {
        callKitManager = CallKitManager()
        callKitManager?.delegate = self
        setupVoIPCallback()
    }

    /// Setup callback to receive VoIP push incoming calls
    private func setupVoIPCallback() {
        PushNotificationService.shared.onIncomingCall = { [weak self] payload in
            self?.handleVoIPIncomingCall(payload)
        }
    }

    /// Handle incoming call from VoIP push (when app was in background/killed)
    private func handleVoIPIncomingCall(_ payload: CallIncomingPayload) {
        print("=== VoIP Push: Incoming call ===")
        print("  callId: \(payload.callId)")
        print("  from: \(payload.from)")
        print("  isVideo: \(payload.isVideo)")
        print("  ciphertext length: \(payload.ciphertext.count)")

        // Store pending incoming call for CallKit answer flow
        pendingIncomingCall = payload

        // Store internal state
        activeCallId = payload.callId
        activeCallPeerId = payload.from
        activeCallIsVideo = payload.isVideo
        activeCallIsOutgoing = false

        // Report to CallKit - this MUST be called immediately for VoIP push
        // CallKit will show the native incoming call UI
        let contact = contacts.getContact(whisperId: payload.from)
        let displayName = contact?.displayName ?? payload.from

        print("VoIP Push: Reporting to CallKit with displayName: \(displayName)")
        callKitManager?.reportIncomingCall(
            callId: payload.callId,
            handle: payload.from,
            displayName: displayName,
            hasVideo: payload.isVideo
        )
        print("=== VoIP Push: reportIncomingCall called ===")
    }

    private func setupMessageHandler() {
        ws.messagePublisher
            .sink { [weak self] data in
                self?.handleMessage(data)
            }
            .store(in: &cancellables)
    }

    // MARK: - TURN Credentials

    func fetchTurnCredentials() async throws {
        guard let sessionToken = auth.currentUser?.sessionToken else {
            throw NetworkError.connectionFailed
        }

        let payload = GetTurnCredentialsPayload(sessionToken: sessionToken)
        let frame = WsFrame(type: Constants.MessageType.getTurnCredentials, payload: payload)
        try await ws.send(frame)
    }

    // MARK: - Initiate Call

    /// Request to start an outgoing call - CallKit handles ALL UI
    func initiateCall(to peerId: String, isVideo: Bool) async throws {
        print("=== initiateCall BEGIN - isVideo: \(isVideo) ===")

        guard auth.currentUser?.sessionToken != nil else {
            print("ERROR: No session token")
            throw NetworkError.connectionFailed
        }

        guard contacts.getPublicKey(for: peerId) != nil else {
            print("ERROR: No public key for \(peerId)")
            throw CryptoError.invalidPublicKey
        }

        // Clean up any previous call state first
        if activeCallId != nil {
            print("Cleaning up previous call state before starting new call...")
            cleanupPreviousCall()
        }

        // Request microphone permission first
        print("Requesting microphone permission...")
        let micPermission = await requestMicrophonePermission()
        guard micPermission else {
            print("ERROR: Microphone permission denied")
            throw NetworkError.serverError(code: "PERMISSION_DENIED", message: "Microphone permission required for calls")
        }
        print("Microphone permission granted")

        // Request camera permission for video calls
        if isVideo {
            print("Requesting camera permission...")
            let cameraPermission = await requestCameraPermission()
            guard cameraPermission else {
                print("ERROR: Camera permission denied")
                throw NetworkError.serverError(code: "PERMISSION_DENIED", message: "Camera permission required for video calls")
            }
            print("Camera permission granted")
        }

        // Generate callId
        let callId = UUID().uuidString.lowercased()
        print("Generated callId: \(callId)")

        // Store pending outgoing call info (WebRTC setup happens after CallKit confirms)
        pendingOutgoingCall = (peerId: peerId, isVideo: isVideo, callId: callId)

        // Store internal state
        activeCallId = callId
        activeCallPeerId = peerId
        activeCallIsVideo = isVideo
        activeCallIsOutgoing = true

        // Get display name for UI
        let contact = contacts.getContact(whisperId: peerId)
        let displayName = contact?.displayName ?? peerId

        // Show outgoing call UI (CallKit only provides UI for incoming calls)
        await MainActor.run {
            OutgoingCallState.shared.showOutgoingCall(peerName: displayName, peerId: peerId, isVideo: isVideo)
        }

        // Request CallKit to start the call (for system integration, not UI)
        // WebRTC connection starts only after CallKit confirms via delegate
        callKitManager?.startOutgoingCall(callId: callId, handle: peerId, displayName: displayName, hasVideo: isVideo)
    }

    /// Actually start the WebRTC connection (called after CallKit confirms)
    private func startOutgoingCallConnection() async throws {
        print("=== startOutgoingCallConnection BEGIN ===")

        guard let pending = pendingOutgoingCall else {
            print("ERROR: No pending outgoing call")
            throw NetworkError.connectionFailed
        }
        print("Pending call: \(pending.callId) to \(pending.peerId)")

        guard let user = auth.currentUser,
              let sessionToken = user.sessionToken else {
            print("ERROR: No authenticated user or session token")
            throw NetworkError.connectionFailed
        }
        print("User: \(user.whisperId)")

        guard let recipientPublicKey = contacts.getPublicKey(for: pending.peerId) else {
            print("ERROR: No public key for recipient \(pending.peerId)")
            throw CryptoError.invalidPublicKey
        }
        print("Got recipient public key")

        let callId = pending.callId

        // Fetch TURN credentials
        print("Fetching TURN credentials...")
        try await fetchTurnCredentials()

        // Wait for credentials with retry
        var attempts = 0
        while turnCredentials == nil && attempts < 10 {
            try await Task.sleep(nanoseconds: 200_000_000)
            attempts += 1
        }

        if turnCredentials == nil {
            print("Warning: No TURN credentials received, attempting direct connection")
        } else {
            print("Got TURN credentials")
        }

        // Create peer connection and generate offer
        print("Creating peer connection...")
        try await createPeerConnection(isVideo: pending.isVideo)
        print("Creating SDP offer...")
        let sdpOffer = try await createOffer()
        print("SDP offer created, length: \(sdpOffer.count)")

        // Encrypt SDP
        let timestamp = Int64(Date().timeIntervalSince1970 * 1000)
        print("Encrypting SDP...")
        let (ciphertext, nonce) = try crypto.encryptMessage(
            sdpOffer,
            recipientPublicKey: recipientPublicKey,
            senderPrivateKey: user.encPrivateKey
        )
        print("SDP encrypted")

        // Sign
        print("Signing message...")
        let signature = try crypto.signMessage(
            messageType: Constants.MessageType.callInitiate,
            messageId: callId,
            from: user.whisperId,
            to: pending.peerId,
            timestamp: timestamp,
            nonce: nonce,
            ciphertext: ciphertext,
            privateKey: user.signPrivateKey
        )
        print("Message signed")

        // Send initiate to server
        let payload = CallInitiatePayload(
            sessionToken: sessionToken,
            callId: callId,
            from: user.whisperId,
            to: pending.peerId,
            isVideo: pending.isVideo,
            timestamp: timestamp,
            nonce: nonce.base64EncodedString(),
            ciphertext: ciphertext.base64EncodedString(),
            sig: signature.base64EncodedString()
        )

        print("Sending call_initiate to server...")
        let frame = WsFrame(type: Constants.MessageType.callInitiate, payload: payload)
        try await ws.send(frame)
        print("call_initiate SENT to server")

        // Clear pending
        pendingOutgoingCall = nil

        // Report connecting to CallKit
        callKitManager?.reportOutgoingCallStartedConnecting(callId: callId)
        print("=== startOutgoingCallConnection END ===")
    }

    // MARK: - Answer Call

    func answerCall(_ incomingPayload: CallIncomingPayload) async throws {
        print("=== answerCall BEGIN ===")
        print("Call from: \(incomingPayload.from)")
        print("Call ID: \(incomingPayload.callId)")
        print("Is Video: \(incomingPayload.isVideo)")

        guard let user = auth.currentUser,
              let sessionToken = user.sessionToken else {
            print("ERROR: No authenticated user or session token")
            throw NetworkError.connectionFailed
        }
        print("User authenticated: \(user.whisperId)")

        guard let senderPublicKey = contacts.getPublicKey(for: incomingPayload.from) else {
            print("ERROR: No public key for caller \(incomingPayload.from)")
            print("Available contacts: \(Array(contacts.contacts.keys))")
            // Add as contact temporarily for the call
            throw CryptoError.invalidPublicKey
        }
        print("Got caller's public key")

        // Fetch TURN if needed
        if turnCredentials == nil {
            try await fetchTurnCredentials()
            try await Task.sleep(nanoseconds: 500_000_000)
        }

        // Decrypt SDP offer
        guard let ciphertextData = Data(base64Encoded: incomingPayload.ciphertext),
              let nonceData = Data(base64Encoded: incomingPayload.nonce) else {
            throw CryptoError.decryptionFailed
        }

        let sdpOffer = try crypto.decryptMessage(
            ciphertext: ciphertextData,
            nonce: nonceData,
            senderPublicKey: senderPublicKey,
            recipientPrivateKey: user.encPrivateKey
        )

        // Create peer connection and set remote description
        try await createPeerConnection(isVideo: incomingPayload.isVideo)

        let remoteDesc = RTCSessionDescription(type: .offer, sdp: sdpOffer)
        try await peerConnection?.setRemoteDescription(remoteDesc)

        // Process pending ICE candidates
        for candidate in pendingIceCandidates {
            try await peerConnection?.add(candidate)
        }
        pendingIceCandidates.removeAll()

        // Create answer
        let sdpAnswer = try await createAnswer()

        // Encrypt answer
        let timestamp = Int64(Date().timeIntervalSince1970 * 1000)
        let (ciphertext, nonce) = try crypto.encryptMessage(
            sdpAnswer,
            recipientPublicKey: senderPublicKey,
            senderPrivateKey: user.encPrivateKey
        )

        // Sign
        let signature = try crypto.signMessage(
            messageType: Constants.MessageType.callAnswer,
            messageId: incomingPayload.callId,
            from: user.whisperId,
            to: incomingPayload.from,
            timestamp: timestamp,
            nonce: nonce,
            ciphertext: ciphertext,
            privateKey: user.signPrivateKey
        )

        // Send answer
        let payload = CallAnswerPayload(
            sessionToken: sessionToken,
            callId: incomingPayload.callId,
            from: user.whisperId,
            to: incomingPayload.from,
            timestamp: timestamp,
            nonce: nonce.base64EncodedString(),
            ciphertext: ciphertext.base64EncodedString(),
            sig: signature.base64EncodedString()
        )

        let frame = WsFrame(type: Constants.MessageType.callAnswer, payload: payload)
        try await ws.send(frame)

        // Update internal state (no UI state - CallKit handles UI)
        activeCallId = incomingPayload.callId
        activeCallPeerId = incomingPayload.from
        activeCallIsVideo = incomingPayload.isVideo
        activeCallIsOutgoing = false
        currentCallStartTime = nil
    }

    // MARK: - End Call

    func endCall(reason: CallEndReason = .ended) async throws {
        // Set the end reason BEFORE cleanup so history is recorded correctly
        lastCallEndReason = reason

        // Always report to CallKit first, even if we can't send to server
        if let callId = activeCallId {
            print("Reporting call end to CallKit: \(callId)")
            callKitManager?.endCall(callId: callId)
        }

        guard let callId = activeCallId,
              let peerId = activeCallPeerId,
              let user = auth.currentUser,
              let sessionToken = user.sessionToken else {
            cleanup()
            return
        }

        guard let recipientPublicKey = contacts.getPublicKey(for: peerId) else {
            cleanup()
            return
        }

        let timestamp = Int64(Date().timeIntervalSince1970 * 1000)
        let message = "end"

        let (ciphertext, nonce) = try crypto.encryptMessage(
            message,
            recipientPublicKey: recipientPublicKey,
            senderPrivateKey: user.encPrivateKey
        )

        let signature = try crypto.signMessage(
            messageType: Constants.MessageType.callEnd,
            messageId: callId,
            from: user.whisperId,
            to: peerId,
            timestamp: timestamp,
            nonce: nonce,
            ciphertext: ciphertext,
            privateKey: user.signPrivateKey
        )

        let payload = CallEndPayload(
            sessionToken: sessionToken,
            callId: callId,
            from: user.whisperId,
            to: peerId,
            timestamp: timestamp,
            nonce: nonce.base64EncodedString(),
            ciphertext: ciphertext.base64EncodedString(),
            sig: signature.base64EncodedString(),
            reason: reason.rawValue
        )

        let frame = WsFrame(type: Constants.MessageType.callEnd, payload: payload)
        try await ws.send(frame)

        // CallKit already notified at start of endCall()
        cleanup()
    }

    // MARK: - Call Controls

    private var isMuted: Bool = false
    private var isSpeakerOn: Bool = false

    func toggleMute() {
        guard let track = localAudioTrack else { return }
        track.isEnabled = !track.isEnabled
        isMuted = !track.isEnabled
        // Sync with CallKit
        if let callId = activeCallId {
            callKitManager?.setMuted(callId: callId, muted: isMuted)
        }
    }

    func toggleSpeaker() {
        let session = AVAudioSession.sharedInstance()
        do {
            if isSpeakerOn {
                try session.overrideOutputAudioPort(.none)
                isSpeakerOn = false
            } else {
                try session.overrideOutputAudioPort(.speaker)
                isSpeakerOn = true
            }
        } catch {
            print("Failed to toggle speaker: \(error)")
        }
    }

    func toggleLocalVideo() {
        guard let track = localVideoTrack else { return }
        track.isEnabled = !track.isEnabled
    }

    func switchCamera() {
        guard let capturer = videoCapturer else {
            print("No video capturer for switching camera")
            return
        }

        // Determine current camera position and switch
        let currentPosition: AVCaptureDevice.Position = capturer.captureSession.inputs
            .compactMap { ($0 as? AVCaptureDeviceInput)?.device }
            .first?.position ?? .front

        let newPosition: AVCaptureDevice.Position = currentPosition == .front ? .back : .front

        guard let device = RTCCameraVideoCapturer.captureDevices().first(where: { $0.position == newPosition }) else {
            print("Could not find camera for position: \(newPosition)")
            return
        }

        // Get supported formats and select a reasonable one
        let formats = RTCCameraVideoCapturer.supportedFormats(for: device)
        guard !formats.isEmpty else {
            print("No formats available for camera")
            return
        }

        // Find a format close to 1280x720 (HD) - prefer non-HDR
        let targetWidth: Int32 = 1280
        let targetHeight: Int32 = 720

        var selectedFormat: AVCaptureDevice.Format?
        var minDiff = Int32.max

        for format in formats {
            let dimensions = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
            let diff = abs(dimensions.width - targetWidth) + abs(dimensions.height - targetHeight)

            // Prefer non-HDR formats
            let mediaSubType = CMFormatDescriptionGetMediaSubType(format.formatDescription)
            let isHDR = mediaSubType == kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange
            let penalty: Int32 = isHDR ? 1000 : 0

            if diff + penalty < minDiff {
                minDiff = diff + penalty
                selectedFormat = format
            }
        }

        guard let format = selectedFormat else {
            print("Could not find suitable format")
            return
        }

        // Get FPS - use 30 fps as target
        let targetFps = 30
        var selectedFps = 15

        for range in format.videoSupportedFrameRateRanges {
            let maxFps = Int(range.maxFrameRate)
            if maxFps >= targetFps {
                selectedFps = min(maxFps, targetFps)
                break
            } else if maxFps > selectedFps {
                selectedFps = maxFps
            }
        }

        let dimensions = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
        print("Switching camera to \(newPosition == .front ? "front" : "back"): \(dimensions.width)x\(dimensions.height) @ \(selectedFps)fps")

        capturer.startCapture(with: device, format: format, fps: selectedFps) { error in
            if let error = error {
                print("ERROR: Failed to switch camera: \(error.localizedDescription)")
            } else {
                print("Camera switched successfully")
            }
        }
    }

    // MARK: - WebRTC Setup

    private func createPeerConnection(isVideo: Bool) async throws {
        guard let factory = peerConnectionFactory else {
            throw NetworkError.connectionFailed
        }

        let config = RTCConfiguration()

        // Setup ICE servers
        var iceServers: [RTCIceServer] = []

        // Always add public STUN servers as fallback
        let stunServer = RTCIceServer(urlStrings: ["stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302"])
        iceServers.append(stunServer)

        // Use TURN credentials if available
        if let turn = turnCredentials {
            for url in turn.urls {
                let server = RTCIceServer(
                    urlStrings: [url],
                    username: turn.username,
                    credential: turn.credential
                )
                iceServers.append(server)
            }
        }

        config.iceServers = iceServers

        config.sdpSemantics = .unifiedPlan
        config.continualGatheringPolicy = .gatherContinually

        let constraints = RTCMediaConstraints(
            mandatoryConstraints: nil,
            optionalConstraints: ["DtlsSrtpKeyAgreement": "true"]
        )

        peerConnection = factory.peerConnection(
            with: config,
            constraints: constraints,
            delegate: self
        )

        // Add audio track
        let audioConstraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        let audioSource = factory.audioSource(with: audioConstraints)
        localAudioTrack = factory.audioTrack(with: audioSource, trackId: "audio0")

        if let audioTrack = localAudioTrack {
            peerConnection?.add(audioTrack, streamIds: ["stream0"])
        }

        // Enable audio if CallKit already activated the session
        enableAudioTracksIfNeeded()

        // Add video track if video call
        if isVideo {
            print("Setting up video track for video call...")
            let videoSource = factory.videoSource()
            localVideoTrack = factory.videoTrack(with: videoSource, trackId: "video0")

            if let videoTrack = localVideoTrack {
                peerConnection?.add(videoTrack, streamIds: ["stream0"])
                print("Video track added to peer connection")

                // Setup camera capturer
                videoCapturer = RTCCameraVideoCapturer(delegate: videoSource)
                print("Video capturer created")

                // Start camera capture on main thread with slight delay to ensure setup is complete
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { [weak self] in
                    self?.startCameraCapture()
                }

                // Connect to legacy local renderer if available
                if let renderer = localVideoRenderer {
                    videoTrack.add(renderer)
                }

                // Note: ActiveVideoCallState renderers will be connected when call connects
                // via showVideoCallScreen() -> connectVideoRenderers()
            } else {
                print("WARNING: Failed to create local video track")
            }
        }

        // Enable video tracks if they exist
        enableVideoTracksIfNeeded()

        // NOTE: Audio session is managed by CallKit
        // CallKit will activate the audio session and notify us via callKitAudioSessionDidActivate
        // We should NOT configure it ourselves when using CallKit
        print("Peer connection created - audio session will be activated by CallKit")
    }

    // MARK: - Permissions

    private func requestMicrophonePermission() async -> Bool {
        if #available(iOS 17.0, *) {
            return await AVAudioApplication.requestRecordPermission()
        } else {
            return await withCheckedContinuation { continuation in
                AVAudioSession.sharedInstance().requestRecordPermission { granted in
                    continuation.resume(returning: granted)
                }
            }
        }
    }

    private func requestCameraPermission() async -> Bool {
        return await withCheckedContinuation { continuation in
            AVCaptureDevice.requestAccess(for: .video) { granted in
                continuation.resume(returning: granted)
            }
        }
    }

    /// Start camera capture with safe format selection
    private func startCameraCapture() {
        print("=== startCameraCapture BEGIN ===")

        guard let capturer = videoCapturer else {
            print("ERROR: No video capturer available")
            return
        }

        // Get front camera
        let devices = RTCCameraVideoCapturer.captureDevices()
        print("Available cameras: \(devices.count)")

        guard let device = devices.first(where: { $0.position == .front }) ?? devices.first else {
            print("ERROR: No camera found")
            return
        }
        print("Using camera: \(device.localizedName), position: \(device.position.rawValue)")

        // Get supported formats and select a reasonable one
        let formats = RTCCameraVideoCapturer.supportedFormats(for: device)
        print("Available formats: \(formats.count)")

        guard !formats.isEmpty else {
            print("ERROR: No supported formats for camera")
            return
        }

        // Find a format close to 1280x720 (HD) - prefer formats without HDR to avoid issues
        let targetWidth: Int32 = 1280
        let targetHeight: Int32 = 720

        var selectedFormat: AVCaptureDevice.Format?
        var minDiff = Int32.max

        for format in formats {
            let dimensions = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
            let diff = abs(dimensions.width - targetWidth) + abs(dimensions.height - targetHeight)

            // Prefer non-HDR formats for better compatibility
            let mediaSubType = CMFormatDescriptionGetMediaSubType(format.formatDescription)
            let isHDR = mediaSubType == kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange
            let penalty: Int32 = isHDR ? 1000 : 0

            if diff + penalty < minDiff {
                minDiff = diff + penalty
                selectedFormat = format
            }
        }

        guard let format = selectedFormat else {
            print("ERROR: Could not find suitable camera format")
            return
        }

        let dimensions = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
        print("Selected format: \(dimensions.width)x\(dimensions.height)")

        // Get FPS - use 30 fps as target
        let targetFps = 30
        var selectedFps = 15 // Default to lower FPS for stability

        for range in format.videoSupportedFrameRateRanges {
            let maxFps = Int(range.maxFrameRate)
            let minFps = Int(range.minFrameRate)
            print("Format FPS range: \(minFps)-\(maxFps)")

            if maxFps >= targetFps {
                selectedFps = min(maxFps, targetFps)
            } else if maxFps > selectedFps {
                selectedFps = maxFps
            }
        }

        print("Starting camera capture: \(dimensions.width)x\(dimensions.height) @ \(selectedFps)fps")

        // Start capture with completion handler for error handling
        capturer.startCapture(with: device, format: format, fps: selectedFps) { error in
            if let error = error {
                print("ERROR: Camera capture failed to start: \(error.localizedDescription)")
            } else {
                print("Camera capture started successfully")
            }
        }

        print("=== startCameraCapture END ===")
    }

    private func createOffer() async throws -> String {
        guard let pc = peerConnection else {
            throw NetworkError.connectionFailed
        }

        let constraints = RTCMediaConstraints(
            mandatoryConstraints: [
                "OfferToReceiveAudio": "true",
                "OfferToReceiveVideo": activeCallIsVideo ? "true" : "false"
            ],
            optionalConstraints: nil
        )

        let offer = try await pc.offer(for: constraints)
        try await pc.setLocalDescription(offer)
        return offer.sdp
    }

    private func createAnswer() async throws -> String {
        guard let pc = peerConnection else {
            throw NetworkError.connectionFailed
        }

        let constraints = RTCMediaConstraints(
            mandatoryConstraints: [
                "OfferToReceiveAudio": "true",
                "OfferToReceiveVideo": activeCallIsVideo ? "true" : "false"
            ],
            optionalConstraints: nil
        )

        let answer = try await pc.answer(for: constraints)
        try await pc.setLocalDescription(answer)
        return answer.sdp
    }

    // MARK: - ICE Candidate

    private func sendIceCandidate(_ candidate: RTCIceCandidate) async {
        guard let callId = activeCallId,
              let peerId = activeCallPeerId,
              let user = auth.currentUser,
              let sessionToken = user.sessionToken,
              let recipientPublicKey = contacts.getPublicKey(for: peerId) else {
            return
        }

        do {
            let candidateJson = [
                "sdpMLineIndex": candidate.sdpMLineIndex,
                "sdpMid": candidate.sdpMid ?? "",
                "candidate": candidate.sdp
            ] as [String: Any]

            let candidateData = try JSONSerialization.data(withJSONObject: candidateJson)
            let candidateString = String(data: candidateData, encoding: .utf8) ?? ""

            let timestamp = Int64(Date().timeIntervalSince1970 * 1000)
            let (ciphertext, nonce) = try crypto.encryptMessage(
                candidateString,
                recipientPublicKey: recipientPublicKey,
                senderPrivateKey: user.encPrivateKey
            )

            let signature = try crypto.signMessage(
                messageType: Constants.MessageType.callIceCandidate,
                messageId: callId,
                from: user.whisperId,
                to: peerId,
                timestamp: timestamp,
                nonce: nonce,
                ciphertext: ciphertext,
                privateKey: user.signPrivateKey
            )

            let payload = CallIceCandidatePayload(
                sessionToken: sessionToken,
                callId: callId,
                from: user.whisperId,
                to: peerId,
                timestamp: timestamp,
                nonce: nonce.base64EncodedString(),
                ciphertext: ciphertext.base64EncodedString(),
                sig: signature.base64EncodedString()
            )

            let frame = WsFrame(type: Constants.MessageType.callIceCandidate, payload: payload)
            try await ws.send(frame)
        } catch {
            print("Failed to send ICE candidate: \(error)")
        }
    }

    // MARK: - Message Handling

    private func handleMessage(_ data: Data) {
        // First, try to decode as RawWsFrame
        guard let raw = try? JSONDecoder().decode(RawWsFrame.self, from: data) else {
            print("CallService: Failed to decode RawWsFrame")
            return
        }

        // Log ALL messages to debug call_incoming issue
        print("CallService handleMessage: type=\(raw.type)")

        // Detailed logging for call-related messages
        if raw.type.hasPrefix("call") || raw.type.hasPrefix("turn") || raw.type == "error" {
            if let jsonString = String(data: data, encoding: .utf8) {
                print("=== CallService received: \(raw.type) ===")
                print(jsonString.prefix(1000))
            }
        }

        switch raw.type {
        case "error":
            // Handle error responses
            if let errorFrame = try? JSONDecoder().decode(WsFrame<ErrorPayload>.self, from: data) {
                print("ERROR from server: \(errorFrame.payload.code) - \(errorFrame.payload.message)")
            }
        case Constants.MessageType.turnCredentials:
            handleTurnCredentials(data)
        case Constants.MessageType.callIncoming:
            handleCallIncoming(data)
        case Constants.MessageType.callAnswer:
            handleCallAnswer(data)
        case Constants.MessageType.callIceCandidate:
            handleIceCandidate(data)
        case Constants.MessageType.callEnd:
            handleCallEnd(data)
        case Constants.MessageType.callRinging:
            handleCallRinging(data)
        default:
            break
        }
    }

    private func handleTurnCredentials(_ data: Data) {
        guard let frame = try? JSONDecoder().decode(WsFrame<TurnCredentialsPayload>.self, from: data) else { return }
        DispatchQueue.main.async {
            self.turnCredentials = frame.payload
        }
    }

    private func handleCallIncoming(_ data: Data) {
        print("=== handleCallIncoming BEGIN ===")

        do {
            let frame = try JSONDecoder().decode(WsFrame<CallIncomingPayload>.self, from: data)
            let payload = frame.payload

            print("Incoming call decoded successfully:")
            print("  callId: \(payload.callId)")
            print("  from: \(payload.from)")
            print("  isVideo: \(payload.isVideo)")

            // Store pending incoming call for CallKit answer flow
            pendingIncomingCall = payload

            // Store internal state
            activeCallId = payload.callId
            activeCallPeerId = payload.from
            activeCallIsVideo = payload.isVideo
            activeCallIsOutgoing = false

            // Report to CallKit - CallKit handles ALL incoming call UI
            let contact = contacts.getContact(whisperId: payload.from)
            let displayName = contact?.displayName ?? payload.from

            print("Reporting to CallKit - displayName: \(displayName)")
            callKitManager?.reportIncomingCall(
                callId: payload.callId,
                handle: payload.from,
                displayName: displayName,
                hasVideo: payload.isVideo
            )
            print("=== handleCallIncoming END (reported to CallKit) ===")
        } catch {
            print("ERROR: Failed to decode call_incoming: \(error)")
            if let jsonString = String(data: data, encoding: .utf8) {
                print("Raw data: \(jsonString)")
            }
        }
    }

    private func handleCallAnswer(_ data: Data) {
        print("=== handleCallAnswer BEGIN ===")

        // Use CallAnswerReceivedPayload - server strips version/session/to fields when forwarding
        guard let frame = try? JSONDecoder().decode(WsFrame<CallAnswerReceivedPayload>.self, from: data) else {
            print("ERROR: Failed to decode call_answer frame")
            return
        }

        guard let user = auth.currentUser else {
            print("ERROR: No authenticated user for call_answer")
            return
        }

        guard let senderPublicKey = contacts.getPublicKey(for: frame.payload.from) else {
            print("ERROR: No public key for \(frame.payload.from)")
            return
        }

        let payload = frame.payload
        print("Processing answer from: \(payload.from) for call: \(payload.callId)")

        Task {
            do {
                // Decrypt SDP answer
                guard let ciphertextData = Data(base64Encoded: payload.ciphertext),
                      let nonceData = Data(base64Encoded: payload.nonce) else {
                    print("ERROR: Failed to decode base64 ciphertext/nonce")
                    return
                }

                print("Decrypting SDP answer...")
                let sdpAnswer = try crypto.decryptMessage(
                    ciphertext: ciphertextData,
                    nonce: nonceData,
                    senderPublicKey: senderPublicKey,
                    recipientPrivateKey: user.encPrivateKey
                )
                print("SDP answer decrypted, length: \(sdpAnswer.count)")

                let remoteDesc = RTCSessionDescription(type: .answer, sdp: sdpAnswer)
                print("Setting remote description...")
                try await peerConnection?.setRemoteDescription(remoteDesc)
                print("Remote description SET successfully")

                // Process pending ICE candidates
                print("Processing \(pendingIceCandidates.count) pending ICE candidates...")
                for candidate in pendingIceCandidates {
                    try await peerConnection?.add(candidate)
                }
                pendingIceCandidates.removeAll()
                print("=== handleCallAnswer END (success) ===")

                // NOTE: No UI state updates - CallKit handles all UI
            } catch {
                print("Failed to handle call answer: \(error)")
            }
        }
    }

    private func handleIceCandidate(_ data: Data) {
        // Use CallIceCandidateReceivedPayload - server strips version/session/to fields when forwarding
        guard let frame = try? JSONDecoder().decode(WsFrame<CallIceCandidateReceivedPayload>.self, from: data) else {
            print("ERROR: Failed to decode ICE candidate frame")
            return
        }

        guard let user = auth.currentUser else {
            print("ERROR: No authenticated user for ICE candidate")
            return
        }

        guard let senderPublicKey = contacts.getPublicKey(for: frame.payload.from) else {
            print("ERROR: No public key for ICE candidate from \(frame.payload.from)")
            return
        }

        print("Processing ICE candidate from: \(frame.payload.from)")

        let payload = frame.payload

        Task {
            do {
                guard let ciphertextData = Data(base64Encoded: payload.ciphertext),
                      let nonceData = Data(base64Encoded: payload.nonce) else {
                    return
                }

                let candidateString = try crypto.decryptMessage(
                    ciphertext: ciphertextData,
                    nonce: nonceData,
                    senderPublicKey: senderPublicKey,
                    recipientPrivateKey: user.encPrivateKey
                )

                guard let candidateData = candidateString.data(using: .utf8),
                      let json = try JSONSerialization.jsonObject(with: candidateData) as? [String: Any],
                      let sdp = json["candidate"] as? String,
                      let sdpMLineIndex = json["sdpMLineIndex"] as? Int32 else {
                    return
                }

                let sdpMid = json["sdpMid"] as? String

                let candidate = RTCIceCandidate(sdp: sdp, sdpMLineIndex: sdpMLineIndex, sdpMid: sdpMid)

                // If remote description not set yet, queue the candidate
                if peerConnection?.remoteDescription == nil {
                    pendingIceCandidates.append(candidate)
                } else {
                    try await peerConnection?.add(candidate)
                }
            } catch {
                print("Failed to handle ICE candidate: \(error)")
            }
        }
    }

    private var lastCallEndReason: CallEndReason = .ended

    private func handleCallEnd(_ data: Data) {
        print("=== handleCallEnd (remote ended) ===")
        // Use CallEndReceivedPayload - server strips version/session/to fields when forwarding
        guard let frame = try? JSONDecoder().decode(WsFrame<CallEndReceivedPayload>.self, from: data) else {
            print("Failed to decode call_end")
            return
        }

        print("Call ended by remote - callId: \(frame.payload.callId), reason: \(frame.payload.reason)")
        lastCallEndReason = CallEndReason(rawValue: frame.payload.reason) ?? .ended

        // Report to CallKit that remote ended the call (not as local end)
        let callIdToEnd = frame.payload.callId
        print("Reporting call ended to CallKit: \(callIdToEnd)")
        callKitManager?.reportCallEnded(callId: callIdToEnd, reason: .remoteEnded)

        // Also end activeCallId if different
        if let activeId = activeCallId, activeId != callIdToEnd {
            print("Also reporting active call ended: \(activeId)")
            callKitManager?.reportCallEnded(callId: activeId, reason: .remoteEnded)
        }

        cleanup()
    }

    private func handleCallRinging(_ data: Data) {
        print("Remote device is ringing")
        // Update outgoing call UI status
        DispatchQueue.main.async {
            OutgoingCallState.shared.updateStatus("Ringing...")
        }
    }

    // MARK: - Cleanup

    /// Clean up previous call state before starting a new call (no history recording)
    private func cleanupPreviousCall() {
        print("=== cleanupPreviousCall ===")

        // Stop video capture first
        if let capturer = videoCapturer {
            capturer.stopCapture()
            print("Stopped previous video capture")
        }
        videoCapturer = nil

        // Remove video renderers
        if let localTrack = localVideoTrack {
            if let renderer = localVideoRenderer {
                localTrack.remove(renderer)
            }
        }

        if let remoteTrack = remoteVideoTrack {
            if let renderer = remoteVideoRenderer {
                remoteTrack.remove(renderer)
            }
        }

        localVideoTrack = nil
        localAudioTrack = nil
        remoteVideoTrack = nil
        remoteAudioTrack = nil

        // Close peer connection
        peerConnection?.close()
        peerConnection = nil

        pendingIceCandidates.removeAll()
        pendingIncomingCall = nil
        pendingOutgoingCall = nil
        currentCallStartTime = nil

        // Clear internal state
        activeCallId = nil
        activeCallPeerId = nil
        activeCallIsVideo = false
        activeCallIsOutgoing = false
        isMuted = false
        isSpeakerOn = false
        isAudioSessionActivated = false

        print("Previous call state cleaned up")
    }

    private func cleanup() {
        // Record call to history before cleanup
        recordCallToHistory()

        // Stop video capture
        videoCapturer?.stopCapture()
        videoCapturer = nil

        // Remove video renderers from tracks before cleaning up
        if let localTrack = localVideoTrack {
            if let renderer = localVideoRenderer {
                localTrack.remove(renderer)
            }
            // Also remove from ActiveVideoCallState renderer
            DispatchQueue.main.async {
                if let renderer = ActiveVideoCallState.shared.localVideoView {
                    localTrack.remove(renderer)
                }
            }
        }

        if let remoteTrack = remoteVideoTrack {
            if let renderer = remoteVideoRenderer {
                remoteTrack.remove(renderer)
            }
            DispatchQueue.main.async {
                if let renderer = ActiveVideoCallState.shared.remoteVideoView {
                    remoteTrack.remove(renderer)
                }
            }
        }

        localVideoTrack = nil
        localAudioTrack = nil
        remoteVideoTrack = nil
        remoteAudioTrack = nil

        peerConnection?.close()
        peerConnection = nil

        pendingIceCandidates.removeAll()
        pendingIncomingCall = nil
        pendingOutgoingCall = nil
        currentCallStartTime = nil

        // Clear internal state
        activeCallId = nil
        activeCallPeerId = nil
        activeCallIsVideo = false
        activeCallIsOutgoing = false
        isMuted = false
        isSpeakerOn = false
        lastCallEndReason = .ended
        isAudioSessionActivated = false

        // Hide all call UIs
        DispatchQueue.main.async {
            OutgoingCallState.shared.hide()
            ActiveVideoCallState.shared.hide()
            ActiveAudioCallState.shared.hide()
        }

        // NOTE: Audio session is managed by CallKit
    }

    // MARK: - Call History

    private func loadCallHistory() {
        guard let data = keychain.getData(for: callHistoryStorageKey),
              let history = try? JSONDecoder().decode([CallRecord].self, from: data) else {
            callHistory = []
            return
        }
        callHistory = history
    }

    private func saveCallHistory() {
        guard let data = try? JSONEncoder().encode(callHistory) else { return }
        keychain.save(data: data, for: callHistoryStorageKey)
    }

    private func recordCallToHistory() {
        guard activeCallId != nil,
              let peerId = activeCallPeerId else { return }

        print("=== recordCallToHistory ===")
        print("  lastCallEndReason: \(lastCallEndReason)")
        print("  currentCallStartTime: \(String(describing: currentCallStartTime))")
        print("  activeCallIsOutgoing: \(activeCallIsOutgoing)")

        // Determine call outcome based on last end reason
        let outcome: CallRecord.CallOutcome
        switch lastCallEndReason {
        case .ended:
            // If call was connected (has start time), it's completed
            outcome = currentCallStartTime != nil ? .completed : (activeCallIsOutgoing ? .cancelled : .missed)
        case .declined:
            outcome = activeCallIsOutgoing ? .noAnswer : .declined
        case .busy:
            outcome = .noAnswer
        case .failed:
            outcome = .failed
        case .timeout:
            outcome = activeCallIsOutgoing ? .noAnswer : .missed
        case .cancelled:
            outcome = .cancelled
        }

        print("  outcome: \(outcome)")

        // Calculate duration if call was connected
        var duration: TimeInterval? = nil
        if let startTime = currentCallStartTime {
            duration = Date().timeIntervalSince(startTime)
        }

        let contact = contacts.getContact(whisperId: peerId)
        let record = CallRecord(
            peerId: peerId,
            peerName: contact?.displayName,
            isVideo: activeCallIsVideo,
            isOutgoing: activeCallIsOutgoing,
            startTime: currentCallStartTime ?? Date(),
            endTime: Date(),
            duration: duration,
            outcome: outcome
        )

        DispatchQueue.main.async {
            self.callHistory.insert(record, at: 0)
            // Keep last 100 calls
            if self.callHistory.count > 100 {
                self.callHistory = Array(self.callHistory.prefix(100))
            }
            self.saveCallHistory()
        }
    }

    /// Clear all call history
    func clearCallHistory() {
        callHistory.removeAll()
        keychain.delete(key: callHistoryStorageKey)
    }

    /// Delete a specific call record
    func deleteCallRecord(_ recordId: String) {
        callHistory.removeAll { $0.id == recordId }
        saveCallHistory()
    }
}

// MARK: - RTCPeerConnectionDelegate

extension CallService: RTCPeerConnectionDelegate {

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {
        print("Signaling state: \(stateChanged.rawValue)")
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {
        print("Received remote stream with \(stream.videoTracks.count) video tracks and \(stream.audioTracks.count) audio tracks")

        if let videoTrack = stream.videoTracks.first {
            remoteVideoTrack = videoTrack

            // Connect to legacy renderer if set
            if let renderer = remoteVideoRenderer {
                videoTrack.add(renderer)
            }

            // Connect to ActiveVideoCallState renderer
            DispatchQueue.main.async {
                if let renderer = ActiveVideoCallState.shared.remoteVideoView {
                    videoTrack.add(renderer)
                    print("Remote video track connected to ActiveVideoCallState renderer")
                }
            }
        }

        if let audioTrack = stream.audioTracks.first {
            remoteAudioTrack = audioTrack
            // Enable if audio session is active
            if isAudioSessionActivated {
                audioTrack.isEnabled = true
            }
        }
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}

    func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
        print("ICE connection state: \(newState.rawValue)")

        switch newState {
        case .checking:
            DispatchQueue.main.async {
                OutgoingCallState.shared.updateStatus("Connecting...")
            }
        case .connected, .completed:
            print("=== ICE CONNECTED - Call is now active ===")
            currentCallStartTime = Date()
            if let callId = activeCallId {
                print("Reporting call connected to CallKit: \(callId)")
                callKitManager?.reportCallConnected(callId: callId)
            }

            // Hide outgoing call UI (animation is handled in OutgoingCallState)
            DispatchQueue.main.async {
                print("Hiding OutgoingCallState...")
                OutgoingCallState.shared.hide()
                print("OutgoingCallState hidden")
            }

            // Show appropriate call screen
            if activeCallIsVideo {
                print("This is a video call - showing video call screen")
                showVideoCallScreen()
            } else {
                print("This is an audio call - showing audio call screen")
                showAudioCallScreen()
            }
        case .disconnected:
            print("ICE disconnected - attempting reconnection")
            DispatchQueue.main.async {
                OutgoingCallState.shared.updateStatus("Reconnecting...")
            }
        case .failed:
            lastCallEndReason = .failed
            Task {
                try? await self.endCall(reason: .failed)
            }
        default:
            break
        }
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {
        print("ICE gathering state: \(newState.rawValue)")
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        Task {
            await sendIceCandidate(candidate)
        }
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}

    func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}
}

// MARK: - CallKitManagerDelegate

extension CallService: CallKitManagerDelegate {

    func callKitDidStartCall(callId: String) {
        // CallKit confirmed outgoing call start - now setup WebRTC connection
        guard activeCallId == callId, activeCallIsOutgoing == true else {
            print("CallKit start: Call mismatch for callId: \(callId)")
            return
        }

        Task {
            do {
                try await startOutgoingCallConnection()
            } catch {
                print("Failed to start outgoing call connection: \(error)")
                callKitManager?.endCall(callId: callId)
                cleanup()
            }
        }
    }

    func callKitDidAnswerCall(callId: String) {
        // User answered incoming call via CallKit UI - start the WebRTC connection
        guard let payload = pendingIncomingCall, payload.callId == callId else {
            print("CallKit answer: No pending call found for callId: \(callId)")
            print("  pendingIncomingCall: \(pendingIncomingCall?.callId ?? "nil")")
            print("  requested callId: \(callId)")
            return
        }

        print("CallKit answer: Answering call \(callId) from \(payload.from)")

        Task {
            do {
                // Ensure we're authenticated before answering
                // App might have been woken by VoIP push and not yet authenticated
                if auth.currentUser?.sessionToken == nil {
                    print("CallKit answer: Not authenticated, attempting reconnect...")
                    try await auth.reconnect()
                    print("CallKit answer: Reconnect successful")
                }

                // Ensure WebSocket is connected
                if ws.connectionState != .connected {
                    print("CallKit answer: WebSocket not connected, waiting...")
                    ws.connect()
                    // Wait for connection
                    for _ in 0..<30 {
                        if ws.connectionState == .connected {
                            break
                        }
                        try await Task.sleep(nanoseconds: 100_000_000)
                    }
                }

                print("CallKit answer: Auth and WS ready, answering call...")
                try await answerCall(payload)
                pendingIncomingCall = nil
                print("CallKit answer: Call answered successfully")
            } catch {
                print("Failed to answer call via CallKit: \(error)")
                callKitManager?.endCall(callId: callId)
                cleanup()
            }
        }
    }

    func callKitDidEndCall(callId: String) {
        Task {
            try? await endCall(reason: .ended)
        }
    }

    func callKitDidMuteCall(callId: String, muted: Bool) {
        localAudioTrack?.isEnabled = !muted
        isMuted = muted
    }

    func callKitDidHoldCall(callId: String, onHold: Bool) {
        // Handle hold if needed
        localAudioTrack?.isEnabled = !onHold
    }

    func callKitAudioSessionDidActivate() {
        // Audio session is now active - WebRTC can use audio
        print("=== CallKit audio session ACTIVATED ===")

        isAudioSessionActivated = true

        let rtcAudioSession = RTCAudioSession.sharedInstance()

        // Tell WebRTC's audio session that it's been activated by CallKit
        rtcAudioSession.lockForConfiguration()
        rtcAudioSession.audioSessionDidActivate(AVAudioSession.sharedInstance())
        rtcAudioSession.isAudioEnabled = true  // Enable audio output
        rtcAudioSession.unlockForConfiguration()

        // Enable audio tracks if they exist (they might be created later)
        enableAudioTracksIfNeeded()
    }

    func callKitAudioSessionDidDeactivate() {
        // Audio session deactivated
        print("=== CallKit audio session DEACTIVATED ===")

        isAudioSessionActivated = false

        let rtcAudioSession = RTCAudioSession.sharedInstance()

        rtcAudioSession.lockForConfiguration()
        rtcAudioSession.audioSessionDidDeactivate(AVAudioSession.sharedInstance())
        rtcAudioSession.isAudioEnabled = false
        rtcAudioSession.unlockForConfiguration()

        print("Audio session deactivated")
    }

    /// Enable audio tracks - called when CallKit activates and when tracks are created
    private func enableAudioTracksIfNeeded() {
        guard isAudioSessionActivated else {
            print("Audio tracks: session not activated yet, will enable later")
            return
        }

        localAudioTrack?.isEnabled = true
        remoteAudioTrack?.isEnabled = true

        print("Audio tracks enabled - local: \(localAudioTrack?.isEnabled ?? false), remote: \(remoteAudioTrack?.isEnabled ?? false)")

        // Enable speaker for video calls
        if activeCallIsVideo {
            enableSpeakerForVideoCall()
        }

        // Also enable video tracks for video calls
        if activeCallIsVideo {
            enableVideoTracksIfNeeded()
        }
    }

    /// Enable video tracks for video calls
    private func enableVideoTracksIfNeeded() {
        guard activeCallIsVideo else { return }

        localVideoTrack?.isEnabled = true
        remoteVideoTrack?.isEnabled = true

        print("Video tracks enabled - local: \(localVideoTrack?.isEnabled ?? false), remote: \(remoteVideoTrack?.isEnabled ?? false)")
    }

    /// Enable speaker by default for video calls
    private func enableSpeakerForVideoCall() {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.overrideOutputAudioPort(.speaker)
            isSpeakerOn = true
            print("Speaker enabled for video call")
        } catch {
            print("Failed to enable speaker for video call: \(error)")
        }
    }

    /// Show video call screen when video call connects
    private func showVideoCallScreen() {
        guard let callId = activeCallId,
              let peerId = activeCallPeerId else {
            return
        }

        let contact = contacts.getContact(whisperId: peerId)
        let peerName = contact?.displayName ?? peerId

        DispatchQueue.main.async {
            // Show video call UI
            ActiveVideoCallState.shared.showVideoCall(callId: callId, peerName: peerName, peerId: peerId)

            // Connect video renderers
            self.connectVideoRenderers()
        }
    }

    /// Connect video tracks to the renderers in ActiveVideoCallState
    private func connectVideoRenderers() {
        DispatchQueue.main.async {
            // Connect local video
            if let localTrack = self.localVideoTrack,
               let localRenderer = ActiveVideoCallState.shared.localVideoView {
                localTrack.add(localRenderer)
                print("Local video renderer connected")
            }

            // Connect remote video
            if let remoteTrack = self.remoteVideoTrack,
               let remoteRenderer = ActiveVideoCallState.shared.remoteVideoView {
                remoteTrack.add(remoteRenderer)
                print("Remote video renderer connected")
            }
        }
    }

    /// Handle video call control actions from the UI
    func handleVideoCallMute() {
        toggleMute()
        DispatchQueue.main.async {
            ActiveVideoCallState.shared.isMuted = self.isMuted
        }
    }

    func handleVideoCallCamera() {
        toggleLocalVideo()
        DispatchQueue.main.async {
            ActiveVideoCallState.shared.isCameraOff = !(self.localVideoTrack?.isEnabled ?? false)
        }
    }

    func handleVideoCallSwitchCamera() {
        switchCamera()
    }

    func handleVideoCallSpeaker() {
        toggleSpeaker()
        DispatchQueue.main.async {
            ActiveVideoCallState.shared.isSpeakerOn = self.isSpeakerOn
        }
    }

    func handleVideoCallEnd() {
        Task {
            try? await endCall(reason: .ended)
        }
    }

    // MARK: - Audio Call Screen

    /// Show audio call screen when audio call connects
    private func showAudioCallScreen() {
        guard let callId = activeCallId,
              let peerId = activeCallPeerId else {
            return
        }

        let contact = contacts.getContact(whisperId: peerId)
        let peerName = contact?.displayName ?? peerId

        DispatchQueue.main.async {
            ActiveAudioCallState.shared.showAudioCall(callId: callId, peerName: peerName, peerId: peerId)
        }
    }

    /// Handle audio call control actions from the UI
    func handleAudioCallMute() {
        toggleMute()
        DispatchQueue.main.async {
            ActiveAudioCallState.shared.isMuted = self.isMuted
        }
    }

    func handleAudioCallSpeaker() {
        toggleSpeaker()
        DispatchQueue.main.async {
            ActiveAudioCallState.shared.isSpeakerOn = self.isSpeakerOn
        }
    }

    func handleAudioCallEnd() {
        Task {
            try? await endCall(reason: .ended)
        }
    }
}
