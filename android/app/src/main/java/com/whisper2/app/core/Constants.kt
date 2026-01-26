package com.whisper2.app.core

/**
 * Whisper2 Core Constants
 * All frozen configuration values in one place
 * DO NOT CHANGE AFTER LAUNCH - breaks cross-platform recovery
 */
object Constants {

    // Protocol Version
    const val PROTOCOL_VERSION = 1
    const val CRYPTO_VERSION = 1

    // Server Configuration
    // Connect via nginx on default HTTPS/WSS port (443)
    // Nginx proxies to internal port 3051
    object Server {
        const val BASE_URL = "https://whisper2.aiakademiturkiye.com"
        const val WS_URL = "wss://whisper2.aiakademiturkiye.com/ws"
        const val PORT = 3051 // Internal port (used by server, not client)

        val httpBaseUrl: String get() = BASE_URL // No port = default 443
        val webSocketUrl: String get() = WS_URL // WebSocket path is /ws
    }

    // TURN Server Configuration (must match server .env TURN_URLS)
    object Turn {
        const val HOST = "turn2.aiakademiturkiye.com"
        const val PORT = 3479
        const val TLS_PORT = 5350

        val urls: List<String> get() = listOf(
            "turn:$HOST:$PORT?transport=udp",
            "turn:$HOST:$PORT?transport=tcp",
            "turns:$HOST:$TLS_PORT?transport=tcp"
        )
    }

    // Crypto Constants (FROZEN - DO NOT CHANGE)
    object Crypto {
        const val NONCE_LENGTH = 24
        const val KEY_LENGTH = 32
        const val PUBLIC_KEY_LENGTH = 32
        const val SECRET_KEY_LENGTH = 32
        const val SIGNATURE_LENGTH = 64
        const val SEED_LENGTH = 32
        const val BIP39_SEED_LENGTH = 64  // Full BIP39 seed is 64 bytes
        const val CHALLENGE_SIZE = 32

        // HKDF parameters (FROZEN - MUST match across all platforms for recovery)
        const val HKDF_SALT = "whisper"
        const val ENCRYPTION_DOMAIN = "whisper/enc"
        const val SIGNING_DOMAIN = "whisper/sign"
        const val CONTACTS_DOMAIN = "whisper/contacts"

        // WhisperID format (server-generated)
        // Format: WSP-XXXX-XXXX-XXXX (Base32: A-Z, 2-7)
        const val WHISPER_ID_PREFIX = "WSP-"
        const val WHISPER_ID_BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    }

    // Message Types (must match server protocol.ts MessageTypes)
    object MessageType {
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
        const val CALL_RINGING = "call_ringing"
        const val CALL_ANSWER = "call_answer"
        const val CALL_ICE_CANDIDATE = "call_ice_candidate"
        const val CALL_END = "call_end"

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

    // Error Codes (must match server protocol.ts ErrorCode)
    object ErrorCode {
        const val NOT_REGISTERED = "NOT_REGISTERED"
        const val AUTH_FAILED = "AUTH_FAILED"
        const val INVALID_PAYLOAD = "INVALID_PAYLOAD"
        const val INVALID_TIMESTAMP = "INVALID_TIMESTAMP"
        const val RATE_LIMITED = "RATE_LIMITED"
        const val USER_BANNED = "USER_BANNED"
        const val NOT_FOUND = "NOT_FOUND"
        const val FORBIDDEN = "FORBIDDEN"
        const val INTERNAL_ERROR = "INTERNAL_ERROR"
    }

    // Timeouts (milliseconds)
    object Timeout {
        const val WS_CONNECT = 10_000L
        const val WS_RECONNECT_BASE = 1_000L
        const val WS_RECONNECT_MAX = 30_000L
        const val HTTP_REQUEST = 30_000L
        const val CALL_STATE_TTL = 180_000L
        const val PING_INTERVAL = 30_000L
        const val PONG_TIMEOUT = 10_000L
    }

    // Storage Keys
    object StorageKey {
        const val ENC_PRIVATE_KEY = "whisper2.enc.private"
        const val ENC_PUBLIC_KEY = "whisper2.enc.public"
        const val SIGN_PRIVATE_KEY = "whisper2.sign.private"
        const val SIGN_PUBLIC_KEY = "whisper2.sign.public"
        const val SESSION_TOKEN = "whisper2.session.token"
        const val SESSION_EXPIRY = "whisper2.session.expiry"
        const val DEVICE_ID = "whisper2.device.id"
        const val WHISPER_ID = "whisper2.whisper.id"
        const val CONTACTS_KEY = "whisper2.contacts.key"
        const val FCM_TOKEN = "whisper2.fcm.token"
    }

    // Limits
    object Limits {
        const val MAX_MESSAGE_SIZE = 64 * 1024 // 64KB
        const val MAX_ATTACHMENT_SIZE = 100 * 1024 * 1024 // 100MB
        const val MAX_GROUP_MEMBERS = 256
        const val MAX_DISPLAY_NAME_LENGTH = 64
        const val MAX_GROUP_TITLE_LENGTH = 128
        const val OUTBOX_MAX_RETRIES = 5
    }
}
