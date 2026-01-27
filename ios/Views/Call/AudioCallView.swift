import SwiftUI

/// Active audio call view - shown when audio call is connected
struct AudioCallView: View {
    @ObservedObject var state: ActiveAudioCallState
    let onEndCall: () -> Void
    let onToggleMute: () -> Void
    let onToggleSpeaker: () -> Void

    var body: some View {
        ZStack {
            // Background gradient
            LinearGradient(
                gradient: Gradient(colors: [
                    Color(red: 0.1, green: 0.1, blue: 0.2),
                    Color(red: 0.05, green: 0.05, blue: 0.1)
                ]),
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()

            VStack(spacing: 30) {
                Spacer()

                // Call status
                Text("Audio Call")
                    .font(.subheadline)
                    .foregroundColor(.green)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                    .background(Color.green.opacity(0.2))
                    .cornerRadius(20)

                // Avatar
                ZStack {
                    Circle()
                        .fill(Color.gray.opacity(0.3))
                        .frame(width: 120, height: 120)

                    Text(String(state.peerName.prefix(1)).uppercased())
                        .font(.system(size: 50, weight: .semibold))
                        .foregroundColor(.white)
                }

                // Name
                Text(state.peerName)
                    .font(.title)
                    .fontWeight(.semibold)
                    .foregroundColor(.white)

                // Duration
                Text(state.callDuration)
                    .font(.title2)
                    .foregroundColor(.white.opacity(0.8))
                    .monospacedDigit()

                Spacer()

                // Controls
                HStack(spacing: 60) {
                    // Mute button
                    VStack(spacing: 8) {
                        Button(action: onToggleMute) {
                            Circle()
                                .fill(state.isMuted ? Color.white : Color.white.opacity(0.2))
                                .frame(width: 60, height: 60)
                                .overlay(
                                    Image(systemName: state.isMuted ? "mic.slash.fill" : "mic.fill")
                                        .font(.title2)
                                        .foregroundColor(state.isMuted ? .black : .white)
                                )
                        }
                        Text(state.isMuted ? "Unmute" : "Mute")
                            .font(.caption)
                            .foregroundColor(.white.opacity(0.7))
                    }

                    // Speaker button
                    VStack(spacing: 8) {
                        Button(action: onToggleSpeaker) {
                            Circle()
                                .fill(state.isSpeakerOn ? Color.white : Color.white.opacity(0.2))
                                .frame(width: 60, height: 60)
                                .overlay(
                                    Image(systemName: state.isSpeakerOn ? "speaker.wave.3.fill" : "speaker.fill")
                                        .font(.title2)
                                        .foregroundColor(state.isSpeakerOn ? .black : .white)
                                )
                        }
                        Text(state.isSpeakerOn ? "Speaker" : "Speaker")
                            .font(.caption)
                            .foregroundColor(.white.opacity(0.7))
                    }
                }
                .padding(.bottom, 40)

                // End call button
                Button(action: onEndCall) {
                    Circle()
                        .fill(Color.red)
                        .frame(width: 70, height: 70)
                        .overlay(
                            Image(systemName: "phone.down.fill")
                                .font(.title)
                                .foregroundColor(.white)
                        )
                }
                .padding(.bottom, 60)
            }
        }
        .statusBar(hidden: true)
    }
}

/// Observable state for active audio call
@MainActor
class ActiveAudioCallState: ObservableObject {
    static let shared = ActiveAudioCallState()

    @Published var isShowingAudioCall = false
    @Published var peerName: String = ""
    @Published var peerId: String = ""
    @Published var callId: String = ""
    @Published var isMuted: Bool = false
    @Published var isSpeakerOn: Bool = false
    @Published var callDuration: String = "00:00"

    private var callStartTime: Date?
    private var durationTimer: Timer?

    private init() {}

    func showAudioCall(callId: String, peerName: String, peerId: String) {
        self.callId = callId
        self.peerName = peerName
        self.peerId = peerId
        self.isMuted = false
        self.isSpeakerOn = false
        self.callDuration = "00:00"
        self.callStartTime = Date()

        withAnimation(.easeIn(duration: 0.3)) {
            self.isShowingAudioCall = true
        }

        // Start duration timer
        startDurationTimer()

        print("AudioCallState: Showing audio call - callId: \(callId), peer: \(peerName)")
    }

    func hide() {
        print("AudioCallState: Hiding")
        self.callStartTime = nil
        stopDurationTimer()

        withAnimation(.easeOut(duration: 0.3)) {
            self.isShowingAudioCall = false
        }
    }

    private func startDurationTimer() {
        durationTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            Task { @MainActor in
                self?.updateDuration()
            }
        }
    }

    private func stopDurationTimer() {
        durationTimer?.invalidate()
        durationTimer = nil
    }

    private func updateDuration() {
        guard let startTime = callStartTime else { return }
        let elapsed = Int(Date().timeIntervalSince(startTime))
        let minutes = elapsed / 60
        let seconds = elapsed % 60
        callDuration = String(format: "%02d:%02d", minutes, seconds)
    }
}

#Preview {
    AudioCallView(
        state: ActiveAudioCallState.shared,
        onEndCall: {},
        onToggleMute: {},
        onToggleSpeaker: {}
    )
}
