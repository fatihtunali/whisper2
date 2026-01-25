import Foundation
import Combine

/// GroupRepository - Protocol for group persistence operations
/// Groups are synced with server and cached locally

protocol GroupRepository {

    // MARK: - CRUD Operations

    /// Save a group to the local store
    /// - Parameter group: The group to save
    /// - Throws: StorageError if save fails
    func save(_ group: Group) async throws

    /// Save multiple groups in a batch
    /// - Parameter groups: Array of groups to save
    /// - Throws: StorageError if save fails
    func saveAll(_ groups: [Group]) async throws

    /// Fetch a group by ID
    /// - Parameter groupId: The group identifier
    /// - Returns: The group if found, nil otherwise
    func fetch(groupId: String) async throws -> Group?

    /// Fetch all groups
    /// - Returns: Array of all groups
    func fetchAll() async throws -> [Group]

    /// Fetch active (non-archived) groups
    /// - Returns: Array of active groups
    func fetchActive() async throws -> [Group]

    /// Fetch archived groups
    /// - Returns: Array of archived groups
    func fetchArchived() async throws -> [Group]

    /// Update a group
    /// - Parameter group: The group with updated fields
    /// - Throws: StorageError if update fails
    func update(_ group: Group) async throws

    /// Delete a group
    /// - Parameter groupId: The group identifier
    /// - Throws: StorageError if delete fails
    func delete(groupId: String) async throws

    /// Delete all groups
    /// - Throws: StorageError if delete fails
    func deleteAll() async throws

    // MARK: - Member Operations

    /// Add a member to a group
    /// - Parameters:
    ///   - groupId: The group identifier
    ///   - member: The member to add
    /// - Throws: GroupError if group not found or member limit exceeded
    func addMember(groupId: String, member: GroupMember) async throws

    /// Remove a member from a group
    /// - Parameters:
    ///   - groupId: The group identifier
    ///   - whisperId: The member's WhisperID
    /// - Throws: GroupError if group not found
    func removeMember(groupId: String, whisperId: WhisperID) async throws

    /// Update a member's role
    /// - Parameters:
    ///   - groupId: The group identifier
    ///   - whisperId: The member's WhisperID
    ///   - role: The new role
    /// - Throws: GroupError if group not found
    func updateMemberRole(
        groupId: String,
        whisperId: WhisperID,
        role: GroupMemberRole
    ) async throws

    /// Fetch groups where user is a member
    /// - Parameter whisperId: The user's WhisperID
    /// - Returns: Array of groups containing this member
    func fetchGroupsContaining(whisperId: WhisperID) async throws -> [Group]

    /// Fetch groups where user is owner
    /// - Parameter whisperId: The user's WhisperID
    /// - Returns: Array of groups owned by this user
    func fetchGroupsOwnedBy(whisperId: WhisperID) async throws -> [Group]

    // MARK: - Group Info Updates

    /// Update group title
    /// - Parameters:
    ///   - groupId: The group identifier
    ///   - title: The new title
    /// - Throws: GroupError if group not found
    func updateTitle(groupId: String, title: String) async throws

    /// Update group description
    /// - Parameters:
    ///   - groupId: The group identifier
    ///   - description: The new description
    /// - Throws: GroupError if group not found
    func updateDescription(groupId: String, description: String?) async throws

    /// Update group avatar URL
    /// - Parameters:
    ///   - groupId: The group identifier
    ///   - avatarUrl: The new avatar URL
    /// - Throws: GroupError if group not found
    func updateAvatarUrl(groupId: String, avatarUrl: String?) async throws

    /// Update group settings
    /// - Parameters:
    ///   - groupId: The group identifier
    ///   - settings: The new settings
    /// - Throws: GroupError if group not found
    func updateSettings(groupId: String, settings: GroupSettings) async throws

    /// Archive a group
    /// - Parameter groupId: The group identifier
    /// - Throws: GroupError if group not found
    func archive(groupId: String) async throws

    /// Unarchive a group
    /// - Parameter groupId: The group identifier
    /// - Throws: GroupError if group not found
    func unarchive(groupId: String) async throws

    // MARK: - Search

    /// Search groups by title
    /// - Parameter query: Search query string
    /// - Returns: Array of matching groups
    func search(query: String) async throws -> [Group]

    // MARK: - Existence & Membership Checks

    /// Check if a group exists
    /// - Parameter groupId: The group identifier
    /// - Returns: true if group exists, false otherwise
    func exists(groupId: String) async throws -> Bool

    /// Check if a user is a member of a group
    /// - Parameters:
    ///   - groupId: The group identifier
    ///   - whisperId: The user's WhisperID
    /// - Returns: true if user is a member, false otherwise
    func isMember(groupId: String, whisperId: WhisperID) async throws -> Bool

    /// Check if a user is an admin of a group
    /// - Parameters:
    ///   - groupId: The group identifier
    ///   - whisperId: The user's WhisperID
    /// - Returns: true if user is an admin or owner, false otherwise
    func isAdmin(groupId: String, whisperId: WhisperID) async throws -> Bool

    /// Check if a user is the owner of a group
    /// - Parameters:
    ///   - groupId: The group identifier
    ///   - whisperId: The user's WhisperID
    /// - Returns: true if user is the owner, false otherwise
    func isOwner(groupId: String, whisperId: WhisperID) async throws -> Bool

    // MARK: - Statistics

    /// Get total group count
    /// - Returns: Number of groups
    func count() async throws -> Int

    /// Get member count for a group
    /// - Parameter groupId: The group identifier
    /// - Returns: Number of members in the group
    func memberCount(groupId: String) async throws -> Int

    // MARK: - Reactive Streams

    /// Publisher for group list changes
    /// - Returns: Publisher that emits updated group list
    func groupsPublisher() -> AnyPublisher<[Group], Never>

    /// Publisher for a specific group's changes
    /// - Parameter groupId: The group identifier
    /// - Returns: Publisher that emits group updates
    func groupPublisher(groupId: String) -> AnyPublisher<Group?, Never>

    /// Publisher for group membership changes
    /// - Parameter groupId: The group identifier
    /// - Returns: Publisher that emits updated member list
    func membersPublisher(groupId: String) -> AnyPublisher<[GroupMember], Never>
}

// MARK: - Default Implementations

extension GroupRepository {

    /// Convenience method to create a new group
    func create(
        title: String,
        ownerId: WhisperID,
        initialMembers: [GroupMember] = []
    ) async throws -> Group {
        let group = Group.create(
            title: title,
            ownerId: ownerId,
            initialMembers: initialMembers
        )
        try await save(group)
        return group
    }

    /// Leave a group (remove self from members)
    func leave(groupId: String, myId: WhisperID) async throws {
        try await removeMember(groupId: groupId, whisperId: myId)
    }
}
