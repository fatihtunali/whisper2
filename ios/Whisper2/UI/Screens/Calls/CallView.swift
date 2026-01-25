import SwiftUI

/// Active call UI (audio only)
struct CallView: View {
    @Bindable var viewModel: CallViewModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack {
            // Background
            LinearGradient(
                colors: [
                    Color(red: 0.1, green: 0.1, blue: 0.2),
                    Color(red: 0.05, green: 0.05, blue: 0.1)
                ],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()

            VStack(spacing: 0) {
                Spacer()

                // Avatar and info
                VStack(spacing: Theme.Spacing.lg) {
                    // Animated rings for ringing/connecting states
                    ZStack {
                        if viewModel.state == .ringing || viewModel.state == .connecting {
                            PulsingRings()
                        }

                        AvatarView(
                            name: viewModel.participantName,
                            imageURL: viewModel.participantAvatarURL,
                            size: 120
                        )
                    }

                    VStack(spacing: Theme.Spacing.xs) {
                        Text(viewModel.participantName)
                            .font(Theme.Typography.title1)
                            .foregroundColor(.white)

                        Text(viewModel.stateDescription)
                            .font(Theme.Typography.subheadline)
                            .foregroundColor(.white.opacity(0.7))
                    }
                }

                Spacer()

                // Call quality indicator (when connected)
                if viewModel.state == .connected {
                    HStack(spacing: Theme.Spacing.xs) {
                        Image(systemName: "waveform")
                            .foregroundColor(.green)
                        Text("Encrypted Call")
                            .font(Theme.Typography.caption1)
                            .foregroundColor(.white.opacity(0.6))
                    }
                    .padding(.bottom, Theme.Spacing.xl)
                }

                // Control buttons
                VStack(spacing: Theme.Spacing.xl) {
                    // Secondary controls
                    HStack(spacing: Theme.Spacing.xxxl) {
                        CallControlButton(
                            icon: viewModel.isMuted ? "mic.slash.fill" : "mic.fill",
                            label: viewModel.isMuted ? "Unmute" : "Mute",
                            isActive: viewModel.isMuted
                        ) {
                            viewModel.toggleMute()
                        }

                        CallControlButton(
                            icon: viewModel.isSpeakerOn ? "speaker.wave.3.fill" : "speaker.fill",
                            label: "Speaker",
                            isActive: viewModel.isSpeakerOn
                        ) {
                            viewModel.toggleSpeaker()
                        }

                        CallControlButton(
                            icon: "ellipsis",
                            label: "More"
                        ) {
                            // Show more options
                        }
                    }

                    // End call button
                    Button {
                        viewModel.endCall()
                    } label: {
                        Image(systemName: "phone.down.fill")
                            .font(.system(size: 28))
                            .foregroundColor(.white)
                            .frame(width: 72, height: 72)
                            .background(Color.red)
                            .clipShape(Circle())
                    }
                }
                .padding(.bottom, Theme.Spacing.huge)
            }
        }
        .onChange(of: viewModel.state) { _, newState in
            if case .idle = newState {
                dismiss()
            }
        }
    }
}

// MARK: - Call Control Button

private struct CallControlButton: View {
    let icon: String
    let label: String
    var isActive: Bool = false
    let action: () -> Void

    var body: some View {
        Button {
            action()
        } label: {
            VStack(spacing: Theme.Spacing.xs) {
                Image(systemName: icon)
                    .font(.system(size: 24))
                    .foregroundColor(isActive ? .black : .white)
                    .frame(width: 56, height: 56)
                    .background(isActive ? .white : .white.opacity(0.2))
                    .clipShape(Circle())

                Text(label)
                    .font(Theme.Typography.caption2)
                    .foregroundColor(.white.opacity(0.7))
            }
        }
    }
}

// MARK: - Pulsing Rings Animation

private struct PulsingRings: View {
    @State private var animate = false

    var body: some View {
        ZStack {
            ForEach(0..<3, id: \.self) { index in
                Circle()
                    .stroke(Color.white.opacity(0.3), lineWidth: 2)
                    .frame(width: 120 + CGFloat(index * 40), height: 120 + CGFloat(index * 40))
                    .scaleEffect(animate ? 1.3 : 1.0)
                    .opacity(animate ? 0 : 0.5)
                    .animation(
                        .easeOut(duration: 2)
                            .repeatForever(autoreverses: false)
                            .delay(Double(index) * 0.4),
                        value: animate
                    )
            }
        }
        .onAppear {
            animate = true
        }
    }
}

// MARK: - Preview

#Preview {
    CallView(viewModel: {
        let vm = CallViewModel()
        vm.participantName = "Alice Smith"
        vm.state = .connected
        vm.duration = 125
        return vm
    }())
}
