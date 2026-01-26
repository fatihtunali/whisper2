import Foundation
import SwiftUI

/// Pending message model for display
struct PendingMessage: Identifiable {
    var id: String { messageId }
    let messageId: String
    let from: String
    let to: String
    let msgType: String
    let timestamp: Int64
    let nonce: String
    let ciphertext: String
    let sig: String
    let replyTo: String?
    let groupId: String?
    let senderPublicKey: String?  // Sender's encPublicKey for decryption

    var timestampDate: Date {
        Date(timeIntervalSince1970: TimeInterval(timestamp) / 1000)
    }

    /// Decrypt and decode the message content
    var decodedText: String? {
        guard let ciphertextData = Data(base64Encoded: ciphertext),
              let nonceData = Data(base64Encoded: nonce) else {
            return nil
        }

        // Try to decrypt if we have sender's public key and our private key
        if let senderPubKeyB64 = senderPublicKey,
           let senderPubKey = Data(base64Encoded: senderPubKeyB64),
           let myPrivateKey = KeychainService.shared.getData(forKey: Constants.StorageKey.encPrivateKey) {
            do {
                let plaintext = try NaClBox.open(
                    ciphertext: ciphertextData,
                    nonce: nonceData,
                    senderPublicKey: senderPubKey,
                    recipientPrivateKey: myPrivateKey
                )
                return String(data: plaintext, encoding: .utf8)
            } catch {
                // Decryption failed, try plain base64 (for unencrypted messages)
                return String(data: ciphertextData, encoding: .utf8)
            }
        }

        // Fallback: try plain base64 decode (for unencrypted/legacy messages)
        return String(data: ciphertextData, encoding: .utf8)
    }
}

/// Manages the app's WebSocket connection and message handling
/// Singleton that maintains connection after login
@Observable
final class AppConnectionManager {
    static let shared = AppConnectionManager()

    // MARK: - State

    private(set) var connectionState: WSConnectionState = .disconnected
    private(set) var pendingMessages: [PendingMessage] = []
    private(set) var lastError: String?

    // Cache for sender public keys (whisperId -> encPublicKey base64)
    private var publicKeyCache: [String: String] = [:]

    // MARK: - Dependencies

    private var wsClient: WSClient?
    private let keychain = KeychainService.shared
    private let sessionManager = SessionManager.shared
    private let authService = AuthService.shared

    // MARK: - Callbacks

    var onMessageReceived: ((PendingMessage) -> Void)?

    private init() {}

    // MARK: - WebSocket Access

    /// Get the current WebSocket client (for use by other services like GroupService)
    func getWSClient() -> WSClient? {
        return wsClient
    }

    // MARK: - Connection

    /// Connect to WebSocket server and authenticate
    @MainActor
    func connect() async {
        guard connectionState == .disconnected else {
            logger.debug("Already connected or connecting", category: .network)
            return
        }

        // Check if we have stored keys for authentication
        guard keychain.isRegistered,
              let signPrivateKey = keychain.getData(forKey: Constants.StorageKey.signPrivateKey),
              let encPrivateKey = keychain.getData(forKey: Constants.StorageKey.encPrivateKey),
              let signPublicKey = keychain.getData(forKey: Constants.StorageKey.signPublicKey),
              let encPublicKey = keychain.getData(forKey: Constants.StorageKey.encPublicKey) else {
            logger.warning("Cannot connect: not registered (no keys)", category: .network)
            lastError = "Not registered"
            return
        }

        connectionState = .connecting
        lastError = nil

        // Create WebSocket client
        let client = WSClient()
        self.wsClient = client

        // Set delegate
        await client.setDelegate(self)

        // Connect to WebSocket
        let connected = await client.connectAndWait(timeout: 15)

        guard connected else {
            connectionState = .disconnected
            lastError = "Failed to connect to server"
            logger.error("WebSocket connection failed", category: .network)
            return
        }

        logger.info("WebSocket connected, authenticating...", category: .network)

        // Authenticate using register flow
        do {
            let connection = WSClientConnectionAdapter(client: client)
            let result = try await authenticateConnection(
                ws: connection,
                signPrivateKey: signPrivateKey,
                signPublicKey: signPublicKey,
                encPublicKey: encPublicKey
            )

            connectionState = .connected
            logger.info("Authenticated as \(result.whisperId)", category: .network)

            // Fetch pending messages
            await fetchPendingMessages(sessionToken: result.sessionToken, ws: connection)

        } catch {
            connectionState = .disconnected
            lastError = "Authentication failed: \(error.localizedDescription)"
            logger.error(error, message: "Authentication failed", category: .network)
            await client.disconnect()
        }
    }

