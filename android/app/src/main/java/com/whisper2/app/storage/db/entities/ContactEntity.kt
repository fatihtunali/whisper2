package com.whisper2.app.storage.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Contact entity for Room database
 *
 * Stores contact information with public keys.
 * This table is fully replaced on restore from backup.
 */
@Entity(
    tableName = "contacts",
    indices = [
        Index(value = ["whisperId"], unique = true)
    ]
)
data class ContactEntity(
    @PrimaryKey
    val id: String,

    /** Contact's WhisperID */
    val whisperId: String,

    /** Display name (user-entered) */
    val displayName: String? = null,

    /** Base64 encoded X25519 encryption public key (32 bytes) */
    val encPublicKeyB64: String,

    /** Base64 encoded Ed25519 signing public key (32 bytes) */
    val signPublicKeyB64: String,

    /** When the contact was added (local time) */
    val addedAt: Long = System.currentTimeMillis(),

    /** When keys were last updated */
    val keysUpdatedAt: Long = System.currentTimeMillis(),

    /** Is this contact blocked */
    val isBlocked: Boolean = false,

    /** Is this contact a favorite */
    val isFavorite: Boolean = false
)

/**
 * Contact for JSON serialization (backup format)
 * Simpler than ContactEntity, without local-only fields
 */
data class ContactBackupItem(
    val whisperId: String,
    val displayName: String?,
    val encPublicKey: String,
    val signPublicKey: String,
    val isBlocked: Boolean,
    val isFavorite: Boolean
)
