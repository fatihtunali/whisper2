package com.whisper2.app.data.local.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey val messageId: String,
    val to: String,
    val groupId: String?,
    val msgType: String,
    val encryptedPayload: String,
    val retryCount: Int = 0,
    val lastAttemptAt: Long? = null,
    val createdAt: Long,
    val status: String
)
