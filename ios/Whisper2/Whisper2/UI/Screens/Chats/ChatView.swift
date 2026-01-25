import SwiftUI

/// Single chat with messages
struct ChatView: View {
    @Bindable var viewModel: ChatViewModel
    @Environment(\.dismiss) private var dismiss
    @FocusState private var isInputFocused: Bool

    var body: some View {
        VStack(spacing: 0) {
            // Messages list
            messagesView

            // Input bar
            InputBar(
                text: $viewModel.messageText,
                isEnabled: !viewModel.isSending,
                onSend: {
                    viewModel.sendMessage()
                },
                onAttachment: {
                    // Show attachment picker
                }
            )
        }
        .background(Theme.Colors.background)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                chatHeader
            }

            ToolbarItem(placement: .navigationBarTrailing) {
                Menu {
                    Button {
                        // Call
                    } label: {
                        Label("Voice Call", systemImage: "phone")
                    }

                    Button {
                        // View profile
                    } label: {
                        Label("View Profile", systemImage: "person.circle")
                    }

                    Divider()

                    Button(role: .destructive) {
                        // Clear chat
                    } label: {
                        Label("Clear Chat", systemImage: "trash")
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
        .onAppear {
            viewModel.loadMessages()
        }
        .alert("Error", isPresented: Binding(
            get: { viewModel.error != nil },
            set: { if !$0 { viewModel.clearError() } }
        )) {
            Button("OK") { viewModel.clearError() }
        } message: {
            Text(viewModel.error ?? "")
        }
    }

    private var chatHeader: some View {
        HStack(spacing: Theme.Spacing.xs) {
            AvatarView(
                name: viewModel.participantName,
                imageURL: viewModel.participantAvatarURL,
                size: 32
            )

            VStack(alignment: .leading, spacing: 0) {
                Text(viewModel.participantName)
                    .font(Theme.Typography.headline)
                    .foregroundColor(Theme.Colors.textPrimary)

                if viewModel.isParticipantTyping {
                    Text("typing...")
                        .font(Theme.Typography.caption2)
                        .foregroundColor(Theme.Colors.primary)
                } else if viewModel.isParticipantOnline {
                    Text("online")
                        .font(Theme.Typography.caption2)
                        .foregroundColor(Theme.Colors.success)
                }
            }
        }
    }

    private var messagesView: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: Theme.Spacing.sm) {
                    // Load more button
                    if viewModel.hasMoreMessages {
                        Button {
                            viewModel.loadMoreMessages()
                        } label: {
                            Text("Load earlier messages")
                                .font(Theme.Typography.caption1)
                                .foregroundColor(Theme.Colors.primary)
                        }
                        .padding(.top, Theme.Spacing.md)
                    }

                    // Messages grouped by date
                    ForEach(viewModel.groupedMessages, id: \.0) { date, messages in
                        DateDivider(date: date)

                        ForEach(messages) { message in
                            MessageRow(
                                message: message,
                                onRetry: { viewModel.retrySending(message) },
                                onDelete: { viewModel.deleteMessage(message) }
                            )
                            .id(message.id)
                        }
                    }
                }
                .padding(.horizontal, Theme.Spacing.md)
                .padding(.bottom, Theme.Spacing.sm)
            }
            .onChange(of: viewModel.messages.count) { _, _ in
                if let lastMessage = viewModel.messages.last {
                    withAnimation {
                        proxy.scrollTo(lastMessage.id, anchor: .bottom)
                    }
                }
            }
            .onTapGesture {
                isInputFocused = false
            }
        }
    }
}

// MARK: - Date Divider

private struct DateDivider: View {
    let date: Date

    private var dateString: String {
        let calendar = Calendar.current
        if calendar.isDateInToday(date) {
            return "Today"
        } else if calendar.isDateInYesterday(date) {
            return "Yesterday"
        } else {
            return date.formatted(date: .abbreviated, time: .omitted)
        }
    }

    var body: some View {
        HStack {
            Rectangle()
                .fill(Theme.Colors.separator)
                .frame(height: 1)

            Text(dateString)
                .font(Theme.Typography.caption2)
                .foregroundColor(Theme.Colors.textTertiary)
                .padding(.horizontal, Theme.Spacing.xs)

            Rectangle()
                .fill(Theme.Colors.separator)
                .frame(height: 1)
        }
        .padding(.vertical, Theme.Spacing.sm)
    }
}

// MARK: - Message Row

private struct MessageRow: View {
    let message: ChatMessage
    let onRetry: () -> Void
    let onDelete: () -> Void

    @State private var showActions = false

    var body: some View {
        MessageBubble(
            content: message.content,
            timestamp: message.timestamp,
            isSent: message.isFromMe,
            status: mapStatus(message.status)
        )
        .contextMenu {
            Button {
                UIPasteboard.general.string = message.content
            } label: {
                Label("Copy", systemImage: "doc.on.doc")
            }

            if message.status == .failed {
                Button {
                    onRetry()
                } label: {
                    Label("Retry", systemImage: "arrow.clockwise")
                }
            }

            if message.isFromMe {
                Button(role: .destructive) {
                    onDelete()
                } label: {
                    Label("Delete", systemImage: "trash")
                }
            }
        }
        .onTapGesture {
            if message.status == .failed {
                showActions = true
            }
        }
        .confirmationDialog("Message failed to send", isPresented: $showActions) {
            Button("Retry") {
                onRetry()
            }
            Button("Delete", role: .destructive) {
                onDelete()
            }
            Button("Cancel", role: .cancel) {}
        }
    }

    private func mapStatus(_ status: ChatMessage.MessageStatus) -> MessageBubble.MessageStatus {
        switch status {
        case .sending: return .sending
        case .sent: return .sent
        case .delivered: return .delivered
        case .read: return .read
        case .failed: return .failed
        }
    }
}

// MARK: - Preview

#Preview {
    NavigationStack {
        ChatView(viewModel: ChatViewModel(
            conversationId: "1",
            participantId: "user1",
            participantName: "Alice Smith"
        ))
    }
}
