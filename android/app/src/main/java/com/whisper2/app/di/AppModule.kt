package com.whisper2.app.di

import android.content.Context
import com.whisper2.app.core.Logger
import com.whisper2.app.crypto.CryptoService
import com.whisper2.app.services.auth.ISessionManager
import com.whisper2.app.services.auth.SessionManager
import com.whisper2.app.services.cleanup.DataCleanup
import com.whisper2.app.storage.db.dao.ConversationDao
import com.whisper2.app.storage.db.dao.MessageDao
import com.whisper2.app.storage.key.SecurePrefs
import com.whisper2.app.ui.state.AppStateManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * Application DI Module
 *
 * Provides core application services and managers.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    private var instanceCount = 0

    @Provides
    @Singleton
    fun provideSecurePrefs(@ApplicationContext context: Context): SecurePrefs {
        instanceCount++
        Logger.info("SecurePrefs instance created (#$instanceCount)", Logger.Category.STORAGE)
        return SecurePrefs(context)
    }

    @Provides
    @Singleton
    fun provideSessionManager(securePrefs: SecurePrefs): ISessionManager {
        instanceCount++
        Logger.info("SessionManager instance created (#$instanceCount)", Logger.Category.AUTH)
        return SessionManager(securePrefs)
    }

    @Provides
    @Singleton
    fun provideAppStateManager(
        sessionManager: ISessionManager,
        conversationDao: ConversationDao,
        messageDao: MessageDao
    ): AppStateManager {
        instanceCount++
        Logger.info("AppStateManager instance created (#$instanceCount)", Logger.Category.AUTH)
        return AppStateManager(sessionManager, conversationDao, messageDao)
    }

    @Provides
    @Singleton
    fun provideDataCleanup(): DataCleanup {
        instanceCount++
        Logger.info("DataCleanup instance created (#$instanceCount)", Logger.Category.CLEANUP)
        return DataCleanup()
    }
}
