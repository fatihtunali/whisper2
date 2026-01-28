package com.whisper2.app.di

import android.content.Context
import com.google.gson.Gson
import com.whisper2.app.data.local.db.dao.*
import com.whisper2.app.data.local.prefs.SecureStorage
import com.whisper2.app.data.network.api.AttachmentsApi
import com.whisper2.app.data.network.ws.WsClientImpl
import com.whisper2.app.crypto.CryptoService
import com.whisper2.app.services.attachments.AttachmentService
import com.whisper2.app.services.auth.AuthService
import com.whisper2.app.services.contacts.ContactsService
import com.whisper2.app.services.groups.GroupService
import com.whisper2.app.services.messaging.MessageHandler
import com.whisper2.app.services.messaging.MessagingService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ServiceModule {

    @Provides
    @Singleton
    fun provideAuthService(
        wsClient: WsClientImpl,
        secureStorage: SecureStorage,
        cryptoService: CryptoService,
        gson: Gson
    ): AuthService = AuthService(wsClient, secureStorage, cryptoService, gson)

    @Provides
    @Singleton
    fun provideAttachmentService(
        @ApplicationContext context: Context,
        attachmentsApi: AttachmentsApi,
        cryptoService: CryptoService,
        secureStorage: SecureStorage,
        @HttpClient httpClient: OkHttpClient
    ): AttachmentService = AttachmentService(
        context, attachmentsApi, cryptoService, secureStorage, httpClient
    )

    @Provides
    @Singleton
    fun provideMessagingService(
        messageDao: MessageDao,
        conversationDao: ConversationDao,
        contactDao: ContactDao,
        outboxDao: OutboxDao,
        wsClient: WsClientImpl,
        cryptoService: CryptoService,
        secureStorage: SecureStorage,
        attachmentService: AttachmentService
    ): MessagingService = MessagingService(
        messageDao, conversationDao, contactDao, outboxDao, wsClient, cryptoService, secureStorage, attachmentService
    )

    @Provides
    @Singleton
    fun provideContactsService(
        contactDao: ContactDao,
        wsClient: WsClientImpl,
        secureStorage: SecureStorage
    ): ContactsService = ContactsService(contactDao, wsClient, secureStorage)

    // GroupService is provided via @Inject constructor

    @Provides
    @Singleton
    fun provideMessageHandler(
        wsClient: WsClientImpl,
        messageDao: MessageDao,
        conversationDao: ConversationDao,
        contactDao: ContactDao,
        cryptoService: CryptoService,
        secureStorage: SecureStorage,
        gson: Gson
    ): MessageHandler = MessageHandler(
        wsClient, messageDao, conversationDao, contactDao, cryptoService, secureStorage, gson
    )
}
