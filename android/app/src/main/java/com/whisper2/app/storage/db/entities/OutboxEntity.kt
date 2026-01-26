package com.whisper2.app.storage.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Outbox entity for persistent message queue
 *
 * Stores messages waiting to be sent with retry state.
 * Survives app restarts.
 */
@Entity(
    tableName = "outbox",
    indices = [
        Index(value = ["messageId"], unique = true),
        Index(value = ["status"]),
        Index(value = ["nextRetryAt"])
    ]
)
data class OutboxEntity(
    @PrimaryKey
    val id: String,

    /** Message ID (also stored in MessageEntity) */
    val messageId: String,

    /** Request ID for correlating server responses */
    val requestId: String,

    /** Recipient WhisperID */
    val recipientId: String,

    /** JSON payload ready to send */
    val payload: String,

    /** Current status */
    val status: String = OutboxStatus.QUEUED,

    /** Number of send attempts */
    val attempts: Int = 0,

    /** Next retry timestamp (null if not scheduled) */
    val nextRetryAt: Long? = null,

    /** Failed error code (if status=failed) */
    val failedCode: String? = null,

    /** Failed error message (if status=failed) */
    val failedMessage: String? = null,

    /** Created timestamp */
    val createdAt: Long = System.currentTimeMillis(),

    /** Last attempt timestamp */
    val lastAttemptAt: Long? = null
)

/**
 * Outbox status values
 */
object OutboxStatus {
    /** Waiting in queue */
    const val QUEUED = "queued"

    /** Currently being sent */
    const val SENDING = "sending"

    /** Successfully sent (will be removed from outbox) */
    const val SENT = "sent"

    /** Permanently failed (no retry) */
    const val FAILED = "failed"
}
