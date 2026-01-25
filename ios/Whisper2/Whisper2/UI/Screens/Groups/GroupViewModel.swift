import SwiftUI
import Observation

/// Model representing a group
struct Group: Identifiable, Equatable {
    let id: String
    var title: String
    var members: [GroupMember]
    var avatarURL: URL?
    var lastMessage: String?
    var lastMessageTimestamp: Date?
    var unreadCount: Int
    var createdAt: Date
    var ownerId: String

    var memberCount: Int { members.count }

    static func == (lhs: Group, rhs: Group) -> Bool {
        lhs.id == rhs.id
    }
}

/// Model representing a group member
struct GroupMember: Identifiable, Equatable {
    let id: String
    let whisperId: String
    var displayName: String
    var avatarURL: URL?
    var isOnline: Bool
    var role: Role

    enum Role: String {
        case owner
        case admin
        case member
    }

    static func == (lhs: GroupMember, rhs: GroupMember) -> Bool {
        lhs.id == rhs.id
    }
}

/// Manages groups list and individual group state
@Observable
final class GroupViewModel {
    // MARK: - State

    var groups: [Group] = []
    var filteredGroups: [Group] = []
    var searchText: String = "" {
        didSet { filterGroups() }
    }
    var isLoading = false
    var error: String?

    // Current group state (when viewing a single group)
    var currentGroup: Group?
    var messages: [ChatMessage] = []
    var messageText: String = ""
    var isSending = false

    // Create group state
    var isCreatingGroup = false
    var newGroupTitle: String = ""
    var selectedMembers: Set<Contact> = []
    var createGroupError: String?

    // MARK: - Dependencies

    // These will be injected when actual services are implemented
    // private let groupService: GroupServiceProtocol
    // private let messagingService: MessagingServiceProtocol

    // MARK: - Computed Properties

    var totalUnreadCount: Int {
        groups.reduce(0) { $0 + $1.unreadCount }
    }

    var canCreateGroup: Bool {
        !newGroupTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
        !selectedMembers.isEmpty
    }

    var canSend: Bool {
        !messageText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !isSending
    }

    // MARK: - Actions - Groups List

    func loadGroups() {
        isLoading = true
        error = nil

        Task { @MainActor in
            do {
                // Simulate loading
                try await Task.sleep(for: .seconds(1))

                // Placeholder data
                groups = [
                    Group(
                        id: "1",
                        title: "Family",
                        members: [
                            GroupMember(id: "m1", whisperId: "WH2-MOM123", displayName: "Mom", avatarURL: nil, isOnline: true, role: .member),
                            GroupMember(id: "m2", whisperId: "WH2-DAD456", displayName: "Dad", avatarURL: nil, isOnline: false, role: .member),
                            GroupMember(id: "m3", whisperId: "WH2-ME0000", displayName: "Me", avatarURL: nil, isOnline: true, role: .owner)
                        ],
                        avatarURL: nil,
                        lastMessage: "Dinner at 7?",
                        lastMessageTimestamp: Date().addingTimeInterval(-1800),
                        unreadCount: 3,
                        createdAt: Date().addingTimeInterval(-86400 * 30),
                        ownerId: "m3"
                    ),
                    Group(
                        id: "2",
                        title: "Work Team",
                        members: [
                            GroupMember(id: "m4", whisperId: "WH2-ALICE1", displayName: "Alice", avatarURL: nil, isOnline: true, role: .admin),
                            GroupMember(id: "m5", whisperId: "WH2-BOB234", displayName: "Bob", avatarURL: nil, isOnline: true, role: .member),
                            GroupMember(id: "m6", whisperId: "WH2-CAROL3", displayName: "Carol", avatarURL: nil, isOnline: false, role: .member),
                            GroupMember(id: "m3", whisperId: "WH2-ME0000", displayName: "Me", avatarURL: nil, isOnline: true, role: .owner)
                        ],
                        avatarURL: nil,
                        lastMessage: "Meeting moved to 3pm",
                        lastMessageTimestamp: Date().addingTimeInterval(-7200),
                        unreadCount: 0,
                        createdAt: Date().addingTimeInterval(-86400 * 7),
                        ownerId: "m3"
                    )
                ]

                filterGroups()
                isLoading = false
            } catch {
                self.error = "Failed to load groups"
                isLoading = false
            }
        }
    }

    func refreshGroups() async {
        try? await Task.sleep(for: .milliseconds(500))
        loadGroups()
    }

    func deleteGroup(_ group: Group) {
        groups.removeAll { $0.id == group.id }
        filterGroups()
    }

    func leaveGroup(_ group: Group) {
        // In real implementation, leave the group on server
        deleteGroup(group)
    }

    // MARK: - Actions - Create Group

