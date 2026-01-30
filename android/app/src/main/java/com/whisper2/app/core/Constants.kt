package com.whisper2.app.core

/** FROZEN CONSTANTS - Must match server/iOS exactly */
object Constants {
    const val WS_URL = "wss://whisper2.aiakademiturkiye.com/ws"
    const val BASE_URL = "https://whisper2.aiakademiturkiye.com"
    const val PROTOCOL_VERSION = 1
    const val CRYPTO_VERSION = 1

    // Crypto (FROZEN)
    const val BIP39_SEED_LENGTH = 64
    const val BIP39_ITERATIONS = 2048
    const val BIP39_SALT = "mnemonic"
    const val HKDF_SALT = "whisper"
    const val ENCRYPTION_DOMAIN = "whisper/enc"
    const val SIGNING_DOMAIN = "whisper/sign"
    const val CONTACTS_DOMAIN = "whisper/contacts"

    const val NACL_NONCE_SIZE = 24
    const val NACL_KEY_SIZE = 32
    const val NACL_PUBLIC_KEY_SIZE = 32
    const val NACL_SECRET_KEY_SIZE = 32
    const val NACL_SIGN_SECRET_KEY_SIZE = 64
    const val NACL_SIGNATURE_SIZE = 64
    const val NACL_BOX_MAC_SIZE = 16

    const val TIMESTAMP_SKEW_MS = 10 * 60 * 1000L
    const val SESSION_TTL_DAYS = 7
    const val MAX_GROUP_MEMBERS = 50
    const val MAX_ATTACHMENT_SIZE = 100 * 1024 * 1024L
    const val MAX_BACKUP_SIZE = 256 * 1024

    const val HEARTBEAT_INTERVAL_MS = 30_000L
    const val RECONNECT_BASE_DELAY_MS = 1000L
    const val RECONNECT_MAX_DELAY_MS = 30_000L
    const val RECONNECT_MAX_ATTEMPTS = 5
    const val CALL_RING_TIMEOUT_MS = 30_000L

    const val DATABASE_NAME = "whisper2_db"
    const val SECURE_PREFS_NAME = "whisper2_secure_prefs"
    const val KEYSTORE_ALIAS = "whisper2_wrapper_key"
    const val PLATFORM = "android"

    object MsgType {
        // Auth
        const val REGISTER_BEGIN = "register_begin"
        const val REGISTER_CHALLENGE = "register_challenge"
        const val REGISTER_PROOF = "register_proof"
        const val REGISTER_ACK = "register_ack"
        const val SESSION_REFRESH = "session_refresh"
        const val SESSION_REFRESH_ACK = "session_refresh_ack"
        const val LOGOUT = "logout"

        // Messaging
        const val SEND_MESSAGE = "send_message"
        const val MESSAGE_ACCEPTED = "message_accepted"
        const val MESSAGE_RECEIVED = "message_received"
        const val DELIVERY_RECEIPT = "delivery_receipt"
        const val MESSAGE_DELIVERED = "message_delivered"
        const val FETCH_PENDING = "fetch_pending"
        const val PENDING_MESSAGES = "pending_messages"
        const val DELETE_MESSAGE = "delete_message"
        const val MESSAGE_DELETED = "message_deleted"

        // Groups
        const val GROUP_CREATE = "group_create"
        const val GROUP_EVENT = "group_event"
        const val GROUP_UPDATE = "group_update"
        const val GROUP_SEND_MESSAGE = "group_send_message"
        const val GROUP_INVITE_RESPONSE = "group_invite_response"

        // Calls
        const val GET_TURN_CREDENTIALS = "get_turn_credentials"
        const val TURN_CREDENTIALS = "turn_credentials"
        const val CALL_INITIATE = "call_initiate"
        const val CALL_INCOMING = "call_incoming"
        const val CALL_ANSWER = "call_answer"
        const val CALL_ICE_CANDIDATE = "call_ice_candidate"
        const val CALL_END = "call_end"
        const val CALL_RINGING = "call_ringing"

        // Push tokens
        const val UPDATE_TOKENS = "update_tokens"

        // Presence
        const val PRESENCE_UPDATE = "presence_update"
        const val TYPING = "typing"
        const val TYPING_NOTIFICATION = "typing_notification"

        // Ping/Pong
        const val PING = "ping"
        const val PONG = "pong"

        // Error
        const val ERROR = "error"
    }

    object ContentType {
        const val TEXT = "text"
        const val IMAGE = "image"
        const val VIDEO = "video"
        const val VOICE = "voice"
        const val AUDIO = "audio"
        const val FILE = "file"
        const val LOCATION = "location"
        const val SYSTEM = "system"
    }

    object MessageStatus {
        const val PENDING = "pending"
        const val SENT = "sent"
        const val DELIVERED = "delivered"
        const val READ = "read"
        const val FAILED = "failed"
    }

    object Direction {
        const val INCOMING = "incoming"
        const val OUTGOING = "outgoing"
    }

    /** Error codes - MUST match server protocol.ts exactly */
    object ErrorCode {
        const val NOT_REGISTERED = "NOT_REGISTERED"      // No active session
        const val AUTH_FAILED = "AUTH_FAILED"            // Auth validation failed
        const val INVALID_PAYLOAD = "INVALID_PAYLOAD"    // Payload schema validation failed
        const val INVALID_TIMESTAMP = "INVALID_TIMESTAMP"// Timestamp outside skew window
        const val RATE_LIMITED = "RATE_LIMITED"          // Rate limit exceeded
        const val USER_BANNED = "USER_BANNED"            // User is banned
        const val NOT_FOUND = "NOT_FOUND"                // Resource not found
        const val FORBIDDEN = "FORBIDDEN"                // Access denied
        const val INTERNAL_ERROR = "INTERNAL_ERROR"      // Server error
    }

    object CallEndReason {
        const val ENDED = "ended"
        const val DECLINED = "declined"
        const val BUSY = "busy"
        const val TIMEOUT = "timeout"
        const val FAILED = "failed"
        const val CANCELLED = "cancelled"
    }

    object PresenceStatus {
        const val ONLINE = "online"
        const val OFFLINE = "offline"
    }

    object DeliveryStatus {
        const val DELIVERED = "delivered"
        const val READ = "read"
    }
}
