package com.whisper2.app.data.local.db.entities

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import java.text.SimpleDateFormat
import java.util.*

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val groupId: String? = null,
    val from: String,
    val to: String,
    val contentType: String = "text", // text, audio, location, image, video, file
    val content: String,
    val timestamp: Long,
    val status: String = "pending", // pending, sent, delivered, read, failed
    val direction: String, // incoming, outgoing
    val replyTo: String? = null,

    // Attachment fields
    val attachmentBlobId: String? = null,
    val attachmentKey: String? = null,
    val attachmentNonce: String? = null,
    val attachmentMimeType: String? = null,
    val attachmentSize: Long? = null,
    val attachmentFileName: String? = null,
    val attachmentDuration: Int? = null,
    val attachmentWidth: Int? = null,
    val attachmentHeight: Int? = null,
    val attachmentThumbnail: String? = null,
    val attachmentLocalPath: String? = null,

    // Location fields
    val locationLatitude: Double? = null,
    val locationLongitude: Double? = null,
    val locationAccuracy: Float? = null,
    val locationPlaceName: String? = null,
    val locationAddress: String? = null,

    val createdAt: Long = System.currentTimeMillis(),
    val readAt: Long? = null,
    val expiresAt: Long? = null // For disappearing messages
) {
    @get:Ignore
    val isExpired: Boolean
        get() = expiresAt != null && System.currentTimeMillis() > expiresAt
    @get:Ignore
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
}