    /// Authenticate the connection using register_begin/register_proof
    private func authenticateConnection(
        ws: WebSocketConnection,
        signPrivateKey: Data,
        signPublicKey: Data,
        encPublicKey: Data
    ) async throws -> (whisperId: String, sessionToken: String) {
        let deviceId = keychain.deviceId ?? UUID().uuidString.lowercased()

        // Step 1: Send register_begin
        var beginPayload: [String: Any] = [
            "protocolVersion": Constants.protocolVersion,
            "cryptoVersion": Constants.cryptoVersion,
            "deviceId": deviceId,
            "platform": "ios"
        ]

        // Include existing whisperId if we have one (recovery flow)
        if let existingWhisperId = keychain.whisperId {
            beginPayload["whisperId"] = existingWhisperId
        }

        logger.debug("Sending register_begin", category: .auth)

        let challengeResponse = try await ws.sendAndWait(
            type: Constants.MessageType.registerBegin,
            payload: beginPayload,
            expectedResponseType: Constants.MessageType.registerChallenge,
            timeout: 10
        )

        // Parse challenge
        guard let challengeId = challengeResponse["challengeId"] as? String,
              let challengeB64 = challengeResponse["challenge"] as? String,
              let challengeBytes = Data(base64Encoded: challengeB64) else {
            throw AuthError.invalidChallenge
        }

        logger.debug("Received challenge: \(challengeId)", category: .auth)

        // Step 2: Sign the challenge
        let signature = try CanonicalSigning.signChallenge(challengeBytes, privateKey: signPrivateKey)

        // Step 3: Send register_proof
        var proofPayload: [String: Any] = [
            "protocolVersion": Constants.protocolVersion,
            "cryptoVersion": Constants.cryptoVersion,
            "challengeId": challengeId,
            "deviceId": deviceId,
            "platform": "ios",
            "encPublicKey": encPublicKey.base64EncodedString(),
            "signPublicKey": signPublicKey.base64EncodedString(),
            "signature": signature.base64EncodedString()
        ]

        if let existingWhisperId = keychain.whisperId {
            proofPayload["whisperId"] = existingWhisperId
        }

        logger.debug("Sending register_proof", category: .auth)

        let ackResponse = try await ws.sendAndWait(
            type: Constants.MessageType.registerProof,
            payload: proofPayload,
            expectedResponseType: Constants.MessageType.registerAck,
            timeout: 10
        )

        // Parse ack
        guard let success = ackResponse["success"] as? Bool, success,
              let whisperId = ackResponse["whisperId"] as? String,
              let sessionToken = ackResponse["sessionToken"] as? String,
              let expiresAtMs = ackResponse["sessionExpiresAt"] as? Int64 else {
            throw AuthError.registrationFailed(reason: "Invalid ack response")
        }

        // Store/update session
        let expiryDate = Date(timeIntervalSince1970: TimeInterval(expiresAtMs) / 1000)
        try sessionManager.storeSession(token: sessionToken, expiry: expiryDate, whisperId: whisperId)

        // Update WhisperID if changed
        keychain.whisperId = whisperId

        return (whisperId, sessionToken)
    }

