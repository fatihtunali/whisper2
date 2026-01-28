package com.whisper2.app.data.local.db.entities

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import java.text.SimpleDateFormat
import java.util.*

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val groupId: String,
    val name: String,
    val creatorId: String,
    val memberCount: Int = 0,
    val unreadCount: Int = 0,
    val lastMessagePreview: String? = null,
    val lastMessageTimestamp: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    @get:Ignore
    val formattedTime: String
        get() {
            val timestamp = lastMessageTimestamp ?: return ""
            return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        }
}

@Entity(tableName = "group_members")
data class GroupMemberEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: String,
    val memberId: String,
    val role: String = "member", // admin, member
    val joinedAt: Long = System.currentTimeMillis()
)
