import Foundation
import Combine

/// Service for managing group conversations
final class GroupService: ObservableObject {
    static let shared = GroupService()

    @Published private(set) var groups: [String: ChatGroup] = [:]  // groupId -> ChatGroup
    @Published private(set) var groupMessages: [String: [Message]] = [:]  // groupId -> messages
    @Published private(set) var groupInvites: [String: GroupInvite] = [:]  // groupId -> invite

    private let ws = WebSocketService.shared
    private let auth = AuthService.shared
    private let contacts = ContactsService.shared
    private let crypto = CryptoService.shared
    private let keychain = KeychainService.shared
    private var cancellables = Set<AnyCancellable>()

    private let groupsStorageKey = "whisper2.groups.data"
    private let groupMessagesStorageKey = "whisper2.group.messages.data"
    private let groupInvitesStorageKey = "whisper2.group.invites.data"

    private init() {
        loadFromStorage()
        setupMessageHandler()
    }

    // MARK: - Create Group

    func createGroup(title: String, memberIds: [String]) async throws -> ChatGroup {
        guard let user = auth.currentUser,
              let sessionToken = user.sessionToken else {
            throw NetworkError.connectionFailed
        }

        // Validate all members have public keys
        for memberId in memberIds {
            guard contacts.hasValidPublicKey(for: memberId) else {
                throw CryptoError.invalidPublicKey
            }
        }

        let payload = GroupCreatePayload(
            sessionToken: sessionToken,
            title: title,
            memberIds: memberIds
        )

        let frame = WsFrame(type: Constants.MessageType.groupCreate, payload: payload)
        try await ws.send(frame)

        // Group will be added when we receive the ack
        // Return a temporary group for now
        let tempGroup = ChatGroup(
            id: UUID().uuidString,
            title: title,
            memberIds: memberIds + [user.whisperId],
            creatorId: user.whisperId
        )

        return tempGroup
    }

    // MARK: - Update Group

    func addMembers(_ memberIds: [String], to groupId: String) async throws {
        guard let sessionToken = auth.currentUser?.sessionToken else {
            throw NetworkError.connectionFailed
        }

        // Validate all new members have public keys
        for memberId in memberIds {
            guard contacts.hasValidPublicKey(for: memberId) else {
                throw CryptoError.invalidPublicKey
            }
        }

        let payload = GroupUpdatePayload(
            sessionToken: sessionToken,
            groupId: groupId,
            addMembers: memberIds
        )

        let frame = WsFrame(type: Constants.MessageType.groupUpdate, payload: payload)
        try await ws.send(frame)
    }

    func removeMembers(_ memberIds: [String], from groupId: String) async throws {
        guard let sessionToken = auth.currentUser?.sessionToken else {
            throw NetworkError.connectionFailed
        }

        let payload = GroupUpdatePayload(
            sessionToken: sessionToken,
            groupId: groupId,
            removeMembers: memberIds
        )

        let frame = WsFrame(type: Constants.MessageType.groupUpdate, payload: payload)
        try await ws.send(frame)
    }

    func updateGroupTitle(_ title: String, groupId: String) async throws {
        guard let sessionToken = auth.currentUser?.sessionToken else {
            throw NetworkError.connectionFailed
        }

        let payload = GroupUpdatePayload(
            sessionToken: sessionToken,
            groupId: groupId,
            title: title
        )

        let frame = WsFrame(type: Constants.MessageType.groupUpdate, payload: payload)
        try await ws.send(frame)
    }

    func leaveGroup(_ groupId: String) async throws {
        guard let user = auth.currentUser,
              let sessionToken = user.sessionToken else {
            throw NetworkError.connectionFailed
        }

        let payload = GroupUpdatePayload(
            sessionToken: sessionToken,
            groupId: groupId,
            removeMembers: [user.whisperId]
        )

        let frame = WsFrame(type: Constants.MessageType.groupUpdate, payload: payload)
        try await ws.send(frame)

        // Remove from local storage
        await MainActor.run {
            groups.removeValue(forKey: groupId)
            groupMessages.removeValue(forKey: groupId)
            saveToStorage()
        }
    }

    // MARK: - Group Invites

    /// Accept a group invite
    func acceptInvite(_ groupId: String) async throws {
        guard let invite = groupInvites[groupId],
              let sessionToken = auth.currentUser?.sessionToken else {
            throw NetworkError.connectionFailed
        }

        // Send accept to server
        let payload = GroupInviteResponsePayload(
            sessionToken: sessionToken,
            groupId: groupId,
            accept: true
        )

        let frame = WsFrame(type: Constants.MessageType.groupInviteResponse, payload: payload)
        try await ws.send(frame)

        // Add group locally
        await MainActor.run {
            let group = ChatGroup(
                id: invite.groupId,
                title: invite.title,
                memberIds: invite.memberIds,
                creatorId: invite.inviterId,
                createdAt: invite.createdAt
            )
            self.groups[groupId] = group
            self.groupInvites.removeValue(forKey: groupId)
            self.saveToStorage()
        }
    }

