import Foundation

/// Group conversation model
struct ChatGroup: Codable, Identifiable, Hashable {
    let id: String  // groupId
    var title: String
    var memberIds: [String]  // WhisperIDs of members
    var creatorId: String
    var createdAt: Date
    var updatedAt: Date
    var lastMessageTime: Date?
    var lastMessage: String?
    var unreadCount: Int

    init(
        id: String,
        title: String,
        memberIds: [String],
        creatorId: String,
        createdAt: Date = Date(),
        updatedAt: Date = Date(),
        lastMessageTime: Date? = nil,
        lastMessage: String? = nil,
        unreadCount: Int = 0
    ) {
        self.id = id
        self.title = title
        self.memberIds = memberIds
        self.creatorId = creatorId
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.lastMessageTime = lastMessageTime
        self.lastMessage = lastMessage
        self.unreadCount = unreadCount
    }

    // Hashable
    func hash(into hasher: inout Hasher) {
        hasher.combine(id)
    }

    static func == (lhs: ChatGroup, rhs: ChatGroup) -> Bool {
        lhs.id == rhs.id
    }
}

/// Group event types (matches server GroupEventPayload.event)
enum GroupEventType: String, Codable {
    case created = "created"
    case updated = "updated"
    case memberAdded = "member_added"
    case memberRemoved = "member_removed"
}

/// Group member from server
struct GroupMember: Codable {
    let whisperId: String
    let role: String  // "owner", "admin", "member"
    let joinedAt: Int64
    let removedAt: Int64?
}

/// Group object from server
struct ServerGroup: Codable {
    let groupId: String
    let title: String
    let ownerId: String
    let createdAt: Int64
    let updatedAt: Int64
    let members: [GroupMember]
}

/// Group event payload (received from server)
/// Server format: { event: string, group: Group, affectedMembers?: string[] }
struct GroupEventPayload: Codable {
    let event: String  // "created", "updated", "member_added", "member_removed"
    let group: ServerGroup
    let affectedMembers: [String]?
}

/// Group message (received)
struct GroupMessagePayload: Codable {
    let groupId: String
    let messageId: String
    let from: String
    let msgType: String
    let timestamp: Int64
    let nonce: String
    let ciphertext: String
    let sig: String
    let attachment: AttachmentPointer?
}

/// Group invite - pending invitation to join a group
struct GroupInvite: Codable, Identifiable {
    let id: String  // groupId
    let groupId: String
    let title: String
    let inviterId: String  // Who invited (creator or admin)
    let inviterName: String?
    let memberIds: [String]
    let memberCount: Int
    let createdAt: Date
    let receivedAt: Date

    init(
        groupId: String,
        title: String,
        inviterId: String,
        inviterName: String? = nil,
        memberIds: [String],
        createdAt: Date = Date(),
        receivedAt: Date = Date()
    ) {
        self.id = groupId
        self.groupId = groupId
        self.title = title
        self.inviterId = inviterId
        self.inviterName = inviterName
        self.memberIds = memberIds
        self.memberCount = memberIds.count
        self.createdAt = createdAt
        self.receivedAt = receivedAt
    }
}
