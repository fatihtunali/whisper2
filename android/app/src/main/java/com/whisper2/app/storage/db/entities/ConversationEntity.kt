package com.whisper2.app.storage.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Conversation entity for Room database
 *
 * Tracks conversation metadata like unread count and last message time.
 */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey
    val id: String, // peerId for direct, groupId for group

    /** Conversation type: direct or group */
    val type: String = ConversationType.DIRECT,

    /** Last message timestamp */
    val lastMessageAt: Long = 0,

    /** Unread message count */
    val unreadCount: Int = 0,

    /** Last message preview (optional) */
    val lastMessagePreview: String? = null,

    /** Created at timestamp */
    val createdAt: Long = System.currentTimeMillis()
)

object ConversationType {
    const val DIRECT = "direct"
    const val GROUP = "group"
}
