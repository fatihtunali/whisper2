package com.whisper2.app.data.network.ws

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import com.whisper2.app.core.Constants

data class WsFrame<T>(
    val type: String,
    val requestId: String? = null,
    val payload: T
)

// Registration
data class RegisterBeginPayload(
    val protocolVersion: Int = Constants.PROTOCOL_VERSION,
    val cryptoVersion: Int = Constants.CRYPTO_VERSION,
    val deviceId: String,
    val platform: String = Constants.PLATFORM,
    val whisperId: String? = null
)

data class RegisterChallengePayload(
    val challengeId: String,
    val challenge: String,
    val expiresAt: Long
)

data class RegisterProofPayload(
    val protocolVersion: Int = Constants.PROTOCOL_VERSION,
    val cryptoVersion: Int = Constants.CRYPTO_VERSION,
    val challengeId: String,
    val deviceId: String,
    val platform: String = Constants.PLATFORM,
    val whisperId: String? = null,
    val encPublicKey: String,
    val signPublicKey: String,
    val signature: String,
    val pushToken: String? = null,
    val voipToken: String? = null  // iOS only, null for Android
)

data class RegisterAckPayload(
    val success: Boolean,
    val whisperId: String,
    val sessionToken: String,
    val sessionExpiresAt: Long,
    val serverTime: Long
)

// Messaging
data class SendMessagePayload(
    val protocolVersion: Int = Constants.PROTOCOL_VERSION,
    val cryptoVersion: Int = Constants.CRYPTO_VERSION,
    val sessionToken: String,
    val messageId: String,
    val from: String,
    val to: String,
    val msgType: String,
    val timestamp: Long,
    val nonce: String,
    val ciphertext: String,
    val sig: String,
    val replyTo: String? = null,
    val reactions: Map<String, List<String>>? = null,  // emoji -> whisperId[]
    val attachment: AttachmentPointer? = null
)

data class MessageReceivedPayload(
    val messageId: String,
    val groupId: String? = null,
    val from: String,
    val to: String,
    val msgType: String,
    val timestamp: Long,
    val nonce: String,
    val ciphertext: String,
    val sig: String,
    val replyTo: String? = null,
    val reactions: Map<String, List<String>>? = null,  // emoji -> whisperId[]
    val attachment: AttachmentPointer? = null,
    // Sender's public keys for message requests (allows adding contact without QR scan)
    val senderEncPublicKey: String? = null,
    val senderSignPublicKey: String? = null
)

// Attachment structure matching server protocol.ts
data class FileKeyBox(
    val nonce: String,      // base64(24 bytes)
    val ciphertext: String  // base64
)

data class AttachmentPointer(
    val objectKey: String,      // S3 path
    val contentType: String,
    val ciphertextSize: Int,
    val fileNonce: String,      // base64(24 bytes)
    val fileKeyBox: FileKeyBox
)

data class DeliveryReceiptPayload(
    val protocolVersion: Int = Constants.PROTOCOL_VERSION,
    val cryptoVersion: Int = Constants.CRYPTO_VERSION,
    val sessionToken: String,
    val messageId: String,
    val from: String,
    val to: String,
    val status: String,
    val timestamp: Long
)

data class FetchPendingPayload(
    val protocolVersion: Int = Constants.PROTOCOL_VERSION,
    val cryptoVersion: Int = Constants.CRYPTO_VERSION,
    val sessionToken: String,
    val cursor: String? = null,
    val limit: Int? = null
)

// Pending messages can contain different types (message_received, group_event, etc.)
// Parse as JsonElement to check the type field
data class PendingMessagesPayload(
    val messages: List<com.google.gson.JsonElement>,
    val nextCursor: String? = null
)

data class MessageAcceptedPayload(
    val messageId: String,
    val status: String
)

data class MessageDeliveredPayload(
    val messageId: String,
    val status: String,
    val timestamp: Long
)

data class TypingPayload(
    val protocolVersion: Int = Constants.PROTOCOL_VERSION,
    val cryptoVersion: Int = Constants.CRYPTO_VERSION,
    val sessionToken: String,
    val to: String,
    val isTyping: Boolean
)

data class TypingNotificationPayload(
    val from: String,
    val isTyping: Boolean
)

// Presence
data class PresenceUpdatePayload(
    val whisperId: String,
    val status: String,  // "online" | "offline"
    val lastSeen: Long? = null
)

/**
 * Payload to update presence settings (whether to share online status with others).
 * When showOnlineStatus is false, the server should not broadcast presence updates for this user.
 */
data class PresenceSettingsPayload(
    val protocolVersion: Int = Constants.PROTOCOL_VERSION,
    val cryptoVersion: Int = Constants.CRYPTO_VERSION,
    val sessionToken: String,
    val showOnlineStatus: Boolean
)

