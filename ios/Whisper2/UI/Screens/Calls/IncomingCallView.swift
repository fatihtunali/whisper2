import SwiftUI

/// Incoming call screen (used with CallKit)
struct IncomingCallView: View {
    @Bindable var viewModel: CallViewModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack {
            // Background
            LinearGradient(
                colors: [
                    Color(red: 0.0, green: 0.3, blue: 0.5),
                    Color(red: 0.0, green: 0.15, blue: 0.3)
                ],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()

            VStack(spacing: 0) {
                // Header
                HStack {
                    Spacer()
                    VStack(spacing: Theme.Spacing.xxs) {
                        Image(systemName: "lock.fill")
                            .font(.system(size: 12))
                        Text("Encrypted")
                            .font(Theme.Typography.caption2)
                    }
                    .foregroundColor(.white.opacity(0.6))
                    .padding()
                }

                Spacer()

                // Caller info
                VStack(spacing: Theme.Spacing.lg) {
                    // Animated avatar
                    ZStack {
                        // Pulsing background
                        Circle()
                            .fill(Color.white.opacity(0.1))
                            .frame(width: 160, height: 160)
                            .modifier(PulseModifier())

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

                        Text("Whisper2 Audio Call")
                            .font(Theme.Typography.subheadline)
                            .foregroundColor(.white.opacity(0.7))
                    }
                }

                Spacer()

                // Action buttons
                HStack(spacing: Theme.Spacing.huge) {
                    // Decline
                    VStack(spacing: Theme.Spacing.sm) {
                        Button {
                            viewModel.declineCall()
                        } label: {
                            Image(systemName: "phone.down.fill")
                                .font(.system(size: 28))
                                .foregroundColor(.white)
                                .frame(width: 72, height: 72)
                                .background(Color.red)
                                .clipShape(Circle())
                        }

                        Text("Decline")
                            .font(Theme.Typography.caption1)
                            .foregroundColor(.white.opacity(0.7))
                    }

                    // Accept
                    VStack(spacing: Theme.Spacing.sm) {
                        Button {
                            viewModel.answerCall()
                        } label: {
                            Image(systemName: "phone.fill")
                                .font(.system(size: 28))
                                .foregroundColor(.white)
                                .frame(width: 72, height: 72)
                                .background(Color.green)
                                .clipShape(Circle())
                        }

                        Text("Accept")
                            .font(Theme.Typography.caption1)
                            .foregroundColor(.white.opacity(0.7))
                    }
                }
                .padding(.bottom, Theme.Spacing.huge)

                // Slide to answer (alternative)
                SlideToAnswerView {
                    viewModel.answerCall()
                }
                .padding(.horizontal, Theme.Spacing.xl)
                .padding(.bottom, Theme.Spacing.xxl)
            }
        }
        .onChange(of: viewModel.state) { _, newState in
            switch newState {
            case .connected, .connecting:
                // Transition to active call view handled by parent
                break
            case .ended, .idle:
                dismiss()
            default:
                break
            }
        }
    }
}

// MARK: - Pulse Modifier

private struct PulseModifier: ViewModifier {
    @State private var isPulsing = false

    func body(content: Content) -> some View {
        content
            .scaleEffect(isPulsing ? 1.1 : 1.0)
            .opacity(isPulsing ? 0.5 : 1.0)
            .animation(
                .easeInOut(duration: 1.0)
                    .repeatForever(autoreverses: true),
                value: isPulsing
            )
            .onAppear {
                isPulsing = true
            }
    }
}

// MARK: - Slide to Answer

private struct SlideToAnswerView: View {
    let onAnswer: () -> Void

    @State private var offset: CGFloat = 0
    @State private var isDragging = false
    @GestureState private var dragOffset: CGFloat = 0

    private let sliderWidth: CGFloat = 280
    private let buttonSize: CGFloat = 56
    private let threshold: CGFloat = 0.8

    var body: some View {
        ZStack(alignment: .leading) {
            // Track
            Capsule()
                .fill(Color.white.opacity(0.2))
                .frame(width: sliderWidth, height: buttonSize)

            // Text
            HStack {
                Spacer()
                Text("Slide to answer")
                    .font(Theme.Typography.subheadline)
                    .foregroundColor(.white.opacity(max(0.6 - (offset / sliderWidth), 0)))
                Spacer()
            }

            // Slider button
            Circle()
                .fill(Color.green)
                .frame(width: buttonSize - 8, height: buttonSize - 8)
                .overlay(
                    Image(systemName: "phone.fill")
                        .font(.system(size: 20))
                        .foregroundColor(.white)
                )
                .offset(x: offset + 4)
                .gesture(
                    DragGesture()
                        .updating($dragOffset) { value, state, _ in
                            state = value.translation.width
                        }
                        .onChanged { value in
                            let newOffset = max(0, min(value.translation.width, sliderWidth - buttonSize))
                            offset = newOffset
                        }
                        .onEnded { value in
                            if offset > (sliderWidth - buttonSize) * threshold {
                                // Answered
                                withAnimation(.spring()) {
                                    offset = sliderWidth - buttonSize
                                }
                                onAnswer()
                            } else {
                                // Reset
                                withAnimation(.spring()) {
                                    offset = 0
                                }
                            }
                        }
                )
        }
        .frame(width: sliderWidth, height: buttonSize)
    }
}

// MARK: - Full Screen Incoming Call

struct FullScreenIncomingCallView: View {
    @Bindable var viewModel: CallViewModel

    var body: some View {
        SwiftUI.Group {
            switch viewModel.state {
            case .ringing where viewModel.direction == .incoming:
                IncomingCallView(viewModel: viewModel)
            case .connecting, .connected:
                CallView(viewModel: viewModel)
            default:
                EmptyView()
            }
        }
    }
}

// MARK: - Preview

#Preview("Incoming Call") {
    IncomingCallView(viewModel: {
        let vm = CallViewModel()
        vm.receiveCall(
            callId: "call123",
            from: "user1",
            name: "Alice Smith"
        )
        return vm
    }())
}

#Preview("Active Call") {
    CallView(viewModel: {
        let vm = CallViewModel()
        vm.participantName = "Alice Smith"
        vm.state = .connected
        vm.duration = 65
        return vm
    }())
}
