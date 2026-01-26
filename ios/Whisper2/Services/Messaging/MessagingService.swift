import Foundation

/// Whisper2 MessagingService
/// Main coordinator for end-to-end encrypted messaging
///
/// Responsibilities:
/// - Encrypt messages with recipient's encPublicKey (NaCl box)
/// - Sign messages canonically (v1 format)
/// - Queue in outbox for reliable delivery
/// - Handle incoming messages: verify, decrypt, persist
/// - Send delivery receipts

// MARK: - Message Types
// Note: MessageContentType, DeliveryStatus, AttachmentPointer, SendMessagePayload,
// and MessageReceivedPayload are defined in WSModels.swift

/// Extended delivery status for internal use (includes pending state)
enum MessagingDeliveryStatus: String, Codable {
    case pending
    case sent
    case delivered
    case read
    case failed
}

/// Outgoing message before encryption
struct OutgoingMessage {
    let id: String
    let recipientId: String
    let contentType: MessageContentType
    let plaintext: Data
    let replyTo: String?
    let attachment: AttachmentPointer?

    init(
        id: String = UUID().uuidString.lowercased(),
        recipientId: String,
        contentType: MessageContentType = .text,
        plaintext: Data,
        replyTo: String? = nil,
        attachment: AttachmentPointer? = nil
    ) {
        self.id = id
        self.recipientId = recipientId
        self.contentType = contentType
        self.plaintext = plaintext
        self.replyTo = replyTo
        self.attachment = attachment
    }
}

/// Incoming message after decryption
struct IncomingMessage {
    let id: String
    let senderId: String
    let recipientId: String
    let contentType: MessageContentType
    let plaintext: Data
    let timestamp: Int64
    let replyTo: String?
    let attachment: AttachmentPointer?
}

// MARK: - Protocol Types
// Note: DeliveryReceiptPayload and FetchPendingPayload are defined in WSModels.swift

/// Protocol versions (must match server)
private let protocolVersion = 1
private let cryptoVersion = 1

// MARK: - Crypto Protocol

/// Protocol for crypto operations (dependency injection)
protocol MessagingCryptoProvider {
    /// Get current user's WhisperID
    var myWhisperId: String? { get }

    /// Get current session token
    var sessionToken: String? { get }

    /// Get my signing private key
    var signPrivateKey: Data? { get }

    /// Get my encryption private key
    var encPrivateKey: Data? { get }

    /// Encrypt message for recipient using NaCl box
    func encrypt(
        plaintext: Data,
        recipientEncPublicKey: Data
    ) throws -> (ciphertext: Data, nonce: Data)

    /// Decrypt message from sender using NaCl box
    func decrypt(
        ciphertext: Data,
        nonce: Data,
        senderEncPublicKey: Data
    ) throws -> Data

    /// Sign data with Ed25519
    /// NOTE: Implementation should sign SHA256(data), not raw data
    /// This matches the server: sig = Ed25519_Sign(SHA256(canonical_bytes), privateKey)
    func sign(data: Data) throws -> Data

    /// Verify Ed25519 signature
    /// NOTE: Implementation should verify against SHA256(data), not raw data
    func verify(
        signature: Data,
        data: Data,
        signPublicKey: Data
    ) -> Bool
}

/// Protocol for contact lookups
protocol ContactProvider {
    /// Get contact's encryption public key
    func getEncPublicKey(for whisperId: String) async throws -> Data

    /// Get contact's signing public key
    func getSignPublicKey(for whisperId: String) async throws -> Data
}

/// Protocol for message persistence
protocol MessagePersistence {
    /// Save incoming message
    func saveIncoming(_ message: IncomingMessage) async throws

    /// Update message delivery status
    func updateStatus(messageId: String, status: DeliveryStatus) async throws

    /// Get conversation ID for peer
    func conversationId(for peerId: String) async throws -> String
}

// MARK: - WebSocket Protocol

