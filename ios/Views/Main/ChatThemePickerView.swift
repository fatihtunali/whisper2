import SwiftUI

/// View for selecting chat theme
struct ChatThemePickerView: View {
    let peerId: String
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var messagingService = MessagingService.shared
    @State private var selectedThemeId: String

    init(peerId: String) {
        self.peerId = peerId
        // Get current theme from conversation
        let conversation = MessagingService.shared.conversations.first { $0.peerId == peerId }
        self._selectedThemeId = State(initialValue: conversation?.chatThemeId ?? "default")
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 20) {
                    Text("Choose a theme for this chat")
                        .font(.subheadline)
                        .foregroundColor(.gray)
                        .padding(.top, 10)

                    LazyVGrid(columns: [
                        GridItem(.flexible()),
                        GridItem(.flexible())
                    ], spacing: 16) {
                        ForEach(ChatTheme.themes) { theme in
                            ThemePreviewCard(
                                theme: theme,
                                isSelected: selectedThemeId == theme.id
                            ) {
                                selectTheme(theme)
                            }
                        }
                    }
                    .padding(.horizontal)

                    Spacer(minLength: 40)
                }
            }
        }
        .navigationTitle("Chat Theme")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func selectTheme(_ theme: ChatTheme) {
        selectedThemeId = theme.id
        messagingService.setChatTheme(for: peerId, themeId: theme.id)
    }
}

/// Preview card for a theme
struct ThemePreviewCard: View {
    let theme: ChatTheme
    let isSelected: Bool
    let onSelect: () -> Void

    var body: some View {
        Button(action: onSelect) {
            VStack(spacing: 8) {
                // Theme preview
                ZStack {
                    RoundedRectangle(cornerRadius: 12)
                        .fill(theme.backgroundColor.color)
                        .frame(height: 140)

                    VStack(spacing: 8) {
                        // Incoming message bubble
                        HStack {
                            RoundedRectangle(cornerRadius: 12)
                                .fill(theme.incomingBubbleColor.color)
                                .frame(width: 80, height: 24)
                            Spacer()
                        }
                        .padding(.horizontal, 12)

                        // Outgoing message bubble
                        HStack {
                            Spacer()
                            RoundedRectangle(cornerRadius: 12)
                                .fill(theme.outgoingBubbleColor.color)
                                .frame(width: 90, height: 24)
                        }
                        .padding(.horizontal, 12)

                        // Incoming message bubble
                        HStack {
                            RoundedRectangle(cornerRadius: 12)
                                .fill(theme.incomingBubbleColor.color)
                                .frame(width: 60, height: 24)
                            Spacer()
                        }
                        .padding(.horizontal, 12)
                    }
                }
                .overlay(
                    RoundedRectangle(cornerRadius: 12)
                        .stroke(isSelected ? theme.accentColor.color : Color.clear, lineWidth: 3)
                )

                // Theme name
                HStack {
                    Text(theme.name)
                        .font(.caption)
                        .fontWeight(.medium)
                        .foregroundColor(isSelected ? theme.accentColor.color : .white)

                    if isSelected {
                        Image(systemName: "checkmark.circle.fill")
                            .font(.caption)
                            .foregroundColor(theme.accentColor.color)
                    }
                }
            }
        }
        .buttonStyle(.plain)
    }
}

#Preview {
    NavigationStack {
        ChatThemePickerView(peerId: "WSP-TEST-1234-5678")
    }
}
