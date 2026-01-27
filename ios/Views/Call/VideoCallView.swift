import SwiftUI
import WebRTC

/// Active video call view - shown when video call is connected
struct VideoCallView: View {
    @ObservedObject var state: ActiveVideoCallState
    let onEndCall: () -> Void
    let onToggleMute: () -> Void
    let onToggleCamera: () -> Void
    let onSwitchCamera: () -> Void
    let onToggleSpeaker: () -> Void

    @State private var showControls = true
    @State private var hideControlsTask: Task<Void, Never>?

    var body: some View {
        ZStack {
            // Background
            Color.black.ignoresSafeArea()

            // Remote video (full screen)
            if let remoteRenderer = state.remoteVideoView {
                VideoRendererView(renderer: remoteRenderer)
                    .ignoresSafeArea()
            } else {
                // Placeholder when no remote video
                VStack {
                    Image(systemName: "video.slash.fill")
                        .font(.system(size: 60))
                        .foregroundColor(.gray)
                    Text("Waiting for video...")
                        .foregroundColor(.gray)
                        .padding(.top, 16)
                }
            }

            // Local video (picture-in-picture)
            VStack {
                HStack {
                    Spacer()
                    if let localRenderer = state.localVideoView {
                        VideoRendererView(renderer: localRenderer)
                            .frame(width: 120, height: 160)
                            .cornerRadius(12)
                            .overlay(
                                RoundedRectangle(cornerRadius: 12)
                                    .stroke(Color.white.opacity(0.3), lineWidth: 1)
                            )
                            .padding(.trailing, 16)
                            .padding(.top, 60)
                    }
                }
                Spacer()
            }

            // Controls overlay
            if showControls {
                VStack {
                    // Top bar with peer info and duration
                    HStack {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(state.peerName)
                                .font(.headline)
                                .foregroundColor(.white)
                            Text(state.callDuration)
                                .font(.subheadline)
                                .foregroundColor(.white.opacity(0.7))
                        }
                        Spacer()
                    }
                    .padding(.horizontal, 20)
                    .padding(.top, 60)

                    Spacer()

                    // Bottom controls
                    HStack(spacing: 40) {
                        // Mute button
                        ControlButton(
                            icon: state.isMuted ? "mic.slash.fill" : "mic.fill",
                            isActive: state.isMuted,
                            action: onToggleMute
                        )

                        // Camera toggle
                        ControlButton(
                            icon: state.isCameraOff ? "video.slash.fill" : "video.fill",
                            isActive: state.isCameraOff,
                            action: onToggleCamera
                        )

                        // Switch camera
                        ControlButton(
                            icon: "camera.rotate.fill",
                            isActive: false,
                            action: onSwitchCamera
                        )

                        // Speaker
                        ControlButton(
                            icon: state.isSpeakerOn ? "speaker.wave.3.fill" : "speaker.fill",
                            isActive: state.isSpeakerOn,
                            action: onToggleSpeaker
                        )
                    }
                    .padding(.bottom, 30)

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
                    .padding(.bottom, 50)
                }
                .background(
                    LinearGradient(
                        gradient: Gradient(colors: [
                            Color.black.opacity(0.7),
                            Color.clear,
                            Color.clear,
                            Color.black.opacity(0.7)
                        ]),
                        startPoint: .top,
                        endPoint: .bottom
                    )
                    .ignoresSafeArea()
                )
            }
        }
        .onTapGesture {
            withAnimation(.easeInOut(duration: 0.2)) {
                showControls.toggle()
            }
            scheduleHideControls()
        }
        .onAppear {
            scheduleHideControls()
        }
        .statusBar(hidden: true)
    }

    private func scheduleHideControls() {
        hideControlsTask?.cancel()
        hideControlsTask = Task {
            try? await Task.sleep(nanoseconds: 5_000_000_000) // 5 seconds
            if !Task.isCancelled {
                await MainActor.run {
                    withAnimation(.easeInOut(duration: 0.2)) {
                        showControls = false
                    }
                }
            }
        }
    }
}

/// Control button for video call
struct ControlButton: View {
    let icon: String
    let isActive: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Circle()
                .fill(isActive ? Color.white : Color.white.opacity(0.3))
                .frame(width: 50, height: 50)
                .overlay(
                    Image(systemName: icon)
                        .font(.title3)
                        .foregroundColor(isActive ? .black : .white)
                )
        }
    }
}

/// SwiftUI wrapper for RTCVideoRenderer
struct VideoRendererView: UIViewRepresentable {
    let renderer: RTCMTLVideoView

    func makeUIView(context: Context) -> RTCMTLVideoView {
        renderer.videoContentMode = .scaleAspectFill
        return renderer
    }

    func updateUIView(_ uiView: RTCMTLVideoView, context: Context) {}
}

/// Observable state for active video call
@MainActor
class ActiveVideoCallState: ObservableObject {
    static let shared = ActiveVideoCallState()

    @Published var isShowingVideoCall = false
    @Published var peerName: String = ""
    @Published var peerId: String = ""
    @Published var callId: String = ""
    @Published var isMuted: Bool = false
    @Published var isCameraOff: Bool = false
    @Published var isSpeakerOn: Bool = true // Speaker on by default for video calls
    @Published var callDuration: String = "00:00"

    // Video renderers
    @Published var localVideoView: RTCMTLVideoView?
    @Published var remoteVideoView: RTCMTLVideoView?

    private var callStartTime: Date?
    private var durationTimer: Timer?

    private init() {}

    func showVideoCall(callId: String, peerName: String, peerId: String) {
        self.callId = callId
        self.peerName = peerName
        self.peerId = peerId
        self.isMuted = false
        self.isCameraOff = false
        self.isSpeakerOn = true
        self.callDuration = "00:00"
        self.callStartTime = Date()
        self.isShowingVideoCall = true

        // Create video renderers
        self.localVideoView = RTCMTLVideoView()
        self.remoteVideoView = RTCMTLVideoView()

        // Start duration timer
        startDurationTimer()

        print("VideoCallState: Showing video call - callId: \(callId), peer: \(peerName)")
    }

    func hide() {
        self.isShowingVideoCall = false
        self.callStartTime = nil
        stopDurationTimer()

        // Clean up renderers
        self.localVideoView = nil
        self.remoteVideoView = nil

        print("VideoCallState: Hidden")
    }

    func toggleMute() {
        isMuted.toggle()
    }

    func toggleCamera() {
        isCameraOff.toggle()
    }

    func toggleSpeaker() {
        isSpeakerOn.toggle()
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
    VideoCallView(
        state: ActiveVideoCallState.shared,
        onEndCall: {},
        onToggleMute: {},
        onToggleCamera: {},
        onSwitchCamera: {},
        onToggleSpeaker: {}
    )
}
