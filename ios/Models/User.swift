import Foundation

/// Local user with all keys and session info
struct LocalUser: Codable {
    let whisperId: String
    let encPublicKey: Data
    let encPrivateKey: Data
    let signPublicKey: Data
    let signPrivateKey: Data
    let seedPhrase: String
    var sessionToken: String?
    var sessionExpiresAt: Int64?
    let deviceId: String
    let createdAt: Date
    
    init(
        whisperId: String,
        encPublicKey: Data,
        encPrivateKey: Data,
        signPublicKey: Data,
        signPrivateKey: Data,
        seedPhrase: String,
        sessionToken: String? = nil,
        sessionExpiresAt: Int64? = nil,
        deviceId: String,
        createdAt: Date = Date()
    ) {
        self.whisperId = whisperId
        self.encPublicKey = encPublicKey
        self.encPrivateKey = encPrivateKey
        self.signPublicKey = signPublicKey
        self.signPrivateKey = signPrivateKey
        self.seedPhrase = seedPhrase
        self.sessionToken = sessionToken
        self.sessionExpiresAt = sessionExpiresAt
        self.deviceId = deviceId
        self.createdAt = createdAt
    }
    
    /// Check if session is valid
    var hasValidSession: Bool {
        guard let token = sessionToken, !token.isEmpty,
              let expiresAt = sessionExpiresAt else {
            return false
        }
        return Double(expiresAt) > Date().timeIntervalSince1970 * 1000
    }
    
    /// Base64 encoded public keys for protocol
    var encPublicKeyBase64: String {
        encPublicKey.base64EncodedString()
    }
    
    var signPublicKeyBase64: String {
        signPublicKey.base64EncodedString()
    }
}

/// Keys derived from seed phrase
struct DerivedKeys {
    let encPublicKey: Data
    let encPrivateKey: Data
    let signPublicKey: Data
    let signPrivateKey: Data
    let contactsKey: Data
}
