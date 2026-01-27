import Foundation

/// Message status
enum MessageStatus: String, Codable {
    case pending    // Not yet sent
    case sent       // Sent to server
    case delivered  // Delivered to recipient
    case read       // Read by recipient
    case failed     // Failed to send
}

/// Message direction
enum MessageDirection: String, Codable {
    case incoming
    case outgoing
}

/// Message model
struct Message: Codable, Identifiable, Hashable {
    let id: String
    let conversationId: String
    let from: String
    let to: String
    let content: String
    let contentType: String
    let timestamp: Date
    var status: MessageStatus
    let direction: MessageDirection
    var replyToId: String?
    var attachmentId: String?
    let createdAt: Date
    var updatedAt: Date
    
    init(
        id: String = UUID().uuidString.lowercased(),
        conversationId: String,
        from: String,
        to: String,
        content: String,
        contentType: String = "text",
        timestamp: Date = Date(),
        status: MessageStatus = .pending,
        direction: MessageDirection,
        replyToId: String? = nil,
        attachmentId: String? = nil,
        createdAt: Date = Date(),
        updatedAt: Date = Date()
    ) {
        self.id = id
        self.conversationId = conversationId
        self.from = from
        self.to = to
        self.content = content
        self.contentType = contentType
        self.timestamp = timestamp
        self.status = status
        self.direction = direction
        self.replyToId = replyToId
        self.attachmentId = attachmentId
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }
    
    /// Timestamp in milliseconds
    var timestampMs: Int64 {
        Int64(timestamp.timeIntervalSince1970 * 1000)
    }
    
    /// Check if message is outgoing
    var isOutgoing: Bool {
        direction == .outgoing
    }
    
    /// Formatted time string
    var timeString: String {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm"
        return formatter.string(from: timestamp)
    }
    
    // Hashable
    func hash(into hasher: inout Hasher) {
        hasher.combine(id)
    }
    
    static func == (lhs: Message, rhs: Message) -> Bool {
        lhs.id == rhs.id
    }
}

/// Outgoing message to be sent
struct OutgoingMessage {
    let id: String
    let to: String
    let content: String
    let contentType: String
    var replyToId: String?
    var attachmentId: String?
    
    init(
        id: String = UUID().uuidString.lowercased(),
        to: String,
        content: String,
        contentType: String = "text",
        replyToId: String? = nil,
        attachmentId: String? = nil
    ) {
        self.id = id
        self.to = to
        self.content = content
        self.contentType = contentType
        self.replyToId = replyToId
        self.attachmentId = attachmentId
    }
}
