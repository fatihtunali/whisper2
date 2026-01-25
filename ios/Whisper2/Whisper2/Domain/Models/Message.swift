import Foundation

/// Message - Represents an encrypted message
/// Messages are end-to-end encrypted and stored locally after decryption

struct Message: Identifiable, Codable, Equatable {

    // MARK: - Properties

    /// Unique message identifier (UUID)
    let messageId: String

    /// Convenience accessor for Identifiable
    var id: String { messageId }

    /// Conversation this message belongs to
    let conversationId: String

    /// Sender's WhisperID
    let senderId: WhisperID

    /// For direct messages: recipient's WhisperID
    let recipientId: WhisperID?

    /// For group messages: the group ID
    let groupId: String?

    /// Type of message content
    let msgType: MessageContentType

    /// When the message was sent
    let timestamp: Date

    /// Current delivery status
    var status: MessageStatus

    /// Encrypted message content (base64)
    let ciphertext: String

    /// Encryption nonce (base64)
    let nonce: String

    /// Decrypted plaintext (stored locally only, not encoded)
    var plaintext: String?

    /// When the message was delivered (server confirmed)
    var deliveredAt: Date?

    /// When the message was read by recipient
    var readAt: Date?

    /// Retry count for failed sends
    var retryCount: Int

    /// Error message if send failed
    var errorMessage: String?

    // MARK: - Attachment Properties

    /// Whether this message has an attachment
    var hasAttachment: Bool {
        attachment != nil
    }

    /// Attachment details (if any)
    var attachment: Attachment?

    // MARK: - Computed Properties

    /// Whether this is an outgoing message (sent by current user)
    func isOutgoing(currentUserId: WhisperID) -> Bool {
        senderId == currentUserId
    }

    /// Whether the message was successfully sent
    var isSent: Bool {
        status == .sent || status == .delivered || status == .read
    }

    /// Whether the message is still being sent
    var isPending: Bool {
        status == .queued || status == .sending
    }

    /// Whether the message failed to send
    var isFailed: Bool {
        status == .failed
    }

    /// Whether the message can be retried
    var canRetry: Bool {
        isFailed && retryCount < Constants.Limits.outboxMaxRetries
    }

    /// Formatted timestamp for display
    var formattedTime: String {
        let formatter = DateFormatter()
        formatter.dateStyle = .none
        formatter.timeStyle = .short
        return formatter.string(from: timestamp)
    }

    // MARK: - Initialization

    /// Create a new outgoing message
    /// - Parameters:
    ///   - senderId: The sender's WhisperID
    ///   - recipientId: The recipient's WhisperID (for direct messages)
    ///   - groupId: The group ID (for group messages)
    ///   - msgType: Content type
    ///   - ciphertext: Encrypted content (base64)
    ///   - nonce: Encryption nonce (base64)
    ///   - plaintext: Original plaintext (for local storage)
    ///   - attachment: Optional attachment
    static func outgoing(
        senderId: WhisperID,
        recipientId: WhisperID? = nil,
        groupId: String? = nil,
        msgType: MessageContentType = .text,
        ciphertext: String,
        nonce: String,
        plaintext: String,
        attachment: Attachment? = nil
    ) -> Message {
        let conversationId: String
        if let recipientId = recipientId {
            conversationId = "direct_\(recipientId.rawValue)"
        } else if let groupId = groupId {
            conversationId = "group_\(groupId)"
        } else {
            fatalError("Message must have either recipientId or groupId")
        }

        return Message(
            messageId: UUID().uuidString,
            conversationId: conversationId,
            senderId: senderId,
            recipientId: recipientId,
            groupId: groupId,
            msgType: msgType,
            timestamp: Date(),
            status: .queued,
            ciphertext: ciphertext,
            nonce: nonce,
            plaintext: plaintext,
            deliveredAt: nil,
            readAt: nil,
            retryCount: 0,
            errorMessage: nil,
            attachment: attachment
        )
    }

