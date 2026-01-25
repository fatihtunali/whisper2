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

        // HKDF info strings (must match server)
        static let encryptionDomain = "whisper/enc"
        static let signingDomain = "whisper/sign"
        static let contactsDomain = "whisper/contacts"

        // WhisperID prefix
        static let whisperIdPrefix = "WH2-"
    }

    // MARK: - Message Types
    enum MessageType {
        // Auth
        static let registerBegin = "register_begin"
        static let registerChallenge = "register_challenge"
        static let registerProof = "register_proof"
        static let registerAck = "register_ack"
        static let sessionRefresh = "session_refresh"
        static let logout = "logout"
        static let updateTokens = "update_tokens"

        // Messaging
        static let sendMessage = "send_message"
        static let messageReceived = "message_received"
        static let deliveryReceipt = "delivery_receipt"
        static let fetchPending = "fetch_pending"
        static let pendingMessages = "pending_messages"

        // Groups
        static let groupCreate = "group_create"
        static let groupUpdate = "group_update"
        static let groupSendMessage = "group_send_message"
        static let groupCreated = "group_created"
        static let groupUpdated = "group_updated"

        // Calls
        static let getTurnCredentials = "get_turn_credentials"
        static let turnCredentials = "turn_credentials"
        static let callInitiate = "call_initiate"
        static let callIncoming = "call_incoming"
        static let callRinging = "call_ringing"
        static let callAnswer = "call_answer"
        static let callIceCandidate = "call_ice_candidate"
        static let callEnd = "call_end"

        // System
        static let ping = "ping"
        static let pong = "pong"
        static let error = "error"
        static let ack = "ack"
        static let forceLogout = "force_logout"
    }

    // MARK: - Error Codes (match server)
    enum ErrorCode {
        static let invalidPayload = "INVALID_PAYLOAD"
        static let unauthorized = "UNAUTHORIZED"
        static let notFound = "NOT_FOUND"
        static let rateLimited = "RATE_LIMITED"
        static let serverError = "SERVER_ERROR"
        static let invalidSignature = "INVALID_SIGNATURE"
        static let invalidChallenge = "INVALID_CHALLENGE"
        static let sessionExpired = "SESSION_EXPIRED"
        static let deviceKicked = "DEVICE_KICKED"
        static let callNotFound = "CALL_NOT_FOUND"
        static let callNotParty = "NOT_CALL_PARTY"
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
