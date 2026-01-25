import SwiftUI
import Observation

/// Call state management
@Observable
final class CallViewModel {
    // MARK: - State

    enum CallState {
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

    var state: CallState = .idle
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

    // MARK: - Dependencies

    // These will be injected when actual services are implemented
    // private let callService: CallServiceProtocol
    // private let webRTCService: WebRTCServiceProtocol

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

    // MARK: - Actions

    func initiateCall(to participantId: String, name: String, avatarURL: URL? = nil) {
        self.participantId = participantId
        self.participantName = name
        self.participantAvatarURL = avatarURL
        self.direction = .outgoing
        self.state = .initiating

        Task { @MainActor in
            do {
                // Simulate call setup
                try await Task.sleep(for: .seconds(1))
                state = .ringing

                // Simulate answer (for demo)
                try await Task.sleep(for: .seconds(2))
                state = .connecting

                try await Task.sleep(for: .milliseconds(500))
                startCall()
            } catch {
                state = .failed(reason: "Failed to connect")
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
                // Simulate connection
                try await Task.sleep(for: .milliseconds(500))
                startCall()
            } catch {
                state = .failed(reason: "Failed to connect")
            }
        }
    }

    func declineCall() {
        guard state == .ringing && direction == .incoming else { return }
        endCall()
    }

    func endCall() {
        stopDurationTimer()
        state = .ended

        // Clean up after a delay
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(2))
            reset()
        }
    }

    func toggleMute() {
        isMuted.toggle()
        // In real implementation, toggle audio track
    }

    func toggleSpeaker() {
        isSpeakerOn.toggle()
        // In real implementation, change audio route
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

    private func startCall() {
        state = .connected
        startTime = Date()
        startDurationTimer()
    }

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
