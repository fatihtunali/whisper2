package com.whisper2.app.di

import android.content.Context
import androidx.room.Room
import com.whisper2.app.core.Constants
import com.whisper2.app.data.local.db.WhisperDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): WhisperDatabase =
        Room.databaseBuilder(ctx, WhisperDatabase::class.java, Constants.DATABASE_NAME)
            .addMigrations(
                WhisperDatabase.MIGRATION_1_2,
                WhisperDatabase.MIGRATION_2_3,
                WhisperDatabase.MIGRATION_3_4,
                WhisperDatabase.MIGRATION_4_5,
                WhisperDatabase.MIGRATION_5_6
            )
            .build()

    @Provides fun messageDao(db: WhisperDatabase) = db.messageDao()
    @Provides fun conversationDao(db: WhisperDatabase) = db.conversationDao()
    @Provides fun contactDao(db: WhisperDatabase) = db.contactDao()
    @Provides fun groupDao(db: WhisperDatabase) = db.groupDao()
    @Provides fun outboxDao(db: WhisperDatabase) = db.outboxDao()
    @Provides fun callRecordDao(db: WhisperDatabase) = db.callRecordDao()
}