    /// Disconnect from WebSocket server
    @MainActor
    func disconnect() async {
        await wsClient?.disconnect()
        wsClient = nil
        connectionState = .disconnected
        logger.info("WebSocket disconnected", category: .network)
    }

    // MARK: - Fetch Pending Messages

    @MainActor
    private func fetchPendingMessages(sessionToken: String, ws: WebSocketConnection) async {
        do {
            let payload: [String: Any] = [
                "protocolVersion": Constants.protocolVersion,
                "cryptoVersion": Constants.cryptoVersion,
                "sessionToken": sessionToken
            ]

            let response = try await ws.sendAndWait(
                type: Constants.MessageType.fetchPending,
                payload: payload,
                expectedResponseType: Constants.MessageType.pendingMessages,
                timeout: 10
            )

            // Parse pending messages
            if let messagesArray = response["messages"] as? [[String: Any]] {
                logger.info("Received \(messagesArray.count) pending messages", category: .messaging)

                // Collect unique sender IDs to fetch their public keys
                var senderIds = Set<String>()
                var parsedMessages: [PendingMessage] = []

                for msgDict in messagesArray {
                    if let message = parsePendingMessage(msgDict) {
                        parsedMessages.append(message)
                        senderIds.insert(message.from)
                    }
                }

                // Fetch public keys for all senders
                await fetchPublicKeysForSenders(Array(senderIds), sessionToken: sessionToken)

                // Update messages with sender public keys and add to pending
                for var message in parsedMessages {
                    if message.senderPublicKey == nil {
                        message = PendingMessage(
                            messageId: message.messageId,
                            from: message.from,
                            to: message.to,
                            msgType: message.msgType,
                            timestamp: message.timestamp,
                            nonce: message.nonce,
                            ciphertext: message.ciphertext,
                            sig: message.sig,
                            replyTo: message.replyTo,
                            groupId: message.groupId,
                            senderPublicKey: publicKeyCache[message.from]
                        )
                    }
                    pendingMessages.append(message)
                    onMessageReceived?(message)
                }
            }

        } catch {
            logger.error(error, message: "Failed to fetch pending messages", category: .messaging)
            lastError = "Failed to fetch messages"
        }
    }

    /// Fetch public keys for multiple senders
    @MainActor
    private func fetchPublicKeysForSenders(_ senderIds: [String], sessionToken: String) async {
        for senderId in senderIds {
            // Skip if already cached
            if publicKeyCache[senderId] != nil { continue }

            // Fetch from server
            if let publicKey = await fetchPublicKey(for: senderId, sessionToken: sessionToken) {
                publicKeyCache[senderId] = publicKey
                logger.debug("Cached public key for \(senderId)", category: .messaging)
            }
        }
    }

    /// Fetch a single user's public key from the server
    @MainActor
    private func fetchPublicKey(for whisperId: String, sessionToken: String) async -> String? {
        let urlString = "\(Constants.Server.baseURL)/users/\(whisperId)/keys"
        guard let url = URL(string: urlString) else { return nil }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 10
        request.setValue("Bearer \(sessionToken)", forHTTPHeaderField: "Authorization")

        do {
            let (data, response) = try await URLSession.shared.data(for: request)

            guard let httpResponse = response as? HTTPURLResponse,
                  httpResponse.statusCode == 200 else {
                return nil
            }

            guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let encPublicKey = json["encPublicKey"] as? String else {
                return nil
            }

            return encPublicKey
        } catch {
            logger.error(error, message: "Failed to fetch public key for \(whisperId)", category: .network)
            return nil
        }
    }

    /// Get cached public key for a sender
    func getCachedPublicKey(for whisperId: String) -> String? {
        return publicKeyCache[whisperId]
    }

