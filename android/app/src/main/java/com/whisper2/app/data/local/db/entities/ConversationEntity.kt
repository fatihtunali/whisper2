package com.whisper2.app.data.local.db.entities

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import java.text.SimpleDateFormat
import java.util.*

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val peerId: String,
    val peerNickname: String?,
    val lastMessageId: String? = null,
    val lastMessagePreview: String? = null,
    val lastMessageTimestamp: Long? = null,
    val unreadCount: Int = 0,
    val isPinned: Boolean = false,
    val isMuted: Boolean = false,
    val isTyping: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
) {
    @get:Ignore
    val formattedTime: String
        get() {
            val timestamp = lastMessageTimestamp ?: return ""
            val now = System.currentTimeMillis()
            val diff = now - timestamp
            return when {
                diff < 60_000 -> "now"
                diff < 3600_000 -> "${diff / 60_000}m"
                diff < 86400_000 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
                diff < 604800_000 -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(timestamp))
                else -> SimpleDateFormat("dd/MM", Locale.getDefault()).format(Date(timestamp))
            }
        }
}
