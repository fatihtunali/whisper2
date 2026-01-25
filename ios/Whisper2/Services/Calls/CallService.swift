import Foundation
import Combine

/// Whisper2 Call Service
/// Manages call state machine and coordinates WebRTC, CallKit, and signaling

// MARK: - Call State

/// Call state machine states
enum CallState: String, Codable {
    case idle
    case outgoingInitiating    // Caller: waiting for callee to ring
    case incomingRinging       // Callee: incoming call ringing
    case connecting            // Both: WebRTC connecting
    case connected             // Both: call connected
    case ended                 // Both: call ended
}

/// Call end reason
enum CallEndReason: String, Codable {
    case ended      // Normal hang up
    case declined   // Callee declined
    case busy       // Callee is busy
    case timeout    // Call timed out
    case failed     // Technical failure
}

/// Call direction
enum CallDirection {
    case outgoing
    case incoming
}

// MARK: - Call Info

/// Represents an active or recent call
struct CallInfo {
    let callId: String
    let remoteWhisperId: String
    let direction: CallDirection
    let isVideo: Bool
    var state: CallState
    let startTime: Date
    var connectTime: Date?
    var endTime: Date?
    var endReason: CallEndReason?
}

// MARK: - Call Service Delegate

protocol CallServiceDelegate: AnyObject {
    /// Called when call state changes
    func callService(_ service: CallService, didChangeState state: CallState, for callId: String)

    /// Called when an incoming call is received
    func callService(_ service: CallService, didReceiveIncomingCall callId: String, from whisperId: String, isVideo: Bool)

    /// Called when remote audio/video track is available
    func callService(_ service: CallService, didReceiveRemoteTrack callId: String)

    /// Called when call ends
    func callService(_ service: CallService, didEndCall callId: String, reason: CallEndReason)

    /// Called on error
    func callService(_ service: CallService, didEncounterError error: CallError, for callId: String?)
}

// MARK: - Call Service

/// Main call service managing the call lifecycle
final class CallService: NSObject {

    // MARK: - Singleton

    static let shared = CallService()

    // MARK: - Dependencies

    private let webRTCService: WebRTCService
    private let turnService: TurnService
    private let callKitService: CallKitService
    private let audioSessionService: AudioSessionService

    // MARK: - State

    private(set) var currentCall: CallInfo?
    private var pendingIceCandidates: [String] = []
    private let stateLock = NSLock()

    // MARK: - Delegate

    weak var delegate: CallServiceDelegate?

    // MARK: - Publishers

    private let stateSubject = CurrentValueSubject<CallState, Never>(.idle)
    var statePublisher: AnyPublisher<CallState, Never> {
        stateSubject.eraseToAnyPublisher()
    }

    // MARK: - Initialization

    private override init() {
        self.webRTCService = WebRTCService()
        self.turnService = TurnService()
        self.callKitService = CallKitService()
        self.audioSessionService = AudioSessionService()

        super.init()

        webRTCService.delegate = self
        callKitService.delegate = self
    }

    // MARK: - Public API

    /// Initiate an outgoing call
    /// - Parameters:
    ///   - whisperId: The WhisperID to call
    ///   - isVideo: Whether this is a video call
    func initiateCall(to whisperId: String, isVideo: Bool = false) async throws {
        logger.info("Initiating call to \(whisperId), video: \(isVideo)", category: .calls)

        // Check if already in a call
        guard currentCall == nil else {
            throw CallError.alreadyInCall
        }

        // Generate call ID
        let callId = UUID().uuidString

        // Update state
        let callInfo = CallInfo(
            callId: callId,
            remoteWhisperId: whisperId,
            direction: .outgoing,
            isVideo: isVideo,
            state: .outgoingInitiating,
            startTime: Date()
        )

        updateCurrentCall(callInfo)
        transitionState(to: .outgoingInitiating, for: callId)

        do {
            // 1. Get TURN credentials
            let turnCreds = try await turnService.getTurnCredentials()
            logger.debug("Got TURN credentials", category: .calls)

            // 2. Configure audio session
            try audioSessionService.configureForVoIP()

            // 3. Create peer connection
            try webRTCService.createPeerConnection(iceServers: turnCreds.iceServers)

            // 4. Create offer
            let offer = try await webRTCService.createOffer()
            logger.debug("Created SDP offer", category: .calls)

            // 5. Report outgoing call to CallKit
            callKitService.reportOutgoingCall(uuid: UUID(uuidString: callId) ?? UUID(), handle: whisperId)

            // 6. Send call_initiate to server
            try await sendCallInitiate(
                callId: callId,
                to: whisperId,
                isVideo: isVideo,
                offer: offer
            )

            logger.info("Call initiate sent for callId: \(callId)", category: .calls)

        } catch {
            logger.error(error, message: "Failed to initiate call", category: .calls)
            await cleanup()
            throw error
        }
    }

