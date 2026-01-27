import Foundation

/// Message request from unknown sender
struct MessageRequest: Codable, Identifiable, Hashable {
    let id: String
    let senderId: String  // WhisperID of sender
    let firstMessageId: String
    let firstMessagePreview: String  // "[Encrypted]" or partial content
    let messageCount: Int
    let firstReceivedAt: Date
    var lastReceivedAt: Date
    var status: MessageRequestStatus
    var senderEncPublicKey: Data?  // Sender's encryption public key (for accepting without QR)

    init(
        id: String = UUID().uuidString,
        senderId: String,
        firstMessageId: String,
        firstMessagePreview: String = "[New message request]",
        messageCount: Int = 1,
        firstReceivedAt: Date = Date(),
        lastReceivedAt: Date = Date(),
        status: MessageRequestStatus = .pending,
        senderEncPublicKey: Data? = nil
    ) {
        self.id = id
        self.senderId = senderId
        self.firstMessageId = firstMessageId
        self.firstMessagePreview = firstMessagePreview
        self.messageCount = messageCount
        self.firstReceivedAt = firstReceivedAt
        self.lastReceivedAt = lastReceivedAt
        self.status = status
        self.senderEncPublicKey = senderEncPublicKey
    }
    
    // Hashable
    func hash(into hasher: inout Hasher) {
        hasher.combine(senderId)
    }
    
    static func == (lhs: MessageRequest, rhs: MessageRequest) -> Bool {
        lhs.senderId == rhs.senderId
    }
}

enum MessageRequestStatus: String, Codable {
    case pending    // Awaiting user action
    case accepted   // User accepted, added to contacts
    case blocked    // User blocked this sender
}

/// Blocked user record
struct BlockedUser: Codable, Identifiable {
    let id: String
    let whisperId: String
    let blockedAt: Date
    var reason: String?
    
    init(whisperId: String, reason: String? = nil) {
        self.id = UUID().uuidString
        self.whisperId = whisperId
        self.blockedAt = Date()
        self.reason = reason
    }
}
