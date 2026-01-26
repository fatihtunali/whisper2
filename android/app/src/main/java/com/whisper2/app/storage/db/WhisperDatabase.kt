package com.whisper2.app.storage.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.whisper2.app.storage.db.dao.ContactDao
import com.whisper2.app.storage.db.dao.ConversationDao
import com.whisper2.app.storage.db.dao.MessageDao
import com.whisper2.app.storage.db.entities.ContactEntity
import com.whisper2.app.storage.db.entities.ConversationEntity
import com.whisper2.app.storage.db.entities.MessageEntity

/**
 * Whisper Room Database
 *
 * Stores messages, conversations, and contacts.
 * Encrypted by Android Keystore.
 */
@Database(
    entities = [
        MessageEntity::class,
        ConversationEntity::class,
        ContactEntity::class
    ],
    version = 2, // UI-G5: Added attachment fields to MessageEntity
    exportSchema = true
)
abstract class WhisperDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun contactDao(): ContactDao

    companion object {
        const val DATABASE_NAME = "whisper_db"
    }
}
