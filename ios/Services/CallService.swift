import Foundation
import AVFoundation
import Combine
import WebRTC

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

/// Service for managing WebRTC calls
final class CallService: NSObject, ObservableObject {
    static let shared = CallService()

    @Published private(set) var activeCall: ActiveCall?
    @Published private(set) var callState: CallState = .idle
    @Published private(set) var turnCredentials: TurnCredentialsPayload?
    @Published private(set) var callHistory: [CallRecord] = []

    // Store incoming call payload for CallKit answer flow
    private var pendingIncomingCall: CallIncomingPayload?

    // Store outgoing call info for CallKit start flow
    private var pendingOutgoingCall: (peerId: String, isVideo: Bool)?

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

    // Storage
    private let callHistoryStorageKey = "whisper2.call.history"
    private var currentCallStartTime: Date?

    private override init() {
        super.init()
        loadCallHistory()
        setupWebRTC()
        setupMessageHandler()
        setupCallKit()
    }

    // MARK: - Setup

    private func setupWebRTC() {
        RTCInitializeSSL()

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

    /// Request to start an outgoing call - CallKit will handle the UI
    func initiateCall(to peerId: String, isVideo: Bool) async throws {
        guard auth.currentUser?.sessionToken != nil else {
            throw NetworkError.connectionFailed
        }

        guard contacts.getPublicKey(for: peerId) != nil else {
            throw CryptoError.invalidPublicKey
        }

        // Request microphone permission first
        let micPermission = await requestMicrophonePermission()
        guard micPermission else {
            throw NetworkError.serverError(code: "PERMISSION_DENIED", message: "Microphone permission required for calls")
        }

        // Request camera permission for video calls
        if isVideo {
            let cameraPermission = await requestCameraPermission()
            guard cameraPermission else {
                throw NetworkError.serverError(code: "PERMISSION_DENIED", message: "Camera permission required for video calls")
            }
        }

        // Generate callId
        let callId = UUID().uuidString.lowercased()

        // Store pending outgoing call info
        pendingOutgoingCall = (peerId: peerId, isVideo: isVideo)

        // Update state
        let contact = contacts.getContact(whisperId: peerId)
        await MainActor.run {
            self.activeCall = ActiveCall(
                callId: callId,
                peerId: peerId,
                peerName: contact?.displayName,
                isVideo: isVideo,
                isOutgoing: true,
                state: .initiating
            )
            self.callState = .initiating
        }

        // Request CallKit to start the call - CallKit will show native call UI
        // When CallKit confirms, callKitDidStartCall will be triggered to setup WebRTC
        callKitManager?.startOutgoingCall(callId: callId, handle: peerId, hasVideo: isVideo)
    }

    /// Actually start the WebRTC connection (called after CallKit confirms)
    private func startOutgoingCallConnection() async throws {
        guard let call = activeCall,
              let user = auth.currentUser,
              let sessionToken = user.sessionToken,
              let pending = pendingOutgoingCall,
              let recipientPublicKey = contacts.getPublicKey(for: pending.peerId) else {
            throw NetworkError.connectionFailed
        }

        // Fetch TURN credentials
        try await fetchTurnCredentials()

        // Wait for credentials with retry
        var attempts = 0
        while turnCredentials == nil && attempts < 10 {
            try await Task.sleep(nanoseconds: 200_000_000)
            attempts += 1
        }

        if turnCredentials == nil {
            print("Warning: No TURN credentials received, attempting direct connection")
        }

        // Create peer connection and generate offer
        try await createPeerConnection(isVideo: pending.isVideo)
        let sdpOffer = try await createOffer()

        // Encrypt SDP
        let timestamp = Int64(Date().timeIntervalSince1970 * 1000)
        let (ciphertext, nonce) = try crypto.encryptMessage(
            sdpOffer,
            recipientPublicKey: recipientPublicKey,
            senderPrivateKey: user.encPrivateKey
        )

        // Sign
        let signature = try crypto.signMessage(
            messageType: Constants.MessageType.callInitiate,
            messageId: call.callId,
            from: user.whisperId,
            to: pending.peerId,
            timestamp: timestamp,
            nonce: nonce,
            ciphertext: ciphertext,
            privateKey: user.signPrivateKey
        )

        // Send initiate to server
        let payload = CallInitiatePayload(
            sessionToken: sessionToken,
            callId: call.callId,
            from: user.whisperId,
            to: pending.peerId,
            isVideo: pending.isVideo,
            timestamp: timestamp,
            nonce: nonce.base64EncodedString(),
            ciphertext: ciphertext.base64EncodedString(),
            sig: signature.base64EncodedString()
        )

        let frame = WsFrame(type: Constants.MessageType.callInitiate, payload: payload)
        try await ws.send(frame)

        // Clear pending
        pendingOutgoingCall = nil

        // Report connecting to CallKit
        callKitManager?.reportOutgoingCallStartedConnecting(callId: call.callId)
    }

    // MARK: - Answer Call

    func answerCall(_ incomingPayload: CallIncomingPayload) async throws {
        guard let user = auth.currentUser,
              let sessionToken = user.sessionToken else {
            throw NetworkError.connectionFailed
        }

        guard let senderPublicKey = contacts.getPublicKey(for: incomingPayload.from) else {
            // Add as contact temporarily for the call
            throw CryptoError.invalidPublicKey
        }

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

        // Update state
        let contact = contacts.getContact(whisperId: incomingPayload.from)
        await MainActor.run {
            self.activeCall = ActiveCall(
                callId: incomingPayload.callId,
                peerId: incomingPayload.from,
                peerName: contact?.nickname,
                isVideo: incomingPayload.isVideo,
                isOutgoing: false,
                state: .connecting
            )
            self.callState = .connecting
        }
    }

    // MARK: - End Call

    func endCall(reason: CallEndReason = .ended) async throws {
        guard let call = activeCall,
              let user = auth.currentUser,
              let sessionToken = user.sessionToken else {
            cleanup()
            return
        }

        guard let recipientPublicKey = contacts.getPublicKey(for: call.peerId) else {
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
            messageId: call.callId,
            from: user.whisperId,
            to: call.peerId,
            timestamp: timestamp,
            nonce: nonce,
            ciphertext: ciphertext,
            privateKey: user.signPrivateKey
        )

        let payload = CallEndPayload(
            sessionToken: sessionToken,
            callId: call.callId,
            from: user.whisperId,
            to: call.peerId,
            timestamp: timestamp,
            nonce: nonce.base64EncodedString(),
            ciphertext: ciphertext.base64EncodedString(),
            sig: signature.base64EncodedString(),
            reason: reason.rawValue
        )

        let frame = WsFrame(type: Constants.MessageType.callEnd, payload: payload)
        try await ws.send(frame)

        // Report to CallKit
        callKitManager?.endCall(callId: call.callId)

        cleanup()
    }

    // MARK: - Call Controls

    func toggleMute() {
        guard let track = localAudioTrack else { return }
        track.isEnabled = !track.isEnabled
        activeCall?.isMuted = !track.isEnabled
    }

    func toggleSpeaker() {
        let session = AVAudioSession.sharedInstance()
        do {
            if activeCall?.isSpeakerOn == true {
                try session.overrideOutputAudioPort(.none)
                activeCall?.isSpeakerOn = false
            } else {
                try session.overrideOutputAudioPort(.speaker)
                activeCall?.isSpeakerOn = true
            }
        } catch {
            print("Failed to toggle speaker: \(error)")
        }
    }

    func toggleLocalVideo() {
        guard let track = localVideoTrack else { return }
        track.isEnabled = !track.isEnabled
        activeCall?.isLocalVideoEnabled = track.isEnabled
    }

    func switchCamera() {
        guard let capturer = videoCapturer else { return }

        let position: AVCaptureDevice.Position = capturer.captureSession.inputs
            .compactMap { ($0 as? AVCaptureDeviceInput)?.device }
            .first?.position == .front ? .back : .front

        guard let device = RTCCameraVideoCapturer.captureDevices().first(where: { $0.position == position }),
              let format = RTCCameraVideoCapturer.supportedFormats(for: device).last,
              let fps = format.videoSupportedFrameRateRanges.first?.maxFrameRate else {
            return
        }

        capturer.startCapture(with: device, format: format, fps: Int(fps))
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

        // Add video track if video call
        if isVideo {
            let videoSource = factory.videoSource()
            localVideoTrack = factory.videoTrack(with: videoSource, trackId: "video0")

            if let videoTrack = localVideoTrack {
                peerConnection?.add(videoTrack, streamIds: ["stream0"])

                // Setup camera capturer
                videoCapturer = RTCCameraVideoCapturer(delegate: videoSource)

                if let device = RTCCameraVideoCapturer.captureDevices().first(where: { $0.position == .front }),
                   let format = RTCCameraVideoCapturer.supportedFormats(for: device).last,
                   let fps = format.videoSupportedFrameRateRanges.first?.maxFrameRate {
                    Task {
                        try? await videoCapturer?.startCapture(with: device, format: format, fps: Int(fps))
                    }
                }

                // Connect to local renderer
                if let renderer = localVideoRenderer {
                    videoTrack.add(renderer)
                }
            }
        }

        // Configure audio session (don't throw - just log errors)
        do {
            try configureAudioSession()
        } catch {
            print("Warning: Failed to configure audio session: \(error)")
        }
    }

    private func configureAudioSession() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetooth, .defaultToSpeaker])
        try session.setActive(true)
    }

    // MARK: - Permissions

    private func requestMicrophonePermission() async -> Bool {
        return await withCheckedContinuation { continuation in
            AVAudioSession.sharedInstance().requestRecordPermission { granted in
                continuation.resume(returning: granted)
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

    private func createOffer() async throws -> String {
        guard let pc = peerConnection else {
            throw NetworkError.connectionFailed
        }

        let constraints = RTCMediaConstraints(
            mandatoryConstraints: [
                "OfferToReceiveAudio": "true",
                "OfferToReceiveVideo": activeCall?.isVideo == true ? "true" : "false"
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
                "OfferToReceiveVideo": activeCall?.isVideo == true ? "true" : "false"
            ],
            optionalConstraints: nil
        )

        let answer = try await pc.answer(for: constraints)
        try await pc.setLocalDescription(answer)
        return answer.sdp
    }

    // MARK: - ICE Candidate

    private func sendIceCandidate(_ candidate: RTCIceCandidate) async {
        guard let call = activeCall,
              let user = auth.currentUser,
              let sessionToken = user.sessionToken,
              let recipientPublicKey = contacts.getPublicKey(for: call.peerId) else {
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
                messageId: call.callId,
                from: user.whisperId,
                to: call.peerId,
                timestamp: timestamp,
                nonce: nonce,
                ciphertext: ciphertext,
                privateKey: user.signPrivateKey
            )

            let payload = CallIceCandidatePayload(
                sessionToken: sessionToken,
                callId: call.callId,
                from: user.whisperId,
                to: call.peerId,
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
        guard let raw = try? JSONDecoder().decode(RawWsFrame.self, from: data) else { return }

        switch raw.type {
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
        guard let frame = try? JSONDecoder().decode(WsFrame<CallIncomingPayload>.self, from: data) else { return }

        let payload = frame.payload

        // Store pending incoming call for CallKit answer flow
        pendingIncomingCall = payload

        // Report to CallKit for native call UI (CallKit handles the incoming call screen)
        let contact = contacts.getContact(whisperId: payload.from)
        callKitManager?.reportIncomingCall(
            callId: payload.callId,
            handle: payload.from,
            displayName: contact?.displayName ?? payload.from,
            hasVideo: payload.isVideo
        )

        // Store call info
        DispatchQueue.main.async {
            self.activeCall = ActiveCall(
                callId: payload.callId,
                peerId: payload.from,
                peerName: contact?.nickname,
                isVideo: payload.isVideo,
                isOutgoing: false,
                state: .ringing
            )
            self.callState = .ringing
        }

        // NOTE: Don't call PushNotificationService.onIncomingCall here
        // CallKit handles the incoming call UI natively on iOS
    }

    private func handleCallAnswer(_ data: Data) {
        guard let frame = try? JSONDecoder().decode(WsFrame<CallAnswerPayload>.self, from: data),
              let user = auth.currentUser,
              let senderPublicKey = contacts.getPublicKey(for: frame.payload.from) else {
            return
        }

        let payload = frame.payload

        Task {
            do {
                // Decrypt SDP answer
                guard let ciphertextData = Data(base64Encoded: payload.ciphertext),
                      let nonceData = Data(base64Encoded: payload.nonce) else {
                    return
                }

                let sdpAnswer = try crypto.decryptMessage(
                    ciphertext: ciphertextData,
                    nonce: nonceData,
                    senderPublicKey: senderPublicKey,
                    recipientPrivateKey: user.encPrivateKey
                )

                let remoteDesc = RTCSessionDescription(type: .answer, sdp: sdpAnswer)
                try await peerConnection?.setRemoteDescription(remoteDesc)

                // Process pending ICE candidates
                for candidate in pendingIceCandidates {
                    try await peerConnection?.add(candidate)
                }
                pendingIceCandidates.removeAll()

                await MainActor.run {
                    self.callState = .connecting
                    self.activeCall?.state = .connecting
                }
            } catch {
                print("Failed to handle call answer: \(error)")
            }
        }
    }

    private func handleIceCandidate(_ data: Data) {
        guard let frame = try? JSONDecoder().decode(WsFrame<CallIceCandidatePayload>.self, from: data),
              let user = auth.currentUser,
              let senderPublicKey = contacts.getPublicKey(for: frame.payload.from) else {
            return
        }

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

    private func handleCallEnd(_ data: Data) {
        guard let frame = try? JSONDecoder().decode(WsFrame<CallEndPayload>.self, from: data) else { return }

        let reason = CallEndReason(rawValue: frame.payload.reason) ?? .ended

        DispatchQueue.main.async {
            self.callState = .ended(reason: reason)
            self.activeCall?.state = .ended(reason: reason)
        }

        // Report to CallKit
        if let callId = activeCall?.callId {
            callKitManager?.endCall(callId: callId)
        }

        cleanup()
    }

    private func handleCallRinging(_ data: Data) {
        DispatchQueue.main.async {
            self.callState = .ringing
            self.activeCall?.state = .ringing
        }
    }

    // MARK: - Cleanup

    private func cleanup() {
        // Record call to history before cleanup
        recordCallToHistory()

        videoCapturer?.stopCapture()
        videoCapturer = nil

        if let renderer = localVideoRenderer {
            localVideoTrack?.remove(renderer)
        }
        localVideoTrack = nil
        localAudioTrack = nil
        remoteVideoTrack = nil
        remoteAudioTrack = nil

        peerConnection?.close()
        peerConnection = nil

        pendingIceCandidates.removeAll()
        currentCallStartTime = nil

        DispatchQueue.main.async {
            self.activeCall = nil
            self.callState = .idle
        }

        // Reset audio session
        try? AVAudioSession.sharedInstance().setActive(false)
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
        guard let call = activeCall else { return }

        // Determine call outcome based on state
        let outcome: CallRecord.CallOutcome
        switch callState {
        case .ended(let reason):
            switch reason {
            case .ended:
                outcome = .completed
            case .declined:
                outcome = call.isOutgoing ? .noAnswer : .declined
            case .busy:
                outcome = .noAnswer
            case .failed:
                outcome = .failed
            case .timeout:
                outcome = call.isOutgoing ? .noAnswer : .missed
            case .cancelled:
                outcome = .cancelled
            }
        case .ringing, .initiating:
            // Call never connected
            outcome = call.isOutgoing ? .cancelled : .missed
        default:
            outcome = .completed
        }

        // Calculate duration if call was connected
        var duration: TimeInterval? = nil
        if let startTime = call.startTime ?? currentCallStartTime {
            duration = Date().timeIntervalSince(startTime)
        }

        let record = CallRecord(
            peerId: call.peerId,
            peerName: call.peerName ?? contacts.getContact(whisperId: call.peerId)?.displayName,
            isVideo: call.isVideo,
            isOutgoing: call.isOutgoing,
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
        if let videoTrack = stream.videoTracks.first {
            remoteVideoTrack = videoTrack
            if let renderer = remoteVideoRenderer {
                videoTrack.add(renderer)
            }
        }
        if let audioTrack = stream.audioTracks.first {
            remoteAudioTrack = audioTrack
        }
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}

    func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
        print("ICE connection state: \(newState.rawValue)")

        DispatchQueue.main.async {
            switch newState {
            case .connected, .completed:
                self.callState = .connected
                self.activeCall?.state = .connected
                self.activeCall?.startTime = Date()
                self.currentCallStartTime = Date()
                self.callKitManager?.reportCallConnected(callId: self.activeCall?.callId ?? "")
            case .disconnected:
                self.callState = .reconnecting
                self.activeCall?.state = .reconnecting
            case .failed:
                self.callState = .ended(reason: .failed)
                self.activeCall?.state = .ended(reason: .failed)
                Task {
                    try? await self.endCall(reason: .failed)
                }
            default:
                break
            }
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
        guard activeCall?.callId == callId, activeCall?.isOutgoing == true else {
            print("CallKit start: Call mismatch for callId: \(callId)")
            return
        }

        Task {
            do {
                try await startOutgoingCallConnection()
            } catch {
                print("Failed to start outgoing call connection: \(error)")
                callKitManager?.endCall(callId: callId)
                await MainActor.run {
                    self.callState = .ended(reason: .failed)
                }
            }
        }
    }

    func callKitDidAnswerCall(callId: String) {
        // User answered incoming call via CallKit UI - start the WebRTC connection
        guard let payload = pendingIncomingCall, payload.callId == callId else {
            print("CallKit answer: No pending call found for callId: \(callId)")
            return
        }

        Task {
            do {
                try await answerCall(payload)
                pendingIncomingCall = nil
            } catch {
                print("Failed to answer call via CallKit: \(error)")
                callKitManager?.endCall(callId: callId)
                await MainActor.run {
                    self.callState = .ended(reason: .failed)
                }
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
        DispatchQueue.main.async {
            self.activeCall?.isMuted = muted
        }
    }

    func callKitDidHoldCall(callId: String, onHold: Bool) {
        // Handle hold if needed
        localAudioTrack?.isEnabled = !onHold
    }

    func callKitAudioSessionDidActivate() {
        // Audio session is now active - WebRTC can use audio
        // This is important for proper audio routing
        print("Audio session activated - WebRTC audio enabled")
    }

    func callKitAudioSessionDidDeactivate() {
        // Audio session deactivated
        print("Audio session deactivated")
    }
}
