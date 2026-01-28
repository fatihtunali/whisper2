package com.whisper2.app.di

import android.content.Context
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.whisper2.app.data.local.keystore.KeystoreManager
import com.whisper2.app.data.local.prefs.SecureStorage
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.security.SecureRandom
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CryptoModule {
    @Provides @Singleton
    fun provideLazySodium(): LazySodiumAndroid = LazySodiumAndroid(SodiumAndroid())

    @Provides @Singleton
    fun provideSecureRandom(): SecureRandom = SecureRandom()

    @Provides @Singleton
    fun provideKeystoreManager(): KeystoreManager = KeystoreManager()

    @Provides @Singleton
    fun provideSecureStorage(@ApplicationContext ctx: Context, km: KeystoreManager): SecureStorage =
        SecureStorage(ctx, km)
}
