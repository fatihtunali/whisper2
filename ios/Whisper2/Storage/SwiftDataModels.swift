import Foundation
import SwiftData

// MARK: - Contact Entity

/// Represents a contact in the user's address book
@Model
final class ContactEntity {
    /// The contact's Whisper ID (e.g., "WSP-XXXX-XXXX-XXXX")
    @Attribute(.unique) var whisperId: String

    /// Contact's X25519 public key for encryption (base64)
    var encPublicKey: String

    /// Contact's Ed25519 public key for signature verification (base64)
    var signPublicKey: String

    /// User-defined display name
    var displayName: String?

    /// When the contact was added
    var addedAt: Date

    /// Whether this contact is blocked
    var isBlocked: Bool

    /// Whether this contact is pinned to top of list
    var isPinned: Bool

    /// Whether notifications are muted for this contact
    var isMuted: Bool

    /// Optional avatar URL
    var avatarURL: String?

    /// Last seen timestamp (if available)
    var lastSeenAt: Date?

    /// Relationship to conversations
    @Relationship(deleteRule: .nullify, inverse: \ConversationEntity.contact)
    var conversation: ConversationEntity?

    init(
        whisperId: String,
        encPublicKey: String,
        signPublicKey: String,
        displayName: String? = nil,
        addedAt: Date = Date(),
        isBlocked: Bool = false,
        isPinned: Bool = false,
        isMuted: Bool = false,
        avatarURL: String? = nil,
        lastSeenAt: Date? = nil
    ) {
        self.whisperId = whisperId
        self.encPublicKey = encPublicKey
        self.signPublicKey = signPublicKey
        self.displayName = displayName
        self.addedAt = addedAt
        self.isBlocked = isBlocked
        self.isPinned = isPinned
        self.isMuted = isMuted
        self.avatarURL = avatarURL
        self.lastSeenAt = lastSeenAt
    }

    /// Display name or Whisper ID if no name set
    var effectiveDisplayName: String {
        displayName ?? whisperId
    }
}

// MARK: - Conversation Entity

/// Represents a chat conversation (1-1 or group)
@Model
final class ConversationEntity {
    /// Unique conversation identifier
    @Attribute(.unique) var id: String

    /// Conversation type: "direct" or "group"
    var type: String

    /// Conversation title (group name or contact display name)
    var title: String?

    /// Timestamp of last message
    var lastMessageAt: Date?

    /// Preview text of last message
    var lastMessagePreview: String?

    /// Number of unread messages
    var unreadCount: Int

    /// Whether the conversation is archived
    var isArchived: Bool

    /// Whether the conversation is pinned
    var isPinned: Bool

    /// Whether notifications are muted
    var isMuted: Bool

    /// For direct chats: the contact
    var contact: ContactEntity?

    /// For group chats: the group
    var group: GroupEntity?

    /// Messages in this conversation
    @Relationship(deleteRule: .cascade, inverse: \MessageEntity.conversation)
    var messages: [MessageEntity] = []

    init(
        id: String,
        type: ConversationType,
        title: String? = nil,
        lastMessageAt: Date? = nil,
        lastMessagePreview: String? = nil,
        unreadCount: Int = 0,
        isArchived: Bool = false,
        isPinned: Bool = false,
        isMuted: Bool = false
    ) {
        self.id = id
        self.type = type.rawValue
        self.title = title
        self.lastMessageAt = lastMessageAt
        self.lastMessagePreview = lastMessagePreview
        self.unreadCount = unreadCount
        self.isArchived = isArchived
        self.isPinned = isPinned
        self.isMuted = isMuted
    }

    /// Conversation type enum
    enum ConversationType: String, Codable {
        case direct
        case group
    }

    var conversationType: ConversationType {
        ConversationType(rawValue: type) ?? .direct
    }

    /// Effective title (contact name, group name, or ID)
    var effectiveTitle: String {
        if let title = title, !title.isEmpty {
            return title
        }
        if let contact = contact {
            return contact.effectiveDisplayName
        }
        if let group = group {
            return group.title
        }
        return id
    }
}

// MARK: - Message Entity

/// Represents a single message
@Model
final class MessageEntity {
    /// Unique message ID (UUID)
    @Attribute(.unique) var messageId: String

    /// Conversation this message belongs to
    var conversation: ConversationEntity?

    /// Conversation ID (for queries)
    var conversationId: String

    /// Sender's Whisper ID
    var senderId: String

    /// For direct messages: recipient's Whisper ID
    var recipientId: String?

    /// For group messages: group ID
    var groupId: String?

    /// Message type: "text", "image", "file", "voice", "location"
    var msgType: String

    /// Server timestamp
    var timestamp: Date

    /// Message status: "sending", "sent", "delivered", "read", "failed"
    var status: String

    /// Encrypted message content (base64)
    var ciphertext: String

    /// Encryption nonce (base64)
    var nonce: String

    /// Decrypted plaintext (cached after decryption)
    var plaintextCache: String?

    /// For attachments: object key in storage
    var attachmentObjectKey: String?

    /// For attachments: file name
    var attachmentFileName: String?

