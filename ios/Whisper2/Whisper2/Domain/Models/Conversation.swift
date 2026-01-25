import Foundation

/// Conversation - Represents a chat thread (direct or group)

struct Conversation: Identifiable, Codable, Equatable {

    // MARK: - Properties

    /// Unique identifier for the conversation
    let id: String

    /// Type of conversation
    let type: ConversationType

    /// Title of the conversation (group name or contact name)
    var title: String

    /// For direct chats: the other participant's WhisperID
    let participantId: WhisperID?

    /// For group chats: the group ID
    let groupId: String?

    /// Timestamp of the last message
    var lastMessageAt: Date?

    /// Preview text of the last message
    var lastMessagePreview: String?

    /// Sender ID of the last message (for group chats)
    var lastMessageSenderId: WhisperID?

    /// Number of unread messages
    var unreadCount: Int

    /// Whether the conversation is pinned
    var isPinned: Bool

    /// Whether notifications are muted
    var isMuted: Bool

    /// Whether the conversation is archived
    var isArchived: Bool

    /// Draft message text (if any)
    var draftText: String?

    /// When the conversation was created
    let createdAt: Date

    // MARK: - Computed Properties

    /// Whether this is a direct (1-on-1) conversation
    var isDirect: Bool {
        type == .direct
    }

    /// Whether this is a group conversation
    var isGroup: Bool {
        type == .group
    }

    /// Whether there are unread messages
    var hasUnread: Bool {
        unreadCount > 0
    }

    /// Display-friendly unread count string
    var unreadBadge: String? {
        guard unreadCount > 0 else { return nil }
        return unreadCount > 99 ? "99+" : "\(unreadCount)"
    }

    // MARK: - Initialization

    /// Create a direct conversation
    /// - Parameters:
    ///   - participantId: The other participant's WhisperID
    ///   - title: Display name for the conversation
    static func direct(
        participantId: WhisperID,
        title: String
    ) -> Conversation {
        Conversation(
            id: "direct_\(participantId.rawValue)",
            type: .direct,
            title: title,
            participantId: participantId,
            groupId: nil,
            lastMessageAt: nil,
            lastMessagePreview: nil,
            lastMessageSenderId: nil,
            unreadCount: 0,
            isPinned: false,
            isMuted: false,
            isArchived: false,
            draftText: nil,
            createdAt: Date()
        )
    }

    /// Create a group conversation
    /// - Parameters:
    ///   - groupId: The group identifier
    ///   - title: Group name
    static func group(
        groupId: String,
        title: String
    ) -> Conversation {
        Conversation(
            id: "group_\(groupId)",
            type: .group,
            title: title,
            participantId: nil,
            groupId: groupId,
            lastMessageAt: nil,
            lastMessagePreview: nil,
            lastMessageSenderId: nil,
            unreadCount: 0,
            isPinned: false,
            isMuted: false,
            isArchived: false,
            draftText: nil,
            createdAt: Date()
        )
    }

    // MARK: - Full Initializer

    init(
        id: String,
        type: ConversationType,
        title: String,
        participantId: WhisperID?,
        groupId: String?,
        lastMessageAt: Date?,
        lastMessagePreview: String?,
        lastMessageSenderId: WhisperID?,
        unreadCount: Int,
        isPinned: Bool,
        isMuted: Bool,
        isArchived: Bool,
        draftText: String?,
        createdAt: Date
    ) {
        self.id = id
        self.type = type
        self.title = title
        self.participantId = participantId
        self.groupId = groupId
        self.lastMessageAt = lastMessageAt
        self.lastMessagePreview = lastMessagePreview
        self.lastMessageSenderId = lastMessageSenderId
        self.unreadCount = unreadCount
        self.isPinned = isPinned
        self.isMuted = isMuted
        self.isArchived = isArchived
        self.draftText = draftText
        self.createdAt = createdAt
    }

    // MARK: - Mutating Methods

    /// Update with a new message
    mutating func updateLastMessage(
        preview: String,
        at timestamp: Date,
        senderId: WhisperID?,
        incrementUnread: Bool = false
    ) {
        self.lastMessagePreview = preview
        self.lastMessageAt = timestamp
        self.lastMessageSenderId = senderId

        if incrementUnread {
            self.unreadCount += 1
        }
    }

    /// Mark all messages as read
    mutating func markAsRead() {
        self.unreadCount = 0
    }

    /// Set draft text
    mutating func setDraft(_ text: String?) {
        self.draftText = text?.isEmpty == true ? nil : text
    }
}

// MARK: - Conversation Type

enum ConversationType: String, Codable {
    case direct
    case group
}

// MARK: - Sorting

extension Conversation {

    /// Sort conversations for display (pinned first, then by last message time)
    static func sortedForDisplay(_ conversations: [Conversation]) -> [Conversation] {
        conversations.sorted { lhs, rhs in
            // Pinned first
            if lhs.isPinned != rhs.isPinned {
                return lhs.isPinned
            }

            // Then by last message time (most recent first)
            let lhsTime = lhs.lastMessageAt ?? lhs.createdAt
            let rhsTime = rhs.lastMessageAt ?? rhs.createdAt

            return lhsTime > rhsTime
        }
    }

    /// Filter out archived conversations
    static func active(_ conversations: [Conversation]) -> [Conversation] {
        conversations.filter { !$0.isArchived }
    }

    /// Get archived conversations only
    static func archived(_ conversations: [Conversation]) -> [Conversation] {
        conversations.filter { $0.isArchived }
    }
}

// MARK: - Equatable

extension Conversation {
    static func == (lhs: Conversation, rhs: Conversation) -> Bool {
        lhs.id == rhs.id
    }
}
