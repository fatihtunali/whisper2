package com.whisper2.app.data.local.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val whisperId: String,
    val displayName: String,
    val encPublicKey: String? = null,
    val signPublicKey: String? = null,
    val isBlocked: Boolean = false,
    val isMessageRequest: Boolean = false,
    val isOnline: Boolean = false,
    val lastSeen: Long? = null,
    val avatarPath: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