    /// For attachments: MIME type
    var attachmentMimeType: String?

    /// For attachments: file size in bytes
    var attachmentSize: Int?

    /// For attachments: local file path (after download)
    var attachmentLocalPath: String?

    /// For attachments: thumbnail data (base64)
    var attachmentThumbnail: String?

    /// Whether this is an outgoing message
    var isOutgoing: Bool

    /// Whether this message has been deleted locally
    var isDeleted: Bool

    /// Reply to message ID (if this is a reply)
    var replyToMessageId: String?

    /// For forwarded messages: original sender ID
    var forwardedFrom: String?

    init(
        messageId: String,
        conversationId: String,
        senderId: String,
        recipientId: String? = nil,
        groupId: String? = nil,
        msgType: MessageType,
        timestamp: Date,
        status: MessageStatus = .sending,
        ciphertext: String,
        nonce: String,
        plaintextCache: String? = nil,
        attachmentObjectKey: String? = nil,
        attachmentFileName: String? = nil,
        attachmentMimeType: String? = nil,
        attachmentSize: Int? = nil,
        attachmentLocalPath: String? = nil,
        attachmentThumbnail: String? = nil,
        isOutgoing: Bool,
        isDeleted: Bool = false,
        replyToMessageId: String? = nil,
        forwardedFrom: String? = nil
    ) {
        self.messageId = messageId
        self.conversationId = conversationId
        self.senderId = senderId
        self.recipientId = recipientId
        self.groupId = groupId
        self.msgType = msgType.rawValue
        self.timestamp = timestamp
        self.status = status.rawValue
        self.ciphertext = ciphertext
        self.nonce = nonce
        self.plaintextCache = plaintextCache
        self.attachmentObjectKey = attachmentObjectKey
        self.attachmentFileName = attachmentFileName
        self.attachmentMimeType = attachmentMimeType
        self.attachmentSize = attachmentSize
        self.attachmentLocalPath = attachmentLocalPath
        self.attachmentThumbnail = attachmentThumbnail
        self.isOutgoing = isOutgoing
        self.isDeleted = isDeleted
        self.replyToMessageId = replyToMessageId
        self.forwardedFrom = forwardedFrom
    }

    /// Message type enum
    enum MessageType: String, Codable, CaseIterable {
        case text
        case image
        case file
        case voice
        case video
        case location
        case contact
        case sticker
    }

    var messageType: MessageType {
        MessageType(rawValue: msgType) ?? .text
    }

    /// Message status enum
    enum MessageStatus: String, Codable, CaseIterable {
        case sending
        case sent
        case delivered
        case read
        case failed
    }

    var messageStatus: MessageStatus {
        get { MessageStatus(rawValue: status) ?? .sending }
        set { status = newValue.rawValue }
    }

    /// Whether the message has an attachment
    var hasAttachment: Bool {
        attachmentObjectKey != nil
    }

    /// Whether the attachment is downloaded
    var isAttachmentDownloaded: Bool {
        attachmentLocalPath != nil
    }
}

// MARK: - Group Entity

/// Represents a group chat
@Model
final class GroupEntity {
    /// Unique group ID
    @Attribute(.unique) var groupId: String

    /// Group title/name
    var title: String

    /// Group owner's Whisper ID
    var ownerId: String

    /// JSON-encoded array of member Whisper IDs
    var membersJSON: String

    /// When the group was created
    var createdAt: Date

    /// When the group was last updated
    var updatedAt: Date

    /// Group avatar URL
    var avatarURL: String?

    /// Group description
    var groupDescription: String?

    /// Whether current user is the owner
    var isOwner: Bool

    /// Relationship to conversation
    @Relationship(deleteRule: .nullify, inverse: \ConversationEntity.group)
    var conversation: ConversationEntity?

    init(
        groupId: String,
        title: String,
        ownerId: String,
        members: [String],
        createdAt: Date = Date(),
        updatedAt: Date = Date(),
        avatarURL: String? = nil,
        groupDescription: String? = nil,
        isOwner: Bool = false
    ) {
        self.groupId = groupId
        self.title = title
        self.ownerId = ownerId
        self.membersJSON = (try? JSONEncoder().encode(members)).flatMap { String(data: $0, encoding: .utf8) } ?? "[]"
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.avatarURL = avatarURL
        self.groupDescription = groupDescription
        self.isOwner = isOwner
    }

    /// Decoded member list
    var members: [String] {
        get {
            guard let data = membersJSON.data(using: .utf8),
                  let decoded = try? JSONDecoder().decode([String].self, from: data) else {
                return []
            }
            return decoded
        }
        set {
            if let data = try? JSONEncoder().encode(newValue),
               let json = String(data: data, encoding: .utf8) {
                membersJSON = json
            }
        }
    }

    /// Number of members
    var memberCount: Int {
        members.count
    }

    /// Check if a Whisper ID is a member
    func isMember(_ whisperId: String) -> Bool {
        members.contains(whisperId)
    }
}

// MARK: - Outbox Queue Item

/// Represents a message waiting to be sent
@Model
final class OutboxQueueItem {
    /// Message ID being sent
    @Attribute(.unique) var messageId: String