    /// Decline a group invite
    func declineInvite(_ groupId: String) async throws {
        guard let sessionToken = auth.currentUser?.sessionToken else {
            throw NetworkError.connectionFailed
        }

        // Send decline to server
        let payload = GroupInviteResponsePayload(
            sessionToken: sessionToken,
            groupId: groupId,
            accept: false
        )

        let frame = WsFrame(type: Constants.MessageType.groupInviteResponse, payload: payload)
        try await ws.send(frame)

        // Remove invite locally
        await MainActor.run {
            self.groupInvites.removeValue(forKey: groupId)
            self.saveToStorage()
        }
    }

    /// Get all pending group invites
    func getPendingInvites() -> [GroupInvite] {
        return Array(groupInvites.values).sorted { $0.receivedAt > $1.receivedAt }
    }

    // MARK: - Send Group Message

    func sendMessage(to groupId: String, content: String, contentType: String = "text", attachment: AttachmentPointer? = nil) async throws {
        guard let user = auth.currentUser,
              let sessionToken = user.sessionToken,
              let group = groups[groupId] else {
            throw NetworkError.connectionFailed
        }

        let messageId = UUID().uuidString.lowercased()
        let timestamp = Int64(Date().timeIntervalSince1970 * 1000)

        // Build recipient envelopes for all members (pairwise encryption)
        var recipientEnvelopes: [RecipientEnvelope] = []

        for memberId in group.memberIds {
            // Skip self
            guard memberId != user.whisperId else { continue }

            // Get member's public key
            guard let memberPublicKey = contacts.getPublicKey(for: memberId) else {
                print("[GroupService] No public key for member \(memberId), skipping")
                continue
            }

            do {
                // Encrypt for this specific member
                let (ciphertext, nonce) = try crypto.encryptMessage(
                    content,
                    recipientPublicKey: memberPublicKey,
                    senderPrivateKey: user.encPrivateKey
                )

                // Sign with toOrGroupId = groupId (NOT memberId)
                // This is the canonical format the server expects
                let signature = try crypto.signMessage(
                    messageType: Constants.MessageType.groupSendMessage,
                    messageId: messageId,
                    from: user.whisperId,
                    to: groupId,  // IMPORTANT: Use groupId, not memberId
                    timestamp: timestamp,
                    nonce: nonce,
                    ciphertext: ciphertext,
                    privateKey: user.signPrivateKey
                )

                let envelope = RecipientEnvelope(
                    to: memberId,
                    nonce: nonce.base64EncodedString(),
                    ciphertext: ciphertext.base64EncodedString(),
                    sig: signature.base64EncodedString()
                )
                recipientEnvelopes.append(envelope)
            } catch {
                print("[GroupService] Failed to encrypt for \(memberId): \(error)")
            }
        }

        // If no recipients could be encrypted for, fail
        guard !recipientEnvelopes.isEmpty else {
            throw CryptoError.encryptionFailed
        }

        // Send ONE frame with all recipients bundled
        let payload = GroupSendMessagePayload(
            sessionToken: sessionToken,
            groupId: groupId,
            messageId: messageId,
            from: user.whisperId,
            msgType: contentType,
            timestamp: timestamp,
            recipients: recipientEnvelopes,
            attachment: attachment
        )

        let frame = WsFrame(type: Constants.MessageType.groupSendMessage, payload: payload)
        try await ws.send(frame)

        print("[GroupService] Sent group message to \(recipientEnvelopes.count) recipients")

        // Add to local messages
        let message = Message(
            id: messageId,
            conversationId: groupId,
            from: user.whisperId,
            to: groupId,
            content: content,
            contentType: contentType,
            timestamp: Date(timeIntervalSince1970: Double(timestamp) / 1000),
            status: .sent,
            direction: .outgoing,
            attachment: attachment
        )

        await MainActor.run {
            if groupMessages[groupId] == nil {
                groupMessages[groupId] = []
            }
            groupMessages[groupId]?.append(message)

            // Update group's last message
            if var group = groups[groupId] {
                group.lastMessage = content
                group.lastMessageTime = message.timestamp
                group.updatedAt = Date()
                groups[groupId] = group
            }

            saveToStorage()
        }
    }

