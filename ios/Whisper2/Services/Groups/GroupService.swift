import Foundation
import Combine

/// Whisper2 Group Service
/// Handles group creation, updates, and group messaging.
/// Messages are end-to-end encrypted with per-recipient envelopes.

// MARK: - Group Models

/// Group role
enum GroupRole: String, Codable {
    case owner
    case admin
    case member
}

/// Group member
struct GroupMember: Codable, Equatable {
    let whisperId: String
    var role: GroupRole
    let joinedAt: Int64
    var removedAt: Int64?

    var isActive: Bool {
        removedAt == nil
    }
}

/// Group model
struct Group: Codable, Identifiable, Equatable {
    let id: String          // groupId
    var title: String
    let ownerId: String
    let createdAt: Int64
    var updatedAt: Int64
    var members: [GroupMember]

    /// Group ID (alias for SwiftUI)
    var groupId: String { id }

    /// Get active members only
    var activeMembers: [GroupMember] {
        members.filter { $0.isActive }
    }

    /// Check if a user is an active member
    func isMember(_ whisperId: String) -> Bool {
        activeMembers.contains { $0.whisperId == whisperId }
    }

    /// Check if a user can manage the group
    func canManage(_ whisperId: String) -> Bool {
        guard let member = activeMembers.first(where: { $0.whisperId == whisperId }) else {
            return false
        }
        return member.role == .owner || member.role == .admin
    }
}

/// Group event types
enum GroupEventType: String, Codable {
    case created
    case updated
    case memberAdded = "member_added"
    case memberRemoved = "member_removed"
}

/// Group event from server
struct GroupEvent: Codable {
    let event: String
    let group: GroupData
    var affectedMembers: [String]?

    struct GroupData: Codable {
        let groupId: String
        let title: String
        let ownerId: String
        let createdAt: Int64
        let updatedAt: Int64
        let members: [GroupMemberData]
    }

    struct GroupMemberData: Codable {
        let whisperId: String
        let role: String
        let joinedAt: Int64
        var removedAt: Int64?
    }

    /// Convert to Group model
    func toGroup() -> Group {
        Group(
            id: group.groupId,
            title: group.title,
            ownerId: group.ownerId,
            createdAt: group.createdAt,
            updatedAt: group.updatedAt,
            members: group.members.map { member in
                GroupMember(
                    whisperId: member.whisperId,
                    role: GroupRole(rawValue: member.role) ?? .member,
                    joinedAt: member.joinedAt,
                    removedAt: member.removedAt
                )
            }
        )
    }
}

/// Recipient envelope for group messages
struct RecipientEnvelope: Codable {
    let to: String              // Recipient whisperId
    let nonce: String           // base64
    let ciphertext: String      // base64
    let sig: String             // base64
}

/// Group changes for update request
struct GroupChanges {
    var title: String?
    var addMembers: [String]?
    var removeMembers: [String]?
    var roleChanges: [(whisperId: String, role: GroupRole)]?
}

// MARK: - Group Service

/// Service for managing groups and group messaging.
final class GroupService {

    static let shared = GroupService()

    private let keychain = KeychainService.shared
    private var groups: [String: Group] = [:]
    private var groupsLock = NSLock()

    /// Publisher for group events
    let groupEventPublisher = PassthroughSubject<(GroupEventType, Group), Never>()

    private init() {}

    // MARK: - Group Management

    /// Create a new group.
    /// - Parameters:
    ///   - title: Group title (1-64 chars)
    ///   - members: Member whisperIds (excluding self)
    /// - Returns: Created group
    func createGroup(title: String, members: [String]) async throws -> Group {
        logger.info("Creating group: \(title) with \(members.count) members", category: .messaging)

        guard let sessionToken = keychain.sessionToken else {
            throw AuthError.notAuthenticated
        }

        // Build payload
        let payload = GroupCreatePayload(
            sessionToken: sessionToken,
            title: title,
            memberIds: members
        )

        // Send via WebSocket
        let response = try await sendWebSocketRequest(
            type: Constants.MessageType.groupCreate,
            payload: payload
        )

        // Parse group event response
        guard let eventData = response["group"] as? [String: Any],
              let jsonData = try? JSONSerialization.data(withJSONObject: response),
              let event = try? JSONDecoder().decode(GroupEvent.self, from: jsonData) else {
            throw GroupError.groupNotFound
        }

        let group = event.toGroup()

        // Store locally
        storeGroup(group)

        // Publish event
        groupEventPublisher.send((.created, group))

        logger.info("Group created: \(group.groupId)", category: .messaging)

        return group
    }

