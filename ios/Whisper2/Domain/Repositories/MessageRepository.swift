import Foundation
import Combine

/// MessageRepository - Protocol for message persistence operations
/// Implementations handle local storage (SQLite/CoreData) and sync with server

protocol MessageRepository {

    // MARK: - CRUD Operations

    /// Save a message to the local store
    /// - Parameter message: The message to save
    /// - Throws: StorageError if save fails
    func save(_ message: Message) async throws

    /// Save multiple messages in a batch
    /// - Parameter messages: Array of messages to save
    /// - Throws: StorageError if save fails
    func saveAll(_ messages: [Message]) async throws

    /// Fetch a message by ID
    /// - Parameter messageId: The message identifier
    /// - Returns: The message if found, nil otherwise
    func fetch(messageId: String) async throws -> Message?

    /// Fetch messages for a conversation
    /// - Parameters:
    ///   - conversationId: The conversation identifier
    ///   - limit: Maximum number of messages to return
    ///   - before: Fetch messages before this date (for pagination)
    /// - Returns: Array of messages, ordered by timestamp descending
    func fetchMessages(
        conversationId: String,
        limit: Int,
        before: Date?
    ) async throws -> [Message]

    /// Fetch all messages in a conversation
    /// - Parameter conversationId: The conversation identifier
    /// - Returns: Array of all messages, ordered by timestamp
    func fetchAllMessages(conversationId: String) async throws -> [Message]

    /// Update a message
    /// - Parameter message: The message with updated fields
    /// - Throws: StorageError if update fails
    func update(_ message: Message) async throws

    /// Delete a message
    /// - Parameter messageId: The message identifier
    /// - Throws: StorageError if delete fails
    func delete(messageId: String) async throws

    /// Delete all messages in a conversation
    /// - Parameter conversationId: The conversation identifier
    /// - Throws: StorageError if delete fails
    func deleteAllMessages(conversationId: String) async throws

    // MARK: - Status Updates

    /// Update message status
    /// - Parameters:
    ///   - messageId: The message identifier
    ///   - status: The new status
    func updateStatus(messageId: String, status: MessageStatus) async throws

    /// Mark messages as read
    /// - Parameters:
    ///   - conversationId: The conversation identifier
    ///   - upToDate: Mark all messages up to this date as read
    func markAsRead(conversationId: String, upToDate: Date) async throws

    // MARK: - Outbox (Pending Messages)

    /// Fetch all pending messages (queued or failed)
    /// - Returns: Array of messages waiting to be sent
    func fetchPendingMessages() async throws -> [Message]

    /// Fetch pending messages for a specific conversation
    /// - Parameter conversationId: The conversation identifier
    /// - Returns: Array of pending messages for the conversation
    func fetchPendingMessages(conversationId: String) async throws -> [Message]

    // MARK: - Search

    /// Search messages by content
    /// - Parameters:
    ///   - query: Search query string
    ///   - conversationId: Optional conversation to limit search to
    ///   - limit: Maximum results to return
    /// - Returns: Array of matching messages
    func search(
        query: String,
        conversationId: String?,
        limit: Int
    ) async throws -> [Message]

    // MARK: - Attachments

    /// Fetch messages with attachments
    /// - Parameters:
    ///   - conversationId: The conversation identifier
    ///   - type: Optional attachment type filter
    ///   - limit: Maximum results to return
    /// - Returns: Array of messages with attachments
    func fetchMessagesWithAttachments(
        conversationId: String,
        type: AttachmentType?,
        limit: Int
    ) async throws -> [Message]

    // MARK: - Statistics

    /// Get unread message count for a conversation
    /// - Parameter conversationId: The conversation identifier
    /// - Returns: Number of unread messages
    func unreadCount(conversationId: String) async throws -> Int

    /// Get total unread message count across all conversations
    /// - Returns: Total number of unread messages
    func totalUnreadCount() async throws -> Int

    /// Get message count for a conversation
    /// - Parameter conversationId: The conversation identifier
    /// - Returns: Total number of messages
    func messageCount(conversationId: String) async throws -> Int

    // MARK: - Reactive Streams

    /// Publisher for new messages in a conversation
    /// - Parameter conversationId: The conversation identifier
    /// - Returns: Publisher that emits new messages
    func messagesPublisher(conversationId: String) -> AnyPublisher<[Message], Never>

    /// Publisher for message status updates
    /// - Returns: Publisher that emits message ID and new status
    func statusUpdatesPublisher() -> AnyPublisher<(String, MessageStatus), Never>

    /// Publisher for unread count changes
    /// - Parameter conversationId: The conversation identifier
    /// - Returns: Publisher that emits updated unread counts
    func unreadCountPublisher(conversationId: String) -> AnyPublisher<Int, Never>
}

// MARK: - Default Implementations

extension MessageRepository {

    /// Fetch messages with default limit
    func fetchMessages(conversationId: String) async throws -> [Message] {
        try await fetchMessages(conversationId: conversationId, limit: 50, before: nil)
    }

    /// Search messages with default limit
    func search(query: String, conversationId: String? = nil) async throws -> [Message] {
        try await search(query: query, conversationId: conversationId, limit: 100)
    }

    /// Fetch messages with attachments with default limit
    func fetchMessagesWithAttachments(
        conversationId: String,
        type: AttachmentType? = nil
    ) async throws -> [Message] {
        try await fetchMessagesWithAttachments(conversationId: conversationId, type: type, limit: 50)
    }
}