    // MARK: - Message Handling

    private func setupMessageHandler() {
        ws.messagePublisher
            .sink { [weak self] data in
                self?.handleMessage(data)
            }
            .store(in: &cancellables)
    }

    private func handleMessage(_ data: Data) {
        guard let raw = try? JSONDecoder().decode(RawWsFrame.self, from: data) else { return }

        switch raw.type {
        case Constants.MessageType.groupEvent:
            handleGroupEvent(data)
        default:
            break
        }
    }

    private func handleGroupEvent(_ data: Data) {
        guard let frame = try? JSONDecoder().decode(WsFrame<GroupEventPayload>.self, from: data) else {
            print("[GroupService] Failed to decode group event")
            return
        }

        let payload = frame.payload
        let serverGroup = payload.group
        let eventType = GroupEventType(rawValue: payload.event)

        print("[GroupService] Received group event: \(payload.event) for group \(serverGroup.groupId)")

        DispatchQueue.main.async {
            // Convert server group to local ChatGroup
            let memberIds = serverGroup.members
                .filter { $0.removedAt == nil }
                .map { $0.whisperId }

            switch eventType {
            case .created:
                // New group created
                let isCreator = serverGroup.ownerId == self.auth.currentUser?.whisperId

                if isCreator {
                    // We created the group, add it directly
                    let group = ChatGroup(
                        id: serverGroup.groupId,
                        title: serverGroup.title,
                        memberIds: memberIds,
                        creatorId: serverGroup.ownerId,
                        createdAt: Date(timeIntervalSince1970: Double(serverGroup.createdAt) / 1000)
                    )
                    self.groups[serverGroup.groupId] = group
                    print("[GroupService] Created group locally: \(serverGroup.groupId)")
                } else {
                    // We were invited - add group directly (no invite accept needed per server design)
                    let group = ChatGroup(
                        id: serverGroup.groupId,
                        title: serverGroup.title,
                        memberIds: memberIds,
                        creatorId: serverGroup.ownerId,
                        createdAt: Date(timeIntervalSince1970: Double(serverGroup.createdAt) / 1000)
                    )
                    self.groups[serverGroup.groupId] = group
                    print("[GroupService] Added to group: \(serverGroup.groupId)")
                }

            case .updated:
                // Group was updated - sync full state from server
                if var group = self.groups[serverGroup.groupId] {
                    group.title = serverGroup.title
                    group.memberIds = memberIds
                    group.updatedAt = Date(timeIntervalSince1970: Double(serverGroup.updatedAt) / 1000)
                    self.groups[serverGroup.groupId] = group
                    print("[GroupService] Updated group: \(serverGroup.groupId)")

                    // Check if we were removed
                    if let myId = self.auth.currentUser?.whisperId,
                       !memberIds.contains(myId) {
                        self.groups.removeValue(forKey: serverGroup.groupId)
                        self.groupMessages.removeValue(forKey: serverGroup.groupId)
                        print("[GroupService] Removed from group: \(serverGroup.groupId)")
                    }
                } else {
                    // We don't have this group yet, add it
                    let group = ChatGroup(
                        id: serverGroup.groupId,
                        title: serverGroup.title,
                        memberIds: memberIds,
                        creatorId: serverGroup.ownerId,
                        createdAt: Date(timeIntervalSince1970: Double(serverGroup.createdAt) / 1000)
                    )
                    self.groups[serverGroup.groupId] = group
                    print("[GroupService] Added group from update event: \(serverGroup.groupId)")
                }

            case .memberAdded, .memberRemoved:
                // These come with full group state, so just sync
                if var group = self.groups[serverGroup.groupId] {
                    group.title = serverGroup.title
                    group.memberIds = memberIds
                    group.updatedAt = Date(timeIntervalSince1970: Double(serverGroup.updatedAt) / 1000)
                    self.groups[serverGroup.groupId] = group

                    // Check if we were removed
                    if let myId = self.auth.currentUser?.whisperId,
                       !memberIds.contains(myId) {
                        self.groups.removeValue(forKey: serverGroup.groupId)
                        self.groupMessages.removeValue(forKey: serverGroup.groupId)
                        print("[GroupService] Removed from group: \(serverGroup.groupId)")
                    }
                }

            case .none:
                print("[GroupService] Unknown group event type: \(payload.event)")
            }

            self.saveToStorage()
        }
    }