    /// Handle an incoming call (called when receiving call_incoming)
    /// - Parameters:
    ///   - callId: The call ID
    ///   - from: The caller's WhisperID
    ///   - isVideo: Whether this is a video call
    ///   - offer: The encrypted SDP offer (base64)
    ///   - nonce: The encryption nonce
    ///   - sig: The signature
    func handleIncomingCall(
        callId: String,
        from whisperId: String,
        isVideo: Bool,
        offer: String,
        nonce: String,
        sig: String
    ) async {
        logger.info("Incoming call from \(whisperId), callId: \(callId)", category: .calls)

        // Check if already in a call
        guard currentCall == nil else {
            logger.warning("Rejecting incoming call - already in call", category: .calls)
            // Send busy
            try? await sendCallEnd(callId: callId, to: whisperId, reason: .busy)
            return
        }

        // Store call info
        let callInfo = CallInfo(
            callId: callId,
            remoteWhisperId: whisperId,
            direction: .incoming,
            isVideo: isVideo,
            state: .incomingRinging,
            startTime: Date()
        )

        updateCurrentCall(callInfo)

        // Store the offer for when call is answered
        // In real implementation, decrypt and store the SDP
        UserDefaults.standard.set(offer, forKey: "pendingOffer_\(callId)")
        UserDefaults.standard.set(nonce, forKey: "pendingOfferNonce_\(callId)")

        // Report to CallKit (shows native call UI)
        let uuid = UUID(uuidString: callId) ?? UUID()
        callKitService.reportIncomingCall(uuid: uuid, handle: whisperId, hasVideo: isVideo) { [weak self] error in
            if let error = error {
                logger.error(error, message: "Failed to report incoming call to CallKit", category: .calls)
                self?.delegate?.callService(self!, didEncounterError: .webRTCFailed(reason: error.localizedDescription), for: callId)
                return
            }

            // Notify delegate
            self?.transitionState(to: .incomingRinging, for: callId)
            self?.delegate?.callService(self!, didReceiveIncomingCall: callId, from: whisperId, isVideo: isVideo)
        }

        // Send ringing to caller
        try? await sendCallRinging(callId: callId, to: whisperId)
    }

    /// Answer an incoming call
    func answerCall() async throws {
        guard let call = currentCall, call.direction == .incoming else {
            throw CallError.notInCall
        }

        logger.info("Answering call \(call.callId)", category: .calls)

        transitionState(to: .connecting, for: call.callId)

        do {
            // 1. Get TURN credentials
            let turnCreds = try await turnService.getTurnCredentials()

            // 2. Configure audio session
            try audioSessionService.configureForVoIP()
            try audioSessionService.activateAudioSession()

            // 3. Create peer connection
            try webRTCService.createPeerConnection(iceServers: turnCreds.iceServers)

            // 4. Get stored offer and set as remote description
            if let offerSdp = UserDefaults.standard.string(forKey: "pendingOffer_\(call.callId)") {
                // In real implementation, decrypt the offer first
                try await webRTCService.setRemoteDescription(sdp: offerSdp, type: .offer)
            }

            // 5. Create answer
            let answer = try await webRTCService.createAnswer()

            // 6. Send answer to caller
            try await sendCallAnswer(callId: call.callId, to: call.remoteWhisperId, answer: answer)

            // 7. Process any pending ICE candidates
            for candidate in pendingIceCandidates {
                try? await webRTCService.addIceCandidate(candidate: candidate)
            }
            pendingIceCandidates.removeAll()

            logger.info("Call answered, waiting for connection", category: .calls)

        } catch {
            logger.error(error, message: "Failed to answer call", category: .calls)
            await cleanup()
            throw error
        }
    }

