import Foundation

/// Disappearing message timer options
enum DisappearingMessageTimer: String, Codable, CaseIterable {
    case off = "off"
    case oneDay = "24h"
    case sevenDays = "7d"
    case thirtyDays = "30d"

    var displayName: String {
        switch self {
        case .off: return "Off"
        case .oneDay: return "24 hours"
        case .sevenDays: return "7 days"
        case .thirtyDays: return "30 days"
        }
    }

    var timeInterval: TimeInterval? {
        switch self {
        case .off: return nil
        case .oneDay: return 86_400        // 24 * 60 * 60
        case .sevenDays: return 604_800    // 7 * 24 * 60 * 60
        case .thirtyDays: return 2_592_000 // 30 * 24 * 60 * 60
        }
    }

    var icon: String {
        switch self {
        case .off: return "infinity"
        case .oneDay: return "clock"
        case .sevenDays: return "calendar"
        case .thirtyDays: return "calendar.badge.clock"
        }
    }
}

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
    var chatThemeId: String?    // Custom chat theme
    var disappearingMessageTimer: DisappearingMessageTimer  // Disappearing messages setting
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
        chatThemeId: String? = nil,
        disappearingMessageTimer: DisappearingMessageTimer = .off,
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
        self.chatThemeId = chatThemeId
        self.disappearingMessageTimer = disappearingMessageTimer
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