    /// Handle incoming group message (called when group message is received)
    func handleIncomingGroupMessage(_ payload: GroupMessagePayload) {
        guard let user = auth.currentUser,
              let senderPublicKey = contacts.getPublicKey(for: payload.from) else {
            print("Cannot decrypt group message - no public key for sender \(payload.from)")
            return
        }

        // Decrypt the message
        guard let ciphertextData = Data(base64Encoded: payload.ciphertext),
              let nonceData = Data(base64Encoded: payload.nonce) else {
            print("Failed to decode group message ciphertext/nonce")
            return
        }

        do {
            let decryptedContent = try crypto.decryptMessage(
                ciphertext: ciphertextData,
                nonce: nonceData,
                senderPublicKey: senderPublicKey,
                recipientPrivateKey: user.encPrivateKey
            )

            // Create message
            let message = Message(
                id: payload.messageId,
                conversationId: payload.groupId,
                from: payload.from,
                to: payload.groupId,
                content: decryptedContent,
                contentType: payload.msgType,
                timestamp: Date(timeIntervalSince1970: Double(payload.timestamp) / 1000),
                status: .delivered,
                direction: .incoming
            )

            DispatchQueue.main.async {
                // Store message
                if self.groupMessages[payload.groupId] == nil {
                    self.groupMessages[payload.groupId] = []
                }

                // Avoid duplicates
                if !self.groupMessages[payload.groupId]!.contains(where: { $0.id == message.id }) {
                    self.groupMessages[payload.groupId]?.append(message)
                }

                // Update group
                if var group = self.groups[payload.groupId] {
                    group.lastMessage = decryptedContent
                    group.lastMessageTime = message.timestamp
                    group.unreadCount += 1
                    group.updatedAt = Date()
                    self.groups[payload.groupId] = group
                }

                self.saveToStorage()
            }
        } catch {
            print("Failed to decrypt group message: \(error)")
        }
    }

    /// Delete a message from a group
    func deleteMessage(messageId: String, groupId: String) {
        if var msgs = groupMessages[groupId] {
            msgs.removeAll { $0.id == messageId }
            groupMessages[groupId] = msgs
            saveMessages()
        }
    }

    // MARK: - Helpers

    func getGroup(_ groupId: String) -> ChatGroup? {
        return groups[groupId]
    }

    func getAllGroups() -> [ChatGroup] {
        return Array(groups.values).sorted { ($0.lastMessageTime ?? $0.createdAt) > ($1.lastMessageTime ?? $1.createdAt) }
    }

    func getMessages(for groupId: String) -> [Message] {
        return groupMessages[groupId] ?? []
    }

    func markAsRead(groupId: String) {
        if var group = groups[groupId] {
            group.unreadCount = 0
            groups[groupId] = group
            saveToStorage()
        }
    }

    // MARK: - Persistence

    private func loadFromStorage() {
        // Load groups
        if let data = keychain.getData(forKey: groupsStorageKey),
           let decoded = try? JSONDecoder().decode([ChatGroup].self, from: data) {
            groups = Dictionary(uniqueKeysWithValues: decoded.map { ($0.id, $0) })
        }

        // Load group messages
        if let data = keychain.getData(forKey: groupMessagesStorageKey),
           let decoded = try? JSONDecoder().decode([String: [Message]].self, from: data) {
            groupMessages = decoded
        }

        // Load group invites
        if let data = keychain.getData(forKey: groupInvitesStorageKey),
           let decoded = try? JSONDecoder().decode([GroupInvite].self, from: data) {
            groupInvites = Dictionary(uniqueKeysWithValues: decoded.map { ($0.groupId, $0) })
        }
    }

    private func saveToStorage() {
        // Save groups
        let groupsArray = Array(groups.values)
        if let data = try? JSONEncoder().encode(groupsArray) {
            keychain.setData(data, forKey: groupsStorageKey)
        }

        // Save group messages
        if let data = try? JSONEncoder().encode(groupMessages) {
            keychain.setData(data, forKey: groupMessagesStorageKey)
        }

        // Save group invites
        let invitesArray = Array(groupInvites.values)
        if let data = try? JSONEncoder().encode(invitesArray) {
            keychain.setData(data, forKey: groupInvitesStorageKey)
        }
    }

    func saveMessages() {
        if let data = try? JSONEncoder().encode(groupMessages) {
            keychain.setData(data, forKey: groupMessagesStorageKey)
        }
    }

    // MARK: - Clear All Data

    /// Clear all group data (for wipe data feature)
    func clearAllData() {
        groups.removeAll()
        groupMessages.removeAll()
        groupInvites.removeAll()
        keychain.delete(key: groupsStorageKey)
        keychain.delete(key: groupMessagesStorageKey)
        keychain.delete(key: groupInvitesStorageKey)
    }
}