    private func parsePendingMessage(_ dict: [String: Any]) -> PendingMessage? {
        guard let messageId = dict["messageId"] as? String,
              let from = dict["from"] as? String,
              let to = dict["to"] as? String,
              let timestamp = dict["timestamp"] as? Int64,
              let nonce = dict["nonce"] as? String,
              let ciphertext = dict["ciphertext"] as? String else {
            return nil
        }

        // Get sender's public key if included in message
        let senderPublicKey = dict["senderEncPublicKey"] as? String

        return PendingMessage(
            messageId: messageId,
            from: from,
            to: to,
            msgType: dict["msgType"] as? String ?? "text",
            timestamp: timestamp,
            nonce: nonce,
            ciphertext: ciphertext,
            sig: dict["sig"] as? String ?? "",
            replyTo: dict["replyTo"] as? String,
            groupId: dict["groupId"] as? String,
            senderPublicKey: senderPublicKey
        )
    }

    // MARK: - Send Message

    /// Send an encrypted message to a recipient
    /// - Parameters:
    ///   - recipientId: Recipient's WhisperId
    ///   - text: Plaintext message to send
    ///   - recipientPublicKey: Recipient's X25519 public key (32 bytes). If nil, message is sent unencrypted (for testing only).
    @MainActor
    func sendMessage(to recipientId: String, text: String, recipientPublicKey: Data? = nil) async throws {
        guard let client = wsClient,
              let sessionToken = sessionManager.sessionToken,
              let whisperId = keychain.whisperId else {
            throw NSError(domain: "AppConnectionManager", code: 1, userInfo: [NSLocalizedDescriptionKey: "Not connected"])
        }

        let connection = WSClientConnectionAdapter(client: client)

        let messageId = UUID().uuidString.lowercased()
        let timestamp = Int64(Date().timeIntervalSince1970 * 1000)

        // Get our encryption private key
        guard let encPrivateKey = keychain.getData(forKey: Constants.StorageKey.encPrivateKey) else {
            throw NSError(domain: "AppConnectionManager", code: 2, userInfo: [NSLocalizedDescriptionKey: "No encryption key"])
        }

        // Get signing key
        guard let signPrivateKey = keychain.getData(forKey: Constants.StorageKey.signPrivateKey) else {
            throw NSError(domain: "AppConnectionManager", code: 2, userInfo: [NSLocalizedDescriptionKey: "No signing key"])
        }

        let nonce: Data
        let ciphertext: Data

        // Encrypt message if we have recipient's public key
        if let recipientPubKey = recipientPublicKey {
            // Use NaClBox for proper end-to-end encryption
            let (encNonce, encCiphertext) = try NaClBox.seal(
                message: Data(text.utf8),
                recipientPublicKey: recipientPubKey,
                senderPrivateKey: encPrivateKey
            )
            nonce = encNonce
            ciphertext = encCiphertext
        } else {
            // Fallback: send plaintext as base64 (for testing/debug only)
            logger.warning("Sending message without encryption - recipient public key not provided", category: .messaging)
            nonce = NaClBox.generateNonce()
            ciphertext = Data(text.utf8)
        }

        // Sign the message
        let signature = try CanonicalSigning.signCanonical(
            messageType: "send_message",
            messageId: messageId,
            from: whisperId,
            to: recipientId,
            timestamp: timestamp,
            nonce: nonce,
            ciphertext: ciphertext,
            privateKey: signPrivateKey
        )

        let payload: [String: Any] = [
            "protocolVersion": Constants.protocolVersion,
            "cryptoVersion": Constants.cryptoVersion,
            "sessionToken": sessionToken,
            "messageId": messageId,
            "from": whisperId,
            "to": recipientId,
            "msgType": "text",
            "timestamp": timestamp,
            "nonce": nonce.base64EncodedString(),
            "ciphertext": ciphertext.base64EncodedString(),
            "sig": signature.base64EncodedString()
        ]

        _ = try await connection.sendAndWait(
            type: Constants.MessageType.sendMessage,
            payload: payload,
            expectedResponseType: Constants.MessageType.messageAccepted,
            timeout: 10
        )

        logger.info("Message sent: \(messageId)", category: .messaging)
    }

