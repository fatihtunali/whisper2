package com.whisper2.app.storage.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Message entity for Room database
 *
 * Stores encrypted message data with decrypted text for text messages.
 * Private keys are NOT stored here - they remain in secure storage.
 */
@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["conversationId", "timestamp"]),
        Index(value = ["from"]),
        Index(value = ["to"])
    ]
)
data class MessageEntity(
    @PrimaryKey
    val messageId: String,

    /** Conversation ID: peerId for direct, groupId for group */
    val conversationId: String,

    /** Sender WhisperID */
    val from: String,

    /** Recipient WhisperID */
    val to: String,

    /** Message type: text, image, voice, file */
    val msgType: String,

    /** Timestamp in milliseconds */
    val timestamp: Long,

    /** Base64 encoded nonce (24 bytes) */
    val nonceB64: String,

    /** Base64 encoded ciphertext */
    val ciphertextB64: String,

    /** Base64 encoded signature (64 bytes) */
    val sigB64: String,

    /** Decrypted text content (only for msgType="text") */
    val text: String? = null,

    /** Delivery status: pending, sent, delivered, read, failed */
    val status: String = MessageStatus.DELIVERED,

    /** Is this an outgoing message from me */
    val isOutgoing: Boolean = false,

    /** Reply to message ID (optional) */
    val replyTo: String? = null,

    /** Created at timestamp (local) */
    val createdAt: Long = System.currentTimeMillis(),

    // =========================================================================
    // UI-G5: Attachment fields
    // =========================================================================

    /** S3 object key for attachment (null = no attachment) */
    val attachmentPointer: String? = null,

    /** MIME type of attachment (e.g., "image/jpeg", "audio/aac") */
    val attachmentContentType: String? = null,

    /** Size of attachment in bytes (ciphertext size) */
    val attachmentSize: Long? = null,

    /** Local file path when downloaded (null = not downloaded) */
    val attachmentLocalPath: String? = null
)

object MessageStatus {
    const val PENDING = "pending"
    const val SENT = "sent"
    const val DELIVERED = "delivered"
    const val READ = "read"
    const val FAILED = "failed"
}

object MessageType {
    const val TEXT = "text"
    const val IMAGE = "image"
    const val VOICE = "voice"
    const val FILE = "file"
    const val SYSTEM = "system"
}
