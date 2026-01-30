package com.whisper2.app.data.local.db.entities

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import java.text.SimpleDateFormat
import java.util.*

/** Disappearing message timer options - must match iOS */
enum class DisappearingMessageTimer(val value: String, val displayName: String, val durationMs: Long?) {
    OFF("off", "Off", null),
    ONE_DAY("24h", "24 hours", 24 * 60 * 60 * 1000L),
    SEVEN_DAYS("7d", "7 days", 7 * 24 * 60 * 60 * 1000L),
    THIRTY_DAYS("30d", "30 days", 30 * 24 * 60 * 60 * 1000L);

    companion object {
        fun fromValue(value: String?): DisappearingMessageTimer {
            return entries.find { it.value == value } ?: OFF
        }
    }
}

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
    val disappearingTimer: String = "off", // DisappearingMessageTimer value
    val updatedAt: Long = System.currentTimeMillis()
) {
    @get:Ignore
    val disappearingMessageTimer: DisappearingMessageTimer
        get() = DisappearingMessageTimer.fromValue(disappearingTimer)

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
