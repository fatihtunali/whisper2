import Foundation

/// Whisper2 WebSocket Message Models
/// All WS message types matching server protocol.ts

// MARK: - Protocol Constants

enum Protocol {
    static let version = 1
    static let cryptoVersion = 1
}

// MARK: - Base Frame

/// Canonical WebSocket frame structure
/// All messages follow this format: { type, payload, requestId? }
struct WSFrame<T: Codable>: Codable {
    let type: String
    let payload: T
    var requestId: String?

    init(type: String, payload: T, requestId: String? = nil) {
        self.type = type
        self.payload = payload
        self.requestId = requestId
    }
}

/// Raw frame for initial decoding (payload as raw JSON)
struct WSRawFrame: Codable {
    let type: String
    let payload: AnyCodable
    var requestId: String?
}

/// Type-erased Codable wrapper for dynamic JSON
struct AnyCodable: Codable {
    let value: Any

    init(_ value: Any) {
        self.value = value
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()

        if container.decodeNil() {
            value = NSNull()
        } else if let bool = try? container.decode(Bool.self) {
            value = bool
        } else if let int = try? container.decode(Int.self) {
            value = int
        } else if let double = try? container.decode(Double.self) {
            value = double
        } else if let string = try? container.decode(String.self) {
            value = string
        } else if let array = try? container.decode([AnyCodable].self) {
            value = array.map { $0.value }
        } else if let dict = try? container.decode([String: AnyCodable].self) {
            value = dict.mapValues { $0.value }
        } else {
            throw DecodingError.dataCorruptedError(in: container, debugDescription: "Cannot decode value")
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()

        switch value {
        case is NSNull:
            try container.encodeNil()
        case let bool as Bool:
            try container.encode(bool)
        case let int as Int:
            try container.encode(int)
        case let double as Double:
            try container.encode(double)
        case let string as String:
            try container.encode(string)
        case let array as [Any]:
            try container.encode(array.map { AnyCodable($0) })
        case let dict as [String: Any]:
            try container.encode(dict.mapValues { AnyCodable($0) })
        default:
            throw EncodingError.invalidValue(value, EncodingError.Context(codingPath: encoder.codingPath, debugDescription: "Cannot encode value"))
        }
    }
}

// MARK: - Error Response

/// Standard error response from server
struct WSError: Codable {
    let code: String
    let message: String
    var requestId: String?
}

// MARK: - Versioned Payload Protocol

/// Base protocol for client messages requiring version info
protocol VersionedPayload: Codable {
    var protocolVersion: Int { get }
    var cryptoVersion: Int { get }
}

// MARK: - Authentication Messages

/// Step 1: Client -> Server - Begin registration
struct RegisterBeginPayload: VersionedPayload {
    let protocolVersion: Int
    let cryptoVersion: Int
    var whisperId: String? // Present if recovery
    let deviceId: String   // UUIDv4
    let platform: String   // "ios" or "android"

    init(deviceId: String, whisperId: String? = nil) {
        self.protocolVersion = Protocol.version
        self.cryptoVersion = Protocol.cryptoVersion
        self.deviceId = deviceId
        self.whisperId = whisperId
        self.platform = "ios"
    }
}

/// Step 2: Server -> Client - Challenge
struct RegisterChallengePayload: Codable {
    let challengeId: String  // uuid
    let challenge: String    // base64(32 bytes)
    let expiresAt: Int64     // timestamp ms
}

/// Step 3: Client -> Server - Proof
struct RegisterProofPayload: VersionedPayload {
    let protocolVersion: Int
    let cryptoVersion: Int
    let challengeId: String
    let deviceId: String
    let platform: String
    var whisperId: String?      // Present if recovery
    let encPublicKey: String    // base64
    let signPublicKey: String   // base64
    let signature: String       // base64(ed25519 signature)
    var pushToken: String?
    var voipToken: String?      // iOS only

