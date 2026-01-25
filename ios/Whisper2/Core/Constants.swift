import Foundation

/// Whisper2 Core Constants
/// All configuration values in one place

enum Constants {

    // MARK: - Protocol Version
    static let protocolVersion = 1
    static let cryptoVersion = 1

    // MARK: - Server Configuration
    enum Server {
        static let baseURL = "https://whisper2.aiakademiturkiye.com"
        static let wsURL = "wss://whisper2.aiakademiturkiye.com"
        static let port = 3051

        static var httpBaseURL: URL {
            URL(string: "\(baseURL):\(port)")!
        }

        static var webSocketURL: URL {
            URL(string: "\(wsURL):\(port)")!
        }
    }

    // MARK: - TURN Server Configuration
    enum Turn {
        static let host = "turn2.aiakademiturkiye.com"
        static let port = 3478
        static let tlsPort = 5349

        static var urls: [String] {
            [
                "turn:\(host):\(port)?transport=udp",
                "turn:\(host):\(port)?transport=tcp",
                "turns:\(host):\(tlsPort)?transport=tcp"
            ]
        }
    }

    // MARK: - Crypto Constants
    enum Crypto {
        static let nonceLength = 24
        static let keyLength = 32
        static let publicKeyLength = 32
        static let secretKeyLength = 32
        static let signatureLength = 64
        static let seedLength = 32
        static let bip39SeedLength = 64  // Full BIP39 seed is 64 bytes
        static let challengeSize = 32

        // HKDF parameters (MUST match across all platforms for recovery)
        static let hkdfSalt = "whisper"
        static let encryptionDomain = "whisper/enc"
        static let signingDomain = "whisper/sign"
        static let contactsDomain = "whisper/contacts"

        // WhisperID prefix and format (must match server)
        // Format: WSP-XXXX-XXXX-XXXX (Base32: A-Z, 2-7)
        static let whisperIdPrefix = "WSP-"
        static let whisperIdBase32Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    }

    // MARK: - Message Types (must match server protocol.ts MessageTypes)
    enum MessageType {
        // Auth
        static let registerBegin = "register_begin"
        static let registerChallenge = "register_challenge"
        static let registerProof = "register_proof"
        static let registerAck = "register_ack"
        static let sessionRefresh = "session_refresh"
        static let sessionRefreshAck = "session_refresh_ack"
        static let logout = "logout"
        static let updateTokens = "update_tokens"

        // Messaging
        static let sendMessage = "send_message"
        static let messageAccepted = "message_accepted"
        static let messageReceived = "message_received"
        static let deliveryReceipt = "delivery_receipt"
        static let messageDelivered = "message_delivered"
        static let fetchPending = "fetch_pending"
        static let pendingMessages = "pending_messages"

        // Groups
        static let groupCreate = "group_create"
        static let groupEvent = "group_event"  // Server sends this for all group changes
        static let groupUpdate = "group_update"
        static let groupSendMessage = "group_send_message"

        // Calls
        static let getTurnCredentials = "get_turn_credentials"
        static let turnCredentials = "turn_credentials"
        static let callInitiate = "call_initiate"
        static let callIncoming = "call_incoming"
        static let callRinging = "call_ringing"
        static let callAnswer = "call_answer"
        static let callIceCandidate = "call_ice_candidate"
        static let callEnd = "call_end"

        // Presence & Typing
        static let presenceUpdate = "presence_update"
        static let typing = "typing"
        static let typingNotification = "typing_notification"

        // System
        static let ping = "ping"
        static let pong = "pong"
        static let error = "error"
        static let forceLogout = "force_logout"
    }

    // MARK: - Error Codes (must match server protocol.ts ErrorCode)
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

    // MARK: - Timeouts
    enum Timeout {
        static let wsConnect: TimeInterval = 10
        static let wsReconnectBase: TimeInterval = 1
        static let wsReconnectMax: TimeInterval = 30
        static let httpRequest: TimeInterval = 30
        static let callStateTTL: TimeInterval = 180
        static let pingInterval: TimeInterval = 30
        static let pongTimeout: TimeInterval = 10
    }

    // MARK: - Storage Keys
    enum StorageKey {
        static let encPrivateKey = "whisper2.enc.private"
        static let encPublicKey = "whisper2.enc.public"
        static let signPrivateKey = "whisper2.sign.private"
        static let signPublicKey = "whisper2.sign.public"
        static let sessionToken = "whisper2.session.token"
        static let sessionExpiry = "whisper2.session.expiry"
        static let deviceId = "whisper2.device.id"
        static let whisperId = "whisper2.whisper.id"
        static let contactsKey = "whisper2.contacts.key"
        static let pushToken = "whisper2.push.token"
        static let voipToken = "whisper2.voip.token"
        static let appLockEnabled = "whisper2.app.lock"
    }

    // MARK: - Limits
    enum Limits {
        static let maxMessageSize = 64 * 1024 // 64KB
        static let maxAttachmentSize = 100 * 1024 * 1024 // 100MB
        static let maxGroupMembers = 256
        static let maxDisplayNameLength = 64
        static let maxGroupTitleLength = 128
        static let outboxMaxRetries = 5
    }

    // MARK: - UI
    enum UI {
        static let animationDuration: TimeInterval = 0.25
        static let debounceInterval: TimeInterval = 0.3
    }
}