    // MARK: - Send Attachment

    /// Send an attachment to a recipient
    /// Note: Full attachment flow requires uploading to S3 first, then sending an AttachmentPointer.
    /// This is a simplified version that sends the attachment as ciphertext (limited to small files).
    @MainActor
    func sendAttachment(to recipientId: String, data: Data, contentType: String, filename: String, recipientPublicKey: Data? = nil) async throws {
        // For large files, we need to upload to S3 first
        // This simplified version only works for small attachments
        // Server limit: MAX_CIPHERTEXT_B64_LEN = 100,000 chars (~75KB decoded)
        // After encryption (+16 bytes) and base64 (x1.33), 70KB raw -> ~93KB base64 < 100KB
        if data.count > 70000 { // ~70KB raw limit (becomes ~93KB base64 after encryption)
            throw NSError(domain: "AppConnectionManager", code: 3, userInfo: [NSLocalizedDescriptionKey: "Attachment too large (\(data.count / 1000)KB). Maximum is 70KB for inline attachments."])
        }

        guard let client = wsClient,
              let sessionToken = sessionManager.sessionToken,
              let whisperId = keychain.whisperId else {
            throw NSError(domain: "AppConnectionManager", code: 1, userInfo: [NSLocalizedDescriptionKey: "Not connected"])
        }

        let connection = WSClientConnectionAdapter(client: client)

        let messageId = UUID().uuidString.lowercased()
        let timestamp = Int64(Date().timeIntervalSince1970 * 1000)

        // Get our encryption private key
        guard let encPrivateKey = keychain.getData(forKey: Constants.StorageKey.encPrivateKey) else {
            throw NSError(domain: "AppConnectionManager", code: 2, userInfo: [NSLocalizedDescriptionKey: "No encryption key"])
        }

        // Get signing key
        guard let signPrivateKey = keychain.getData(forKey: Constants.StorageKey.signPrivateKey) else {
            throw NSError(domain: "AppConnectionManager", code: 2, userInfo: [NSLocalizedDescriptionKey: "No signing key"])
        }

        let nonce: Data
        let ciphertext: Data

        // Encrypt if we have recipient's public key
        if let recipientPubKey = recipientPublicKey {
            let (encNonce, encCiphertext) = try NaClBox.seal(
                message: data,
                recipientPublicKey: recipientPubKey,
                senderPrivateKey: encPrivateKey
            )
            nonce = encNonce
            ciphertext = encCiphertext
        } else {
            logger.warning("Sending attachment without encryption", category: .messaging)
            nonce = NaClBox.generateNonce()
            ciphertext = data
        }

        // Sign the message
        let signature = try CanonicalSigning.signCanonical(
            messageType: "send_message",
            messageId: messageId,
            from: whisperId,
            to: recipientId,
            timestamp: timestamp,
            nonce: nonce,
            ciphertext: ciphertext,
            privateKey: signPrivateKey
        )

        // Determine msgType based on contentType
        let msgType: String
        if contentType.starts(with: "image/") {
            msgType = "image"
        } else if contentType.starts(with: "video/") {
            msgType = "video"
        } else if contentType.starts(with: "audio/") {
            msgType = "voice"
        } else {
            msgType = "file"
        }

        let payload: [String: Any] = [
            "protocolVersion": Constants.protocolVersion,
            "cryptoVersion": Constants.cryptoVersion,
            "sessionToken": sessionToken,
            "messageId": messageId,
            "from": whisperId,
            "to": recipientId,
            "msgType": msgType,
            "timestamp": timestamp,
            "nonce": nonce.base64EncodedString(),
            "ciphertext": ciphertext.base64EncodedString(),
            "sig": signature.base64EncodedString()
        ]

        _ = try await connection.sendAndWait(
            type: Constants.MessageType.sendMessage,
            payload: payload,
            expectedResponseType: Constants.MessageType.messageAccepted,
            timeout: 30
        )

        logger.info("Attachment sent: \(messageId) (\(filename))", category: .messaging)
    }

