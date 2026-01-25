import Foundation
import WebRTC

// MARK: - WebRTC Connection State

/// Simplified connection state enum
enum WebRTCConnectionState: String {
    case new
    case connecting
    case connected
    case disconnected
    case failed
    case closed
}

// MARK: - WebRTC Service Delegate

protocol WebRTCServiceDelegate: AnyObject {
    /// Called when a new ICE candidate is generated
    func webRTCService(_ service: WebRTCService, didGenerateCandidate candidate: String)

    /// Called when the connection state changes
    func webRTCService(_ service: WebRTCService, didChangeConnectionState state: WebRTCConnectionState)

    /// Called when remote audio track is received
    func webRTCService(_ service: WebRTCService, didReceiveRemoteAudioTrack hasAudio: Bool)
}

// MARK: - WebRTC Service

/// WebRTC wrapper service for audio/video calls
final class WebRTCService: NSObject {

    // MARK: - Properties

    weak var delegate: WebRTCServiceDelegate?

    private var peerConnectionFactory: RTCPeerConnectionFactory?
    private var peerConnection: RTCPeerConnection?
    private var localAudioTrack: RTCAudioTrack?
    private var remoteAudioTrack: RTCAudioTrack?

    private var pendingCandidates: [RTCIceCandidate] = []
    private var hasRemoteDescription = false

    // MARK: - Constants

    private static let audioTrackId = "audio0"
    private static let mediaStreamId = "stream0"

    // MARK: - Initialization

    override init() {
        super.init()
        initializeFactory()
    }

    private func initializeFactory() {
        // Initialize WebRTC
        RTCInitializeSSL()

        // Create factory with default encoders/decoders
        let videoEncoderFactory = RTCDefaultVideoEncoderFactory()
        let videoDecoderFactory = RTCDefaultVideoDecoderFactory()

        peerConnectionFactory = RTCPeerConnectionFactory(
            encoderFactory: videoEncoderFactory,
            decoderFactory: videoDecoderFactory
        )

        logger.debug("WebRTC factory initialized", category: .calls)
    }

    // MARK: - Public API

    /// Create a peer connection with the given ICE servers
    /// - Parameter iceServers: Array of ICE server configurations
    func createPeerConnection(iceServers: [RTCIceServer]) throws {
        guard let factory = peerConnectionFactory else {
            throw CallError.webRTCFailed(reason: "Factory not initialized")
        }

        // Clean up existing connection
        close()

        // Configure peer connection
        let config = RTCConfiguration()
        config.iceServers = iceServers
        config.sdpSemantics = .unifiedPlan
        config.continualGatheringPolicy = .gatherContinually
        config.bundlePolicy = .maxBundle
        config.rtcpMuxPolicy = .require
        config.tcpCandidatePolicy = .disabled
        config.candidateNetworkPolicy = .all
        config.iceTransportPolicy = .all

        // Create constraints
        let constraints = RTCMediaConstraints(
            mandatoryConstraints: nil,
            optionalConstraints: ["DtlsSrtpKeyAgreement": kRTCMediaConstraintsValueTrue]
        )

        // Create peer connection
        guard let pc = factory.peerConnection(
            with: config,
            constraints: constraints,
            delegate: self
        ) else {
            throw CallError.webRTCFailed(reason: "Failed to create peer connection")
        }

        peerConnection = pc

        // Create and add audio track
        try addLocalAudioTrack()

        logger.info("Peer connection created with \(iceServers.count) ICE servers", category: .calls)
    }

    /// Create an SDP offer
    /// - Returns: The SDP offer string
    func createOffer() async throws -> String {
        guard let pc = peerConnection else {
            throw CallError.webRTCFailed(reason: "No peer connection")
        }

        let constraints = RTCMediaConstraints(
            mandatoryConstraints: [
                kRTCMediaConstraintsOfferToReceiveAudio: kRTCMediaConstraintsValueTrue,
                kRTCMediaConstraintsOfferToReceiveVideo: kRTCMediaConstraintsValueFalse
            ],
            optionalConstraints: nil
        )

        return try await withCheckedThrowingContinuation { continuation in
            pc.offer(for: constraints) { [weak self] sdp, error in
                if let error = error {
                    continuation.resume(throwing: CallError.webRTCFailed(reason: error.localizedDescription))
                    return
                }

                guard let sdp = sdp else {
                    continuation.resume(throwing: CallError.webRTCFailed(reason: "No SDP generated"))
                    return
                }

                pc.setLocalDescription(sdp) { error in
                    if let error = error {
                        continuation.resume(throwing: CallError.webRTCFailed(reason: error.localizedDescription))
                        return
                    }

                    logger.debug("Local description (offer) set", category: .calls)
                    continuation.resume(returning: sdp.sdp)
                }
            }
        }
    }