    init(
        challengeId: String,
        deviceId: String,
        encPublicKey: String,
        signPublicKey: String,
        signature: String,
        whisperId: String? = nil,
        pushToken: String? = nil,
        voipToken: String? = nil
    ) {
        self.protocolVersion = Protocol.version
        self.cryptoVersion = Protocol.cryptoVersion
        self.challengeId = challengeId
        self.deviceId = deviceId
        self.platform = "ios"
        self.whisperId = whisperId
        self.encPublicKey = encPublicKey
        self.signPublicKey = signPublicKey
        self.signature = signature
        self.pushToken = pushToken
        self.voipToken = voipToken
    }
}

/// Step 4: Server -> Client - Registration acknowledgment
struct RegisterAckPayload: Codable {
    let success: Bool
    let whisperId: String
    let sessionToken: String
    let sessionExpiresAt: Int64  // timestamp ms
    let serverTime: Int64        // timestamp ms
}

// MARK: - Session Management

/// Session refresh request
struct SessionRefreshPayload: VersionedPayload {
    let protocolVersion: Int
    let cryptoVersion: Int
    let sessionToken: String

    init(sessionToken: String) {
        self.protocolVersion = Protocol.version
        self.cryptoVersion = Protocol.cryptoVersion
        self.sessionToken = sessionToken
    }
}

/// Session refresh response
struct SessionRefreshAckPayload: Codable {
    let sessionToken: String
    let sessionExpiresAt: Int64
    let serverTime: Int64
}

/// Logout request
struct LogoutPayload: VersionedPayload {
    let protocolVersion: Int
    let cryptoVersion: Int
    let sessionToken: String

    init(sessionToken: String) {
        self.protocolVersion = Protocol.version
        self.cryptoVersion = Protocol.cryptoVersion
        self.sessionToken = sessionToken
    }
}

/// Force logout notification (another device logged in)
struct ForceLogoutPayload: Codable {
    let reason: String
}

// MARK: - Ping/Pong

struct PingPayload: Codable {
    let timestamp: Int64

    init() {
        self.timestamp = Int64(Date().timeIntervalSince1970 * 1000)
    }
}

struct PongPayload: Codable {
    let timestamp: Int64
    let serverTime: Int64
}

// MARK: - Messaging

/// Message types (for WebSocket messages)
enum WSMessageContentType: String, Codable {
    case text
    case image
    case voice
    case file
    case system
}

/// Attachment pointer for encrypted files
struct AttachmentPointer: Codable {
    let objectKey: String        // S3 path
    let contentType: String      // Original content type
    let ciphertextSize: Int      // Size after encryption
    let fileNonce: String        // base64(24 bytes)
    let fileKeyBox: FileKeyBox   // Encrypted file key

    struct FileKeyBox: Codable {
        let nonce: String        // base64(24 bytes)
        let ciphertext: String   // base64
    }
}

/// Send message request
struct SendMessagePayload: VersionedPayload {
    let protocolVersion: Int
    let cryptoVersion: Int
    let sessionToken: String
    let messageId: String        // uuid
    let from: String             // sender whisperId
    let to: String               // recipient whisperId
    let msgType: String          // text/image/voice/file/system
    let timestamp: Int64         // ms
    let nonce: String            // base64(24 bytes)
    let ciphertext: String       // base64
    let sig: String              // base64
    var replyTo: String?         // optional uuid
    var reactions: [String: [String]]?  // emoji -> whisperId[]
    var attachment: AttachmentPointer?

    init(
        sessionToken: String,
        messageId: String,
        from: String,
        to: String,
        msgType: WSMessageContentType,
        timestamp: Int64,
        nonce: String,
        ciphertext: String,
        sig: String,
        replyTo: String? = nil,
        attachment: AttachmentPointer? = nil
    ) {
        self.protocolVersion = Protocol.version
        self.cryptoVersion = Protocol.cryptoVersion
        self.sessionToken = sessionToken
        self.messageId = messageId
        self.from = from
        self.to = to
        self.msgType = msgType.rawValue
        self.timestamp = timestamp
        self.nonce = nonce
        self.ciphertext = ciphertext
        self.sig = sig
        self.replyTo = replyTo
        self.attachment = attachment
    }
}

/// Message accepted acknowledgment
struct MessageAcceptedPayload: Codable {
    let messageId: String
    let status: String  // "sent"
}

/// Incoming message notification
struct MessageReceivedPayload: Codable {
    let messageId: String
    var groupId: String?        // Present for group messages
    let from: String
    let to: String
    let msgType: String
    let timestamp: Int64
    let nonce: String
    let ciphertext: String
    let sig: String
    var replyTo: String?
    var reactions: [String: [String]]?
    var attachment: AttachmentPointer?
}

/// Delivery receipt (sent by recipient)
struct DeliveryReceiptPayload: VersionedPayload {
    let protocolVersion: Int
    let cryptoVersion: Int
    let sessionToken: String
    let messageId: String
    let from: String             // recipient whisperId
    let to: String               // sender whisperId
    let status: String           // "delivered" or "read"
    let timestamp: Int64