    /// Update a group.
    /// - Parameters:
    ///   - groupId: Group to update
    ///   - changes: Changes to apply
    func updateGroup(groupId: String, changes: GroupChanges) async throws {
        logger.info("Updating group: \(groupId)", category: .messaging)

        guard let sessionToken = keychain.sessionToken else {
            throw AuthError.notAuthenticated
        }

        // Build payload
        var payload = GroupUpdatePayload(
            sessionToken: sessionToken,
            groupId: groupId,
            addMembers: changes.addMembers,
            removeMembers: changes.removeMembers,
            title: changes.title
        )

        // Note: roleChanges would need to be added to GroupUpdatePayload if needed

        // Send via WebSocket
        _ = try await sendWebSocketRequest(
            type: Constants.MessageType.groupUpdate,
            payload: payload
        )

        // Group update will come back via group_event
        logger.info("Group update sent: \(groupId)", category: .messaging)
    }

    /// Send a message to a group.
    /// Encrypts message for each member individually.
    /// - Parameters:
    ///   - groupId: Target group
    ///   - text: Message text
    ///   - attachment: Optional attachment
    func sendGroupMessage(
        groupId: String,
        text: String,
        attachment: AttachmentPointer? = nil
    ) async throws {
        logger.info("Sending group message to: \(groupId)", category: .messaging)

        guard let sessionToken = keychain.sessionToken,
              let myWhisperId = keychain.whisperId else {
            throw AuthError.notAuthenticated
        }

        guard let encPrivateKey = keychain.encPrivateKey,
              let signPrivateKey = keychain.signPrivateKey else {
            throw CryptoError.invalidPrivateKey
        }

        // Get group
        guard let group = getGroup(groupId) else {
            throw GroupError.groupNotFound
        }

        // Verify we're a member
        guard group.isMember(myWhisperId) else {
            throw GroupError.notGroupMember
        }

        let messageId = UUID().uuidString
        let timestamp = Int64(Date().timeIntervalSince1970 * 1000)

        // Create envelope for each active member (except self)
        var envelopes: [RecipientEnvelope] = []

        for member in group.activeMembers {
            if member.whisperId == myWhisperId {
                continue
            }

            // Get recipient's public key
            guard let recipientKeys = try await lookupUserKeys(member.whisperId) else {
                logger.warning("Could not find keys for member: \(member.whisperId)", category: .messaging)
                continue
            }

            let recipientEncPublicKey = try recipientKeys.encPublicKey.base64Decoded()

            // Encrypt message for this recipient
            let envelope = try createEnvelope(
                text: text,
                to: member.whisperId,
                recipientPublicKey: recipientEncPublicKey,
                senderPrivateKey: encPrivateKey,
                signPrivateKey: signPrivateKey,
                messageId: messageId,
                groupId: groupId,
                timestamp: timestamp
            )

            envelopes.append(envelope)
        }

        // Build payload with all envelopes
        let payload = GroupSendMessageRequest(
            protocolVersion: Protocol.version,
            cryptoVersion: Protocol.cryptoVersion,
            sessionToken: sessionToken,
            groupId: groupId,
            messageId: messageId,
            from: myWhisperId,
            msgType: "text",
            timestamp: timestamp,
            recipients: envelopes,
            attachment: attachment
        )

        // Send via WebSocket
        _ = try await sendWebSocketRequest(
            type: Constants.MessageType.groupSendMessage,
            payload: payload
        )

        logger.info("Group message sent: \(messageId) to \(envelopes.count) members", category: .messaging)
    }

    // MARK: - Event Handling

    /// Handle incoming group_event from WebSocket.
    /// - Parameter eventData: Raw event data
    func handleGroupEvent(_ eventData: [String: Any]) {
        guard let jsonData = try? JSONSerialization.data(withJSONObject: eventData),
              let event = try? JSONDecoder().decode(GroupEvent.self, from: jsonData) else {
            logger.error("Failed to parse group event", category: .messaging)
            return
        }

        let group = event.toGroup()
        let eventType = GroupEventType(rawValue: event.event) ?? .updated

        // Store/update group
        storeGroup(group)

        // Publish event
        groupEventPublisher.send((eventType, group))

        logger.info("Group event received: \(eventType.rawValue) for \(group.groupId)", category: .messaging)
    }

