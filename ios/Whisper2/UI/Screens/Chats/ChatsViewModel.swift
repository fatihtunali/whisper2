import SwiftUI
import Observation

/// Model representing a conversation
struct Conversation: Identifiable, Equatable {
    let id: String
    let participantId: String
    let participantName: String
    let participantAvatarURL: URL?
    var lastMessage: String?
    var lastMessageTimestamp: Date?
    var unreadCount: Int
    var isOnline: Bool
    var isTyping: Bool

    static func == (lhs: Conversation, rhs: Conversation) -> Bool {
        lhs.id == rhs.id
    }
}

/// Manages the conversations list
@Observable
final class ChatsViewModel {
    // MARK: - State

    var conversations: [Conversation] = []
    var filteredConversations: [Conversation] = []
    var searchText: String = "" {
        didSet { filterConversations() }
    }
    var isLoading = false
    var error: String?

    // Sort options
    enum SortOption {
        case recent
        case unread
        case alphabetical
    }
    var sortOption: SortOption = .recent {
        didSet { sortConversations() }
    }

    // MARK: - Dependencies

    // These will be injected when actual services are implemented
    // private let messagingService: MessagingServiceProtocol
    // private let contactsService: ContactsServiceProtocol

    // MARK: - Computed Properties

    var hasUnread: Bool {
        conversations.contains { $0.unreadCount > 0 }
    }

    var totalUnreadCount: Int {
        conversations.reduce(0) { $0 + $1.unreadCount }
    }

    // MARK: - Actions

    func loadConversations() {
        isLoading = true
        error = nil

        Task { @MainActor in
            do {
                // Simulate loading
                try await Task.sleep(for: .seconds(1))

                // Placeholder data
                conversations = [
                    Conversation(
                        id: "1",
                        participantId: "user1",
                        participantName: "Alice Smith",
                        participantAvatarURL: nil,
                        lastMessage: "Hey, how are you?",
                        lastMessageTimestamp: Date().addingTimeInterval(-300),
                        unreadCount: 2,
                        isOnline: true,
                        isTyping: false
                    ),
                    Conversation(
                        id: "2",
                        participantId: "user2",
                        participantName: "Bob Johnson",
                        participantAvatarURL: nil,
                        lastMessage: "See you tomorrow!",
                        lastMessageTimestamp: Date().addingTimeInterval(-3600),
                        unreadCount: 0,
                        isOnline: false,
                        isTyping: false
                    ),
                    Conversation(
                        id: "3",
                        participantId: "user3",
                        participantName: "Carol Williams",
                        participantAvatarURL: nil,
                        lastMessage: "Thanks for the help!",
                        lastMessageTimestamp: Date().addingTimeInterval(-86400),
                        unreadCount: 0,
                        isOnline: true,
                        isTyping: true
                    )
                ]

                filterConversations()
                isLoading = false
            } catch {
                self.error = "Failed to load conversations"
                isLoading = false
            }
        }
    }

    func refreshConversations() async {
        // Simulate refresh
        try? await Task.sleep(for: .milliseconds(500))
        loadConversations()
    }

    func markAsRead(_ conversation: Conversation) {
        guard let index = conversations.firstIndex(where: { $0.id == conversation.id }) else { return }
        conversations[index].unreadCount = 0
        filterConversations()
    }

    func deleteConversation(_ conversation: Conversation) {
        conversations.removeAll { $0.id == conversation.id }
        filterConversations()
    }

    func archiveConversation(_ conversation: Conversation) {
        // Archive implementation
        deleteConversation(conversation) // For now, just remove
    }

    // MARK: - Private Methods

    private func filterConversations() {
        if searchText.isEmpty {
            filteredConversations = conversations
        } else {
            filteredConversations = conversations.filter { conversation in
                conversation.participantName.localizedCaseInsensitiveContains(searchText) ||
                (conversation.lastMessage?.localizedCaseInsensitiveContains(searchText) ?? false)
            }
        }
        sortConversations()
    }

    private func sortConversations() {
        switch sortOption {
        case .recent:
            filteredConversations.sort { ($0.lastMessageTimestamp ?? .distantPast) > ($1.lastMessageTimestamp ?? .distantPast) }
        case .unread:
            filteredConversations.sort { $0.unreadCount > $1.unreadCount }
        case .alphabetical:
            filteredConversations.sort { $0.participantName < $1.participantName }
        }
    }

    func clearError() {
        error = nil
    }
}
