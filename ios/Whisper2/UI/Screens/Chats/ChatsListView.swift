import SwiftUI

/// List of conversations matching original Whisper UI
struct ChatsListView: View {
    @State private var viewModel = ChatsViewModel()
    @State private var contactsViewModel = ContactsViewModel()
    @EnvironmentObject var themeManager: ThemeManager
    @State private var showingAddContact = false
    @State private var selectedConversation: ConversationUI?

    var body: some View {
        ZStack {
            Color.whisperBackground.ignoresSafeArea()

            VStack(spacing: 0) {
                // Header (matching Expo)
                headerView

                // Connection status banner
                connectionStatusBanner

                // Chat List
                if viewModel.isLoading && viewModel.conversations.isEmpty {
                    loadingView
                } else if viewModel.filteredConversations.isEmpty {
                    emptyStateView
                } else {
                    ScrollView {
                        LazyVStack(spacing: WhisperSpacing.sm) {
                            ForEach(viewModel.filteredConversations) { conversation in
                                NavigationLink(value: conversation) {
                                    ConversationRowView(conversation: conversation)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                        .padding(.horizontal, WhisperSpacing.md)
                        .padding(.top, WhisperSpacing.sm)
                    }
                    .refreshable {
                        await viewModel.refreshConversations()
                    }
                }
            }
        }
        .navigationDestination(for: ConversationUI.self) { conversation in
            ChatView(viewModel: ChatViewModel(conversation: conversation))
                .onAppear {
                    viewModel.markAsRead(conversation)
                }
        }
        .sheet(isPresented: $showingAddContact) {
            AddContactView(viewModel: contactsViewModel, isPresented: $showingAddContact)
        }
        .id(themeManager.themeMode) // Force view refresh when theme changes
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

    // MARK: - Connection Status Banner

    @ViewBuilder
    private var connectionStatusBanner: some View {
        if !viewModel.isConnected && !viewModel.isLoading {
            HStack(spacing: WhisperSpacing.sm) {
                Image(systemName: "wifi.slash")
                    .foregroundColor(.whisperTextSecondary)
                Text("Disconnected")
                    .font(.whisper(size: WhisperFontSize.sm))
                    .foregroundColor(.whisperTextSecondary)
                Spacer()
                Button("Connect") {
                    viewModel.loadConversations()
                }
                .font(.whisper(size: WhisperFontSize.sm, weight: .semibold))
                .foregroundColor(.whisperPrimary)
            }
            .frame(maxWidth: .infinity)
            .padding(.horizontal, WhisperSpacing.md)
            .padding(.vertical, WhisperSpacing.sm)
            .background(Color.whisperSurface)
        }
    }

    // MARK: - Header (Matching Expo ChatsScreen)

    private var headerView: some View {
        HStack {
            Text("Chats")
                .font(.whisper(size: WhisperFontSize.xxl, weight: .bold))
                .foregroundColor(.whisperText)

            Spacer()

            // Add Contact Button
            Button(action: { showingAddContact = true }) {
                Image(systemName: "plus")
                    .font(.whisper(size: WhisperFontSize.md, weight: .semibold))
                    .foregroundColor(.whisperText)
                    .frame(width: 36, height: 36)
                    .background(Color.whisperPrimary)
                    .clipShape(Circle())
            }
        }
        .padding(.horizontal, WhisperSpacing.lg)
        .padding(.vertical, WhisperSpacing.md)
        .background(Color.whisperBackground)
        .overlay(
            Rectangle()
                .fill(Color.whisperBorder)
                .frame(height: 1),
            alignment: .bottom
        )
    }

    // MARK: - Loading View

    private var loadingView: some View {
        VStack(spacing: WhisperSpacing.md) {
            Spacer()
            ProgressView()
                .scaleEffect(1.5)
                .tint(.whisperPrimary)
            Text("Loading conversations...")
                .font(.whisper(size: WhisperFontSize.md))
                .foregroundColor(.whisperTextSecondary)
            Spacer()
        }
    }

    // MARK: - Empty State (Matching Expo)

    private var emptyStateView: some View {
        VStack(spacing: WhisperSpacing.lg) {
            Spacer()

            Image(systemName: "message.fill")
                .font(.system(size: 64))
                .foregroundColor(.whisperTextMuted)

            Text("No conversations yet")
                .font(.whisper(size: WhisperFontSize.xl, weight: .semibold))
                .foregroundColor(.whisperText)

            Text("Add a contact to start messaging")
                .font(.whisper(size: WhisperFontSize.md))
                .foregroundColor(.whisperTextSecondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, WhisperSpacing.xl)

            Button(action: { showingAddContact = true }) {
                HStack(spacing: WhisperSpacing.sm) {
                    Image(systemName: "plus")
                    Text("Add Contact")
                }
                .font(.whisper(size: WhisperFontSize.md, weight: .semibold))
                .foregroundColor(.whisperText)
                .padding(.horizontal, WhisperSpacing.lg)
                .padding(.vertical, WhisperSpacing.sm)
                .background(Color.whisperPrimary)
                .cornerRadius(WhisperRadius.md)
            }

            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// MARK: - Conversation Row (Matching Expo)

struct ConversationRowView: View {
    let conversation: ConversationUI
    @EnvironmentObject var themeManager: ThemeManager

    var body: some View {
        VStack(spacing: 0) {
            // Top border line
            Rectangle()
                .fill(Color.whisperBorder)
                .frame(height: 1)

            // Main content
            HStack(spacing: WhisperSpacing.md) {
                // Avatar with gradient ring
                avatarView

                // Content
                VStack(alignment: .leading, spacing: WhisperSpacing.xs) {
                    // Top row: Name + Time
                    HStack(alignment: .center) {
                        Text(conversation.participantName)
                            .font(.whisper(size: WhisperFontSize.md, weight: .semibold))
                            .foregroundColor(.whisperText)
                            .lineLimit(1)

                        Spacer()

                        if let timestamp = conversation.lastMessageTimestamp {
                            Text(formatTime(timestamp))
                                .font(.whisper(size: WhisperFontSize.xs))
                                .foregroundColor(conversation.unreadCount > 0 ? .whisperPrimary : .whisperTextMuted)
                        }
                    }

                    // Bottom row: Message preview + Unread count
                    HStack(alignment: .center) {
                        if conversation.isTyping {
                            TypingIndicatorView()
                        } else {
                            Text(conversation.lastMessage ?? "No messages yet")
                                .font(.whisper(size: WhisperFontSize.sm))
                                .foregroundColor(.whisperTextSecondary)
                                .lineLimit(1)
                        }

                        Spacer()

                        if conversation.unreadCount > 0 {
                            Text("\(conversation.unreadCount)")
                                .font(.whisper(size: WhisperFontSize.xs, weight: .bold))
                                .foregroundColor(.white)
                                .padding(.horizontal, 8)
                                .frame(minWidth: 22, minHeight: 22)
                                .background(Color.whisperPrimary)
                                .clipShape(Capsule())
                        }
                    }
                }
            }
            .padding(.horizontal, WhisperSpacing.md)
            .padding(.vertical, WhisperSpacing.md)

            // Bottom border line
            Rectangle()
                .fill(Color.whisperBorder)
                .frame(height: 1)
        }
        .background(Color.whisperSurface)
        .cornerRadius(WhisperRadius.md)
        .shadow(color: Color.black.opacity(0.05), radius: 2, x: 0, y: 1)
        .contentShape(Rectangle())
    }

    private var avatarView: some View {
        ZStack {
            Circle()
                .stroke(
                    LinearGradient(
                        colors: [Color.whisperPrimary, Color.whisperPrimary.opacity(0.6)],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    ),
                    lineWidth: 2
                )
                .frame(width: 54, height: 54)

            Circle()
                .fill(Color.whisperPrimary.opacity(0.15))
                .frame(width: 48, height: 48)
                .overlay {
                    Text(String(conversation.participantName.prefix(1)).uppercased())
                        .font(.whisper(size: WhisperFontSize.lg, weight: .bold))
                        .foregroundColor(.whisperPrimary)
                }

            // Online indicator
            if conversation.isOnline {
                Circle()
                    .fill(Color.whisperSuccess)
                    .frame(width: 14, height: 14)
                    .overlay(
                        Circle()
                            .stroke(Color.whisperSurface, lineWidth: 2)
                    )
                    .offset(x: 18, y: 18)
            }
        }
    }

    private func formatTime(_ date: Date) -> String {
        let calendar = Calendar.current
        let now = Date()

        if calendar.isDateInToday(date) {
            let formatter = DateFormatter()
            formatter.timeStyle = .short
            return formatter.string(from: date)
        } else if calendar.isDateInYesterday(date) {
            return "Yesterday"
        } else if let daysAgo = calendar.dateComponents([.day], from: date, to: now).day, daysAgo < 7 {
            let formatter = DateFormatter()
            formatter.dateFormat = "EEEE"
            return formatter.string(from: date)
        } else {
            let formatter = DateFormatter()
            formatter.dateStyle = .short
            return formatter.string(from: date)
        }
    }
}

// MARK: - Typing Indicator

private struct TypingIndicatorView: View {
    @State private var animationPhase = 0

    var body: some View {
        HStack(spacing: 3) {
            ForEach(0..<3, id: \.self) { index in
                Circle()
                    .fill(Color.whisperTextMuted)
                    .frame(width: 6, height: 6)
                    .scaleEffect(animationPhase == index ? 1.2 : 0.8)
            }

            Text("typing...")
                .font(.whisper(size: WhisperFontSize.sm))
                .foregroundColor(.whisperTextMuted)
                .italic()
        }
        .onAppear {
            withAnimation(.easeInOut(duration: 0.4).repeatForever()) {
                animationPhase = (animationPhase + 1) % 3
            }
        }
    }
}

// MARK: - Preview

#Preview {
    ChatsListView()
        .environmentObject(ThemeManager.shared)
}
