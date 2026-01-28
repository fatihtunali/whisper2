import SwiftUI

/// View shown for incoming calls
struct IncomingCallView: View {
    let callPayload: CallIncomingPayload
    let onAnswer: () -> Void
    let onDecline: () -> Void

    @State private var animationPhase = 0.0
    private let contacts = ContactsService.shared

    private var callerName: String {
        contacts.getContact(whisperId: callPayload.from)?.displayName ?? callPayload.from
    }

    var body: some View {
        ZStack {
            // Animated background
            RadialGradient(
                colors: [
                    Color.green.opacity(0.3),
                    Color.black
                ],
                center: .center,
                startRadius: 0,
                endRadius: 400 + CGFloat(animationPhase) * 100
            )
            .ignoresSafeArea()
            .animation(
                .easeInOut(duration: 2.0).repeatForever(autoreverses: true),
                value: animationPhase
            )

            VStack(spacing: 40) {
                Spacer()

                // Call type indicator
                HStack {
                    Image(systemName: callPayload.isVideo ? "video.fill" : "phone.fill")
                    Text(callPayload.isVideo ? "Incoming Video Call" : "Incoming Call")
                }
                .font(.subheadline)
                .foregroundColor(.green)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .background(Color.green.opacity(0.2))
                .cornerRadius(20)

                // Caller avatar
                ZStack {
                    // Pulsing rings
                    ForEach(0..<3) { i in
                        Circle()
                            .stroke(Color.green.opacity(0.3 - Double(i) * 0.1), lineWidth: 2)
                            .frame(width: 140 + CGFloat(i) * 30, height: 140 + CGFloat(i) * 30)
                            .scaleEffect(1.0 + CGFloat(animationPhase) * 0.1)
                    }

                    Circle()
                        .fill(Color.gray.opacity(0.3))
                        .frame(width: 120, height: 120)
                        .overlay(
                            Text(String(callerName.prefix(1)).uppercased())
                                .font(.system(size: 50, weight: .semibold))
                                .foregroundColor(.white)
                        )
                }

                // Caller name
                Text(callerName)
                    .font(.title)
                    .fontWeight(.semibold)
                    .foregroundColor(.white)

                Text("Whisper2 \(callPayload.isVideo ? "Video" : "Audio")")
                    .font(.subheadline)
                    .foregroundColor(.gray)

                Spacer()

                // Action buttons
                HStack(spacing: 80) {
                    // Decline
                    Button(action: onDecline) {
                        VStack(spacing: 8) {
                            Circle()
                                .fill(Color.red)
                                .frame(width: 70, height: 70)
                                .overlay(
                                    Image(systemName: "phone.down.fill")
                                        .font(.title)
                                        .foregroundColor(.white)
                                )

                            Text("Decline")
                                .font(.caption)
                                .foregroundColor(.gray)
                        }
                    }

                    // Answer
                    Button(action: onAnswer) {
                        VStack(spacing: 8) {
                            Circle()
                                .fill(Color.green)
                                .frame(width: 70, height: 70)
                                .overlay(
                                    Image(systemName: callPayload.isVideo ? "video.fill" : "phone.fill")
                                        .font(.title)
                                        .foregroundColor(.white)
                                )

                            Text("Answer")
                                .font(.caption)
                                .foregroundColor(.gray)
                        }
                    }
                }
                .padding(.bottom, 80)
            }
        }
        .onAppear {
            animationPhase = 1.0
        }
    }
}

/// View model for managing incoming call state
@MainActor
class IncomingCallViewModel: ObservableObject {
    @Published var incomingCall: CallIncomingPayload?
    @Published var showIncomingCall = false

    private let callService = CallService.shared

    init() {
        setupCallListener()
    }

    private func setupCallListener() {
        // Listen for incoming calls from push service
        PushNotificationService.shared.onIncomingCall = { [weak self] payload in
            DispatchQueue.main.async {
                self?.incomingCall = payload
                self?.showIncomingCall = true
            }
        }
    }

    func answerCall() {
        guard let call = incomingCall else { return }

        Task {
            do {
                try await callService.answerCall(call)
                showIncomingCall = false
            } catch {
                print("Failed to answer call: \(error)")
            }
        }
    }

    func declineCall() {
        guard let call = incomingCall else { return }

        Task {
            do {
                // End the call with declined reason
                try await callService.endCall(reason: .declined)
                showIncomingCall = false
                incomingCall = nil
            } catch {
                print("Failed to decline call: \(error)")
            }
        }
    }
}

#Preview {
    IncomingCallView(
        callPayload: CallIncomingPayload(
            callId: "test",
            from: "WSP-TEST-1234-5678",
            isVideo: true,
            timestamp: Int64(Date().timeIntervalSince1970 * 1000),
            nonce: "",
            ciphertext: "",
            sig: ""
        ),
        onAnswer: {},
        onDecline: {}
    )
}
