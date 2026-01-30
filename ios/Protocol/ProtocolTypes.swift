import Foundation

// MARK: - Error Payload

struct ErrorPayload: Codable {
    let code: String
    let message: String
    let requestId: String?
}

// MARK: - Auth Payloads (match server protocol.ts)

struct RegisterBeginPayload: Codable {
    let protocolVersion: Int
    let cryptoVersion: Int
    let whisperId: String?
    let deviceId: String
    let platform: String

    init(deviceId: String, whisperId: String? = nil) {
        self.protocolVersion = Constants.protocolVersion
        self.cryptoVersion = Constants.cryptoVersion
        self.deviceId = deviceId
        self.whisperId = whisperId
        self.platform = "ios"
    }
}

struct RegisterChallengePayload: Codable {
    let challengeId: String
    let challenge: String  // base64
    let expiresAt: Int64
}

struct RegisterProofPayload: Codable {
    let protocolVersion: Int
    let cryptoVersion: Int
    let challengeId: String
    let deviceId: String
    let platform: String
    let whisperId: String?
    let encPublicKey: String  // base64
    let signPublicKey: String  // base64
    let signature: String  // base64
    let pushToken: String?
    let voipToken: String?

    init(
        challengeId: String,
        deviceId: String,
        whisperId: String? = nil,
        encPublicKey: String,
        signPublicKey: String,
        signature: String,
        pushToken: String? = nil,
        voipToken: String? = nil
    ) {
        self.protocolVersion = Constants.protocolVersion
        self.cryptoVersion = Constants.cryptoVersion
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

struct RegisterAckPayload: Codable {
    let success: Bool
    let whisperId: String
    let sessionToken: String
    let sessionExpiresAt: Int64
    let serverTime: Int64
}

// MARK: - Session Payloads

struct SessionRefreshPayload: Codable {
    let protocolVersion: Int
    let cryptoVersion: Int
    let sessionToken: String

    init(sessionToken: String) {
        self.protocolVersion = Constants.protocolVersion
        self.cryptoVersion = Constants.cryptoVersion
        self.sessionToken = sessionToken
    }
}

struct SessionRefreshAckPayload: Codable {
    let sessionToken: String
    let sessionExpiresAt: Int64
    let serverTime: Int64
}

struct LogoutPayload: Codable {
    let protocolVersion: Int
    let cryptoVersion: Int
    let sessionToken: String

    init(sessionToken: String) {
        self.protocolVersion = Constants.protocolVersion
        self.cryptoVersion = Constants.cryptoVersion
        self.sessionToken = sessionToken
    }
}

// MARK: - Ping/Pong

struct PingPayload: Codable {
    let timestamp: Int64
}

struct PongPayload: Codable {
    let timestamp: Int64
    let serverTime: Int64
}

// MARK: - Attachment (from server protocol.ts)

struct FileKeyBox: Codable {
    let nonce: String  // base64(24 bytes)
    let ciphertext: String  // base64
}

struct AttachmentPointer: Codable {
    let objectKey: String  // S3 path
    let contentType: String
    let ciphertextSize: Int
    let fileNonce: String  // base64(24 bytes)
    let fileKeyBox: FileKeyBox
}

// MARK: - Messaging Payloads

struct SendMessagePayload: Codable {
    let protocolVersion: Int
    let cryptoVersion: Int
    let sessionToken: String
    let messageId: String
    let from: String
    let to: String
    let msgType: String
    let timestamp: Int64
    let nonce: String  // base64
    let ciphertext: String  // base64
    let sig: String  // base64
    let replyTo: String?
    let reactions: [String: [String]]?  // emoji -> whisperId[]
    let attachment: AttachmentPointer?

    init(
        sessionToken: String,
        messageId: String,
        from: String,
        to: String,
        msgType: String = "text",
        timestamp: Int64,
        nonce: String,
        ciphertext: String,
        sig: String,
        replyTo: String? = nil,
        reactions: [String: [String]]? = nil,
        attachment: AttachmentPointer? = nil
    ) {
        self.protocolVersion = Constants.protocolVersion
        self.cryptoVersion = Constants.cryptoVersion
        self.sessionToken = sessionToken
        self.messageId = messageId
        self.from = from
        self.to = to
        self.msgType = msgType
        self.timestamp = timestamp
        self.nonce = nonce
        self.ciphertext = ciphertext
        self.sig = sig
        self.replyTo = replyTo
        self.reactions = reactions
        self.attachment = attachment
    }
}

struct MessageAcceptedPayload: Codable {
    let messageId: String
    let status: String
}

struct MessageReceivedPayload: Codable {
    let messageId: String
    let groupId: String?
    let from: String
    let to: String
    let msgType: String
    let timestamp: Int64
    let nonce: String
    let ciphertext: String
    let sig: String
    let replyTo: String?
    let reactions: [String: [String]]?  // emoji -> whisperId[]
    let attachment: AttachmentPointer?
    let senderEncPublicKey: String?  // base64, included for message requests from unknown senders
}

struct DeliveryReceiptPayload: Codable {
    let protocolVersion: Int
    let cryptoVersion: Int
    let sessionToken: String
    let messageId: String
    let from: String
    let to: String
    let status: String
    let timestamp: Int64

    init(sessionToken: String, messageId: String, from: String, to: String, status: String, timestamp: Int64) {
        self.protocolVersion = Constants.protocolVersion
        self.cryptoVersion = Constants.cryptoVersion
        self.sessionToken = sessionToken
        self.messageId = messageId
        self.from = from
        self.to = to
        self.status = status
        self.timestamp = timestamp
    }
}

struct MessageDeliveredPayload: Codable {
    let messageId: String
    let status: String
    let timestamp: Int64
}

/// Delete message payload - for deleting messages
struct DeleteMessagePayload: Codable {
    let protocolVersion: Int
    let cryptoVersion: Int
    let sessionToken: String
    let messageId: String
    let conversationId: String  // peerId or groupId
    let deleteForEveryone: Bool
    let timestamp: Int64
    let sig: String

    init(
        sessionToken: String,
        messageId: String,
        conversationId: String,
        deleteForEveryone: Bool,
        timestamp: Int64,
        sig: String
    ) {
        self.protocolVersion = Constants.protocolVersion
        self.cryptoVersion = Constants.cryptoVersion
        self.sessionToken = sessionToken
        self.messageId = messageId
        self.conversationId = conversationId
        self.deleteForEveryone = deleteForEveryone
        self.timestamp = timestamp
        self.sig = sig
    }
}

/// Message deleted notification - received when a message is deleted
struct MessageDeletedPayload: Codable {
    let messageId: String
    let conversationId: String
    let deletedBy: String
    let deleteForEveryone: Bool
    let timestamp: Int64
}

struct FetchPendingPayload: Codable {
    let protocolVersion: Int
    let cryptoVersion: Int
    let sessionToken: String
    let cursor: String?
    let limit: Int?

    init(sessionToken: String, cursor: String? = nil, limit: Int? = nil) {
        self.protocolVersion = Constants.protocolVersion
        self.cryptoVersion = Constants.cryptoVersion
        self.sessionToken = sessionToken
        self.cursor = cursor
        self.limit = limit
    }
}

struct PendingMessagesPayload: Codable {
    let messages: [MessageReceivedPayload]
    let nextCursor: String?
}

// MARK: - Groups (from server protocol.ts Section 7)

struct GroupCreatePayload: Codable {
    let protocolVersion: Int
    let cryptoVersion: Int
    let sessionToken: String
    let title: String
    let memberIds: [String]  // whisperId[]

    init(sessionToken: String, title: String, memberIds: [String]) {
        self.protocolVersion = Constants.protocolVersion
        self.cryptoVersion = Constants.cryptoVersion
        self.sessionToken = sessionToken
        self.title = title
        self.memberIds = memberIds
    }
}

struct GroupCreateAckPayload: Codable {
    let groupId: String
    let title: String
    let memberIds: [String]
    let createdAt: Int64
}

/// Role change for group members
struct RoleChange: Codable {
    let whisperId: String
    let role: String  // "admin" or "member"
}

struct GroupUpdatePayload: Codable {
    let protocolVersion: Int
    let cryptoVersion: Int
    let sessionToken: String
    let groupId: String
    let addMembers: [String]?
    let removeMembers: [String]?
    let title: String?
    let roleChanges: [RoleChange]?

    init(sessionToken: String, groupId: String, addMembers: [String]? = nil, removeMembers: [String]? = nil, title: String? = nil, roleChanges: [RoleChange]? = nil) {
        self.protocolVersion = Constants.protocolVersion
        self.cryptoVersion = Constants.cryptoVersion
        self.sessionToken = sessionToken
        self.groupId = groupId
        self.addMembers = addMembers
        self.removeMembers = removeMembers
        self.title = title
        self.roleChanges = roleChanges
    }
}

/// Recipient envelope for group messages (per-recipient encryption)
struct RecipientEnvelope: Codable {
    let to: String
    let nonce: String
    let ciphertext: String
    let sig: String

    init(to: String, nonce: String, ciphertext: String, sig: String) {
        self.to = to
        self.nonce = nonce
        self.ciphertext = ciphertext
        self.sig = sig
    }
}

struct GroupSendMessagePayload: Codable {
    let protocolVersion: Int
    let cryptoVersion: Int
    let sessionToken: String
    let groupId: String
    let messageId: String
    let from: String
    let msgType: String
    let timestamp: Int64
    let recipients: [RecipientEnvelope]  // All recipients bundled in one frame
    let attachment: AttachmentPointer?
    let replyTo: String?
    let reactions: [String: [String]]?  // emoji -> [whisperId]

    init(
        sessionToken: String,
        groupId: String,
        messageId: String,
        from: String,
        msgType: String = "text",
        timestamp: Int64,
        recipients: [RecipientEnvelope],
        attachment: AttachmentPointer? = nil,
        replyTo: String? = nil,
        reactions: [String: [String]]? = nil
    ) {
        self.protocolVersion = Constants.protocolVersion
        self.cryptoVersion = Constants.cryptoVersion
        self.sessionToken = sessionToken
        self.groupId = groupId
        self.messageId = messageId
        self.from = from
        self.msgType = msgType
        self.timestamp = timestamp
        self.recipients = recipients
        self.attachment = attachment
        self.replyTo = replyTo
        self.reactions = reactions
    }
}

struct GroupInviteResponsePayload: Codable {
    let protocolVersion: Int
    let cryptoVersion: Int
    let sessionToken: String
    let groupId: String
    let accepted: Bool

    init(sessionToken: String, groupId: String, accepted: Bool) {
        self.protocolVersion = Constants.protocolVersion
        self.cryptoVersion = Constants.cryptoVersion
        self.sessionToken = sessionToken
        self.groupId = groupId
        self.accepted = accepted
    }
}

// MARK: - Push Token Update (from server protocol.ts Section 8)

struct UpdateTokensPayload: Codable {
    let protocolVersion: Int
    let cryptoVersion: Int
    let sessionToken: String
    let pushToken: String?
    let voipToken: String?  // iOS only

    init(sessionToken: String, pushToken: String? = nil, voipToken: String? = nil) {
        self.protocolVersion = Constants.protocolVersion
        self.cryptoVersion = Constants.cryptoVersion
        self.sessionToken = sessionToken
        self.pushToken = pushToken
        self.voipToken = voipToken
    }
}

// MARK: - Calls (from server protocol.ts Section 9)

struct GetTurnCredentialsPayload: Codable {
    let protocolVersion: Int
    let cryptoVersion: Int
    let sessionToken: String

    init(sessionToken: String) {
        self.protocolVersion = Constants.protocolVersion
        self.cryptoVersion = Constants.cryptoVersion
        self.sessionToken = sessionToken
    }
}

struct TurnCredentialsPayload: Codable {
    let urls: [String]
    let username: String
    let credential: String
    let ttl: Int
}

struct CallInitiatePayload: Codable {
    let protocolVersion: Int
    let cryptoVersion: Int
    let sessionToken: String
    let callId: String
    let from: String
    let to: String
    let isVideo: Bool
    let timestamp: Int64
    let nonce: String
    let ciphertext: String  // base64(sdpOfferString)
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
        self.protocolVersion = Constants.protocolVersion
        self.cryptoVersion = Constants.cryptoVersion
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

struct CallIncomingPayload: Codable {
    let callId: String
    let from: String
    let isVideo: Bool
    let timestamp: Int64
    let nonce: String
    let ciphertext: String
    let sig: String
}

// MARK: - Received Call Payloads (server forwards without version/session/to fields)

/// Received call_answer - server strips protocolVersion, cryptoVersion, sessionToken, to
struct CallAnswerReceivedPayload: Codable {
    let callId: String
    let from: String
    let timestamp: Int64
    let nonce: String
    let ciphertext: String
    let sig: String
}

/// Received call_ice_candidate - server strips protocolVersion, cryptoVersion, sessionToken, to
struct CallIceCandidateReceivedPayload: Codable {
    let callId: String
    let from: String
    let timestamp: Int64
    let nonce: String
    let ciphertext: String
    let sig: String
}

/// Received call_end - server strips protocolVersion, cryptoVersion, sessionToken, to
struct CallEndReceivedPayload: Codable {
    let callId: String
    let from: String
    let reason: String
    // These may or may not be present when forwarded
    let timestamp: Int64?
    let nonce: String?
    let ciphertext: String?
    let sig: String?
}

/// Received call_ringing - server strips protocolVersion, cryptoVersion, sessionToken, to
struct CallRingingReceivedPayload: Codable {
    let callId: String
    let from: String
    // These may or may not be present when forwarded
    let timestamp: Int64?
    let nonce: String?
    let ciphertext: String?
    let sig: String?
}

struct CallAnswerPayload: Codable {
    let protocolVersion: Int
    let cryptoVersion: Int
    let sessionToken: String
    let callId: String
    let from: String
    let to: String
    let timestamp: Int64
    let nonce: String
    let ciphertext: String  // encrypted SDP answer
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
        self.protocolVersion = Constants.protocolVersion
        self.cryptoVersion = Constants.cryptoVersion
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

struct CallIceCandidatePayload: Codable {
    let protocolVersion: Int
    let cryptoVersion: Int
    let sessionToken: String
    let callId: String
    let from: String
    let to: String
    let timestamp: Int64
    let nonce: String
    let ciphertext: String  // encrypted ICE candidate
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
        self.protocolVersion = Constants.protocolVersion
        self.cryptoVersion = Constants.cryptoVersion
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

struct CallEndPayload: Codable {
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
    let reason: String  // ended | declined | busy | timeout | failed

    init(
        sessionToken: String,
        callId: String,
        from: String,
        to: String,
        timestamp: Int64,
        nonce: String,
        ciphertext: String,
        sig: String,
        reason: String
    ) {
        self.protocolVersion = Constants.protocolVersion
        self.cryptoVersion = Constants.cryptoVersion
        self.sessionToken = sessionToken
        self.callId = callId
        self.from = from
        self.to = to
        self.timestamp = timestamp
        self.nonce = nonce
        self.ciphertext = ciphertext
        self.sig = sig
        self.reason = reason
    }
}

struct CallRingingPayload: Codable {
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
        self.protocolVersion = Constants.protocolVersion
        self.cryptoVersion = Constants.cryptoVersion
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

struct CallRingingNotificationPayload: Codable {
    let callId: String
    let from: String
}

// MARK: - Typing & Presence

struct TypingPayload: Codable {
    let protocolVersion: Int
    let cryptoVersion: Int
    let sessionToken: String
    let to: String
    let isTyping: Bool

    init(sessionToken: String, to: String, isTyping: Bool) {
        self.protocolVersion = Constants.protocolVersion
        self.cryptoVersion = Constants.cryptoVersion
        self.sessionToken = sessionToken
        self.to = to
        self.isTyping = isTyping
    }
}

struct TypingNotificationPayload: Codable {
    let from: String
    let isTyping: Bool
}

struct PresenceUpdatePayload: Codable {
    let whisperId: String
    let status: String  // online | offline
    let lastSeen: Int64?
}
