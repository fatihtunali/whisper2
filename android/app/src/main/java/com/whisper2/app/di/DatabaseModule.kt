package com.whisper2.app.di

import android.content.Context
import androidx.room.Room
import com.whisper2.app.storage.db.WhisperDatabase
import com.whisper2.app.storage.db.dao.ContactDao
import com.whisper2.app.storage.db.dao.ConversationDao
import com.whisper2.app.storage.db.dao.MessageDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Database DI Module
 *
 * Provides Room database and DAOs.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): WhisperDatabase {
        return Room.databaseBuilder(
            context,
            WhisperDatabase::class.java,
            WhisperDatabase.DATABASE_NAME
        )
            .fallbackToDestructiveMigration() // For development - handle migrations properly in production
            .build()
    }

    @Provides
    @Singleton
    fun provideMessageDao(database: WhisperDatabase): MessageDao {
        return database.messageDao()
    }

    @Provides
    @Singleton
    fun provideConversationDao(database: WhisperDatabase): ConversationDao {
        return database.conversationDao()
    }

    @Provides
    @Singleton
    fun provideContactDao(database: WhisperDatabase): ContactDao {
        return database.contactDao()
    }
}
