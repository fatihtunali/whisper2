import SwiftUI
import Observation
import CoreLocation
import Contacts

/// Model representing a group
struct GroupUI: Identifiable, Equatable, Hashable {
    let id: String
    var title: String
    var members: [GroupMemberUI]
    var avatarURL: URL?
    var lastMessage: String?
    var lastMessageTimestamp: Date?
    var unreadCount: Int
    var createdAt: Date
    var ownerId: String

    var memberCount: Int { members.count }

    static func == (lhs: GroupUI, rhs: GroupUI) -> Bool {
        lhs.id == rhs.id
    }

    func hash(into hasher: inout Hasher) {
        hasher.combine(id)
    }
}

/// Model representing a group member
struct GroupMemberUI: Identifiable, Equatable {
    let id: String
    let whisperId: String
    var displayName: String
    var avatarURL: URL?
    var isOnline: Bool
    var role: Role
    var encPublicKey: String? = nil  // X25519 public key for encryption

    enum Role: String {
        case owner
        case admin
        case member
    }

    static func == (lhs: GroupMemberUI, rhs: GroupMemberUI) -> Bool {
        lhs.id == rhs.id
    }

    /// Get member's public key as Data
    var encPublicKeyData: Data? {
        guard let keyString = encPublicKey else { return nil }
        return Data(base64Encoded: keyString)
    }
}

/// Manages groups list and individual group state - connected to real server
@Observable
final class GroupViewModel {
    // MARK: - State

    var groups: [GroupUI] = []
    var filteredGroups: [GroupUI] = []
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
    var selectedMembers: Set<ContactUI> = []
    var createGroupError: String?

    // MARK: - Dependencies

