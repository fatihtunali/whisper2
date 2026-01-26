import SwiftUI
import Observation
import Combine

/// Call state management - connects to real CallService
@Observable
final class CallViewModel {
    // MARK: - State

    enum ViewState: Equatable {
        case idle
        case initiating
        case ringing
        case connecting
        case connected
        case ended
        case failed(reason: String)
    }

    enum CallDirection {
        case outgoing
        case incoming
    }

    var state: ViewState = .idle
    var direction: CallDirection = .outgoing

    // Participant info
    var participantId: String = ""
    var participantName: String = ""
    var participantAvatarURL: URL?

    // Call info
    var callId: String?
    var startTime: Date?
    var duration: TimeInterval = 0

    // Audio controls
    var isMuted = false
    var isSpeakerOn = false

    // Timer
    private var durationTimer: Timer?
    private var cancellables = Set<AnyCancellable>()

    // MARK: - Dependencies

    private let callService = CallService.shared
    private let keychain = KeychainService.shared

    // MARK: - Init

    init() {
        setupCallServiceObserver()
    }

    // MARK: - Computed Properties

    var formattedDuration: String {
        let minutes = Int(duration) / 60
        let seconds = Int(duration) % 60
        return String(format: "%02d:%02d", minutes, seconds)
    }

    var stateDescription: String {
        switch state {
        case .idle:
            return ""
        case .initiating:
            return "Calling..."
        case .ringing:
            return direction == .outgoing ? "Ringing..." : "Incoming call"
        case .connecting:
            return "Connecting..."
        case .connected:
            return formattedDuration
        case .ended:
            return "Call ended"
        case .failed(let reason):
            return reason
        }
    }

    // MARK: - CallService Observer

    private func setupCallServiceObserver() {
        callService.statePublisher
            .receive(on: DispatchQueue.main)
            .sink { [weak self] callState in
                self?.handleCallStateChange(callState)
            }
            .store(in: &cancellables)
    }

    private func handleCallStateChange(_ callState: CallState) {
        switch callState {
        case .idle:
            state = .idle
        case .outgoingInitiating:
            state = .initiating
            direction = .outgoing
        case .incomingRinging:
            state = .ringing
            direction = .incoming
        case .connecting:
            state = .connecting
        case .connected:
            state = .connected
            if startTime == nil {
                startTime = Date()
                startDurationTimer()
            }
        case .ended:
            stopDurationTimer()
            state = .ended
        }

        // Update call info from service
        if let currentCall = callService.currentCall {
            callId = currentCall.callId
            participantId = currentCall.remoteWhisperId
        }
    }

    // MARK: - Actions

    func initiateCall(to participantId: String, name: String, avatarURL: URL? = nil, isVideo: Bool = false) {
        self.participantId = participantId
        self.participantName = name
        self.participantAvatarURL = avatarURL
        self.direction = .outgoing
        self.state = .initiating

        Task { @MainActor in
            do {
                try await callService.initiateCall(to: participantId, isVideo: isVideo)
            } catch {
                logger.error(error, message: "Failed to initiate call", category: .calls)
                state = .failed(reason: error.localizedDescription)
            }
        }
    }

    func receiveCall(
        callId: String,
        from participantId: String,
        name: String,
        avatarURL: URL? = nil
    ) {
        self.callId = callId
        self.participantId = participantId
        self.participantName = name
        self.participantAvatarURL = avatarURL
        self.direction = .incoming
        self.state = .ringing
    }

    func answerCall() {
        guard state == .ringing && direction == .incoming else { return }

        state = .connecting

        Task { @MainActor in
            do {
                try await callService.answerCall()
            } catch {
                logger.error(error, message: "Failed to answer call", category: .calls)
                state = .failed(reason: error.localizedDescription)
            }
        }
    }

    func declineCall() {
        guard state == .ringing && direction == .incoming else { return }

        Task { @MainActor in
            await callService.endCall(reason: .declined)
        }
    }

    func endCall() {
        stopDurationTimer()

        Task { @MainActor in
            await callService.endCall(reason: .ended)
        }
    }

    func toggleMute() {
        isMuted.toggle()
        // Mute is handled by CallKitService delegate which notifies CallService
        // The WebRTC audio track is toggled there
    }

    func toggleSpeaker() {
        isSpeakerOn.toggle()
        // Audio route change handled by AudioSessionService
    }

    func reset() {
        state = .idle
        direction = .outgoing
        participantId = ""
        participantName = ""
        participantAvatarURL = nil
        callId = nil
        startTime = nil
        duration = 0
        isMuted = false
        isSpeakerOn = false
        stopDurationTimer()
    }

    // MARK: - Private Methods

    private func startDurationTimer() {
        durationTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            guard let self = self, let startTime = self.startTime else { return }
            self.duration = Date().timeIntervalSince(startTime)
        }
    }

    private func stopDurationTimer() {
        durationTimer?.invalidate()
        durationTimer = nil
    }
}
