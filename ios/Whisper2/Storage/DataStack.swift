import Foundation
import SwiftData
import SwiftUI

/// Whisper2 Data Stack
/// SwiftData container setup and management

@MainActor
final class DataStack: ObservableObject {
    static let shared = DataStack()

    /// The SwiftData model container
    let modelContainer: ModelContainer

    /// Main context for UI operations
    var mainContext: ModelContext {
        modelContainer.mainContext
    }

    private init() {
        do {
            let schema = Schema([
                ContactEntity.self,
                ConversationEntity.self,
                MessageEntity.self,
                GroupEntity.self,
                OutboxQueueItem.self
            ])

            let configuration = ModelConfiguration(
                "Whisper2",
                schema: schema,
                isStoredInMemoryOnly: false,
                allowsSave: true,
                groupContainer: .none,
                cloudKitDatabase: .none
            )

            modelContainer = try ModelContainer(
                for: schema,
                configurations: [configuration]
            )

            logger.info("DataStack initialized successfully", category: .storage)
        } catch {
            logger.fault("Failed to create ModelContainer: \(error.localizedDescription)", category: .storage)
            fatalError("Failed to create ModelContainer: \(error)")
        }
    }

    /// Create a background context for non-UI operations
    func backgroundContext() -> ModelContext {
        ModelContext(modelContainer)
    }

    // MARK: - CRUD Operations

    /// Save changes to the main context
    func save() throws {
        if mainContext.hasChanges {
            try mainContext.save()
            logger.debug("Main context saved", category: .storage)
        }
    }

    /// Save changes to a specific context
    func save(context: ModelContext) throws {
        if context.hasChanges {
            try context.save()
            logger.debug("Context saved", category: .storage)
        }
    }

    // MARK: - Contact Operations

    /// Fetch contact by Whisper ID
    func fetchContact(whisperId: String) -> ContactEntity? {
        let descriptor = DataQueries.contact(whisperId: whisperId)
        return try? mainContext.fetch(descriptor).first
    }

    /// Fetch all contacts
    func fetchAllContacts() -> [ContactEntity] {
        let descriptor = DataQueries.allContacts
        return (try? mainContext.fetch(descriptor)) ?? []
    }

    /// Fetch active (non-blocked) contacts
    func fetchActiveContacts() -> [ContactEntity] {
        let descriptor = DataQueries.activeContacts
        return (try? mainContext.fetch(descriptor)) ?? []
    }

    /// Insert a new contact
    @discardableResult
    func insertContact(
        whisperId: String,
        encPublicKey: String,
        signPublicKey: String,
        displayName: String? = nil
    ) -> ContactEntity {
        let contact = ContactEntity(
            whisperId: whisperId,
            encPublicKey: encPublicKey,
            signPublicKey: signPublicKey,
            displayName: displayName
        )
        mainContext.insert(contact)
        logger.debug("Inserted contact: \(whisperId)", category: .storage)
        return contact
    }

    /// Delete a contact
    func deleteContact(_ contact: ContactEntity) {
        mainContext.delete(contact)
        logger.debug("Deleted contact: \(contact.whisperId)", category: .storage)
    }

    // MARK: - Conversation Operations

    /// Fetch conversation by ID
    func fetchConversation(id: String) -> ConversationEntity? {
        let descriptor = DataQueries.conversation(id: id)
        return try? mainContext.fetch(descriptor).first
    }

    /// Fetch all conversations
    func fetchAllConversations() -> [ConversationEntity] {
        let descriptor = DataQueries.allConversations
        return (try? mainContext.fetch(descriptor)) ?? []
    }

    /// Fetch active conversations
    func fetchActiveConversations() -> [ConversationEntity] {
        let descriptor = DataQueries.activeConversations
        return (try? mainContext.fetch(descriptor)) ?? []
    }

    /// Get or create a direct conversation with a contact
    @discardableResult
    func getOrCreateDirectConversation(with contact: ContactEntity) -> ConversationEntity {
        // Check if conversation exists
        if let existing = contact.conversation {
            return existing
        }

        // Create new conversation
        let conversationId = "direct:\(contact.whisperId)"
        let conversation = ConversationEntity(
            id: conversationId,
            type: .direct,
            title: contact.effectiveDisplayName
        )
        conversation.contact = contact

        mainContext.insert(conversation)
        logger.debug("Created direct conversation: \(conversationId)", category: .storage)
        return conversation
    }

