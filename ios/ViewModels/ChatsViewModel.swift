import Foundation
import Combine

/// Unified chat item that can represent either a 1:1 conversation or a group
struct ChatItem: Identifiable {
    let id: String
    let name: String
    let lastMessage: String?
    let lastMessageTimestamp: Date
    let unreadCount: Int
    let isTyping: Bool
    let isGroup: Bool
    let memberCount: Int?
    let isPinned: Bool
    let isMuted: Bool

    // For navigation
    var conversationId: String? { isGroup ? nil : id }
    var groupId: String? { isGroup ? id : nil }
}

/// View model for chat list
@MainActor
final class ChatsViewModel: ObservableObject {
    @Published var conversations: [Conversation] = []
    @Published var chatItems: [ChatItem] = []
    @Published var isLoading = false
    @Published var error: String?

    private let messagingService = MessagingService.shared
    private let groupService = GroupService.shared
    private var cancellables = Set<AnyCancellable>()

    init() {
        setupBindings()
        loadConversations()
    }

    private func setupBindings() {
        // Combine conversations and groups into unified chat items
        Publishers.CombineLatest(
            messagingService.$conversations,
            groupService.$groups.map { Array($0.values) }
        )
        .receive(on: DispatchQueue.main)
        .sink { [weak self] conversations, groups in
            self?.conversations = conversations
            self?.updateChatItems(conversations: conversations, groups: groups)
        }
        .store(in: &cancellables)
    }

    private func updateChatItems(conversations: [Conversation], groups: [ChatGroup]) {
        var items: [ChatItem] = []

        // Add 1:1 conversations
        for conv in conversations {
            items.append(ChatItem(
                id: conv.peerId,
                name: conv.peerNickname ?? String(conv.peerId.suffix(8)),
                lastMessage: conv.lastMessage,
                lastMessageTimestamp: conv.lastMessageTime ?? Date.distantPast,
                unreadCount: conv.unreadCount,
                isTyping: conv.isTyping,
                isGroup: false,
                memberCount: nil,
                isPinned: conv.isPinned,
                isMuted: conv.isMuted
            ))
        }

        // Add groups
        for group in groups {
            items.append(ChatItem(
                id: group.id,
                name: group.title,
                lastMessage: group.lastMessage,
                lastMessageTimestamp: group.lastMessageTime ?? group.createdAt,
                unreadCount: group.unreadCount,
                isTyping: false,
                isGroup: true,
                memberCount: group.memberIds.count,
                isPinned: false,
                isMuted: false
            ))
        }

        // Sort by last message timestamp (most recent first), pinned items on top
        chatItems = items.sorted { item1, item2 in
            if item1.isPinned != item2.isPinned {
                return item1.isPinned
            }
            return item1.lastMessageTimestamp > item2.lastMessageTimestamp
        }
    }
    
    func loadConversations() {
        isLoading = true
        // Conversations are managed by MessagingService
        // They will be updated via the binding
        isLoading = false
    }
    
    func deleteConversations(at offsets: IndexSet) {
        // Get the conversation IDs to delete
        let conversationsToDelete = offsets.map { conversations[$0] }
        for conversation in conversationsToDelete {
            messagingService.deleteConversation(conversationId: conversation.peerId)
        }
    }

    func deleteConversation(_ conversation: Conversation) {
        messagingService.deleteConversation(conversationId: conversation.peerId)
    }

    func markAsRead(_ conversationId: String) {
        messagingService.markAsRead(conversationId: conversationId)
    }

    func refreshConversations() async {
        isLoading = true
        do {
            try await messagingService.fetchPendingMessages()
        } catch {
            self.error = error.localizedDescription
        }
        isLoading = false
    }

    // MARK: - Pin/Unpin Conversations

    func togglePin(_ conversation: Conversation) {
        messagingService.togglePin(conversationId: conversation.peerId)
    }

    func pinConversation(_ conversation: Conversation) {
        messagingService.setPin(conversationId: conversation.peerId, isPinned: true)
    }

    func unpinConversation(_ conversation: Conversation) {
        messagingService.setPin(conversationId: conversation.peerId, isPinned: false)
    }

    // MARK: - Mute/Unmute Conversations

    func toggleMute(_ conversation: Conversation) {
        messagingService.toggleMute(conversationId: conversation.peerId)
    }

    func muteConversation(_ conversation: Conversation) {
        messagingService.setMute(conversationId: conversation.peerId, isMuted: true)
    }

    func unmuteConversation(_ conversation: Conversation) {
        messagingService.setMute(conversationId: conversation.peerId, isMuted: false)
    }

    // MARK: - Archive Conversations

    func archiveConversation(_ conversation: Conversation) {
        messagingService.archiveConversation(conversationId: conversation.peerId)
    }
}