    /// JSON-encoded payload to send
    var payload: String

    /// Number of send attempts
    var attempts: Int

    /// When to retry next
    var nextRetryAt: Date

    /// When this item was created
    var createdAt: Date

    /// Error message from last failed attempt
    var lastError: String?

    /// Whether this item is currently being processed
    var isProcessing: Bool

    init(
        messageId: String,
        payload: String,
        attempts: Int = 0,
        nextRetryAt: Date = Date(),
        createdAt: Date = Date(),
        lastError: String? = nil,
        isProcessing: Bool = false
    ) {
        self.messageId = messageId
        self.payload = payload
        self.attempts = attempts
        self.nextRetryAt = nextRetryAt
        self.createdAt = createdAt
        self.lastError = lastError
        self.isProcessing = isProcessing
    }

    /// Check if max retries exceeded
    var isMaxRetriesExceeded: Bool {
        attempts >= Constants.Limits.outboxMaxRetries
    }

    /// Check if ready to retry
    var isReadyToRetry: Bool {
        !isProcessing && nextRetryAt <= Date() && !isMaxRetriesExceeded
    }

    /// Increment attempts and calculate next retry time
    func recordFailedAttempt(error: String?) {
        attempts += 1
        lastError = error
        isProcessing = false

        // Exponential backoff: 1s, 2s, 4s, 8s, 16s
        let delay = pow(2.0, Double(attempts - 1))
        nextRetryAt = Date().addingTimeInterval(delay)
    }

    /// Mark as processing
    func startProcessing() {
        isProcessing = true
    }
}

// MARK: - Query Descriptors

/// Commonly used SwiftData queries
enum DataQueries {
    /// Fetch all contacts (sorting should be done after fetch)
    static var allContacts: FetchDescriptor<ContactEntity> {
        FetchDescriptor<ContactEntity>()
    }

    /// Fetch non-blocked contacts
    static var activeContacts: FetchDescriptor<ContactEntity> {
        FetchDescriptor<ContactEntity>(
            predicate: #Predicate<ContactEntity> { contact in !contact.isBlocked }
        )
    }

    /// Fetch all conversations (sorting should be done after fetch)
    static var allConversations: FetchDescriptor<ConversationEntity> {
        FetchDescriptor<ConversationEntity>()
    }

    /// Fetch conversations with unread messages
    static var unreadConversations: FetchDescriptor<ConversationEntity> {
        FetchDescriptor<ConversationEntity>(
            predicate: #Predicate<ConversationEntity> { conv in conv.unreadCount > 0 }
        )
    }

    /// Fetch non-archived conversations
    static var activeConversations: FetchDescriptor<ConversationEntity> {
        FetchDescriptor<ConversationEntity>(
            predicate: #Predicate<ConversationEntity> { conv in !conv.isArchived }
        )
    }

    /// Fetch messages for a conversation
    static func messagesForConversation(_ conversationId: String, limit: Int = 50) -> FetchDescriptor<MessageEntity> {
        var descriptor = FetchDescriptor<MessageEntity>(
            predicate: #Predicate<MessageEntity> { msg in msg.conversationId == conversationId && !msg.isDeleted }
        )
        descriptor.fetchLimit = limit
        return descriptor
    }

    /// Fetch pending outbox items ready to retry
    static func pendingOutboxItems() -> FetchDescriptor<OutboxQueueItem> {
        let now = Date()
        let maxRetries = Constants.Limits.outboxMaxRetries
        return FetchDescriptor<OutboxQueueItem>(
            predicate: #Predicate<OutboxQueueItem> { item in
                !item.isProcessing &&
                item.nextRetryAt <= now &&
                item.attempts < maxRetries
            }
        )
    }

    /// Fetch all groups
    static var allGroups: FetchDescriptor<GroupEntity> {
        FetchDescriptor<GroupEntity>()
    }

    /// Contact by Whisper ID
    static func contact(whisperId: String) -> FetchDescriptor<ContactEntity> {
        FetchDescriptor<ContactEntity>(
            predicate: #Predicate<ContactEntity> { contact in contact.whisperId == whisperId }
        )
    }

    /// Conversation by ID
    static func conversation(id: String) -> FetchDescriptor<ConversationEntity> {
        FetchDescriptor<ConversationEntity>(
            predicate: #Predicate<ConversationEntity> { conv in conv.id == id }
        )
    }

    /// Group by ID
    static func group(groupId: String) -> FetchDescriptor<GroupEntity> {
        FetchDescriptor<GroupEntity>(
            predicate: #Predicate<GroupEntity> { group in group.groupId == groupId }
        )
    }

    /// Message by ID
    static func message(messageId: String) -> FetchDescriptor<MessageEntity> {
        FetchDescriptor<MessageEntity>(
            predicate: #Predicate<MessageEntity> { msg in msg.messageId == messageId }
        )
    }

    /// Outbox item by message ID
    static func outboxItem(messageId: String) -> FetchDescriptor<OutboxQueueItem> {
        FetchDescriptor<OutboxQueueItem>(
            predicate: #Predicate<OutboxQueueItem> { item in item.messageId == messageId }
        )
    }
}
