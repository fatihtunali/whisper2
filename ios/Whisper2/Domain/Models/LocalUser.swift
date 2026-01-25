import Foundation

/// LocalUser - Represents the current authenticated user
/// Keys are stored in Keychain, this model holds references and metadata

struct LocalUser: Codable, Equatable {

    // MARK: - Properties

    /// Unique WhisperID for this user
    let whisperId: WhisperID

    /// Device identifier (UUID)
    let deviceId: String

    /// Current session token (JWT)
    var sessionToken: String?

    /// Session token expiry date
    var sessionExpiry: Date?

    /// When the user account was created
    let createdAt: Date

    /// Last successful authentication time
    var lastAuthAt: Date?

    /// Push notification token (APNS)
    var pushToken: String?

    /// VoIP push token (PushKit)
    var voipToken: String?

    // MARK: - Keychain References
    // These are keys for looking up the actual values in Keychain

    /// Keychain key for encryption private key
    var encPrivateKeyRef: String {
        Constants.StorageKey.encPrivateKey
    }

    /// Keychain key for encryption public key
    var encPublicKeyRef: String {
        Constants.StorageKey.encPublicKey
    }

    /// Keychain key for signing private key
    var signPrivateKeyRef: String {
        Constants.StorageKey.signPrivateKey
    }

    /// Keychain key for signing public key
    var signPublicKeyRef: String {
        Constants.StorageKey.signPublicKey
    }

    /// Keychain key for contacts encryption key
    var contactsKeyRef: String {
        Constants.StorageKey.contactsKey
    }

    // MARK: - Computed Properties

    /// Whether the user has an active session
    var isAuthenticated: Bool {
        guard let token = sessionToken, !token.isEmpty else {
            return false
        }

        if let expiry = sessionExpiry {
            return expiry > Date()
        }

        return true
    }

    /// Whether the session is about to expire (within 5 minutes)
    var sessionExpiringSoon: Bool {
        guard let expiry = sessionExpiry else {
            return false
        }
        return expiry.timeIntervalSinceNow < 300
    }

    // MARK: - Initialization

    /// Create a new local user after registration
    /// - Parameters:
    ///   - whisperId: The user's WhisperID
    ///   - deviceId: The device identifier
    init(whisperId: WhisperID, deviceId: String) {
        self.whisperId = whisperId
        self.deviceId = deviceId
        self.createdAt = Date()
    }

    /// Create from stored data
    /// - Parameters:
    ///   - whisperId: The user's WhisperID
    ///   - deviceId: The device identifier
    ///   - sessionToken: Current session token
    ///   - sessionExpiry: Token expiry date
    ///   - createdAt: Account creation date
    ///   - lastAuthAt: Last authentication time
    ///   - pushToken: APNS push token
    ///   - voipToken: VoIP push token
    init(
        whisperId: WhisperID,
        deviceId: String,
        sessionToken: String?,
        sessionExpiry: Date?,
        createdAt: Date,
        lastAuthAt: Date?,
        pushToken: String?,
        voipToken: String?
    ) {
        self.whisperId = whisperId
        self.deviceId = deviceId
        self.sessionToken = sessionToken
        self.sessionExpiry = sessionExpiry
        self.createdAt = createdAt
        self.lastAuthAt = lastAuthAt
        self.pushToken = pushToken
        self.voipToken = voipToken
    }

    // MARK: - Mutating Methods

    /// Update session credentials
    mutating func updateSession(token: String, expiry: Date) {
        self.sessionToken = token
        self.sessionExpiry = expiry
        self.lastAuthAt = Date()
    }

    /// Clear session (logout)
    mutating func clearSession() {
        self.sessionToken = nil
        self.sessionExpiry = nil
    }

    /// Update push tokens
    mutating func updatePushTokens(push: String?, voip: String?) {
        if let push = push {
            self.pushToken = push
        }
        if let voip = voip {
            self.voipToken = voip
        }
    }
}

// MARK: - UserDefaults Keys

extension LocalUser {

    /// UserDefaults key for storing local user data
    static let storageKey = "whisper2.local.user"
}
