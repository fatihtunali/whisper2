package com.whisper2.app.ui.state

import com.whisper2.app.network.ws.WsState
import com.whisper2.app.storage.db.entities.ConversationEntity
import com.whisper2.app.storage.db.entities.MessageEntity

/**
 * Application-wide UI state
 * All data comes from real services, no mock data
 */

/**
 * Authentication state
 */
sealed class AuthState {
    /** Not authenticated, needs registration or recovery */
    object Unauthenticated : AuthState()

    /** Authentication in progress */
    object Authenticating : AuthState()

    /** Fully authenticated with valid session */
    data class Authenticated(
        val whisperId: String,
        val deviceId: String
    ) : AuthState()

    /** Authentication failed */
    data class Error(val message: String) : AuthState()
}

/**
 * Connection state for UI display
 */
data class ConnectionState(
    val wsState: WsState = WsState.DISCONNECTED,
    val isOnline: Boolean = false,
    val lastConnectedAt: Long? = null,
    val reconnectAttempt: Int = 0
)

/**
 * Conversation list item for UI
 */
data class ConversationUiItem(
    val id: String,
    val displayName: String, // WhisperID or contact name
    val lastMessage: String?,
    val lastMessageTime: Long,
    val unreadCount: Int,
    val isOnline: Boolean = false // If we track presence
) {
    companion object {
        fun from(entity: ConversationEntity, displayName: String? = null): ConversationUiItem {
            return ConversationUiItem(
                id = entity.id,
                displayName = displayName ?: entity.id.take(16), // Truncated WhisperID
                lastMessage = entity.lastMessagePreview,
                lastMessageTime = entity.lastMessageAt,
                unreadCount = entity.unreadCount
            )
        }
    }
}

/**
 * Message UI item with optional attachment
 */
data class MessageUiItem(
    val id: String,
    val text: String?,
    val timestamp: Long,
    val isOutgoing: Boolean,
    val status: MessageUiStatus,
    val senderName: String? = null, // For group chats
    val attachment: AttachmentUiItem? = null // UI-G5: Attachment support
) {
    companion object {
        fun from(entity: MessageEntity, attachmentState: AttachmentDownloadState? = null): MessageUiItem {
            // Parse attachment if present
            val attachment = if (entity.attachmentPointer != null) {
                AttachmentUiItem(
                    objectKey = entity.attachmentPointer,
                    contentType = entity.attachmentContentType ?: "application/octet-stream",
                    sizeBytes = entity.attachmentSize ?: 0,
                    state = attachmentState ?: AttachmentDownloadState.NotDownloaded,
                    localPath = entity.attachmentLocalPath
                )
            } else null

            return MessageUiItem(
                id = entity.messageId,
                text = entity.text,
                timestamp = entity.timestamp,
                isOutgoing = entity.isOutgoing,
                status = MessageUiStatus.fromString(entity.status),
                attachment = attachment
            )
        }
    }
}

/**
 * Attachment UI state
 */
data class AttachmentUiItem(
    val objectKey: String,
    val contentType: String,
    val sizeBytes: Long,
    val state: AttachmentDownloadState,
    val localPath: String? = null // Path when downloaded
) {
    val isImage: Boolean get() = contentType.startsWith("image/")
    val isVideo: Boolean get() = contentType.startsWith("video/")
    val isAudio: Boolean get() = contentType.startsWith("audio/")
    val isVoice: Boolean get() = contentType == "audio/aac" || contentType == "audio/opus"

    val displaySize: String get() {
        return when {
            sizeBytes < 1024 -> "$sizeBytes B"
            sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024} KB"
            else -> "${sizeBytes / (1024 * 1024)} MB"
        }
    }
}

/**
 * Attachment download state
 */
sealed class AttachmentDownloadState {
    object NotDownloaded : AttachmentDownloadState()
    data class Downloading(val progress: Float = 0f) : AttachmentDownloadState()
    object Ready : AttachmentDownloadState()
    data class Failed(val error: String) : AttachmentDownloadState()
}

/**
 * Message delivery status for UI
 */
enum class MessageUiStatus {
    PENDING,    // Queued, not sent yet
    SENDING,    // Currently being sent
    SENT,       // Server acknowledged
    DELIVERED,  // Recipient received
    READ,       // Recipient read
    FAILED;     // Send failed

    companion object {
        fun fromString(status: String): MessageUiStatus {
            return when (status.lowercase()) {
                "pending" -> PENDING
                "sending" -> SENDING
                "sent" -> SENT
                "delivered" -> DELIVERED
                "read" -> READ
                "failed" -> FAILED
                else -> PENDING
            }
        }
    }
}

/**
 * Outbox state for UI feedback
 */
data class OutboxState(
    val queuedCount: Int = 0,
    val sendingCount: Int = 0,
    val failedCount: Int = 0
) {
    val hasPending: Boolean get() = queuedCount > 0 || sendingCount > 0
    val hasFailures: Boolean get() = failedCount > 0
}

// =============================================================================
// UI-G6: Call State
// =============================================================================

/**
 * Call UI state for displaying call screens
 */
sealed class CallUiState {
    /** No active call */
    object Idle : CallUiState()

    /** Incoming call - show full-screen UI */
    data class Incoming(
        val callId: String,
        val from: String,
        val fromDisplayName: String? = null,
        val isVideo: Boolean
    ) : CallUiState()

    /** Outgoing call - ringing */
    data class Outgoing(
        val callId: String,
        val to: String,
        val toDisplayName: String? = null,
        val isVideo: Boolean
    ) : CallUiState()

    /** Connecting (after accept, before media flowing) */
    data class Connecting(
        val callId: String,
        val peerId: String,
        val isVideo: Boolean
    ) : CallUiState()

    /** Active call in progress */
    data class InCall(
        val callId: String,
        val peerId: String,
        val peerDisplayName: String? = null,
        val isVideo: Boolean,
        val durationSeconds: Int = 0,
        val isMuted: Boolean = false,
        val isSpeakerOn: Boolean = false,
        val isVideoEnabled: Boolean = true
    ) : CallUiState()

    /** Call ended */
    data class Ended(
        val callId: String,
        val reason: CallEndReason,
        val durationSeconds: Int = 0
    ) : CallUiState()
}

/**
 * Call end reasons
 */
enum class CallEndReason {
    ENDED,      // Normal end by either party
    DECLINED,   // Recipient declined
    BUSY,       // Recipient is busy
    TIMEOUT,    // No answer
    FAILED,     // Technical failure
    MISSED      // Incoming call not answered
}