// Delete message
data class DeleteMessagePayload(
    val protocolVersion: Int = Constants.PROTOCOL_VERSION,
    val cryptoVersion: Int = Constants.CRYPTO_VERSION,
    val sessionToken: String,
    val messageId: String,
    val conversationId: String,  // peerId or groupId
    val deleteForEveryone: Boolean,
    val timestamp: Long,
    val sig: String
)

data class MessageDeletedPayload(
    val messageId: String,
    val conversationId: String,
    val deletedBy: String,
    val deleteForEveryone: Boolean,
    val timestamp: Long
)

// Calls
data class GetTurnCredentialsPayload(
    val protocolVersion: Int = Constants.PROTOCOL_VERSION,
    val cryptoVersion: Int = Constants.CRYPTO_VERSION,
    val sessionToken: String
)

data class TurnCredentialsPayload(
    val urls: List<String>,
    val username: String,
    val credential: String,
    val ttl: Int
)

data class CallInitiatePayload(
    val protocolVersion: Int = Constants.PROTOCOL_VERSION,
    val cryptoVersion: Int = Constants.CRYPTO_VERSION,
    val sessionToken: String,
    val callId: String,
    val from: String,
    val to: String,
    @SerializedName("isVideo")
    val isVideo: Boolean,
    val timestamp: Long,
    val nonce: String,
    val ciphertext: String,
    val sig: String
)

data class CallIncomingPayload(
    val callId: String,
    val from: String,
    @SerializedName("isVideo")
    val isVideo: Boolean,
    val timestamp: Long,
    val nonce: String,
    val ciphertext: String,
    val sig: String
)

data class CallAnswerPayload(
    val protocolVersion: Int = Constants.PROTOCOL_VERSION,
    val cryptoVersion: Int = Constants.CRYPTO_VERSION,
    val sessionToken: String,
    val callId: String,
    val from: String,
    val to: String,
    val timestamp: Long,
    val nonce: String,
    val ciphertext: String,
    val sig: String
)

data class CallIceCandidatePayload(
    val protocolVersion: Int = Constants.PROTOCOL_VERSION,
    val cryptoVersion: Int = Constants.CRYPTO_VERSION,
    val sessionToken: String,
    val callId: String,
    val from: String,
    val to: String,
    val timestamp: Long,
    val nonce: String,
    val ciphertext: String,
    val sig: String
)

data class CallEndPayload(
    @SerializedName("protocolVersion") val protocolVersion: Int = Constants.PROTOCOL_VERSION,
    @SerializedName("cryptoVersion") val cryptoVersion: Int = Constants.CRYPTO_VERSION,
    @SerializedName("sessionToken") val sessionToken: String,
    @SerializedName("callId") val callId: String,
    @SerializedName("from") val from: String,
    @SerializedName("to") val to: String,
    @SerializedName("timestamp") val timestamp: Long,
    @SerializedName("nonce") val nonce: String,
    @SerializedName("ciphertext") val ciphertext: String,
    @SerializedName("sig") val sig: String,
    @SerializedName("reason") val reason: String
)

data class CallRingingPayload(
    val protocolVersion: Int = Constants.PROTOCOL_VERSION,
    val cryptoVersion: Int = Constants.CRYPTO_VERSION,
    val sessionToken: String,
    val callId: String,
    val from: String,
    val to: String,
    val timestamp: Long,
    val nonce: String,
    val ciphertext: String,
    val sig: String
)

data class CallRingingNotificationPayload(
    val callId: String,
    val from: String
)

// Incoming call_answer notification from server (no sessionToken/protocolVersion)
data class CallAnswerNotificationPayload(
    val callId: String,
    val from: String,
    val timestamp: Long,
    val nonce: String,
    val ciphertext: String,
    val sig: String
)

// Incoming call_ice_candidate notification from server (no sessionToken/protocolVersion)
data class CallIceCandidateNotificationPayload(
    val callId: String,
    val from: String,
    val timestamp: Long,
    val nonce: String,
    val ciphertext: String,
    val sig: String
)

// Incoming call_end notification from server (matches iOS CallEndReceivedPayload)
data class CallEndNotificationPayload(
    val callId: String,
    val from: String,
    val reason: String,
    val timestamp: Long? = null,
    val nonce: String? = null,
    val ciphertext: String? = null,
    val sig: String? = null
)

// System
data class PingPayload(val timestamp: Long)
data class PongPayload(val timestamp: Long, val serverTime: Long)
data class ErrorPayload(
    val code: String,
    val message: String,
    val requestId: String? = null
)

// Tokens
data class UpdateTokensPayload(
    val protocolVersion: Int = Constants.PROTOCOL_VERSION,
    val cryptoVersion: Int = Constants.CRYPTO_VERSION,
    val sessionToken: String,
    val pushToken: String? = null,
    val voipToken: String? = null  // iOS only, null for Android
)

// Session
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

data class LogoutPayload(
    val protocolVersion: Int = Constants.PROTOCOL_VERSION,
    val cryptoVersion: Int = Constants.CRYPTO_VERSION,
    val sessionToken: String
)