    /// Create a received message
    /// - Parameters:
    ///   - messageId: Server-assigned message ID
    ///   - senderId: The sender's WhisperID
    ///   - recipientId: The recipient's WhisperID
    ///   - groupId: The group ID (if group message)
    ///   - msgType: Content type
    ///   - timestamp: Server timestamp
    ///   - ciphertext: Encrypted content (base64)
    ///   - nonce: Encryption nonce (base64)
    ///   - attachment: Optional attachment
    static func received(
        messageId: String,
        senderId: WhisperID,
        recipientId: WhisperID,
        groupId: String? = nil,
        msgType: MessageContentType = .text,
        timestamp: Date,
        ciphertext: String,
        nonce: String,
        attachment: Attachment? = nil
    ) -> Message {
        let conversationId: String
        if let groupId = groupId {
            conversationId = "group_\(groupId)"
        } else {
            conversationId = "direct_\(senderId.rawValue)"
        }

        return Message(
            messageId: messageId,
            conversationId: conversationId,
            senderId: senderId,
            recipientId: recipientId,
            groupId: groupId,
            msgType: msgType,
            timestamp: timestamp,
            status: .delivered,
            ciphertext: ciphertext,
            nonce: nonce,
            plaintext: nil,
            deliveredAt: Date(),
            readAt: nil,
            retryCount: 0,
            errorMessage: nil,
            attachment: attachment
        )
    }

    // MARK: - Full Initializer

    init(
        messageId: String,
        conversationId: String,
        senderId: WhisperID,
        recipientId: WhisperID?,
        groupId: String?,
        msgType: MessageContentType,
        timestamp: Date,
        status: MessageStatus,
        ciphertext: String,
        nonce: String,
        plaintext: String?,
        deliveredAt: Date?,
        readAt: Date?,
        retryCount: Int,
        errorMessage: String?,
        attachment: Attachment?
    ) {
        self.messageId = messageId
        self.conversationId = conversationId
        self.senderId = senderId
        self.recipientId = recipientId
        self.groupId = groupId
        self.msgType = msgType
        self.timestamp = timestamp
        self.status = status
        self.ciphertext = ciphertext
        self.nonce = nonce
        self.plaintext = plaintext
        self.deliveredAt = deliveredAt
        self.readAt = readAt
        self.retryCount = retryCount
        self.errorMessage = errorMessage
        self.attachment = attachment
    }

    // MARK: - Mutating Methods

    /// Update message status
    mutating func updateStatus(_ newStatus: MessageStatus) {
        self.status = newStatus

        switch newStatus {
        case .delivered:
            self.deliveredAt = Date()
        case .read:
            self.readAt = Date()
        case .failed:
            self.retryCount += 1
        default:
            break
        }
    }

    /// Set decrypted plaintext
    mutating func setPlaintext(_ text: String) {
        self.plaintext = text
    }

    /// Mark as failed with error
    mutating func markFailed(error: String) {
        self.status = .failed
        self.errorMessage = error
        self.retryCount += 1
    }
}

// MARK: - Message Status

enum MessageStatus: String, Codable {
    /// Queued for sending
    case queued

    /// Currently being sent
    case sending

    /// Sent to server
    case sent

    /// Delivered to recipient
    case delivered

    /// Read by recipient
    case read

    /// Failed to send
    case failed

    var icon: String {
        switch self {
        case .queued: return "clock"
        case .sending: return "arrow.up.circle"
        case .sent: return "checkmark"
        case .delivered: return "checkmark.circle"
        case .read: return "checkmark.circle.fill"
        case .failed: return "exclamationmark.circle"
        }
    }
}

// MARK: - Message Content Type

enum MessageContentType: String, Codable {
    /// Plain text message
    case text

    /// Image attachment
    case image

    /// Video attachment
    case video

    /// Audio attachment
    case audio

    /// File attachment
    case file

    /// Voice note
    case voiceNote

    /// Location share
    case location

    /// Contact share
    case contact

    /// System message (e.g., "User joined group")
    case system
}

// MARK: - Equatable

extension Message {
    static func == (lhs: Message, rhs: Message) -> Bool {
        lhs.messageId == rhs.messageId
    }
}