    /// End the current call
    /// - Parameter reason: The reason for ending the call
    func endCall(reason: CallEndReason = .ended) async {
        guard let call = currentCall else {
            logger.warning("endCall called but no active call", category: .calls)
            return
        }

        logger.info("Ending call \(call.callId) with reason: \(reason.rawValue)", category: .calls)

        // Send call_end to server
        try? await sendCallEnd(callId: call.callId, to: call.remoteWhisperId, reason: reason)

        // Update state and cleanup
        transitionState(to: .ended, for: call.callId)

        await cleanup()

        delegate?.callService(self, didEndCall: call.callId, reason: reason)
    }

    /// Handle call_answer from remote (caller receives this)
    func handleCallAnswer(callId: String, answer: String, nonce: String, sig: String) async {
        guard let call = currentCall, call.callId == callId else {
            logger.warning("Received answer for unknown call: \(callId)", category: .calls)
            return
        }

        logger.info("Received call answer for \(callId)", category: .calls)

        transitionState(to: .connecting, for: callId)

        do {
            // Activate audio
            try audioSessionService.activateAudioSession()

            // Set remote description (the answer)
            // In real implementation, decrypt first
            try await webRTCService.setRemoteDescription(sdp: answer, type: .answer)

            // Process pending ICE candidates
            for candidate in pendingIceCandidates {
                try? await webRTCService.addIceCandidate(candidate: candidate)
            }
            pendingIceCandidates.removeAll()

        } catch {
            logger.error(error, message: "Failed to process call answer", category: .calls)
            await endCall(reason: .failed)
        }
    }

    /// Handle ICE candidate from remote
    func handleIceCandidate(callId: String, candidate: String, nonce: String, sig: String) async {
        guard let call = currentCall, call.callId == callId else {
            logger.warning("Received ICE candidate for unknown call: \(callId)", category: .calls)
            return
        }

        logger.debug("Received ICE candidate for \(callId)", category: .calls)

        // In real implementation, decrypt the candidate first

        // If we're not yet connected, queue the candidate
        if call.state == .outgoingInitiating || call.state == .incomingRinging {
            pendingIceCandidates.append(candidate)
            return
        }

        do {
            try await webRTCService.addIceCandidate(candidate: candidate)
        } catch {
            logger.error(error, message: "Failed to add ICE candidate", category: .calls)
        }
    }

    /// Handle call_end from remote
    func handleCallEnd(callId: String, reason: CallEndReason) async {
        guard let call = currentCall, call.callId == callId else {
            logger.warning("Received call_end for unknown call: \(callId)", category: .calls)
            return
        }

        logger.info("Remote ended call \(callId) with reason: \(reason.rawValue)", category: .calls)

        transitionState(to: .ended, for: callId)

        // Report to CallKit
        let uuid = UUID(uuidString: callId) ?? UUID()
        callKitService.endCall(uuid: uuid)

        await cleanup()

        delegate?.callService(self, didEndCall: callId, reason: reason)
    }

    /// Handle call_ringing from callee
    func handleCallRinging(callId: String) {
        guard let call = currentCall, call.callId == callId else {
            return
        }

        logger.info("Call \(callId) is ringing", category: .calls)
        // Could update UI to show "Ringing..."
    }

    // MARK: - Private Methods

    private func updateCurrentCall(_ call: CallInfo?) {
        stateLock.lock()
        currentCall = call
        stateLock.unlock()
    }

    private func transitionState(to newState: CallState, for callId: String) {
        stateLock.lock()
        if var call = currentCall, call.callId == callId {
            let oldState = call.state
            call.state = newState

            if newState == .connected {
                call.connectTime = Date()
            } else if newState == .ended {
                call.endTime = Date()
            }

            currentCall = call
            stateLock.unlock()

            logger.info("Call state: \(oldState.rawValue) -> \(newState.rawValue)", category: .calls)

            stateSubject.send(newState)
            delegate?.callService(self, didChangeState: newState, for: callId)
        } else {
            stateLock.unlock()
        }
    }

