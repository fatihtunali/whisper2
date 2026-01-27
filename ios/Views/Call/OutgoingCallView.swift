import SwiftUI
import AVFoundation

/// Minimal outgoing call view - shown when initiating a call
/// CallKit handles incoming calls, but outgoing calls need custom UI
struct OutgoingCallView: View {
    let peerName: String
    let peerId: String
    let isVideo: Bool
    let onEndCall: () -> Void

    @State private var callStatus: String = "Calling..."
    @State private var animationPhase = 0.0

    var body: some View {
        ZStack {
            // Background
            Color.black.ignoresSafeArea()

            VStack(spacing: 30) {
                Spacer()

                // Call type indicator
                HStack {
                    Image(systemName: isVideo ? "video.fill" : "phone.fill")
                    Text(isVideo ? "Video Call" : "Audio Call")
                }
                .font(.subheadline)
                .foregroundColor(.green)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .background(Color.green.opacity(0.2))
                .cornerRadius(20)

                // Avatar with pulsing animation
                ZStack {
                    // Pulsing rings
                    ForEach(0..<3, id: \.self) { i in
                        Circle()
                            .stroke(Color.green.opacity(0.3 - Double(i) * 0.1), lineWidth: 2)
                            .frame(width: 140 + CGFloat(i) * 30, height: 140 + CGFloat(i) * 30)
                            .scaleEffect(1.0 + CGFloat(animationPhase) * 0.1)
                    }

                    Circle()
                        .fill(Color.gray.opacity(0.3))
                        .frame(width: 120, height: 120)
                        .overlay(
                            Text(String(peerName.prefix(1)).uppercased())
                                .font(.system(size: 50, weight: .semibold))
                                .foregroundColor(.white)
                        )
                }

                // Name
                Text(peerName)
                    .font(.title)
                    .fontWeight(.semibold)
                    .foregroundColor(.white)

                // Status
                Text(callStatus)
                    .font(.headline)
                    .foregroundColor(.gray)

                Spacer()

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
                .padding(.bottom, 80)
            }
        }
        .onAppear {
            withAnimation(.easeInOut(duration: 2.0).repeatForever(autoreverses: true)) {
                animationPhase = 1.0
            }
        }
    }

    func updateStatus(_ status: String) {
        callStatus = status
    }
}

/// Observable state for outgoing call UI
@MainActor
class OutgoingCallState: ObservableObject {
    static let shared = OutgoingCallState()

    @Published var isShowingOutgoingCall = false
    @Published var peerName: String = ""
    @Published var peerId: String = ""
    @Published var isVideo: Bool = false
    @Published var callStatus: String = "Calling..."

    private let ringbackPlayer = RingbackTonePlayer.shared

    private init() {}

    func showOutgoingCall(peerName: String, peerId: String, isVideo: Bool) {
        self.peerName = peerName
        self.peerId = peerId
        self.isVideo = isVideo
        self.callStatus = "Calling..."
        self.isShowingOutgoingCall = true

        // Start ringback tone
        ringbackPlayer.start()
    }

    func updateStatus(_ status: String) {
        self.callStatus = status

        // Stop ringback when call connects or has specific status
        if status == "Connected" || status == "Connecting..." {
            ringbackPlayer.stop()
        }
    }

    func hide() {
        self.isShowingOutgoingCall = false

        // Stop ringback tone when call UI hides
        ringbackPlayer.stop()
    }
}

#Preview {
    OutgoingCallView(
        peerName: "John Doe",
        peerId: "WSP-TEST-1234",
        isVideo: false,
        onEndCall: {}
    )
}
