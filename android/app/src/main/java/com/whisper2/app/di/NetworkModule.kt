package com.whisper2.app.di

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.whisper2.app.core.Constants
import com.whisper2.app.core.Logger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private var instanceCount = 0

    @Provides
    @Singleton
    fun provideGson(): Gson {
        instanceCount++
        Logger.info("Gson instance created (#$instanceCount)", Logger.Category.NETWORK)
        return GsonBuilder()
            .setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .create()
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        instanceCount++
        Logger.info("OkHttpClient instance created (#$instanceCount)", Logger.Category.NETWORK)

        val loggingInterceptor = HttpLoggingInterceptor { message ->
            Logger.debug(message, Logger.Category.NETWORK)
        }.apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        return OkHttpClient.Builder()
            .connectTimeout(Constants.Timeout.WS_CONNECT, TimeUnit.MILLISECONDS)
            .readTimeout(Constants.Timeout.HTTP_REQUEST, TimeUnit.MILLISECONDS)
            .writeTimeout(Constants.Timeout.HTTP_REQUEST, TimeUnit.MILLISECONDS)
            .addInterceptor(loggingInterceptor)
            .build()
    }
}
