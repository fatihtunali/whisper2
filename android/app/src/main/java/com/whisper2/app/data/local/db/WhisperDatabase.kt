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
        GroupInviteEntity::class,
        OutboxEntity::class,
        CallRecordEntity::class
    ],
    version = 7,
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

        // Migration from version 3 to 4: Add group invites table
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS group_invites (
                        groupId TEXT NOT NULL PRIMARY KEY,
                        groupName TEXT NOT NULL,
                        inviterId TEXT NOT NULL,
                        inviterName TEXT NOT NULL,
                        memberCount INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL
                    )
                """)
            }
        }

        // Migration from version 4 to 5: Add avatar support for contacts and conversations
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add avatarPath to contacts table
                database.execSQL("ALTER TABLE contacts ADD COLUMN avatarPath TEXT DEFAULT NULL")
                // Add peerAvatarPath to conversations table
                database.execSQL("ALTER TABLE conversations ADD COLUMN peerAvatarPath TEXT DEFAULT NULL")
            }
        }

        // Migration from version 5 to 6: Add chat theme support for conversations
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add chatThemeId to conversations table with default value 'default'
                database.execSQL("ALTER TABLE conversations ADD COLUMN chatThemeId TEXT NOT NULL DEFAULT 'default'")
            }
        }

        // Migration from version 6 to 7: Add presence columns to contacts
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add isOnline and lastSeen columns to contacts table
                database.execSQL("ALTER TABLE contacts ADD COLUMN isOnline INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE contacts ADD COLUMN lastSeen INTEGER DEFAULT NULL")
            }
        }
    }
}
