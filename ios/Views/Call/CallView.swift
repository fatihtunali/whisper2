import SwiftUI
import AVFoundation

/// Main call view showing active call UI
struct CallView: View {
    @ObservedObject var callService = CallService.shared
    @Environment(\.dismiss) private var dismiss
    @State private var callDuration: TimeInterval = 0
    @State private var timer: Timer?

    var body: some View {
        ZStack {
            // Background
            Color.black.ignoresSafeArea()

            // Video views (if video call)
            if callService.activeCall?.isVideo == true {
                VideoCallView()
            }

            // Call UI overlay
            VStack {
                // Top bar with call info
                CallInfoBar(
                    peerName: callService.activeCall?.peerName ?? callService.activeCall?.peerId ?? "Unknown",
                    callState: callService.callState,
                    duration: callDuration,
                    isVideo: callService.activeCall?.isVideo ?? false
                )

                Spacer()

                // Call controls
                CallControlsView(
                    isMuted: callService.activeCall?.isMuted ?? false,
                    isSpeakerOn: callService.activeCall?.isSpeakerOn ?? false,
                    isVideoEnabled: callService.activeCall?.isLocalVideoEnabled ?? true,
                    isVideo: callService.activeCall?.isVideo ?? false,
                    onMuteToggle: { callService.toggleMute() },
                    onSpeakerToggle: { callService.toggleSpeaker() },
                    onVideoToggle: { callService.toggleLocalVideo() },
                    onCameraSwitch: { callService.switchCamera() },
                    onEndCall: { endCall() }
                )
                .padding(.bottom, 50)
            }
        }
        .onAppear {
            startTimer()
        }
        .onDisappear {
            stopTimer()
        }
        .onChange(of: callService.callState) { _, newState in
            if case .ended = newState {
                stopTimer()
                // Dismiss after a short delay to show end reason
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                    dismiss()
                }
            }
        }
    }

    private func startTimer() {
        timer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { _ in
            if let startTime = callService.activeCall?.startTime {
                callDuration = Date().timeIntervalSince(startTime)
            }
        }
    }

    private func stopTimer() {
        timer?.invalidate()
        timer = nil
    }

    private func endCall() {
        Task {
            try? await callService.endCall()
        }
    }
}

/// Call info bar at the top
struct CallInfoBar: View {
    let peerName: String
    let callState: CallState
    let duration: TimeInterval
    let isVideo: Bool

    var body: some View {
        VStack(spacing: 8) {
            // Avatar (for audio calls)
            if !isVideo {
                Circle()
                    .fill(Color.gray.opacity(0.3))
                    .frame(width: 100, height: 100)
                    .overlay(
                        Text(String(peerName.prefix(1)).uppercased())
                            .font(.system(size: 40, weight: .semibold))
                            .foregroundColor(.white)
                    )
                    .padding(.top, 60)
            }

            // Name
            Text(peerName)
                .font(.title2)
                .fontWeight(.semibold)
                .foregroundColor(.white)
                .padding(.top, isVideo ? 60 : 16)

            // Status/Duration
            Text(statusText)
                .font(.subheadline)
                .foregroundColor(.gray)
        }
        .padding()
    }

    private var statusText: String {
        switch callState {
        case .idle:
            return ""
        case .initiating:
            return "Calling..."
        case .ringing:
            return "Ringing..."
        case .connecting:
            return "Connecting..."
        case .connected:
            return formatDuration(duration)
        case .reconnecting:
            return "Reconnecting..."
        case .ended(let reason):
            return endReasonText(reason)
        }
    }

    private func formatDuration(_ duration: TimeInterval) -> String {
        let minutes = Int(duration) / 60
        let seconds = Int(duration) % 60
        return String(format: "%02d:%02d", minutes, seconds)
    }

    private func endReasonText(_ reason: CallEndReason) -> String {
        switch reason {
        case .ended:
            return "Call ended"
        case .declined:
            return "Call declined"
        case .busy:
            return "User busy"
        case .timeout:
            return "No answer"
        case .failed:
            return "Call failed"
        case .cancelled:
            return "Call cancelled"
        }
    }
}

/// Call control buttons
struct CallControlsView: View {
    let isMuted: Bool
    let isSpeakerOn: Bool
    let isVideoEnabled: Bool
    let isVideo: Bool
    let onMuteToggle: () -> Void
    let onSpeakerToggle: () -> Void
    let onVideoToggle: () -> Void
    let onCameraSwitch: () -> Void
    let onEndCall: () -> Void

    var body: some View {
        VStack(spacing: 24) {
            // Top row of controls
            HStack(spacing: 40) {
                // Mute
                CallControlButton(
                    icon: isMuted ? "mic.slash.fill" : "mic.fill",
                    label: isMuted ? "Unmute" : "Mute",
                    isActive: isMuted,
                    action: onMuteToggle
                )

                // Video toggle (video calls only)
                if isVideo {
                    CallControlButton(
                        icon: isVideoEnabled ? "video.fill" : "video.slash.fill",
                        label: isVideoEnabled ? "Stop Video" : "Start Video",
                        isActive: !isVideoEnabled,
                        action: onVideoToggle
                    )
                }

                // Speaker
                CallControlButton(
                    icon: isSpeakerOn ? "speaker.wave.3.fill" : "speaker.fill",
                    label: isSpeakerOn ? "Speaker On" : "Speaker",
                    isActive: isSpeakerOn,
                    action: onSpeakerToggle
                )

                // Switch camera (video calls only)
                if isVideo {
                    CallControlButton(
                        icon: "camera.rotate.fill",
                        label: "Flip",
                        isActive: false,
                        action: onCameraSwitch
                    )
                }
            }

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
        }
    }
}

/// Individual call control button
struct CallControlButton: View {
    let icon: String
    let label: String
    let isActive: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 8) {
                Circle()
                    .fill(isActive ? Color.white : Color.gray.opacity(0.3))
                    .frame(width: 56, height: 56)
                    .overlay(
                        Image(systemName: icon)
                            .font(.title3)
                            .foregroundColor(isActive ? .black : .white)
                    )

                Text(label)
                    .font(.caption2)
                    .foregroundColor(.gray)
            }
        }
    }
}

/// Video call view with local and remote video
struct VideoCallView: View {
    @ObservedObject var callService = CallService.shared

    var body: some View {
        ZStack {
            // Remote video (full screen)
            RemoteVideoView()
                .ignoresSafeArea()

            // Local video (picture-in-picture)
            VStack {
                HStack {
                    Spacer()
                    LocalVideoView()
                        .frame(width: 120, height: 160)
                        .cornerRadius(12)
                        .overlay(
                            RoundedRectangle(cornerRadius: 12)
                                .stroke(Color.white.opacity(0.3), lineWidth: 1)
                        )
                        .padding()
                }
                Spacer()
            }
        }
    }
}

/// Remote video view wrapper
struct RemoteVideoView: UIViewRepresentable {
    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.backgroundColor = .black
        // WebRTC remote video renderer would be added here
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {}
}

/// Local video view wrapper
struct LocalVideoView: UIViewRepresentable {
    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.backgroundColor = .darkGray
        // WebRTC local video renderer would be added here
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {}
}

#Preview {
    CallView()
}