/// Protocol for WebSocket communication
protocol WebSocketProvider {
    /// Send message over WebSocket
    func send(type: String, payload: Encodable) async throws

    /// Connection state
    var isConnected: Bool { get }
}

// MARK: - MessagingService

/// Main messaging service actor
/// Thread-safe coordinator for all messaging operations
actor MessagingService {

    // MARK: - Dependencies

    private let crypto: MessagingCryptoProvider
    private let contacts: ContactProvider
    private let persistence: MessagePersistence
    private let webSocket: WebSocketProvider
    private let outbox: OutboxQueue
    private let deduper: Deduper

    // MARK: - Callbacks

    /// Called when a new message is received and decrypted
    var onMessageReceived: ((IncomingMessage) async -> Void)?

    /// Called when a delivery status update is received
    var onDeliveryStatusChanged: ((String, DeliveryStatus) async -> Void)?

    // MARK: - Initialization

    init(
        crypto: MessagingCryptoProvider,
        contacts: ContactProvider,
        persistence: MessagePersistence,
        webSocket: WebSocketProvider,
        outbox: OutboxQueue,
        deduper: Deduper
    ) {
        self.crypto = crypto
        self.contacts = contacts
        self.persistence = persistence
        self.webSocket = webSocket
        self.outbox = outbox
        self.deduper = deduper
    }

    // MARK: - Send Message

    /// Send a text message to a recipient
    /// - Parameters:
    ///   - recipientId: Recipient's WhisperID
    ///   - text: Plain text content
    /// - Returns: Message ID
    @discardableResult
    func sendMessage(to recipientId: String, text: String) async throws -> String {
        let plaintext = Data(text.utf8)
        let message = OutgoingMessage(
            recipientId: recipientId,
            contentType: .text,
            plaintext: plaintext
        )
        return try await sendMessage(message)
    }

    /// Send a message to a recipient
    /// - Parameter message: Outgoing message to send
    /// - Returns: Message ID
    @discardableResult
    func sendMessage(_ message: OutgoingMessage) async throws -> String {
        guard let myWhisperId = crypto.myWhisperId else {
            throw MessagingError.sendFailed(reason: "Not authenticated")
        }

        guard let sessionToken = crypto.sessionToken else {
            throw MessagingError.sendFailed(reason: "No session token")
        }

        // Get recipient's encryption public key
        let recipientEncPublicKey: Data
        do {
            recipientEncPublicKey = try await contacts.getEncPublicKey(for: message.recipientId)
        } catch {
            logger.error(error, message: "Failed to get recipient public key", category: .messaging)
            throw MessagingError.recipientNotFound
        }

        // Encrypt the message
        let (ciphertext, nonce): (Data, Data)
        do {
            (ciphertext, nonce) = try crypto.encrypt(
                plaintext: message.plaintext,
                recipientEncPublicKey: recipientEncPublicKey
            )
        } catch {
            logger.error(error, message: "Encryption failed", category: .messaging)
            throw MessagingError.encryptionFailed
        }

        // Build timestamp
        let timestamp = Time.nowMs

        // Build canonical bytes for signing
        let nonceB64 = nonce.base64
        let ciphertextB64 = ciphertext.base64

        let canonicalBytes = buildCanonicalBytes(
            messageType: "send_message",
            messageId: message.id,
            from: myWhisperId,
            toOrGroupId: message.recipientId,
            timestamp: timestamp,
            nonceB64: nonceB64,
            ciphertextB64: ciphertextB64
        )

        // Sign the canonical bytes
        let signature: Data
        do {
            signature = try crypto.sign(data: canonicalBytes)
        } catch {
            logger.error(error, message: "Signing failed", category: .messaging)
            throw MessagingError.sendFailed(reason: "Signing failed")
        }

        // Build payload - convert MessageContentType to WSMessageContentType
        let wsContentType = WSMessageContentType(rawValue: message.contentType.rawValue) ?? .text
        let payload = SendMessagePayload(
            sessionToken: sessionToken,
            messageId: message.id,
            from: myWhisperId,
            to: message.recipientId,
            msgType: wsContentType,
            timestamp: timestamp,
            nonce: nonceB64,
            ciphertext: ciphertextB64,
            sig: signature.base64,
            replyTo: message.replyTo,
            attachment: message.attachment
        )

        // Queue in outbox for reliable delivery
        let outboxItem = OutboxItem(
            messageId: message.id,
            recipientId: message.recipientId,
            payload: payload,
            createdAt: Date()
        )

        await outbox.enqueue(outboxItem)

        // Attempt immediate send
        await outbox.processQueue()

        logger.info("Message queued: \(message.id) to \(message.recipientId)", category: .messaging)

        return message.id
    }

    // MARK: - Handle Incoming Message

    /// Handle incoming message_received from server
    /// - Parameter payload: The received message payload
    func handleMessageReceived(_ payload: MessageReceivedPayload) async {
        logger.debug("Received message: \(payload.messageId) from \(payload.from)", category: .messaging)

        // Check for duplicates
        let conversationId: String
        do {
            conversationId = try await persistence.conversationId(for: payload.from)
        } catch {
            logger.error(error, message: "Failed to get conversation ID", category: .messaging)
            return
        }

        if await deduper.isDuplicate(messageId: payload.messageId, conversationId: conversationId) {
            logger.debug("Duplicate message ignored: \(payload.messageId)", category: .messaging)
            return
        }

        // Verify signature
        let senderSignPublicKey: Data
        do {
            senderSignPublicKey = try await contacts.getSignPublicKey(for: payload.from)
        } catch {
            logger.error(error, message: "Failed to get sender's sign public key", category: .messaging)
            return
        }

        let canonicalBytes = buildCanonicalBytes(
            messageType: "send_message",
            messageId: payload.messageId,
            from: payload.from,
            toOrGroupId: payload.to,
            timestamp: payload.timestamp,
            nonceB64: payload.nonce,
            ciphertextB64: payload.ciphertext
        )

        guard let signatureData = try? payload.sig.base64Decoded() else {
            logger.warning("Invalid signature base64: \(payload.messageId)", category: .messaging)
            return
        }

        let sigValid = crypto.verify(
            signature: signatureData,
            data: canonicalBytes,
            signPublicKey: senderSignPublicKey
        )

        guard sigValid else {
            logger.warning("Signature verification failed: \(payload.messageId)", category: .messaging)
            return
        }

        // Decrypt the message
        guard let nonceData = try? payload.nonce.base64Decoded(),
              let ciphertextData = try? payload.ciphertext.base64Decoded() else {
            logger.warning("Invalid nonce/ciphertext base64: \(payload.messageId)", category: .messaging)
            return
        }

        let senderEncPublicKey: Data
        do {
            senderEncPublicKey = try await contacts.getEncPublicKey(for: payload.from)
        } catch {
            logger.error(error, message: "Failed to get sender's enc public key", category: .messaging)
            return
        }

        let plaintext: Data
        do {
            plaintext = try crypto.decrypt(
                ciphertext: ciphertextData,
                nonce: nonceData,
                senderEncPublicKey: senderEncPublicKey
            )
        } catch {
            logger.error(error, message: "Decryption failed: \(payload.messageId)", category: .messaging)
            return
        }

        // Build incoming message
        let contentType = MessageContentType(rawValue: payload.msgType) ?? .text
        let incomingMessage = IncomingMessage(
            id: payload.messageId,
            senderId: payload.from,
            recipientId: payload.to,
            contentType: contentType,
            plaintext: plaintext,
            timestamp: payload.timestamp,
            replyTo: payload.replyTo,
            attachment: payload.attachment
        )

        // Mark as processed for dedup
        await deduper.markProcessed(messageId: payload.messageId, conversationId: conversationId)

        // Persist the message
        do {
            try await persistence.saveIncoming(incomingMessage)
        } catch {
            logger.error(error, message: "Failed to persist message", category: .messaging)
        }

        // Send delivery receipt
        await sendDeliveryReceipt(
            messageId: payload.messageId,
            from: payload.to,  // We are the recipient
            to: payload.from,  // Sender gets the receipt
            status: .delivered
        )

        // Notify callback
        await onMessageReceived?(incomingMessage)

        logger.info("Message processed: \(payload.messageId) from \(payload.from)", category: .messaging)
    }

    // MARK: - Delivery Receipts

    /// Send a delivery receipt
    /// Note: Uses MessagingDeliveryStatus for internal status tracking
    func sendDeliveryReceipt(
        messageId: String,
        from: String,
        to: String,
        status: MessagingDeliveryStatus
    ) async {
        guard let sessionToken = crypto.sessionToken else {
            logger.warning("Cannot send receipt: no session token", category: .messaging)
            return
        }

        // Convert to WS DeliveryStatus
        let wsStatus: DeliveryStatus = status == .delivered ? .delivered : .read
        let payload = DeliveryReceiptPayload(
            sessionToken: sessionToken,
            messageId: messageId,
            from: from,
            to: to,
            status: wsStatus,
            timestamp: Time.nowMs
        )

        do {
            try await webSocket.send(type: Constants.MessageType.deliveryReceipt, payload: payload)
            logger.debug("Delivery receipt sent: \(messageId) status=\(status)", category: .messaging)
        } catch {
            logger.error(error, message: "Failed to send delivery receipt", category: .messaging)
        }
    }

    /// Handle incoming delivery status notification
    func handleDeliveryStatus(messageId: String, status: String, timestamp: Int64) async {
        let deliveryStatus: DeliveryStatus
        switch status {
        case "delivered":
            deliveryStatus = .delivered
        case "read":
            deliveryStatus = .read
        default:
            return
        }

        do {
            try await persistence.updateStatus(messageId: messageId, status: deliveryStatus)
        } catch {
            logger.error(error, message: "Failed to update delivery status", category: .messaging)
        }

        await onDeliveryStatusChanged?(messageId, deliveryStatus)

        logger.debug("Delivery status updated: \(messageId) -> \(status)", category: .messaging)
    }

    // MARK: - Mark as Read

    /// Mark a message as read and send read receipt
    func markAsRead(messageId: String, senderId: String) async {
        guard let myWhisperId = crypto.myWhisperId else { return }

        do {
            try await persistence.updateStatus(messageId: messageId, status: .read)
        } catch {
            logger.error(error, message: "Failed to mark message as read", category: .messaging)
        }

        await sendDeliveryReceipt(
            messageId: messageId,
            from: myWhisperId,
            to: senderId,
            status: .read
        )
    }

    // MARK: - Canonical Signing

    /// Build canonical bytes for signing (must match server exactly)
    ///
    /// Format:
    /// v1\n
    /// messageType\n
    /// messageId\n
    /// from\n
    /// toOrGroupId\n
    /// timestamp\n
    /// base64(nonce)\n
    /// base64(ciphertext)\n
    private func buildCanonicalBytes(
        messageType: String,
        messageId: String,
        from: String,
        toOrGroupId: String,
        timestamp: Int64,
        nonceB64: String,
        ciphertextB64: String
    ) -> Data {
        // Build canonical string exactly matching server format
        // Each line ends with \n, including the last ciphertext line
        let canonical = "v1\n" +
            "\(messageType)\n" +
            "\(messageId)\n" +
            "\(from)\n" +
            "\(toOrGroupId)\n" +
            "\(timestamp)\n" +
            "\(nonceB64)\n" +
            "\(ciphertextB64)\n"
        return Data(canonical.utf8)
    }
}