    /// Get or create a group conversation
    @discardableResult
    func getOrCreateGroupConversation(for group: GroupEntity) -> ConversationEntity {
        // Check if conversation exists
        if let existing = group.conversation {
            return existing
        }

        // Create new conversation
        let conversationId = "group:\(group.groupId)"
        let conversation = ConversationEntity(
            id: conversationId,
            type: .group,
            title: group.title
        )
        conversation.group = group

        mainContext.insert(conversation)
        logger.debug("Created group conversation: \(conversationId)", category: .storage)
        return conversation
    }

    /// Update conversation with new message
    func updateConversationWithMessage(
        _ conversation: ConversationEntity,
        preview: String,
        timestamp: Date,
        incrementUnread: Bool
    ) {
        conversation.lastMessageAt = timestamp
        conversation.lastMessagePreview = preview.prefix(100).description
        if incrementUnread {
            conversation.unreadCount += 1
        }
    }

    /// Mark conversation as read
    func markConversationAsRead(_ conversation: ConversationEntity) {
        conversation.unreadCount = 0
    }

    // MARK: - Message Operations

    /// Fetch message by ID
    func fetchMessage(messageId: String) -> MessageEntity? {
        let descriptor = DataQueries.message(messageId: messageId)
        return try? mainContext.fetch(descriptor).first
    }

    /// Fetch messages for a conversation
    func fetchMessages(conversationId: String, limit: Int = 50) -> [MessageEntity] {
        let descriptor = DataQueries.messagesForConversation(conversationId, limit: limit)
        return (try? mainContext.fetch(descriptor)) ?? []
    }

    /// Insert a new message
    @discardableResult
    func insertMessage(
        messageId: String,
        conversationId: String,
        senderId: String,
        recipientId: String? = nil,
        groupId: String? = nil,
        msgType: MessageEntity.MessageType,
        timestamp: Date,
        status: MessageEntity.MessageStatus,
        ciphertext: String,
        nonce: String,
        isOutgoing: Bool
    ) -> MessageEntity {
        let message = MessageEntity(
            messageId: messageId,
            conversationId: conversationId,
            senderId: senderId,
            recipientId: recipientId,
            groupId: groupId,
            msgType: msgType,
            timestamp: timestamp,
            status: status,
            ciphertext: ciphertext,
            nonce: nonce,
            isOutgoing: isOutgoing
        )

        // Link to conversation
        if let conversation = fetchConversation(id: conversationId) {
            message.conversation = conversation
        }

        mainContext.insert(message)
        logger.debug("Inserted message: \(messageId)", category: .storage)
        return message
    }

    /// Update message status
    func updateMessageStatus(_ messageId: String, status: MessageEntity.MessageStatus) {
        if let message = fetchMessage(messageId: messageId) {
            message.messageStatus = status
            logger.debug("Updated message \(messageId) status to \(status.rawValue)", category: .storage)
        }
    }

    // MARK: - Group Operations

    /// Fetch group by ID
    func fetchGroup(groupId: String) -> GroupEntity? {
        let descriptor = DataQueries.group(groupId: groupId)
        return try? mainContext.fetch(descriptor).first
    }

    /// Fetch all groups
    func fetchAllGroups() -> [GroupEntity] {
        let descriptor = DataQueries.allGroups
        return (try? mainContext.fetch(descriptor)) ?? []
    }

    /// Insert a new group
    @discardableResult
    func insertGroup(
        groupId: String,
        title: String,
        ownerId: String,
        members: [String],
        isOwner: Bool
    ) -> GroupEntity {
        let group = GroupEntity(
            groupId: groupId,
            title: title,
            ownerId: ownerId,
            members: members,
            isOwner: isOwner
        )
        mainContext.insert(group)
        logger.debug("Inserted group: \(groupId)", category: .storage)
        return group
    }

