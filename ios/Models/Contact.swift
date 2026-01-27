import Foundation

/// Contact information
struct Contact: Codable, Identifiable, Hashable {
    let id: String
    let whisperId: String
    let encPublicKey: Data
    var nickname: String?
    var avatarUrl: String?
    var isBlocked: Bool
    let addedAt: Date
    var lastSeen: Date?
    var isOnline: Bool
    
    init(
        id: String = UUID().uuidString,
        whisperId: String,
        encPublicKey: Data,
        nickname: String? = nil,
        avatarUrl: String? = nil,
        isBlocked: Bool = false,
        addedAt: Date = Date(),
        lastSeen: Date? = nil,
        isOnline: Bool = false
    ) {
        self.id = id
        self.whisperId = whisperId
        self.encPublicKey = encPublicKey
        self.nickname = nickname
        self.avatarUrl = avatarUrl
        self.isBlocked = isBlocked
        self.addedAt = addedAt
        self.lastSeen = lastSeen
        self.isOnline = isOnline
    }
    
    /// Display name: nickname or whisper ID
    var displayName: String {
        nickname ?? whisperId
    }
    
    /// Base64 encoded public key
    var encPublicKeyBase64: String {
        encPublicKey.base64EncodedString()
    }
    
    // Hashable
    func hash(into hasher: inout Hasher) {
        hasher.combine(whisperId)
    }
    
    static func == (lhs: Contact, rhs: Contact) -> Bool {
        lhs.whisperId == rhs.whisperId
    }
}

/// Contact for creating/updating
struct ContactInput {
    let whisperId: String
    let encPublicKey: Data
    var nickname: String?
}