    // MARK: - Send Message with Attachment Pointer (E2E Flow)

    /// Send a message with an attachment pointer (proper E2E flow)
    /// The attachment data has already been encrypted and uploaded to S3.
    /// This method only sends the metadata (objectKey + encrypted fileKey).
    /// Server never sees the actual file content!
    @MainActor
    func sendMessageWithAttachment(to recipientId: String, text: String, attachment: AttachmentPointer, recipientPublicKey: Data) async throws {
        guard let client = wsClient,
              let sessionToken = sessionManager.sessionToken,
              let whisperId = keychain.whisperId else {
            throw NSError(domain: "AppConnectionManager", code: 1, userInfo: [NSLocalizedDescriptionKey: "Not connected"])
        }

        let connection = WSClientConnectionAdapter(client: client)

        let messageId = UUID().uuidString.lowercased()
        let timestamp = Int64(Date().timeIntervalSince1970 * 1000)

        // Get our encryption private key
        guard let encPrivateKey = keychain.getData(forKey: Constants.StorageKey.encPrivateKey) else {
            throw NSError(domain: "AppConnectionManager", code: 2, userInfo: [NSLocalizedDescriptionKey: "No encryption key"])
        }

        // Get signing key
        guard let signPrivateKey = keychain.getData(forKey: Constants.StorageKey.signPrivateKey) else {
            throw NSError(domain: "AppConnectionManager", code: 2, userInfo: [NSLocalizedDescriptionKey: "No signing key"])
        }

        // Encrypt the message text (placeholder text like "📷 Photo")
        let (nonce, ciphertext) = try NaClBox.seal(
            message: Data(text.utf8),
            recipientPublicKey: recipientPublicKey,
            senderPrivateKey: encPrivateKey
        )

        // Sign the message
        let signature = try CanonicalSigning.signCanonical(
            messageType: "send_message",
            messageId: messageId,
            from: whisperId,
            to: recipientId,
            timestamp: timestamp,
            nonce: nonce,
            ciphertext: ciphertext,
            privateKey: signPrivateKey
        )

        // Determine msgType based on content type
        let msgType: String
        if attachment.contentType.starts(with: "image/") {
            msgType = "image"
        } else if attachment.contentType.starts(with: "video/") {
            msgType = "video"
        } else if attachment.contentType.starts(with: "audio/") {
            msgType = "voice"
        } else {
            msgType = "file"
        }

        // Build payload with attachment pointer (metadata only)
        let payload: [String: Any] = [
            "protocolVersion": Constants.protocolVersion,
            "cryptoVersion": Constants.cryptoVersion,
            "sessionToken": sessionToken,
            "messageId": messageId,
            "from": whisperId,
            "to": recipientId,
            "msgType": msgType,
            "timestamp": timestamp,
            "nonce": nonce.base64EncodedString(),
            "ciphertext": ciphertext.base64EncodedString(),
            "sig": signature.base64EncodedString(),
            "attachment": [
                "objectKey": attachment.objectKey,
                "contentType": attachment.contentType,
                "ciphertextSize": attachment.ciphertextSize,
                "fileNonce": attachment.fileNonce,
                "fileKeyBox": [
                    "nonce": attachment.fileKeyBox.nonce,
                    "ciphertext": attachment.fileKeyBox.ciphertext
                ]
            ]
        ]

        _ = try await connection.sendAndWait(
            type: Constants.MessageType.sendMessage,
            payload: payload,
            expectedResponseType: Constants.MessageType.messageAccepted,
            timeout: 30
        )

        logger.info("Message with attachment sent: \(messageId) (E2E via S3)", category: .messaging)
    }

    // MARK: - Typing Indicator

