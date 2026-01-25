import Foundation

/// Group - Represents a group chat

struct Group: Identifiable, Codable, Equatable {

    // MARK: - Properties

    /// Unique group identifier
    let groupId: String

    /// Convenience accessor for Identifiable
    var id: String { groupId }

    /// Group display name
    var title: String

    /// Group description (optional)
    var description: String?

    /// Owner's WhisperID
    let ownerId: WhisperID

    /// List of group members with their roles
    var members: [GroupMember]

    /// When the group was created
    let createdAt: Date

    /// When the group was last updated
    var updatedAt: Date

    /// Group avatar URL (optional)
    var avatarUrl: String?

    /// Whether the group is archived
    var isArchived: Bool

    /// Group settings
    var settings: GroupSettings

    // MARK: - Computed Properties

    /// Number of members in the group
    var memberCount: Int {
        members.count
    }

    /// Whether the group has reached maximum capacity
    var isFull: Bool {
        members.count >= Constants.Limits.maxGroupMembers
    }

    /// Get member IDs only
    var memberIds: [WhisperID] {
        members.map(\.whisperId)
    }

    /// Get admin member IDs
    var adminIds: [WhisperID] {
        members.filter { $0.role == .admin || $0.role == .owner }
            .map(\.whisperId)
    }

    // MARK: - Initialization

    /// Create a new group
    /// - Parameters:
    ///   - title: Group name
    ///   - ownerId: Creator's WhisperID
    ///   - initialMembers: Initial list of members (owner included automatically)
    static func create(
        title: String,
        ownerId: WhisperID,
        initialMembers: [GroupMember] = []
    ) -> Group {
        var members = initialMembers

        // Ensure owner is in the member list with owner role
        if !members.contains(where: { $0.whisperId == ownerId }) {
            members.insert(GroupMember(whisperId: ownerId, role: .owner), at: 0)
        } else {
            // Update existing entry to owner role
            if let index = members.firstIndex(where: { $0.whisperId == ownerId }) {
                members[index].role = .owner
            }
        }

        let now = Date()
        return Group(
            groupId: UUID().uuidString,
            title: title,
            description: nil,
            ownerId: ownerId,
            members: members,
            createdAt: now,
            updatedAt: now,
            avatarUrl: nil,
            isArchived: false,
            settings: GroupSettings()
        )
    }

    // MARK: - Full Initializer

    init(
        groupId: String,
        title: String,
        description: String?,
        ownerId: WhisperID,
        members: [GroupMember],
        createdAt: Date,
        updatedAt: Date,
        avatarUrl: String?,
        isArchived: Bool,
        settings: GroupSettings
    ) {
        self.groupId = groupId
        self.title = title
        self.description = description
        self.ownerId = ownerId
        self.members = members
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.avatarUrl = avatarUrl
        self.isArchived = isArchived
        self.settings = settings
    }

    // MARK: - Member Management

    /// Check if a user is a member
    func isMember(_ whisperId: WhisperID) -> Bool {
        members.contains { $0.whisperId == whisperId }
    }

    /// Check if a user is the owner
    func isOwner(_ whisperId: WhisperID) -> Bool {
        ownerId == whisperId
    }

    /// Check if a user is an admin (or owner)
    func isAdmin(_ whisperId: WhisperID) -> Bool {
        if isOwner(whisperId) { return true }
        return members.first { $0.whisperId == whisperId }?.role == .admin
    }

    /// Get a specific member
    func member(_ whisperId: WhisperID) -> GroupMember? {
        members.first { $0.whisperId == whisperId }
    }

    /// Add a new member
    mutating func addMember(_ member: GroupMember) throws {
        guard !isFull else {
            throw GroupError.memberLimitExceeded
        }

        guard !isMember(member.whisperId) else {
            return // Already a member
        }

        members.append(member)
        updatedAt = Date()
    }

    /// Remove a member
    mutating func removeMember(_ whisperId: WhisperID) {
        guard !isOwner(whisperId) else {
            return // Cannot remove owner
        }

        members.removeAll { $0.whisperId == whisperId }
        updatedAt = Date()
    }

    /// Update a member's role
    mutating func updateMemberRole(_ whisperId: WhisperID, role: GroupMemberRole) {
        guard !isOwner(whisperId) else {
            return // Cannot change owner's role
        }

        if let index = members.firstIndex(where: { $0.whisperId == whisperId }) {
            members[index].role = role
            updatedAt = Date()
        }
    }

    /// Update group title
    mutating func updateTitle(_ newTitle: String) {
        self.title = String(newTitle.prefix(Constants.Limits.maxGroupTitleLength))
        self.updatedAt = Date()
    }
}

// MARK: - Group Member

struct GroupMember: Codable, Equatable, Identifiable {

    /// Member's WhisperID
    let whisperId: WhisperID

    /// Member's role in the group
    var role: GroupMemberRole

    /// When the member joined
    let joinedAt: Date

    /// Display name override for this group
    var nickname: String?

    /// Convenience accessor for Identifiable
    var id: String { whisperId.rawValue }

    /// Create a new group member
    init(whisperId: WhisperID, role: GroupMemberRole = .member, nickname: String? = nil) {
        self.whisperId = whisperId
        self.role = role
        self.joinedAt = Date()
        self.nickname = nickname
    }

    /// Full initializer
    init(whisperId: WhisperID, role: GroupMemberRole, joinedAt: Date, nickname: String?) {
        self.whisperId = whisperId
        self.role = role
        self.joinedAt = joinedAt
        self.nickname = nickname
    }
}

// MARK: - Group Member Role

enum GroupMemberRole: String, Codable, Comparable {
    case owner
    case admin
    case member

    var displayText: String {
        switch self {
        case .owner: return "Owner"
        case .admin: return "Admin"
        case .member: return "Member"
        }
    }

    /// Comparison for role hierarchy (owner > admin > member)
    static func < (lhs: GroupMemberRole, rhs: GroupMemberRole) -> Bool {
        let order: [GroupMemberRole: Int] = [.owner: 2, .admin: 1, .member: 0]
        return (order[lhs] ?? 0) < (order[rhs] ?? 0)
    }
}

// MARK: - Group Settings

struct GroupSettings: Codable, Equatable {

    /// Only admins can send messages
    var adminOnlyMessages: Bool

    /// Only admins can change group info
    var adminOnlyEdit: Bool

    /// Members can invite others
    var membersCanInvite: Bool

    /// Show message read receipts
    var showReadReceipts: Bool

    /// Default initializer with sensible defaults
    init(
        adminOnlyMessages: Bool = false,
        adminOnlyEdit: Bool = false,
        membersCanInvite: Bool = true,
        showReadReceipts: Bool = true
    ) {
        self.adminOnlyMessages = adminOnlyMessages
        self.adminOnlyEdit = adminOnlyEdit
        self.membersCanInvite = membersCanInvite
        self.showReadReceipts = showReadReceipts
    }
}

// MARK: - Equatable

extension Group {
    static func == (lhs: Group, rhs: Group) -> Bool {
        lhs.groupId == rhs.groupId
    }
}
