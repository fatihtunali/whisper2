import Foundation

/// Whisper2 Constants - matches server protocol.ts
enum Constants {
    
    // MARK: - Protocol Versions (from server protocol.ts)
    static let protocolVersion = 1
    static let cryptoVersion = 1
    
    // MARK: - Server
    static let wsURL = "wss://whisper2.aiakademiturkiye.com/ws"
    static let baseURL = "https://whisper2.aiakademiturkiye.com"
    
    // MARK: - Timestamp (from server protocol.ts)
    static let timestampSkewMs: Int64 = 600_000  // ±10 minutes
    
    // MARK: - Crypto Sizes
    static let nonceSize = 24
    static let keySize = 32
    static let signatureSize = 64
    static let challengeSize = 32
    static let bip39SeedLength = 64
    
    // MARK: - HKDF (FROZEN - from server test-vectors.ts)
    static let hkdfSalt = "whisper"
    static let encryptionDomain = "whisper/enc"
    static let signingDomain = "whisper/sign"
    static let contactsDomain = "whisper/contacts"
    
    // MARK: - BIP39
    static let bip39Iterations = 2048
    static let bip39Salt = "mnemonic"

    // MARK: - Validation Limits (from server)
    static let maxGroupMembers = 50
    static let maxAttachmentSize = 100 * 1024 * 1024  // 100MB
    static let maxBackupSize = 256 * 1024  // 256KB

    // MARK: - Session & Timing (from server)
    static let sessionTtlDays = 7
    static let heartbeatIntervalMs: Int64 = 30_000
    static let reconnectBaseDelayMs: Int64 = 1_000
    static let reconnectMaxDelayMs: Int64 = 30_000
    static let reconnectMaxAttempts = 5
    static let callRingTimeoutMs: Int64 = 30_000

    // MARK: - WhisperID Format (from server protocol.ts)
    static let whisperIdRegex = "^WSP-[A-Z2-7]{4}-[A-Z2-7]{4}-[A-Z2-7]{4}$"
    
    // MARK: - Keychain Keys
    static let keychainService = "com.whisper2.app"
    
    enum StorageKey {
        static let encPrivateKey = "whisper2.enc.private"
        static let encPublicKey = "whisper2.enc.public"
        static let signPrivateKey = "whisper2.sign.private"
        static let signPublicKey = "whisper2.sign.public"
        static let contactsKey = "whisper2.contacts.key"
        static let sessionToken = "whisper2.session.token"
        static let deviceId = "whisper2.device.id"
        static let whisperId = "whisper2.whisper.id"
        static let mnemonic = "whisper2.mnemonic"
    }
    
    // MARK: - Message Types (from server protocol.ts MessageTypes)
    enum MessageType {
        // Auth
        static let registerBegin = "register_begin"
        static let registerChallenge = "register_challenge"
        static let registerProof = "register_proof"
        static let registerAck = "register_ack"
        static let sessionRefresh = "session_refresh"
        static let sessionRefreshAck = "session_refresh_ack"
        static let logout = "logout"
        
        // Messaging
        static let sendMessage = "send_message"
        static let messageAccepted = "message_accepted"
        static let messageReceived = "message_received"
        static let deliveryReceipt = "delivery_receipt"
        static let messageDelivered = "message_delivered"
        static let fetchPending = "fetch_pending"
        static let pendingMessages = "pending_messages"
        static let deleteMessage = "delete_message"
        static let messageDeleted = "message_deleted"
        
        // Groups
        static let groupCreate = "group_create"
        static let groupEvent = "group_event"
        static let groupUpdate = "group_update"
        static let groupSendMessage = "group_send_message"
        static let groupInviteResponse = "group_invite_response"
        
        // Calls
        static let getTurnCredentials = "get_turn_credentials"
        static let turnCredentials = "turn_credentials"
        static let callInitiate = "call_initiate"
        static let callIncoming = "call_incoming"
        static let callAnswer = "call_answer"
        static let callIceCandidate = "call_ice_candidate"
        static let callEnd = "call_end"
        static let callRinging = "call_ringing"
        
        // Push tokens
        static let updateTokens = "update_tokens"
        
        // Presence
        static let presenceUpdate = "presence_update"
        static let typing = "typing"
        static let typingNotification = "typing_notification"
        
        // Ping/Pong
        static let ping = "ping"
        static let pong = "pong"

        // Account Management
        static let deleteAccount = "delete_account"
        static let accountDeleted = "account_deleted"

        // Error
        static let error = "error"
    }
    
    // MARK: - Error Codes (from server protocol.ts ErrorCode)
    enum ErrorCode {
        static let notRegistered = "NOT_REGISTERED"
        static let authFailed = "AUTH_FAILED"
        static let invalidPayload = "INVALID_PAYLOAD"
        static let invalidTimestamp = "INVALID_TIMESTAMP"
        static let rateLimited = "RATE_LIMITED"
        static let userBanned = "USER_BANNED"
        static let notFound = "NOT_FOUND"
        static let forbidden = "FORBIDDEN"
        static let internalError = "INTERNAL_ERROR"
    }
}