    // MARK: - Local Storage

    /// Get a group by ID.
    func getGroup(_ groupId: String) -> Group? {
        groupsLock.lock()
        defer { groupsLock.unlock() }
        return groups[groupId]
    }

    /// Get all groups.
    func getAllGroups() -> [Group] {
        groupsLock.lock()
        defer { groupsLock.unlock() }
        return Array(groups.values)
    }

    /// Store a group locally.
    func storeGroup(_ group: Group) {
        groupsLock.lock()
        defer { groupsLock.unlock() }
        groups[group.groupId] = group
    }

    /// Remove a group locally.
    func removeGroup(_ groupId: String) {
        groupsLock.lock()
        defer { groupsLock.unlock() }
        groups.removeValue(forKey: groupId)
    }

    /// Clear all local group data.
    func clearAll() {
        groupsLock.lock()
        defer { groupsLock.unlock() }
        groups.removeAll()
    }

    // MARK: - Private Helpers

    /// Create an envelope for a specific recipient.
    private func createEnvelope(
        text: String,
        to: String,
        recipientPublicKey: Data,
        senderPrivateKey: Data,
        signPrivateKey: Data,
        messageId: String,
        groupId: String,
        timestamp: Int64
    ) throws -> RecipientEnvelope {
        // Encrypt plaintext using box (X25519-XSalsa20-Poly1305)
        guard let plaintextData = text.data(using: .utf8) else {
            throw CryptoError.encryptionFailed
        }

        let (nonce, ciphertext) = try GroupCrypto.shared.boxSeal(
            plaintext: plaintextData,
            recipientPublicKey: recipientPublicKey,
            senderPrivateKey: senderPrivateKey
        )

        // Sign the message (canonical format)
        let signature = try GroupCrypto.shared.sign(
            privateKey: signPrivateKey,
            messageType: "group_send_message",
            messageId: messageId,
            from: keychain.whisperId!,
            toOrGroupId: groupId,
            timestamp: timestamp,
            nonce: nonce.base64,
            ciphertext: ciphertext.base64
        )

        return RecipientEnvelope(
            to: to,
            nonce: nonce.base64,
            ciphertext: ciphertext.base64,
            sig: signature.base64
        )
    }

    /// Lookup user's public keys from server.
    private func lookupUserKeys(_ whisperId: String) async throws -> UserKeysResponse? {
        guard let sessionToken = keychain.sessionToken else {
            throw AuthError.notAuthenticated
        }

        guard let url = URL(string: "\(Constants.Server.httpBaseURL)/users/\(whisperId)/keys") else {
            throw NetworkError.invalidURL
        }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = Constants.Timeout.httpRequest
        request.setValue("Bearer \(sessionToken)", forHTTPHeaderField: "Authorization")

        let (data, response) = try await URLSession.shared.data(for: request)

        guard let httpResponse = response as? HTTPURLResponse else {
            throw NetworkError.invalidResponse
        }

        switch httpResponse.statusCode {
        case 200:
            return try JSONDecoder().decode(UserKeysResponse.self, from: data)
        case 404:
            return nil
        default:
            throw NetworkError.httpError(statusCode: httpResponse.statusCode, message: nil)
        }
    }

    /// Send request via WebSocket and wait for response.
    /// This is a placeholder - actual implementation depends on WebSocket manager.
    private func sendWebSocketRequest<T: Encodable>(
        type: String,
        payload: T
    ) async throws -> [String: Any] {
        // TODO: Implement WebSocket request/response handling
        // This should:
        // 1. Generate requestId
        // 2. Send frame via WebSocket
        // 3. Wait for response with matching requestId
        // 4. Return response payload

        // Placeholder - actual implementation depends on WebSocket manager
        throw NetworkError.connectionFailed
    }
}

// MARK: - Supporting Types

/// Group send message request (with multiple envelopes)
private struct GroupSendMessageRequest: Codable {
    let protocolVersion: Int
    let cryptoVersion: Int
    let sessionToken: String
    let groupId: String
    let messageId: String
    let from: String
    let msgType: String
    let timestamp: Int64
    let recipients: [RecipientEnvelope]
    var replyTo: String?
    var reactions: [String: [String]]?
    var attachment: AttachmentPointer?
}