    func createGroup() {
        guard canCreateGroup else { return }

        isCreatingGroup = true
        createGroupError = nil

        let title = newGroupTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        let members = Array(selectedMembers)

        Task { @MainActor in
            do {
                // Simulate creation
                try await Task.sleep(for: .seconds(1))

                let newGroup = Group(
                    id: UUID().uuidString,
                    title: title,
                    members: members.map { contact in
                        GroupMember(
                            id: contact.id,
                            whisperId: contact.whisperId,
                            displayName: contact.displayName,
                            avatarURL: contact.avatarURL,
                            isOnline: contact.isOnline,
                            role: .member
                        )
                    } + [
                        GroupMember(id: "me", whisperId: "WH2-ME0000", displayName: "Me", avatarURL: nil, isOnline: true, role: .owner)
                    ],
                    avatarURL: nil,
                    lastMessage: nil,
                    lastMessageTimestamp: nil,
                    unreadCount: 0,
                    createdAt: Date(),
                    ownerId: "me"
                )

                groups.insert(newGroup, at: 0)
                filterGroups()

                // Reset form
                resetCreateGroupForm()
            } catch {
                createGroupError = "Failed to create group"
                isCreatingGroup = false
            }
        }
    }

    func resetCreateGroupForm() {
        newGroupTitle = ""
        selectedMembers.removeAll()
        createGroupError = nil
        isCreatingGroup = false
    }

    // MARK: - Actions - Group Chat

    func loadGroupMessages(_ group: Group) {
        currentGroup = group
        isLoading = true
        error = nil

        Task { @MainActor in
            do {
                try await Task.sleep(for: .milliseconds(500))

                // Placeholder messages
                let now = Date()
                messages = [
                    ChatMessage(
                        id: "g1",
                        senderId: group.members.first?.id ?? "",
                        content: "Hey everyone!",
                        timestamp: now.addingTimeInterval(-3600),
                        status: .read,
                        isFromMe: false
                    ),
                    ChatMessage(
                        id: "g2",
                        senderId: "me",
                        content: "Hi! What's up?",
                        timestamp: now.addingTimeInterval(-3500),
                        status: .read,
                        isFromMe: true
                    ),
                    ChatMessage(
                        id: "g3",
                        senderId: group.members.dropFirst().first?.id ?? "",
                        content: "Planning the weekend!",
                        timestamp: now.addingTimeInterval(-3400),
                        status: .read,
                        isFromMe: false
                    )
                ]

                isLoading = false
            } catch {
                self.error = "Failed to load messages"
                isLoading = false
            }
        }
    }

    func sendGroupMessage() {
        guard canSend, let group = currentGroup else { return }

        let text = messageText.trimmingCharacters(in: .whitespacesAndNewlines)
        messageText = ""

        let newMessage = ChatMessage(
            id: UUID().uuidString,
            senderId: "me",
            content: text,
            timestamp: Date(),
            status: .sending,
            isFromMe: true
        )

        messages.append(newMessage)
        isSending = true

        Task { @MainActor in
            do {
                try await Task.sleep(for: .milliseconds(500))

                if let index = messages.firstIndex(where: { $0.id == newMessage.id }) {
                    messages[index].status = .sent
                }

                // Update last message in group
                if let groupIndex = groups.firstIndex(where: { $0.id == group.id }) {
                    groups[groupIndex].lastMessage = text
                    groups[groupIndex].lastMessageTimestamp = Date()
                }

                isSending = false
            } catch {
                if let index = messages.firstIndex(where: { $0.id == newMessage.id }) {
                    messages[index].status = .failed
                }
                isSending = false
            }
        }
    }

    // MARK: - Actions - Group Management

    func updateGroupTitle(_ group: Group, newTitle: String) {
        guard let index = groups.firstIndex(where: { $0.id == group.id }) else { return }
        groups[index].title = newTitle
        if currentGroup?.id == group.id {
            currentGroup?.title = newTitle
        }
        filterGroups()
    }

    func addMember(_ contact: Contact, to group: Group) {
        guard let index = groups.firstIndex(where: { $0.id == group.id }) else { return }

        let newMember = GroupMember(
            id: contact.id,
            whisperId: contact.whisperId,
            displayName: contact.displayName,
            avatarURL: contact.avatarURL,
            isOnline: contact.isOnline,
            role: .member
        )

        groups[index].members.append(newMember)
        if currentGroup?.id == group.id {
            currentGroup?.members.append(newMember)
        }
    }

    func removeMember(_ member: GroupMember, from group: Group) {
        guard let index = groups.firstIndex(where: { $0.id == group.id }) else { return }
        groups[index].members.removeAll { $0.id == member.id }
        if currentGroup?.id == group.id {
            currentGroup?.members.removeAll { $0.id == member.id }
        }
    }

    // MARK: - Private Methods

    private func filterGroups() {
        if searchText.isEmpty {
            filteredGroups = groups.sorted { ($0.lastMessageTimestamp ?? .distantPast) > ($1.lastMessageTimestamp ?? .distantPast) }
        } else {
            filteredGroups = groups
                .filter { group in
                    group.title.localizedCaseInsensitiveContains(searchText) ||
                    group.members.contains { $0.displayName.localizedCaseInsensitiveContains(searchText) }
                }
                .sorted { ($0.lastMessageTimestamp ?? .distantPast) > ($1.lastMessageTimestamp ?? .distantPast) }
        }
    }

    func clearError() {
        error = nil
    }

    func clearCreateGroupError() {
        createGroupError = nil
    }
}
