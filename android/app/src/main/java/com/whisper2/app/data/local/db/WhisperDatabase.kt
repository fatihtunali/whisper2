package com.whisper2.app.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.whisper2.app.data.local.db.dao.*
import com.whisper2.app.data.local.db.entities.*

@Database(
    entities = [
        MessageEntity::class,
        ConversationEntity::class,
        ContactEntity::class,
        GroupEntity::class,
        GroupMemberEntity::class,
        OutboxEntity::class,
        CallRecordEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class WhisperDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun contactDao(): ContactDao
    abstract fun groupDao(): GroupDao
    abstract fun outboxDao(): OutboxDao
    abstract fun callRecordDao(): CallRecordDao

    companion object {
        // Migration from version 1 to 2: Add unreadCount column to groups table
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE groups ADD COLUMN unreadCount INTEGER NOT NULL DEFAULT 0")
            }
        }

        // Migration from version 2 to 3: Add disappearing messages support
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add expiresAt to messages for disappearing messages
                database.execSQL("ALTER TABLE messages ADD COLUMN expiresAt INTEGER DEFAULT NULL")
                // Add disappearingTimer to conversations
                database.execSQL("ALTER TABLE conversations ADD COLUMN disappearingTimer TEXT NOT NULL DEFAULT 'off'")
            }
        }
    }
}