    private let keychain = KeychainService.shared
    private let connectionManager = AppConnectionManager.shared

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
            // Groups are stored locally - server notifies via group_event for updates
            // Load from local storage (UserDefaults for now)
            if let data = UserDefaults.standard.data(forKey: "whisper2.groups"),
               let decoded = try? JSONDecoder().decode([GroupStorable].self, from: data) {
                groups = decoded.map { $0.toGroupUI() }
            }
            filterGroups()
            isLoading = false
            logger.debug("Groups loaded: \(groups.count) groups", category: .messaging)
        }
    }

    /// Save groups to local storage
    private func saveGroups() {
        let storableGroups = groups.map { GroupStorable(from: $0) }
        if let data = try? JSONEncoder().encode(storableGroups) {
            UserDefaults.standard.set(data, forKey: "whisper2.groups")
        }
    }

    func refreshGroups() async {
        loadGroups()
    }

    func deleteGroupUI(_ group: Group) {
        groups.removeAll { $0.id == group.id }
        filterGroups()
    }

    func leaveGroupUI(_ group: Group) {
        // In real implementation, leave the group on server
        deleteGroupUI(group)
    }

    func leaveGroupUI(_ group: GroupUI) {
        // In real implementation, leave the group on server
        groups.removeAll { $0.id == group.id }
        filterGroups()
    }

    // MARK: - Actions - Create Group

    func createGroupUI() {
        guard canCreateGroup else { return }

        isCreatingGroup = true
        createGroupError = nil

        let title = newGroupTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        let members = Array(selectedMembers)

        // Get real WhisperId from keychain
        guard let myWhisperId = keychain.whisperId else {
            createGroupError = "Not registered - please create an account first"
            isCreatingGroup = false
            return
        }

        Task { @MainActor in
            do {
                // Send group_create to server
                let memberIds = members.map { $0.whisperId }
                let groupId = try await connectionManager.createGroup(title: title, memberIds: memberIds)

                let newGroup = GroupUI(
                    id: groupId,
                    title: title,
                    members: members.map { contact in
                        GroupMemberUI(
                            id: contact.id,
                            whisperId: contact.whisperId,
                            displayName: contact.displayName,
                            avatarURL: contact.avatarURL,
                            isOnline: contact.isOnline,
                            role: .member,
                            encPublicKey: contact.encPublicKey
                        )
                    } + [
                        GroupMemberUI(id: myWhisperId, whisperId: myWhisperId, displayName: "Me", avatarURL: nil, isOnline: true, role: .owner)
                    ],
                    avatarURL: nil,
                    lastMessage: nil,
                    lastMessageTimestamp: nil,
                    unreadCount: 0,
                    createdAt: Date(),
                    ownerId: myWhisperId
                )

                groups.insert(newGroup, at: 0)
                filterGroups()
                saveGroups()

                logger.info("Group created on server: \(title)", category: .messaging)

                // Reset form
                resetCreateGroupForm()
            } catch {
                createGroupError = "Failed to create group: \(error.localizedDescription)"
                isCreatingGroup = false
                logger.error(error, message: "Failed to create group on server", category: .messaging)
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
            // Group messages are delivered in real-time via WebSocket
            // Load from local message cache (pending messages with this groupId)
            let groupMessages = connectionManager.pendingMessages.filter { $0.groupId == group.id }
            let myWhisperId = keychain.whisperId ?? ""

            messages = groupMessages.map { msg in
                ChatMessage(
                    id: msg.messageId,
                    senderId: msg.from,
                    content: msg.decodedText ?? "[Encrypted]",
                    timestamp: msg.timestampDate,
                    status: .delivered,
                    isFromMe: msg.from == myWhisperId
                )
            }.sorted { $0.timestamp < $1.timestamp }

            isLoading = false
            logger.debug("Group messages loaded: \(messages.count) messages", category: .messaging)
        }
    }

    func loadGroupMessagesUI(_ group: GroupUI) {
        isLoading = true
        error = nil

        Task { @MainActor in
            // Group messages are delivered in real-time via WebSocket
            // Load from local message cache (pending messages with this groupId)
            let groupMessages = connectionManager.pendingMessages.filter { $0.groupId == group.id }
            let myWhisperId = keychain.whisperId ?? ""

            messages = groupMessages.map { msg in
                ChatMessage(
                    id: msg.messageId,
                    senderId: msg.from,
                    content: msg.decodedText ?? "[Encrypted]",
                    timestamp: msg.timestampDate,
                    status: .delivered,
                    isFromMe: msg.from == myWhisperId
                )
            }.sorted { $0.timestamp < $1.timestamp }

            isLoading = false
            logger.debug("Group messages loaded: \(messages.count) messages", category: .messaging)
        }
    }

    func sendGroupMessage() {
        guard canSend, let group = currentGroup else { return }

        let text = messageText.trimmingCharacters(in: .whitespacesAndNewlines)
        let messageId = UUID().uuidString.lowercased()
        let myWhisperId = keychain.whisperId ?? "unknown"
        messageText = ""

        let newMessage = ChatMessage(
            id: messageId,
            senderId: myWhisperId,
            content: text,
            timestamp: Date(),
            status: .sending,
            isFromMe: true
        )

        messages.append(newMessage)
        isSending = true

        Task { @MainActor in
            do {
                // Get corresponding GroupUI for members list
                guard let groupUI = groups.first(where: { $0.id == group.id }) else {
                    throw NSError(domain: "GroupViewModel", code: 1, userInfo: [NSLocalizedDescriptionKey: "Group not found"])
                }

                // Send to server - one message per member, encrypted for each
                let members = groupUI.members.map { member in
                    (whisperId: member.whisperId, publicKey: member.encPublicKeyData)
                }

                try await connectionManager.sendGroupMessage(
                    groupId: group.id,
                    text: text,
                    members: members
                )

                if let index = messages.firstIndex(where: { $0.id == messageId }) {
                    messages[index].status = .sent
                }

                // Update last message in group
                if let groupIndex = groups.firstIndex(where: { $0.id == group.id }) {
                    groups[groupIndex].lastMessage = text
                    groups[groupIndex].lastMessageTimestamp = Date()
                    saveGroups()
                }

                isSending = false
                logger.info("Group message sent to server", category: .messaging)
            } catch {
                if let index = messages.firstIndex(where: { $0.id == messageId }) {
                    messages[index].status = .failed
                }
                self.error = "Failed to send: \(error.localizedDescription)"
                isSending = false
                logger.error(error, message: "Failed to send group message", category: .messaging)
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

    func updateGroupTitleUI(_ group: GroupUI, newTitle: String) {
        guard let index = groups.firstIndex(where: { $0.id == group.id }) else { return }
        groups[index].title = newTitle
        filterGroups()
    }

    func addMember(_ contact: ContactUI, to group: Group) {
        guard let index = groups.firstIndex(where: { $0.id == group.id }) else { return }

        let newMember = GroupMemberUI(
            id: contact.id,
            whisperId: contact.whisperId,
            displayName: contact.displayName,
            avatarURL: contact.avatarURL,
            isOnline: contact.isOnline,
            role: .member,
            encPublicKey: contact.encPublicKey
        )

        groups[index].members.append(newMember)
        if currentGroup?.groupId == group.groupId {
            var updatedGroup = currentGroup
            updatedGroup?.members.append(GroupMember(whisperId: WhisperID(trusted: contact.whisperId), role: .member))
            currentGroup = updatedGroup
        }
    }

    func addMemberUI(_ contact: ContactUI, to group: GroupUI) {
        guard let index = groups.firstIndex(where: { $0.id == group.id }) else { return }

        let newMember = GroupMemberUI(
            id: contact.id,
            whisperId: contact.whisperId,
            displayName: contact.displayName,
            avatarURL: contact.avatarURL,
            isOnline: contact.isOnline,
            role: .member,
            encPublicKey: contact.encPublicKey
        )

        groups[index].members.append(newMember)
    }

    func removeMember(_ member: GroupMemberUI, from group: Group) {
        guard let index = groups.firstIndex(where: { $0.id == group.id }) else { return }
        groups[index].members.removeAll { $0.id == member.id }
        if currentGroup?.groupId == group.groupId {
            currentGroup?.members.removeAll { $0.whisperId.rawValue == member.whisperId }
        }
    }

    func removeMemberUI(_ member: GroupMemberUI, from group: GroupUI) {
        guard let index = groups.firstIndex(where: { $0.id == group.id }) else { return }
        groups[index].members.removeAll { $0.id == member.id }
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

    // MARK: - Attachment Sending (E2E Flow)

    func sendGroupImageMessage(_ image: UIImage, to group: GroupUI) {
        // E2E Flow: Encrypt locally → Upload to S3 → Send only metadata via server
        // Server never sees the actual image content

        // Resize image for reasonable file size (max 2048px)
        let resizedImage = resizeImageIfNeeded(image, maxDimension: 2048)

        // Compress with good quality
        guard let imageData = resizedImage.jpegData(compressionQuality: 0.8) else {
            error = "Failed to compress image"
            return
        }

        // Check against max attachment size
        if imageData.count > Constants.Limits.maxAttachmentSize {
            error = "Image too large. Maximum is \(Constants.Limits.maxAttachmentSize / 1_000_000)MB."
            return
        }

        sendGroupAttachmentData(imageData, contentType: "image/jpeg", displayText: "📷 Photo", to: group)
    }

    /// Send attachment to group using proper E2E flow
    private func sendGroupAttachmentData(_ data: Data, contentType: String, displayText: String, to group: GroupUI) {
        let messageId = UUID().uuidString.lowercased()
        let myWhisperId = keychain.whisperId ?? "me"

        let newMessage = ChatMessage(
            id: messageId,
            senderId: myWhisperId,
            content: displayText,
            timestamp: Date(),
            status: .sending,
            isFromMe: true
        )

        messages.append(newMessage)
        isSending = true

        Task { @MainActor in
            do {
                // Get our encryption private key
                guard let encPrivateKey = keychain.getData(forKey: Constants.StorageKey.encPrivateKey) else {
                    throw AuthError.notAuthenticated
                }

                // 1. Upload encrypted attachment to S3 (once)
                let uploadedAttachment = try await AttachmentService.shared.uploadAttachment(
                    data: data,
                    contentType: contentType
                )

                // 2. Send to each group member with their own attachment pointer
                for member in group.members where member.whisperId != myWhisperId {
                    guard let memberPubKey = member.encPublicKeyData else {
                        logger.warning("Missing public key for member \(member.whisperId)", category: .messaging)
                        continue
                    }

                    // Create attachment pointer for this specific member
                    let attachmentPointer = try uploadedAttachment.pointer(
                        forRecipient: memberPubKey,
                        senderPrivateKey: encPrivateKey
                    )

                    // Send message with attachment metadata
                    try await connectionManager.sendMessageWithAttachment(
                        to: member.whisperId,
                        text: displayText,
                        attachment: attachmentPointer,
                        recipientPublicKey: memberPubKey
                    )
                }

                if let index = messages.firstIndex(where: { $0.id == messageId }) {
                    messages[index].status = .sent
                }

                // Update last message in group
                if let groupIndex = groups.firstIndex(where: { $0.id == group.id }) {
                    groups[groupIndex].lastMessage = displayText
                    groups[groupIndex].lastMessageTimestamp = Date()
                }

                isSending = false
                logger.info("Attachment sent to group \(group.title) (E2E via S3)", category: .messaging)
            } catch {
                if let index = messages.firstIndex(where: { $0.id == messageId }) {
                    messages[index].status = .failed
                }
                self.error = "Failed to send: \(error.localizedDescription)"
                isSending = false
                logger.error(error, message: "Failed to send group attachment", category: .messaging)
            }
        }
    }

    func sendGroupDocumentMessage(_ url: URL, to group: GroupUI) {
        let filename = url.lastPathComponent
        let displayText = "📄 \(filename)"

        // Read document data
        guard let data = try? Data(contentsOf: url) else {
            error = "Failed to read document"
            return
        }

        // Check size limit
        if data.count > Constants.Limits.maxAttachmentSize {
            error = "Document too large. Maximum is \(Constants.Limits.maxAttachmentSize / 1_000_000)MB."
            return
        }

        // Determine content type
        let contentType: String
        switch url.pathExtension.lowercased() {
        case "pdf": contentType = "application/pdf"
        default: contentType = "application/octet-stream"
        }

        // Use the proper E2E attachment flow
        sendGroupAttachmentData(data, contentType: contentType, displayText: displayText, to: group)
    }

    func sendGroupLocationMessage(_ location: CLLocationCoordinate2D, to group: GroupUI) {
        let messageId = UUID().uuidString.lowercased()
        let myWhisperId = keychain.whisperId ?? "me"
        let locationText = "📍 Location: \(location.latitude), \(location.longitude)"

        let newMessage = ChatMessage(
            id: messageId,
            senderId: myWhisperId,
            content: locationText,
            timestamp: Date(),
            status: .sending,
            isFromMe: true
        )

        messages.append(newMessage)
        isSending = true

        Task { @MainActor in
            do {
                // Send to each group member
                for member in group.members where member.whisperId != myWhisperId {
                    try await connectionManager.sendMessage(
                        to: member.whisperId,
                        text: locationText,
                        recipientPublicKey: member.encPublicKeyData
                    )
                }

                if let index = messages.firstIndex(where: { $0.id == messageId }) {
                    messages[index].status = .sent
                }

                // Update last message in group
                if let groupIndex = groups.firstIndex(where: { $0.id == group.id }) {
                    groups[groupIndex].lastMessage = locationText
                    groups[groupIndex].lastMessageTimestamp = Date()
                }

                isSending = false
                logger.info("Location sent to group \(group.title)", category: .messaging)
            } catch {
                if let index = messages.firstIndex(where: { $0.id == messageId }) {
                    messages[index].status = .failed
                }
                self.error = "Failed to send location: \(error.localizedDescription)"
                isSending = false
            }
        }
    }

    func sendGroupContactMessage(_ contact: CNContact, to group: GroupUI) {
        let messageId = UUID().uuidString.lowercased()
        let myWhisperId = keychain.whisperId ?? "me"
        let name = "\(contact.givenName) \(contact.familyName)".trimmingCharacters(in: .whitespaces)
        let phone = contact.phoneNumbers.first?.value.stringValue ?? ""
        let contactText = "👤 Contact: \(name)\n📱 \(phone)"

        let newMessage = ChatMessage(
            id: messageId,
            senderId: myWhisperId,
            content: contactText,
            timestamp: Date(),
            status: .sending,
            isFromMe: true
        )

        messages.append(newMessage)
        isSending = true

        Task { @MainActor in
            do {
                // Send to each group member
                for member in group.members where member.whisperId != myWhisperId {
                    try await connectionManager.sendMessage(
                        to: member.whisperId,
                        text: contactText,
                        recipientPublicKey: member.encPublicKeyData
                    )
                }

                if let index = messages.firstIndex(where: { $0.id == messageId }) {
                    messages[index].status = .sent
                }

                // Update last message in group
                if let groupIndex = groups.firstIndex(where: { $0.id == group.id }) {
                    groups[groupIndex].lastMessage = contactText
                    groups[groupIndex].lastMessageTimestamp = Date()
                }

                isSending = false
                logger.info("Contact sent to group \(group.title)", category: .messaging)
            } catch {
                if let index = messages.firstIndex(where: { $0.id == messageId }) {
                    messages[index].status = .failed
                }
                self.error = "Failed to send contact: \(error.localizedDescription)"
                isSending = false
            }
        }
    }

    // MARK: - Image Helpers

    private func resizeImageIfNeeded(_ image: UIImage, maxDimension: CGFloat) -> UIImage {
        let size = image.size
        let maxSize = max(size.width, size.height)

        if maxSize <= maxDimension {
            return image
        }

        let scale = maxDimension / maxSize
        let newSize = CGSize(width: size.width * scale, height: size.height * scale)

        let renderer = UIGraphicsImageRenderer(size: newSize)
        return renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: newSize))
        }
    }
}

// MARK: - Group Storage Model

/// Codable model for persisting groups
private struct GroupStorable: Codable {
    let id: String
    var title: String
    var members: [GroupMemberStorable]
    var avatarURL: String?
    var lastMessage: String?
    var lastMessageTimestamp: Date?
    var unreadCount: Int
    var createdAt: Date
    var ownerId: String

    init(from group: GroupUI) {
        self.id = group.id
        self.title = group.title
        self.members = group.members.map { GroupMemberStorable(from: $0) }
        self.avatarURL = group.avatarURL?.absoluteString
        self.lastMessage = group.lastMessage
        self.lastMessageTimestamp = group.lastMessageTimestamp
        self.unreadCount = group.unreadCount
        self.createdAt = group.createdAt
        self.ownerId = group.ownerId
    }

    func toGroupUI() -> GroupUI {
        GroupUI(
            id: id,
            title: title,
            members: members.map { $0.toGroupMemberUI() },
            avatarURL: avatarURL.flatMap { URL(string: $0) },
            lastMessage: lastMessage,
            lastMessageTimestamp: lastMessageTimestamp,
            unreadCount: unreadCount,
            createdAt: createdAt,
            ownerId: ownerId
        )
    }
}

private struct GroupMemberStorable: Codable {
    let id: String
    let whisperId: String
    var displayName: String
    var avatarURL: String?
    var isOnline: Bool
    var role: String
    var encPublicKey: String?

    init(from member: GroupMemberUI) {
        self.id = member.id
        self.whisperId = member.whisperId
        self.displayName = member.displayName
        self.avatarURL = member.avatarURL?.absoluteString
        self.isOnline = member.isOnline
        self.role = member.role.rawValue
        self.encPublicKey = member.encPublicKey
    }

    func toGroupMemberUI() -> GroupMemberUI {
        GroupMemberUI(
            id: id,
            whisperId: whisperId,
            displayName: displayName,
            avatarURL: avatarURL.flatMap { URL(string: $0) },
            isOnline: isOnline,
            role: GroupMemberUI.Role(rawValue: role) ?? .member,
            encPublicKey: encPublicKey
        )
    }
}