    init(
        sessionToken: String,
        messageId: String,
        from: String,
        to: String,
        status: DeliveryStatus,
        timestamp: Int64
    ) {
        self.protocolVersion = Protocol.version
        self.cryptoVersion = Protocol.cryptoVersion
        self.sessionToken = sessionToken
        self.messageId = messageId
        self.from = from
        self.to = to
        self.status = status.rawValue
        self.timestamp = timestamp
    }
}

enum DeliveryStatus: String, Codable {
    case delivered
    case read
}

/// Delivery notification (received by sender)
struct MessageDeliveredPayload: Codable {
    let messageId: String
    let status: String
    let timestamp: Int64
}

/// Fetch pending messages request
struct FetchPendingPayload: VersionedPayload {
    let protocolVersion: Int
    let cryptoVersion: Int
    let sessionToken: String
    var cursor: String?
    var limit: Int?              // default 50

    init(sessionToken: String, cursor: String? = nil, limit: Int? = nil) {
        self.protocolVersion = Protocol.version
        self.cryptoVersion = Protocol.cryptoVersion
        self.sessionToken = sessionToken
        self.cursor = cursor
        self.limit = limit
    }
}

/// Pending messages response
struct PendingMessagesPayload: Codable {
    let messages: [MessageReceivedPayload]
    var nextCursor: String?
}

// MARK: - Groups

/// Create group request
struct GroupCreatePayload: VersionedPayload {
    let protocolVersion: Int
    let cryptoVersion: Int
    let sessionToken: String
    let title: String
    let memberIds: [String]      // whisperId[]

    init(sessionToken: String, title: String, memberIds: [String]) {
        self.protocolVersion = Protocol.version
        self.cryptoVersion = Protocol.cryptoVersion
        self.sessionToken = sessionToken
        self.title = title
        self.memberIds = memberIds
    }
}

/// Group create acknowledgment (sent via group_event)
struct GroupCreateAckPayload: Codable {
    let groupId: String
    let title: String
    let memberIds: [String]
    let createdAt: Int64
}

/// Group member info for WebSocket messages
struct WSGroupMember: Codable {
    let whisperId: String
    let role: String  // "owner", "admin", "member"
    let joinedAt: Int64
    var removedAt: Int64?
}

/// Group info in group_event
struct GroupInfo: Codable {
    let groupId: String
    let title: String
    let ownerId: String
    let createdAt: Int64
    let updatedAt: Int64
    let members: [WSGroupMember]
}

/// Group event payload (server sends this for all group changes)
/// Event types: "created", "updated", "member_added", "member_removed"
struct GroupEventPayload: Codable {
    let event: String  // "created", "updated", "member_added", "member_removed"
    let group: GroupInfo
    var affectedMembers: [String]?
}

/// Role change entry for group update (owner only)
struct RoleChange: Codable {
    let whisperId: String
    let role: String  // "admin" or "member"
}

/// Update group request
struct GroupUpdatePayload: VersionedPayload {
    let protocolVersion: Int
    let cryptoVersion: Int
    let sessionToken: String
    let groupId: String
    var title: String?
    var addMembers: [String]?
    var removeMembers: [String]?
    var roleChanges: [RoleChange]?  // owner only