    private func cleanup() async {
        logger.debug("Cleaning up call resources", category: .calls)

        // Clear pending candidates
        pendingIceCandidates.removeAll()

        // Close WebRTC connection
        webRTCService.close()

        // Deactivate audio session
        audioSessionService.deactivateAudioSession()

        // Clear current call
        updateCurrentCall(nil)

        // Reset state
        stateSubject.send(.idle)
    }

    // MARK: - Signaling Methods

    private func sendCallInitiate(callId: String, to whisperId: String, isVideo: Bool, offer: String) async throws {
        // In real implementation:
        // 1. Get recipient's public key
        // 2. Encrypt the offer SDP
        // 3. Sign the message
        // 4. Send via WebSocket

        // Placeholder - actual implementation depends on your WebSocket service
        logger.debug("Sending call_initiate to server", category: .calls)

        // TODO: Implement actual WebSocket sending
        // let payload = CallInitiatePayload(...)
        // try await websocketService.send(type: .callInitiate, payload: payload)
    }

    private func sendCallRinging(callId: String, to whisperId: String) async throws {
        logger.debug("Sending call_ringing to server", category: .calls)
        // TODO: Implement actual WebSocket sending
    }

    private func sendCallAnswer(callId: String, to whisperId: String, answer: String) async throws {
        logger.debug("Sending call_answer to server", category: .calls)
        // TODO: Implement actual WebSocket sending
    }

    private func sendCallEnd(callId: String, to whisperId: String, reason: CallEndReason) async throws {
        logger.debug("Sending call_end to server", category: .calls)
        // TODO: Implement actual WebSocket sending
    }

    private func sendIceCandidate(callId: String, to whisperId: String, candidate: String) async throws {
        logger.debug("Sending call_ice_candidate to server", category: .calls)
        // TODO: Implement actual WebSocket sending
    }
}

// MARK: - WebRTCServiceDelegate

extension CallService: WebRTCServiceDelegate {
    func webRTCService(_ service: WebRTCService, didGenerateCandidate candidate: String) {
        guard let call = currentCall else { return }

        Task {
            try? await sendIceCandidate(callId: call.callId, to: call.remoteWhisperId, candidate: candidate)
        }
    }

    func webRTCService(_ service: WebRTCService, didChangeConnectionState state: WebRTCConnectionState) {
        guard let call = currentCall else { return }

        logger.info("WebRTC connection state: \(state.rawValue)", category: .calls)

        switch state {
        case .connected:
            transitionState(to: .connected, for: call.callId)
            delegate?.callService(self, didReceiveRemoteTrack: call.callId)

        case .disconnected, .failed:
            Task {
                await endCall(reason: .failed)
            }

        case .closed:
            // Already handling cleanup elsewhere
            break

        default:
            break
        }
    }

    func webRTCService(_ service: WebRTCService, didReceiveRemoteAudioTrack hasAudio: Bool) {
        guard let call = currentCall else { return }
        if hasAudio {
            delegate?.callService(self, didReceiveRemoteTrack: call.callId)
        }
    }
}

// MARK: - CallKitServiceDelegate

extension CallService: CallKitServiceDelegate {
    func callKitService(_ service: CallKitService, didAnswerCall uuid: UUID) {
        Task {
            try? await answerCall()
        }
    }

    func callKitService(_ service: CallKitService, didEndCall uuid: UUID) {
        Task {
            await endCall(reason: .ended)
        }
    }

    func callKitService(_ service: CallKitService, didMuteCall uuid: UUID, isMuted: Bool) {
        webRTCService.setAudioEnabled(!isMuted)
    }

    func callKitService(_ service: CallKitService, didHoldCall uuid: UUID, isOnHold: Bool) {
        // Handle hold if needed
        webRTCService.setAudioEnabled(!isOnHold)
    }

    func callKitService(_ service: CallKitService, providerDidReset provider: Any) {
        Task {
            await cleanup()
        }
    }
}
