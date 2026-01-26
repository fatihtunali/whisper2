package com.whisper2.app.network.ws

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import com.whisper2.app.core.Constants
import com.whisper2.app.network.api.AttachmentPointer

/**
 * WebSocket Protocol Models
 * Matches server protocol.ts exactly
 */

// =============================================================================
// ENVELOPE
// =============================================================================

/**
 * Generic WS frame envelope
 */
data class WsEnvelope<T>(
    val type: String,
    val requestId: String? = null,
    val payload: T? = null
)

/**
 * Raw envelope for initial parsing (payload as JsonObject)
 */
data class WsRawEnvelope(
    val type: String,
    val requestId: String? = null,
    val payload: JsonObject? = null
)

// =============================================================================
// AUTH PAYLOADS
// =============================================================================

/**
 * register_begin payload (Client → Server)
 */
data class RegisterBeginPayload(
    val protocolVersion: Int = Constants.PROTOCOL_VERSION,
    val cryptoVersion: Int = Constants.CRYPTO_VERSION,
    val deviceId: String,
    val platform: String = "android",
    val whisperId: String? = null // present if recovery
)

/**
 * register_challenge payload (Server → Client)
 */
data class RegisterChallengePayload(
    val challengeId: String,
    val challenge: String, // base64(32 bytes)
    val expiresAt: Long
)

/**
 * register_proof payload (Client → Server)
 */
data class RegisterProofPayload(
    val protocolVersion: Int = Constants.PROTOCOL_VERSION,
    val cryptoVersion: Int = Constants.CRYPTO_VERSION,
    val challengeId: String,
    val deviceId: String,
    val platform: String = "android",
    val whisperId: String? = null, // present if recovery
    val encPublicKey: String, // base64
    val signPublicKey: String, // base64
    val signature: String, // base64(Ed25519(SHA256(challengeBytes)))
    val pushToken: String? = null,
    val voipToken: String? = null // iOS only
)

/**
 * register_ack payload (Server → Client)
 */
data class RegisterAckPayload(
    val success: Boolean,
    val whisperId: String? = null,
    val sessionToken: String? = null,
    val sessionExpiresAt: Long? = null,
    val serverTime: Long? = null
)

// =============================================================================
// ERROR PAYLOAD
// =============================================================================

/**
 * Error payload (Server → Client)
 */
data class ErrorPayload(
    val code: String,
    val message: String,
    val requestId: String? = null
)

// =============================================================================
// SESSION PAYLOADS
// =============================================================================

data class SessionRefreshPayload(
    val protocolVersion: Int = Constants.PROTOCOL_VERSION,
    val cryptoVersion: Int = Constants.CRYPTO_VERSION,
    val sessionToken: String
)

data class SessionRefreshAckPayload(
    val sessionToken: String,
    val sessionExpiresAt: Long,
    val serverTime: Long
)

/**
 * logout payload (Client → Server)
 */
data class LogoutPayload(
    val protocolVersion: Int = Constants.PROTOCOL_VERSION,
    val cryptoVersion: Int = Constants.CRYPTO_VERSION,
    val sessionToken: String
)

/**
 * update_tokens payload (Client → Server)
 * For updating FCM/VoIP push tokens
 */
data class UpdateTokensPayload(
    val protocolVersion: Int = Constants.PROTOCOL_VERSION,
    val cryptoVersion: Int = Constants.CRYPTO_VERSION,
    val sessionToken: String,
    val pushToken: String? = null,
    val voipToken: String? = null  // iOS only
)

// =============================================================================
// PING/PONG
// =============================================================================

data class PingPayload(
    val timestamp: Long
)

data class PongPayload(
    val timestamp: Long,
    val serverTime: Long
)

// =============================================================================
// MESSAGING PAYLOADS
// =============================================================================

/**
 * fetch_pending payload (Client → Server)
 */