    init(
        sessionToken: String,
        groupId: String,
        title: String? = nil,
        addMembers: [String]? = nil,
        removeMembers: [String]? = nil,
        roleChanges: [RoleChange]? = nil
    ) {
        self.protocolVersion = Protocol.version
        self.cryptoVersion = Protocol.cryptoVersion
        self.sessionToken = sessionToken
        self.groupId = groupId
        self.title = title
        self.addMembers = addMembers
        self.removeMembers = removeMembers
        self.roleChanges = roleChanges
    }
}

/// Send group message (one per member, encrypted for each)
struct GroupSendMessagePayload: VersionedPayload {
    let protocolVersion: Int
    let cryptoVersion: Int
    let sessionToken: String
    let groupId: String
    let messageId: String
    let from: String
    let to: String               // Individual member
    let msgType: String
    let timestamp: Int64
    let nonce: String
    let ciphertext: String
    let sig: String
    var attachment: AttachmentPointer?

    init(
        sessionToken: String,
        groupId: String,
        messageId: String,
        from: String,
        to: String,
        msgType: WSMessageContentType,
        timestamp: Int64,
        nonce: String,
        ciphertext: String,
        sig: String,
        attachment: AttachmentPointer? = nil
    ) {
        self.protocolVersion = Protocol.version
        self.cryptoVersion = Protocol.cryptoVersion
        self.sessionToken = sessionToken
        self.groupId = groupId
        self.messageId = messageId
        self.from = from
        self.to = to
        self.msgType = msgType.rawValue
        self.timestamp = timestamp
        self.nonce = nonce
        self.ciphertext = ciphertext
        self.sig = sig
        self.attachment = attachment
    }
}

// MARK: - Calls

/// Get TURN credentials request
struct GetTurnCredentialsPayload: VersionedPayload {
    let protocolVersion: Int
    let cryptoVersion: Int
    let sessionToken: String

    init(sessionToken: String) {
        self.protocolVersion = Protocol.version
        self.cryptoVersion = Protocol.cryptoVersion
        self.sessionToken = sessionToken
    }
}

/// TURN credentials response
struct TurnCredentialsPayload: Codable {
    let urls: [String]
    let username: String
    let credential: String
    let ttl: Int
}

/// Initiate call (caller -> server)
struct CallInitiatePayload: VersionedPayload {
    let protocolVersion: Int
    let cryptoVersion: Int
    let sessionToken: String
    let callId: String
    let from: String
    let to: String
    let isVideo: Bool
    let timestamp: Int64
    let nonce: String
    let ciphertext: String       // base64(encrypted SDP offer)
    let sig: String

    init(
        sessionToken: String,
        callId: String,
        from: String,
        to: String,
        isVideo: Bool,
        timestamp: Int64,
        nonce: String,
        ciphertext: String,
        sig: String
    ) {
        self.protocolVersion = Protocol.version
        self.cryptoVersion = Protocol.cryptoVersion
        self.sessionToken = sessionToken
        self.callId = callId
        self.from = from
        self.to = to
        self.isVideo = isVideo
        self.timestamp = timestamp
        self.nonce = nonce
        self.ciphertext = ciphertext
        self.sig = sig
    }
}

/// Incoming call notification (server -> callee)
struct CallIncomingPayload: Codable {
    let callId: String
    let from: String
    let isVideo: Bool
    let timestamp: Int64
    let nonce: String
    let ciphertext: String
    let sig: String
}

/// Call ringing (callee -> server -> caller)
struct CallRingingPayload: VersionedPayload {
    let protocolVersion: Int
    let cryptoVersion: Int
    let sessionToken: String
    let callId: String
    let from: String
    let to: String
    let timestamp: Int64
    let nonce: String
    let ciphertext: String
    let sig: String

    init(
        sessionToken: String,
        callId: String,
        from: String,
        to: String,
        timestamp: Int64,
        nonce: String,
        ciphertext: String,
        sig: String
    ) {
        self.protocolVersion = Protocol.version
        self.cryptoVersion = Protocol.cryptoVersion
        self.sessionToken = sessionToken
        self.callId = callId
        self.from = from
        self.to = to
        self.timestamp = timestamp
        self.nonce = nonce
        self.ciphertext = ciphertext
        self.sig = sig
    }
}

/// Call ringing notification (server -> caller)
struct CallRingingNotificationPayload: Codable {
    let callId: String
    let from: String
}

/// Answer call (callee -> server -> caller)
struct CallAnswerPayload: VersionedPayload {
    let protocolVersion: Int
    let cryptoVersion: Int
    let sessionToken: String
    let callId: String
    let from: String
    let to: String
    let timestamp: Int64
    let nonce: String
    let ciphertext: String       // Encrypted SDP answer
    let sig: String

