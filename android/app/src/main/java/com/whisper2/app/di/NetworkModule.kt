package com.whisper2.app.di

import com.google.gson.Gson
import com.whisper2.app.core.Constants
import com.whisper2.app.data.network.api.WhisperApi
import com.whisper2.app.data.network.api.AttachmentsApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier annotation class WsClient
@Qualifier annotation class HttpClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides @Singleton @WsClient
    fun provideWsClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)  // Disabled for WebSocket
        .writeTimeout(30, TimeUnit.SECONDS)
        // REMOVED: pingInterval - we handle pings at application level
        // OkHttp's pingInterval can conflict with app-level heartbeat and cause unexpected disconnects
        .build()

    @Provides @Singleton @HttpClient
    fun provideHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    @Provides @Singleton
    fun provideRetrofit(@HttpClient client: OkHttpClient, gson: Gson): Retrofit = Retrofit.Builder()
        .baseUrl(Constants.BASE_URL)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()

    @Provides @Singleton
    fun provideWhisperApi(retrofit: Retrofit): WhisperApi = retrofit.create(WhisperApi::class.java)

    @Provides @Singleton
    fun provideAttachmentsApi(retrofit: Retrofit): AttachmentsApi = retrofit.create(AttachmentsApi::class.java)
}