    /// Update group members
    func updateGroupMembers(_ groupId: String, members: [String]) {
        if let group = fetchGroup(groupId: groupId) {
            group.members = members
            group.updatedAt = Date()
            logger.debug("Updated group \(groupId) members", category: .storage)
        }
    }

    // MARK: - Outbox Operations

    /// Fetch outbox item by message ID
    func fetchOutboxItem(messageId: String) -> OutboxQueueItem? {
        let descriptor = DataQueries.outboxItem(messageId: messageId)
        return try? mainContext.fetch(descriptor).first
    }

    /// Fetch pending outbox items
    func fetchPendingOutboxItems() -> [OutboxQueueItem] {
        let descriptor = DataQueries.pendingOutboxItems()
        return (try? mainContext.fetch(descriptor)) ?? []
    }

    /// Insert outbox item
    @discardableResult
    func insertOutboxItem(messageId: String, payload: String) -> OutboxQueueItem {
        let item = OutboxQueueItem(
            messageId: messageId,
            payload: payload
        )
        mainContext.insert(item)
        logger.debug("Inserted outbox item: \(messageId)", category: .storage)
        return item
    }

    /// Delete outbox item (message sent successfully)
    func deleteOutboxItem(messageId: String) {
        if let item = fetchOutboxItem(messageId: messageId) {
            mainContext.delete(item)
            logger.debug("Deleted outbox item: \(messageId)", category: .storage)
        }
    }

    // MARK: - Cleanup Operations

    /// Delete all messages older than a date
    func deleteMessagesOlderThan(_ date: Date) throws {
        let descriptor = FetchDescriptor<MessageEntity>(
            predicate: #Predicate { $0.timestamp < date }
        )
        let messages = try mainContext.fetch(descriptor)
        for message in messages {
            mainContext.delete(message)
        }
        try save()
        logger.info("Deleted \(messages.count) messages older than \(date)", category: .storage)
    }

    /// Clear all data (full reset)
    func clearAllData() throws {
        try mainContext.delete(model: OutboxQueueItem.self)
        try mainContext.delete(model: MessageEntity.self)
        try mainContext.delete(model: ConversationEntity.self)
        try mainContext.delete(model: GroupEntity.self)
        try mainContext.delete(model: ContactEntity.self)
        try save()
        logger.info("Cleared all SwiftData storage", category: .storage)
    }

    // MARK: - Statistics

    /// Get unread message count across all conversations
    func totalUnreadCount() -> Int {
        let descriptor = DataQueries.unreadConversations
        let conversations = (try? mainContext.fetch(descriptor)) ?? []
        return conversations.reduce(0) { $0 + $1.unreadCount }
    }

    /// Get pending outbox count
    func pendingOutboxCount() -> Int {
        let descriptor = DataQueries.pendingOutboxItems()
        return (try? mainContext.fetch(descriptor))?.count ?? 0
    }
}

// MARK: - SwiftUI Environment

extension EnvironmentValues {
    @Entry var dataStack: DataStack = DataStack.shared
}

// MARK: - View Modifier for Model Container

struct DataStackModifier: ViewModifier {
    func body(content: Content) -> some View {
        content
            .modelContainer(DataStack.shared.modelContainer)
            .environment(\.dataStack, DataStack.shared)
    }
}

extension View {
    /// Inject the Whisper2 data stack into the view hierarchy
    func withDataStack() -> some View {
        modifier(DataStackModifier())
    }
}

// MARK: - Preview Support

extension DataStack {
    /// Create an in-memory data stack for previews
    @MainActor
    static var preview: DataStack {
        let stack = DataStack()

        // Add sample data for previews (using proper WSP format)
        let _ = stack.insertContact(
            whisperId: "WSP-PREV-1234-ABCD",
            encPublicKey: "base64enckey==",
            signPublicKey: "base64signkey==",
            displayName: "Alice"
        )

        let _ = stack.insertContact(
            whisperId: "WSP-PREV-5678-EFGH",
            encPublicKey: "base64enckey2==",
            signPublicKey: "base64signkey2==",
            displayName: "Bob"
        )

        return stack
    }
}
