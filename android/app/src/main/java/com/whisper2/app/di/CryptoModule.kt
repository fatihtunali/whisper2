package com.whisper2.app.di

import com.whisper2.app.core.Logger
import com.whisper2.app.crypto.CryptoService
import com.whisper2.app.storage.SecureStorage
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CryptoModule {

    private var instanceCount = 0

    @Provides
    @Singleton
    fun provideCryptoService(secureStorage: SecureStorage): CryptoService {
        instanceCount++
        Logger.info("CryptoService instance created (#$instanceCount)", Logger.Category.CRYPTO)
        return CryptoService(secureStorage)
    }
}