data class FetchPendingPayload(
    val protocolVersion: Int = Constants.PROTOCOL_VERSION,
    val cryptoVersion: Int = Constants.CRYPTO_VERSION,
    val sessionToken: String,
    val cursor: String? = null,
    val limit: Int = 50
)

/**
 * pending_messages payload (Server → Client)
 */
data class PendingMessagesPayload(
    val messages: List<PendingMessageItem>,
    val nextCursor: String? = null
)

/**
 * Single pending message item (matches server MessageReceivedPayload)
 * Used in pending_messages.messages array
 * Optional fields are omitted when not present (not sent as null)
 */
data class PendingMessageItem(
    val messageId: String,
    val groupId: String? = null,  // present for group messages
    val from: String,
    val to: String,
    val msgType: String,
    val timestamp: Long,
    val nonce: String,
    val ciphertext: String,
    val sig: String,
    val replyTo: String? = null,  // message ID being replied to
    val reactions: Map<String, List<String>>? = null,  // emoji -> whisperId[]
    val attachment: AttachmentPointer? = null  // attachment metadata
)

/**
 * delivery_receipt payload (Client → Server)
 */
data class DeliveryReceiptPayload(
    val protocolVersion: Int = Constants.PROTOCOL_VERSION,
    val cryptoVersion: Int = Constants.CRYPTO_VERSION,
    val sessionToken: String,
    val messageId: String,
    val from: String, // recipient whisperId (who sends receipt)
    val to: String, // sender whisperId (who sent message)
    val status: String, // "delivered" or "read"
    val timestamp: Long
)

/**
 * message_delivered payload (Server → Client)
 * Notification to sender that recipient received/read message
 */
data class MessageDeliveredPayload(
    val messageId: String,
    val status: String, // "delivered" or "read"
    val timestamp: Long
)

// =============================================================================
// MESSAGE TYPES (Constants) - Must match server protocol.ts MessageTypes exactly
// =============================================================================

object WsMessageTypes {
    // Auth
    const val REGISTER_BEGIN = "register_begin"
    const val REGISTER_CHALLENGE = "register_challenge"
    const val REGISTER_PROOF = "register_proof"
    const val REGISTER_ACK = "register_ack"
    const val SESSION_REFRESH = "session_refresh"
    const val SESSION_REFRESH_ACK = "session_refresh_ack"
    const val LOGOUT = "logout"
    const val UPDATE_TOKENS = "update_tokens"

    // Messaging
    const val SEND_MESSAGE = "send_message"
    const val MESSAGE_ACCEPTED = "message_accepted"
    const val MESSAGE_RECEIVED = "message_received"
    const val DELIVERY_RECEIPT = "delivery_receipt"
    const val MESSAGE_DELIVERED = "message_delivered"
    const val FETCH_PENDING = "fetch_pending"
    const val PENDING_MESSAGES = "pending_messages"

    // Groups
    const val GROUP_CREATE = "group_create"
    const val GROUP_EVENT = "group_event"
    const val GROUP_UPDATE = "group_update"
    const val GROUP_SEND_MESSAGE = "group_send_message"

    // Calls
    const val GET_TURN_CREDENTIALS = "get_turn_credentials"
    const val TURN_CREDENTIALS = "turn_credentials"
    const val CALL_INITIATE = "call_initiate"
    const val CALL_INCOMING = "call_incoming"
    const val CALL_ANSWER = "call_answer"
    const val CALL_ICE_CANDIDATE = "call_ice_candidate"
    const val CALL_END = "call_end"
    const val CALL_RINGING = "call_ringing"

    // Presence & Typing
    const val PRESENCE_UPDATE = "presence_update"
    const val TYPING = "typing"
    const val TYPING_NOTIFICATION = "typing_notification"

    // System
    const val PING = "ping"
    const val PONG = "pong"
    const val ERROR = "error"
    const val FORCE_LOGOUT = "force_logout"
}

// =============================================================================
// ERROR CODES
// =============================================================================