    init(
        sessionToken: String,
        callId: String,
        from: String,
        to: String,
        timestamp: Int64,
        nonce: String,
        ciphertext: String,
        sig: String
    ) {
        self.protocolVersion = Protocol.version
        self.cryptoVersion = Protocol.cryptoVersion
        self.sessionToken = sessionToken
        self.callId = callId
        self.from = from
        self.to = to
        self.timestamp = timestamp
        self.nonce = nonce
        self.ciphertext = ciphertext
        self.sig = sig
    }
}

/// ICE candidate exchange
struct CallIceCandidatePayload: VersionedPayload {
    let protocolVersion: Int
    let cryptoVersion: Int
    let sessionToken: String
    let callId: String
    let from: String
    let to: String
    let timestamp: Int64
    let nonce: String
    let ciphertext: String       // Encrypted ICE candidate
    let sig: String

    init(
        sessionToken: String,
        callId: String,
        from: String,
        to: String,
        timestamp: Int64,
        nonce: String,
        ciphertext: String,
        sig: String
    ) {
        self.protocolVersion = Protocol.version
        self.cryptoVersion = Protocol.cryptoVersion
        self.sessionToken = sessionToken
        self.callId = callId
        self.from = from
        self.to = to
        self.timestamp = timestamp
        self.nonce = nonce
        self.ciphertext = ciphertext
        self.sig = sig
    }
}

/// End call reasons (WebSocket message format)
enum WSCallEndReason: String, Codable {
    case ended
    case declined
    case busy
    case timeout
    case failed
}

/// End call
struct CallEndPayload: VersionedPayload {
    let protocolVersion: Int
    let cryptoVersion: Int
    let sessionToken: String
    let callId: String
    let from: String
    let to: String
    let timestamp: Int64
    let nonce: String
    let ciphertext: String
    let sig: String
    let reason: String

    init(
        sessionToken: String,
        callId: String,
        from: String,
        to: String,
        timestamp: Int64,
        nonce: String,
        ciphertext: String,
        sig: String,
        reason: WSCallEndReason
    ) {
        self.protocolVersion = Protocol.version
        self.cryptoVersion = Protocol.cryptoVersion
        self.sessionToken = sessionToken
        self.callId = callId
        self.from = from
        self.to = to
        self.timestamp = timestamp
        self.nonce = nonce
        self.ciphertext = ciphertext
        self.sig = sig
        self.reason = reason.rawValue
    }
}

// MARK: - Push Token Update

struct UpdateTokensPayload: VersionedPayload {
    let protocolVersion: Int
    let cryptoVersion: Int
    let sessionToken: String
    var pushToken: String?
    var voipToken: String?

    init(sessionToken: String, pushToken: String? = nil, voipToken: String? = nil) {
        self.protocolVersion = Protocol.version
        self.cryptoVersion = Protocol.cryptoVersion
        self.sessionToken = sessionToken
        self.pushToken = pushToken
        self.voipToken = voipToken
    }
}

// MARK: - Presence & Typing

struct PresenceUpdatePayload: Codable {
    let whisperId: String
    let status: String           // "online" or "offline"
    var lastSeen: Int64?
}

struct TypingPayload: VersionedPayload {
    let protocolVersion: Int
    let cryptoVersion: Int
    let sessionToken: String
    let to: String               // Peer whisperId
    let isTyping: Bool

    init(sessionToken: String, to: String, isTyping: Bool) {
        self.protocolVersion = Protocol.version
        self.cryptoVersion = Protocol.cryptoVersion
        self.sessionToken = sessionToken
        self.to = to
        self.isTyping = isTyping
    }
}

struct TypingNotificationPayload: Codable {
    let from: String
    let isTyping: Bool
}

// MARK: - Empty Payload (for messages without payload)

struct EmptyPayload: Codable {}