/// User keys response from server
private struct UserKeysResponse: Codable {
    let whisperId: String
    let encPublicKey: String
    let signPublicKey: String
    let status: String
}

// MARK: - Group Crypto

/// Crypto operations for group messaging.
final class GroupCrypto {

    static let shared = GroupCrypto()

    private init() {}

    /// Generate random bytes.
    func randomBytes(_ count: Int) throws -> Data {
        var data = Data(count: count)
        let result = data.withUnsafeMutableBytes { ptr in
            SecRandomCopyBytes(kSecRandomDefault, count, ptr.baseAddress!)
        }
        guard result == errSecSuccess else {
            throw CryptoError.keyDerivationFailed
        }
        return data
    }

    /// Encrypt data with box (X25519-XSalsa20-Poly1305).
    func boxSeal(
        plaintext: Data,
        recipientPublicKey: Data,
        senderPrivateKey: Data
    ) throws -> (nonce: Data, ciphertext: Data) {
        guard recipientPublicKey.count == Constants.Crypto.publicKeyLength else {
            throw CryptoError.invalidPublicKey
        }
        guard senderPrivateKey.count == Constants.Crypto.secretKeyLength else {
            throw CryptoError.invalidPrivateKey
        }

        let nonce = try randomBytes(Constants.Crypto.nonceLength)

        // TODO: Implement actual NaCl box_seal
        // crypto_box_easy(c, m, mlen, n, pk, sk)

        // Placeholder - needs real crypto implementation
        throw CryptoError.encryptionFailed
    }

    /// Decrypt data with box.
    func boxOpen(
        ciphertext: Data,
        nonce: Data,
        senderPublicKey: Data,
        recipientPrivateKey: Data
    ) throws -> Data {
        guard senderPublicKey.count == Constants.Crypto.publicKeyLength else {
            throw CryptoError.invalidPublicKey
        }
        guard recipientPrivateKey.count == Constants.Crypto.secretKeyLength else {
            throw CryptoError.invalidPrivateKey
        }
        guard nonce.count == Constants.Crypto.nonceLength else {
            throw CryptoError.invalidNonce
        }

        // TODO: Implement actual NaCl box_open
        // crypto_box_open_easy(m, c, clen, n, pk, sk)

        // Placeholder - needs real crypto implementation
        throw CryptoError.decryptionFailed
    }

    /// Sign message using Ed25519.
    /// Uses canonical signing format matching server implementation.
    func sign(
        privateKey: Data,
        messageType: String,
        messageId: String,
        from: String,
        toOrGroupId: String,
        timestamp: Int64,
        nonce: String,
        ciphertext: String
    ) throws -> Data {
        guard privateKey.count == Constants.Crypto.secretKeyLength else {
            throw CryptoError.invalidPrivateKey
        }

        // Build canonical signing message (matches server)
        let signingMessage = [
            "messageType": messageType,
            "messageId": messageId,
            "from": from,
            "toOrGroupId": toOrGroupId,
            "timestamp": String(timestamp),
            "nonce": nonce,
            "ciphertext": ciphertext
        ]

        // Sort keys and serialize
        let sortedKeys = signingMessage.keys.sorted()
        var parts: [String] = []
        for key in sortedKeys {
            parts.append("\(key):\(signingMessage[key]!)")
        }
        let canonicalMessage = parts.joined(separator: "|")

        guard let messageData = canonicalMessage.data(using: .utf8) else {
            throw CryptoError.signatureFailed
        }

        // TODO: Implement actual Ed25519 signing
        // crypto_sign_detached(sig, siglen, m, mlen, sk)

        // Placeholder - needs real crypto implementation
        throw CryptoError.signatureFailed
    }

    /// Verify Ed25519 signature.
    func verify(
        publicKey: Data,
        signature: Data,
        message: Data
    ) throws -> Bool {
        guard publicKey.count == Constants.Crypto.publicKeyLength else {
            throw CryptoError.invalidPublicKey
        }
        guard signature.count == Constants.Crypto.signatureLength else {
            throw CryptoError.signatureVerificationFailed
        }

        // TODO: Implement actual Ed25519 verification
        // crypto_sign_verify_detached(sig, m, mlen, pk)

        // Placeholder - needs real crypto implementation
        throw CryptoError.signatureVerificationFailed
    }
}
