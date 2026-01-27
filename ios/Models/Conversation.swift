import Foundation

/// Conversation/Chat metadata
struct Conversation: Codable, Identifiable, Hashable {
    let id: String
    let peerId: String          // WhisperId of the other person
    var peerNickname: String?
    var lastMessage: String?
    var lastMessageTime: Date?
    var unreadCount: Int
    var isTyping: Bool
    var isPinned: Bool
    var isMuted: Bool
    let createdAt: Date
    var updatedAt: Date
    
    init(
        id: String = UUID().uuidString,
        peerId: String,
        peerNickname: String? = nil,
        lastMessage: String? = nil,
        lastMessageTime: Date? = nil,
        unreadCount: Int = 0,
        isTyping: Bool = false,
        isPinned: Bool = false,
        isMuted: Bool = false,
        createdAt: Date = Date(),
        updatedAt: Date = Date()
    ) {
        self.id = id
        self.peerId = peerId
        self.peerNickname = peerNickname
        self.lastMessage = lastMessage
        self.lastMessageTime = lastMessageTime
        self.unreadCount = unreadCount
        self.isTyping = isTyping
        self.isPinned = isPinned
        self.isMuted = isMuted
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }
    
    /// Display name for the conversation
    var displayName: String {
        peerNickname ?? peerId
    }
    
    /// Has unread messages
    var hasUnread: Bool {
        unreadCount > 0
    }
    
    /// Formatted last message time
    var lastMessageTimeString: String? {
        guard let time = lastMessageTime else { return nil }
        
        let formatter = DateFormatter()
        let calendar = Calendar.current
        
        if calendar.isDateInToday(time) {
            formatter.dateFormat = "HH:mm"
        } else if calendar.isDateInYesterday(time) {
            return "Yesterday"
        } else if calendar.isDate(time, equalTo: Date(), toGranularity: .weekOfYear) {
            formatter.dateFormat = "EEEE"
        } else {
            formatter.dateFormat = "dd/MM/yy"
        }
        
        return formatter.string(from: time)
    }
    
    // Hashable
    func hash(into hasher: inout Hasher) {
        hasher.combine(id)
    }
    
    static func == (lhs: Conversation, rhs: Conversation) -> Bool {
        lhs.id == rhs.id
    }
}