    /// Create an SDP answer (after receiving an offer)
    /// - Returns: The SDP answer string
    func createAnswer() async throws -> String {
        guard let pc = peerConnection else {
            throw CallError.webRTCFailed(reason: "No peer connection")
        }

        let constraints = RTCMediaConstraints(
            mandatoryConstraints: [
                kRTCMediaConstraintsOfferToReceiveAudio: kRTCMediaConstraintsValueTrue,
                kRTCMediaConstraintsOfferToReceiveVideo: kRTCMediaConstraintsValueFalse
            ],
            optionalConstraints: nil
        )

        return try await withCheckedThrowingContinuation { continuation in
            pc.answer(for: constraints) { [weak self] sdp, error in
                if let error = error {
                    continuation.resume(throwing: CallError.webRTCFailed(reason: error.localizedDescription))
                    return
                }

                guard let sdp = sdp else {
                    continuation.resume(throwing: CallError.webRTCFailed(reason: "No SDP generated"))
                    return
                }

                pc.setLocalDescription(sdp) { error in
                    if let error = error {
                        continuation.resume(throwing: CallError.webRTCFailed(reason: error.localizedDescription))
                        return
                    }

                    logger.debug("Local description (answer) set", category: .calls)
                    continuation.resume(returning: sdp.sdp)
                }
            }
        }
    }

    /// Set the remote session description
    /// - Parameters:
    ///   - sdp: The SDP string
    ///   - type: The SDP type (offer or answer)
    func setRemoteDescription(sdp: String, type: RTCSdpType) async throws {
        guard let pc = peerConnection else {
            throw CallError.webRTCFailed(reason: "No peer connection")
        }

        let sessionDescription = RTCSessionDescription(type: type, sdp: sdp)

        return try await withCheckedThrowingContinuation { continuation in
            pc.setRemoteDescription(sessionDescription) { [weak self] error in
                if let error = error {
                    continuation.resume(throwing: CallError.webRTCFailed(reason: error.localizedDescription))
                    return
                }

                self?.hasRemoteDescription = true

                // Process any pending ICE candidates
                self?.processPendingCandidates()

                logger.debug("Remote description set (type: \(type.rawValue))", category: .calls)
                continuation.resume()
            }
        }
    }

    /// Add an ICE candidate from the remote peer
    /// - Parameter candidate: The ICE candidate string (JSON)
    func addIceCandidate(candidate: String) async throws {
        guard let pc = peerConnection else {
            throw CallError.webRTCFailed(reason: "No peer connection")
        }

        // Parse candidate JSON
        guard let data = candidate.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let sdp = json["candidate"] as? String,
              let sdpMLineIndex = json["sdpMLineIndex"] as? Int32,
              let sdpMid = json["sdpMid"] as? String else {
            throw CallError.webRTCFailed(reason: "Invalid ICE candidate format")
        }

        let iceCandidate = RTCIceCandidate(sdp: sdp, sdpMLineIndex: sdpMLineIndex, sdpMid: sdpMid)

        // If we don't have remote description yet, queue the candidate
        if !hasRemoteDescription {
            pendingCandidates.append(iceCandidate)
            logger.debug("Queued ICE candidate (no remote description yet)", category: .calls)
            return
        }

        return try await withCheckedThrowingContinuation { continuation in
            pc.add(iceCandidate) { error in
                if let error = error {
                    logger.warning("Failed to add ICE candidate: \(error.localizedDescription)", category: .calls)
                    // Don't fail - some candidates may be invalid but others work
                }
                continuation.resume()
            }
        }
    }

    /// Enable or disable the local audio track
    /// - Parameter enabled: Whether audio should be enabled
    func setAudioEnabled(_ enabled: Bool) {
        localAudioTrack?.isEnabled = enabled
        logger.debug("Audio enabled: \(enabled)", category: .calls)
    }

