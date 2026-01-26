package com.whisper2.app.di

import android.content.Context
import com.whisper2.app.core.Logger
import com.whisper2.app.storage.SecureStorage
import com.whisper2.app.storage.SecureStorageImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    private var instanceCount = 0

    @Provides
    @Singleton
    fun provideSecureStorage(@ApplicationContext context: Context): SecureStorage {
        instanceCount++
        Logger.info("SecureStorage instance created (#$instanceCount)", Logger.Category.STORAGE)
        return SecureStorageImpl(context)
    }
}