    /// Send typing indicator to a recipient
    @MainActor
    func sendTypingIndicator(to recipientId: String, isTyping: Bool) async {
        guard let client = wsClient,
              let sessionToken = sessionManager.sessionToken,
              let whisperId = keychain.whisperId else {
            return
        }

        let payload: [String: Any] = [
            "protocolVersion": Constants.protocolVersion,
            "sessionToken": sessionToken,
            "from": whisperId,
            "to": recipientId,
            "isTyping": isTyping
        ]

        do {
            let frame: [String: Any] = [
                "type": Constants.MessageType.typing,
                "payload": payload
            ]
            let data = try JSONSerialization.data(withJSONObject: frame)
            try await client.send(data)
            logger.debug("Sent typing indicator: \(isTyping) to \(recipientId)", category: .messaging)
        } catch {
            logger.error(error, message: "Failed to send typing indicator", category: .messaging)
        }
    }

    // MARK: - Group Operations

    /// Create a new group on the server
    @MainActor
    func createGroup(title: String, memberIds: [String]) async throws -> String {
        guard let client = wsClient,
              let sessionToken = sessionManager.sessionToken,
              let whisperId = keychain.whisperId else {
            throw NSError(domain: "AppConnectionManager", code: 1, userInfo: [NSLocalizedDescriptionKey: "Not connected"])
        }

        let connection = WSClientConnectionAdapter(client: client)
        let groupId = UUID().uuidString.lowercased()

        let payload: [String: Any] = [
            "protocolVersion": Constants.protocolVersion,
            "cryptoVersion": Constants.cryptoVersion,
            "sessionToken": sessionToken,
            "groupId": groupId,
            "title": title,
            "members": memberIds,
            "createdBy": whisperId
        ]

        let response = try await connection.sendAndWait(
            type: Constants.MessageType.groupCreate,
            payload: payload,
            expectedResponseType: Constants.MessageType.groupEvent,
            timeout: 15
        )

        // Server may return a different groupId
        let actualGroupId = response["groupId"] as? String ?? groupId
        logger.info("Group created: \(actualGroupId)", category: .messaging)
        return actualGroupId
    }

    /// Send a message to all group members
    /// Note: Group messages are sent individually to each member, encrypted per-recipient
    @MainActor
    func sendGroupMessage(groupId: String, text: String, members: [(whisperId: String, publicKey: Data?)]) async throws {
        guard let client = wsClient,
              let sessionToken = sessionManager.sessionToken,
              let whisperId = keychain.whisperId else {
            throw NSError(domain: "AppConnectionManager", code: 1, userInfo: [NSLocalizedDescriptionKey: "Not connected"])
        }

        let connection = WSClientConnectionAdapter(client: client)
        let messageId = UUID().uuidString.lowercased()
        let timestamp = Int64(Date().timeIntervalSince1970 * 1000)

        // Get our keys
        guard let encPrivateKey = keychain.getData(forKey: Constants.StorageKey.encPrivateKey),
              let signPrivateKey = keychain.getData(forKey: Constants.StorageKey.signPrivateKey) else {
            throw NSError(domain: "AppConnectionManager", code: 2, userInfo: [NSLocalizedDescriptionKey: "No encryption key"])
        }

        // Send to each member individually
        for member in members where member.whisperId != whisperId {
            let nonce: Data
            let ciphertext: Data

            // Encrypt for this member if we have their public key
            if let memberPubKey = member.publicKey {
                let (encNonce, encCiphertext) = try NaClBox.seal(
                    message: Data(text.utf8),
                    recipientPublicKey: memberPubKey,
                    senderPrivateKey: encPrivateKey
                )
                nonce = encNonce
                ciphertext = encCiphertext
            } else {
                // Fallback: send without encryption (not recommended)
                logger.warning("Sending group message without encryption to \(member.whisperId)", category: .messaging)
                nonce = NaClBox.generateNonce()
                ciphertext = Data(text.utf8)
            }

            // Sign the message
            let signature = try CanonicalSigning.signCanonical(
                messageType: "group_send_message",
                messageId: messageId,
                from: whisperId,
                to: member.whisperId,
                timestamp: timestamp,
                nonce: nonce,
                ciphertext: ciphertext,
                privateKey: signPrivateKey
            )

            let payload: [String: Any] = [
                "protocolVersion": Constants.protocolVersion,
                "cryptoVersion": Constants.cryptoVersion,
                "sessionToken": sessionToken,
                "messageId": "\(messageId)-\(member.whisperId)", // Unique per recipient
                "groupId": groupId,
                "from": whisperId,
                "to": member.whisperId,
                "msgType": "text",
                "timestamp": timestamp,
                "nonce": nonce.base64EncodedString(),
                "ciphertext": ciphertext.base64EncodedString(),
                "sig": signature.base64EncodedString()
            ]

            _ = try await connection.sendAndWait(
                type: Constants.MessageType.groupSendMessage,
                payload: payload,
                expectedResponseType: Constants.MessageType.messageAccepted,
                timeout: 10
            )
        }

        logger.info("Group message sent to \(members.count - 1) members", category: .messaging)
    }
}