    /// Close the peer connection and clean up
    func close() {
        logger.debug("Closing WebRTC connection", category: .calls)

        localAudioTrack = nil
        remoteAudioTrack = nil
        pendingCandidates.removeAll()
        hasRemoteDescription = false

        peerConnection?.close()
        peerConnection = nil
    }

    // MARK: - Private Methods

    private func addLocalAudioTrack() throws {
        guard let factory = peerConnectionFactory,
              let pc = peerConnection else {
            throw CallError.webRTCFailed(reason: "Not initialized")
        }

        // Create audio source and track
        let audioSource = factory.audioSource(with: audioConstraints())
        let audioTrack = factory.audioTrack(with: audioSource, trackId: Self.audioTrackId)

        // Add track to peer connection
        pc.add(audioTrack, streamIds: [Self.mediaStreamId])

        localAudioTrack = audioTrack
        localAudioTrack?.isEnabled = true

        logger.debug("Local audio track added", category: .calls)
    }

    private func audioConstraints() -> RTCMediaConstraints {
        return RTCMediaConstraints(
            mandatoryConstraints: nil,
            optionalConstraints: [
                "googEchoCancellation": kRTCMediaConstraintsValueTrue,
                "googAutoGainControl": kRTCMediaConstraintsValueTrue,
                "googNoiseSuppression": kRTCMediaConstraintsValueTrue,
                "googHighpassFilter": kRTCMediaConstraintsValueTrue
            ]
        )
    }

    private func processPendingCandidates() {
        guard let pc = peerConnection, hasRemoteDescription else { return }

        for candidate in pendingCandidates {
            pc.add(candidate) { error in
                if let error = error {
                    logger.warning("Failed to add pending ICE candidate: \(error.localizedDescription)", category: .calls)
                }
            }
        }

        let count = pendingCandidates.count
        pendingCandidates.removeAll()

        if count > 0 {
            logger.debug("Processed \(count) pending ICE candidates", category: .calls)
        }
    }

    private func mapConnectionState(_ state: RTCIceConnectionState) -> WebRTCConnectionState {
        switch state {
        case .new: return .new
        case .checking: return .connecting
        case .connected, .completed: return .connected
        case .disconnected: return .disconnected
        case .failed: return .failed
        case .closed: return .closed
        @unknown default: return .new
        }
    }
}

// MARK: - RTCPeerConnectionDelegate

extension WebRTCService: RTCPeerConnectionDelegate {

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {
        logger.debug("Signaling state: \(stateChanged.rawValue)", category: .calls)
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {
        logger.debug("Remote stream added with \(stream.audioTracks.count) audio tracks", category: .calls)

        if let audioTrack = stream.audioTracks.first {
            remoteAudioTrack = audioTrack
            delegate?.webRTCService(self, didReceiveRemoteAudioTrack: true)
        }
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {
        logger.debug("Remote stream removed", category: .calls)
        remoteAudioTrack = nil
    }

    func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {
        logger.debug("Negotiation needed", category: .calls)
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
        let state = mapConnectionState(newState)
        logger.info("ICE connection state: \(state.rawValue)", category: .calls)
        delegate?.webRTCService(self, didChangeConnectionState: state)
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {
        logger.debug("ICE gathering state: \(newState.rawValue)", category: .calls)
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        // Serialize candidate to JSON
        let candidateDict: [String: Any] = [
            "candidate": candidate.sdp,
            "sdpMLineIndex": candidate.sdpMLineIndex,
            "sdpMid": candidate.sdpMid ?? ""
        ]

        if let data = try? JSONSerialization.data(withJSONObject: candidateDict),
           let jsonString = String(data: data, encoding: .utf8) {
            logger.debug("Generated ICE candidate", category: .calls)
            delegate?.webRTCService(self, didGenerateCandidate: jsonString)
        }
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {
        logger.debug("ICE candidates removed: \(candidates.count)", category: .calls)
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {
        logger.debug("Data channel opened: \(dataChannel.label)", category: .calls)
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCPeerConnectionState) {
        logger.debug("Peer connection state: \(newState.rawValue)", category: .calls)
    }
}