object WsErrorCodes {
    const val NOT_REGISTERED = "NOT_REGISTERED"
    const val AUTH_FAILED = "AUTH_FAILED"
    const val INVALID_PAYLOAD = "INVALID_PAYLOAD"
    const val INVALID_TIMESTAMP = "INVALID_TIMESTAMP"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val USER_BANNED = "USER_BANNED"
    const val NOT_FOUND = "NOT_FOUND"
    const val FORBIDDEN = "FORBIDDEN"
    const val INTERNAL_ERROR = "INTERNAL_ERROR"

    // Step 6: Permanent error codes (no retry)
    const val INVALID_SIGNATURE = "INVALID_SIGNATURE"
    const val RECIPIENT_NOT_FOUND = "RECIPIENT_NOT_FOUND"
    const val UNAUTHORIZED = "UNAUTHORIZED"

    /** Check if error code is permanent (no retry) */
    fun isPermanent(code: String): Boolean {
        return code in listOf(
            INVALID_SIGNATURE,
            RECIPIENT_NOT_FOUND,
            INVALID_PAYLOAD,
            UNAUTHORIZED
        )
    }
}

// =============================================================================
// MESSAGE ACCEPTED PAYLOAD (Step 6)
// =============================================================================

/**
 * message_accepted payload (Server → Client)
 * Sent when server successfully queues a message for delivery
 */
data class MessageAcceptedPayload(
    val messageId: String,
    val status: String // "sent"
)

// =============================================================================
// CALL PAYLOADS (Step 12)
// =============================================================================

/**
 * TURN credentials request (Client → Server)
 */
data class GetTurnCredentialsPayload(
    val protocolVersion: Int = Constants.PROTOCOL_VERSION,
    val cryptoVersion: Int = Constants.CRYPTO_VERSION,
    val sessionToken: String
)

/**
 * TURN credentials response (Server → Client)
 */
data class TurnCredentialsPayload(
    val urls: List<String>,
    val username: String,
    val credential: String,
    val ttl: Int
)

/**
 * Call payload - used for all call signaling messages
 * Fields are optional because:
 * - call_incoming from server has no protocolVersion/cryptoVersion/sessionToken
 * - different message types have different required fields
 */
data class CallPayload(
    val protocolVersion: Int? = null,
    val cryptoVersion: Int? = null,
    val sessionToken: String? = null,
    val callId: String,
    val from: String,
    val to: String? = null,
    val isVideo: Boolean? = null,
    val timestamp: Long,
    val nonce: String,
    val ciphertext: String,
    val sig: String,
    val reason: String? = null // for call_end: "ended", "declined", "busy", "timeout", "failed"
)

/**
 * Call end reasons
 */
object CallEndReason {
    const val ENDED = "ended"
    const val DECLINED = "declined"
    const val BUSY = "busy"
    const val TIMEOUT = "timeout"
    const val FAILED = "failed"
}

// =============================================================================
// GROUP PAYLOADS (matches server protocol.ts)
// =============================================================================

/**
 * group_create payload (Client → Server)
 */
data class GroupCreatePayload(
    val protocolVersion: Int = Constants.PROTOCOL_VERSION,
    val cryptoVersion: Int = Constants.CRYPTO_VERSION,
    val sessionToken: String,
    val title: String,        // 1-64 characters
    val memberIds: List<String>  // WhisperID[]
)

/**
 * group_create ack (Server → Client)
 */
data class GroupCreateAckPayload(
    val groupId: String,
    val title: String,
    val memberIds: List<String>,
    val createdAt: Long
)

/**
 * group_update payload (Client → Server)
 */
data class GroupUpdatePayload(
    val protocolVersion: Int = Constants.PROTOCOL_VERSION,
    val cryptoVersion: Int = Constants.CRYPTO_VERSION,
    val sessionToken: String,
    val groupId: String,
    val title: String? = null,
    val addMembers: List<String>? = null,
    val removeMembers: List<String>? = null,
    val roleChanges: List<RoleChange>? = null
)

