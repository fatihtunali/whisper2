import SwiftUI

/// View for configuring disappearing messages timer
struct DisappearingMessageSettingsView: View {
    let peerId: String
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var messagingService = MessagingService.shared
    @State private var selectedTimer: DisappearingMessageTimer

    init(peerId: String) {
        self.peerId = peerId
        // Get current timer from conversation
        let currentTimer = MessagingService.shared.getDisappearingMessageTimer(for: peerId)
        self._selectedTimer = State(initialValue: currentTimer)
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            VStack(spacing: 24) {
                // Header info
                VStack(spacing: 12) {
                    Image(systemName: "timer")
                        .font(.system(size: 50))
                        .foregroundStyle(
                            LinearGradient(
                                colors: [.blue, .purple],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )

                    Text("Disappearing Messages")
                        .font(.title2)
                        .fontWeight(.bold)
                        .foregroundColor(.white)

                    Text("Messages will be automatically deleted after the selected time. This applies to new messages only.")
                        .font(.subheadline)
                        .foregroundColor(.gray)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 20)
                }
                .padding(.top, 20)

                // Timer options
                VStack(spacing: 12) {
                    ForEach(DisappearingMessageTimer.allCases, id: \.self) { timer in
                        TimerOptionRow(
                            timer: timer,
                            isSelected: selectedTimer == timer
                        ) {
                            selectTimer(timer)
                        }
                    }
                }
                .padding(.horizontal)

                Spacer()

                // Current status
                if selectedTimer != .off {
                    HStack(spacing: 8) {
                        Image(systemName: "info.circle.fill")
                            .foregroundColor(.blue)
                        Text("New messages in this chat will disappear after \(selectedTimer.displayName.lowercased())")
                            .font(.caption)
                            .foregroundColor(.gray)
                    }
                    .padding()
                    .background(Color.blue.opacity(0.1))
                    .cornerRadius(12)
                    .padding(.horizontal)
                    .padding(.bottom, 20)
                }
            }
        }
        .navigationTitle("Disappearing Messages")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func selectTimer(_ timer: DisappearingMessageTimer) {
        selectedTimer = timer
        messagingService.setDisappearingMessageTimer(for: peerId, timer: timer)
    }
}

/// Row for a timer option
struct TimerOptionRow: View {
    let timer: DisappearingMessageTimer
    let isSelected: Bool
    let onSelect: () -> Void

    var body: some View {
        Button(action: onSelect) {
            HStack(spacing: 16) {
                // Icon
                ZStack {
                    Circle()
                        .fill(isSelected ? Color.blue.opacity(0.2) : Color.gray.opacity(0.2))
                        .frame(width: 44, height: 44)

                    Image(systemName: timer.icon)
                        .font(.system(size: 18))
                        .foregroundColor(isSelected ? .blue : .gray)
                }

                // Label
                VStack(alignment: .leading, spacing: 2) {
                    Text(timer.displayName)
                        .font(.headline)
                        .foregroundColor(.white)

                    if timer != .off {
                        Text("Messages auto-delete after \(timer.displayName.lowercased())")
                            .font(.caption)
                            .foregroundColor(.gray)
                    } else {
                        Text("Messages won't be automatically deleted")
                            .font(.caption)
                            .foregroundColor(.gray)
                    }
                }

                Spacer()

                // Checkmark
                if isSelected {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.title2)
                        .foregroundColor(.blue)
                }
            }
            .padding()
            .background(isSelected ? Color.blue.opacity(0.1) : Color.gray.opacity(0.1))
            .cornerRadius(12)
        }
        .buttonStyle(.plain)
    }
}

#Preview {
    NavigationStack {
        DisappearingMessageSettingsView(peerId: "WSP-TEST-1234-5678")
    }
}
