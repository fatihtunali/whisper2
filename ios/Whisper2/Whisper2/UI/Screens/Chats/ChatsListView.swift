import SwiftUI

/// List of conversations
struct ChatsListView: View {
    @Bindable var viewModel: ChatsViewModel
    @State private var selectedConversation: Conversation?
    @State private var showingNewChat = false

    var body: some View {
        NavigationStack {
            Group {
                if viewModel.isLoading && viewModel.conversations.isEmpty {
                    ChatListSkeletonView()
                } else if viewModel.filteredConversations.isEmpty {
                    emptyState
                } else {
                    conversationsList
                }
            }
            .navigationTitle("Chats")
            .searchable(text: $viewModel.searchText, prompt: "Search conversations")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        showingNewChat = true
                    } label: {
                        Image(systemName: "square.and.pencil")
                    }
                }

                ToolbarItem(placement: .navigationBarLeading) {
                    Menu {
                        Button {
                            viewModel.sortOption = .recent
                        } label: {
                            Label("Recent", systemImage: viewModel.sortOption == .recent ? "checkmark" : "")
                        }

                        Button {
                            viewModel.sortOption = .unread
                        } label: {
                            Label("Unread", systemImage: viewModel.sortOption == .unread ? "checkmark" : "")
                        }

                        Button {
                            viewModel.sortOption = .alphabetical
                        } label: {
                            Label("A-Z", systemImage: viewModel.sortOption == .alphabetical ? "checkmark" : "")
                        }
                    } label: {
                        Image(systemName: "arrow.up.arrow.down")
                    }
                }
            }
            .refreshable {
                await viewModel.refreshConversations()
            }
            .sheet(isPresented: $showingNewChat) {
                NewChatView()
            }
            .navigationDestination(item: $selectedConversation) { conversation in
                ChatView(viewModel: ChatViewModel(conversation: conversation))
            }
        }
        .onAppear {
            if viewModel.conversations.isEmpty {
                viewModel.loadConversations()
            }
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

    private var conversationsList: some View {
        List {
            ForEach(viewModel.filteredConversations) { conversation in
                ConversationRow(conversation: conversation)
                    .contentShape(Rectangle())
                    .onTapGesture {
                        viewModel.markAsRead(conversation)
                        selectedConversation = conversation
                    }
                    .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                        Button(role: .destructive) {
                            viewModel.deleteConversation(conversation)
                        } label: {
                            Label("Delete", systemImage: "trash")
                        }

                        Button {
                            viewModel.archiveConversation(conversation)
                        } label: {
                            Label("Archive", systemImage: "archivebox")
                        }
                        .tint(.orange)
                    }
                    .swipeActions(edge: .leading) {
                        if conversation.unreadCount > 0 {
                            Button {
                                viewModel.markAsRead(conversation)
                            } label: {
                                Label("Read", systemImage: "envelope.open")
                            }
                            .tint(.blue)
                        }
                    }
            }
        }
        .listStyle(.plain)
    }

    private var emptyState: some View {
        Group {
            if viewModel.searchText.isEmpty {
                CenteredEmptyStateView(
                    emptyState: .noChats {
                        showingNewChat = true
                    }
                )
            } else {
                CenteredEmptyStateView(
                    emptyState: .noSearchResults()
                )
            }
        }
    }
}

// MARK: - Conversation Row

private struct ConversationRow: View {
    let conversation: Conversation

    private var timeString: String {
        guard let timestamp = conversation.lastMessageTimestamp else { return "" }

        let calendar = Calendar.current
        if calendar.isDateInToday(timestamp) {
            return timestamp.formatted(date: .omitted, time: .shortened)
        } else if calendar.isDateInYesterday(timestamp) {
            return "Yesterday"
        } else if let daysAgo = calendar.dateComponents([.day], from: timestamp, to: Date()).day, daysAgo < 7 {
            return timestamp.formatted(.dateTime.weekday(.abbreviated))
        } else {
            return timestamp.formatted(date: .abbreviated, time: .omitted)
        }
    }

    var body: some View {
        HStack(spacing: Theme.Spacing.sm) {
            // Avatar
            AvatarView(
                name: conversation.participantName,
                imageURL: conversation.participantAvatarURL,
                size: Theme.AvatarSize.md,
                showOnlineIndicator: true,
                isOnline: conversation.isOnline
            )

            // Content
            VStack(alignment: .leading, spacing: Theme.Spacing.xxs) {
                HStack {
                    Text(conversation.participantName)
                        .font(Theme.Typography.headline)
                        .foregroundColor(Theme.Colors.textPrimary)
                        .lineLimit(1)

                    Spacer()

                    Text(timeString)
                        .font(Theme.Typography.caption1)
                        .foregroundColor(
                            conversation.unreadCount > 0
                                ? Theme.Colors.primary
                                : Theme.Colors.textTertiary
                        )
                }

                HStack {
                    if conversation.isTyping {
                        TypingIndicator()
                    } else {
                        Text(conversation.lastMessage ?? "No messages yet")
                            .font(Theme.Typography.subheadline)
                            .foregroundColor(Theme.Colors.textSecondary)
                            .lineLimit(2)
                    }

                    Spacer()

                    if conversation.unreadCount > 0 {
                        Text("\(conversation.unreadCount)")
                            .font(Theme.Typography.caption2)
                            .foregroundColor(.white)
                            .padding(.horizontal, Theme.Spacing.xs)
                            .padding(.vertical, 2)
                            .background(Theme.Colors.primary)
                            .clipShape(Capsule())
                    }
                }
            }
        }
        .padding(.vertical, Theme.Spacing.xs)
    }
}

// MARK: - Typing Indicator

private struct TypingIndicator: View {
    @State private var animationPhase = 0

    var body: some View {
        HStack(spacing: 3) {
            ForEach(0..<3, id: \.self) { index in
                Circle()
                    .fill(Theme.Colors.textTertiary)
                    .frame(width: 6, height: 6)
                    .scaleEffect(animationPhase == index ? 1.2 : 0.8)
            }

            Text("typing")
                .font(Theme.Typography.caption1)
                .foregroundColor(Theme.Colors.textTertiary)
                .italic()
        }
        .onAppear {
            withAnimation(.easeInOut(duration: 0.4).repeatForever()) {
                animationPhase = (animationPhase + 1) % 3
            }
        }
    }
}

// MARK: - New Chat View (placeholder)

private struct NewChatView: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Text("Select a contact to start a new chat")
                .foregroundColor(Theme.Colors.textSecondary)
                .navigationTitle("New Chat")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .navigationBarLeading) {
                        Button("Cancel") {
                            dismiss()
                        }
                    }
                }
        }
    }
}

// MARK: - Preview

#Preview {
    ChatsListView(viewModel: ChatsViewModel())
}