// MARK: - WSClientDelegate

extension AppConnectionManager: WSClientDelegate {
    nonisolated func wsClient(_ client: WSClient, didChangeState state: WSConnectionState) {
        Task { @MainActor in
            self.connectionState = state
        }
    }

    nonisolated func wsClient(_ client: WSClient, didReceiveMessage data: Data) {
        Task { @MainActor in
            // Parse incoming message
            guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let type = json["type"] as? String else {
                return
            }

            switch type {
            case Constants.MessageType.messageReceived:
                if let payload = json["payload"] as? [String: Any],
                   var message = self.parsePendingMessage(payload) {
                    // Fetch sender's public key if not cached
                    if message.senderPublicKey == nil,
                       let sessionToken = self.sessionManager.sessionToken {
                        if let cachedKey = self.publicKeyCache[message.from] {
                            message = PendingMessage(
                                messageId: message.messageId,
                                from: message.from,
                                to: message.to,
                                msgType: message.msgType,
                                timestamp: message.timestamp,
                                nonce: message.nonce,
                                ciphertext: message.ciphertext,
                                sig: message.sig,
                                replyTo: message.replyTo,
                                groupId: message.groupId,
                                senderPublicKey: cachedKey
                            )
                        } else if let publicKey = await self.fetchPublicKey(for: message.from, sessionToken: sessionToken) {
                            self.publicKeyCache[message.from] = publicKey
                            message = PendingMessage(
                                messageId: message.messageId,
                                from: message.from,
                                to: message.to,
                                msgType: message.msgType,
                                timestamp: message.timestamp,
                                nonce: message.nonce,
                                ciphertext: message.ciphertext,
                                sig: message.sig,
                                replyTo: message.replyTo,
                                groupId: message.groupId,
                                senderPublicKey: publicKey
                            )
                        }
                    }
                    self.pendingMessages.append(message)
                    self.onMessageReceived?(message)
                    logger.info("Received message from \(message.from)", category: .messaging)
                }

            case Constants.MessageType.messageDelivered:
                logger.debug("Message delivered notification", category: .messaging)

            default:
                logger.debug("Received message type: \(type)", category: .messaging)
            }
        }
    }

    nonisolated func wsClient(_ client: WSClient, didEncounterError error: Error) {
        Task { @MainActor in
            self.lastError = error.localizedDescription
            logger.error(error, message: "WebSocket error", category: .network)
        }
    }

    nonisolated func wsClientDidReceiveForceLogout(_ client: WSClient, reason: String) {
        Task { @MainActor in
            self.connectionState = .disconnected
            self.lastError = "Session ended: \(reason)"
            logger.warning("Force logout: \(reason)", category: .auth)
        }
    }
}