/**
 * Role change for group_update
 */
data class RoleChange(
    val whisperId: String,
    val role: String  // "admin" or "member"
)

/**
 * Group member info
 */
data class GroupMember(
    val whisperId: String,
    val role: String,  // "owner", "admin", "member"
    val joinedAt: Long,
    val removedAt: Long? = null
)

/**
 * Group info (used in group_event)
 */
data class GroupInfo(
    val groupId: String,
    val title: String,
    val ownerId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val members: List<GroupMember>
)

/**
 * group_event payload (Server → Client)
 */
data class GroupEventPayload(
    val event: String,  // "created", "updated", "member_added", "member_removed"
    val group: GroupInfo,
    val affectedMembers: List<String>? = null
)

/**
 * Group event types
 */
object GroupEventType {
    const val CREATED = "created"
    const val UPDATED = "updated"
    const val MEMBER_ADDED = "member_added"
    const val MEMBER_REMOVED = "member_removed"
}

/**
 * Group roles
 */
object GroupRole {
    const val OWNER = "owner"
    const val ADMIN = "admin"
    const val MEMBER = "member"
}

/**
 * Recipient entry for group_send_message
 */
data class GroupMessageRecipient(
    val to: String,           // WhisperID
    val nonce: String,        // base64(24 bytes)
    val ciphertext: String,   // base64
    val sig: String           // base64(64 bytes)
)

/**
 * group_send_message payload (Client → Server)
 */
data class GroupSendMessagePayload(
    val protocolVersion: Int = Constants.PROTOCOL_VERSION,
    val cryptoVersion: Int = Constants.CRYPTO_VERSION,
    val sessionToken: String,
    val groupId: String,
    val messageId: String,
    val from: String,
    val msgType: String,
    val timestamp: Long,
    val recipients: List<GroupMessageRecipient>,
    val replyTo: String? = null,
    val reactions: Map<String, List<String>>? = null,
    val attachment: AttachmentPointer? = null
)

// =============================================================================
// PRESENCE & TYPING PAYLOADS (matches server protocol.ts)
// =============================================================================

/**
 * presence_update payload (Server → Client)
 */
data class PresenceUpdatePayload(
    val whisperId: String,
    val status: String,  // "online" or "offline"
    val lastSeen: Long? = null
)

/**
 * Presence status values
 */
object PresenceStatus {
    const val ONLINE = "online"
    const val OFFLINE = "offline"
}

/**
 * typing payload (Client → Server)
 */
data class TypingPayload(
    val protocolVersion: Int = Constants.PROTOCOL_VERSION,
    val cryptoVersion: Int = Constants.CRYPTO_VERSION,
    val sessionToken: String,
    val to: String,       // peer WhisperID
    val isTyping: Boolean
)

/**
 * typing_notification payload (Server → Client)
 */
data class TypingNotificationPayload(
    val from: String,
    val isTyping: Boolean
)

// =============================================================================
// PARSER HELPER
// =============================================================================

object WsParser {
    @PublishedApi
    internal val gson = Gson()

    /**
     * Parse raw JSON string to WsRawEnvelope
     */
    fun parseRaw(json: String): WsRawEnvelope {
        return gson.fromJson(json, WsRawEnvelope::class.java)
    }

    /**
     * Parse payload to specific type
     */
    inline fun <reified T> parsePayload(payload: JsonObject?): T? {
        if (payload == null) return null
        return gson.fromJson(payload, T::class.java)
    }

    /**
     * Serialize envelope to JSON
     */
    fun <T> toJson(envelope: WsEnvelope<T>): String {
        return gson.toJson(envelope)
    }

    /**
     * Create envelope with payload
     */
    fun <T> createEnvelope(type: String, payload: T, requestId: String? = null): String {
        val envelope = WsEnvelope(type, requestId, payload)
        return gson.toJson(envelope)
    }
}
