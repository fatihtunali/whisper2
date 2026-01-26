package com.whisper2.app.di

import com.whisper2.app.core.Logger
import com.whisper2.app.network.ws.WsClient
import com.whisper2.app.network.ws.WsState
import com.whisper2.app.services.auth.ISessionManager
import com.whisper2.app.services.calls.CallUiService
import com.whisper2.app.services.messaging.MessagingService
import com.whisper2.app.services.push.FcmTokenManager
import com.whisper2.app.services.push.IncomingCallUi
import com.whisper2.app.services.push.PendingMessageFetcher
import com.whisper2.app.services.push.PushHandler
import com.whisper2.app.services.push.WsConnectionManager
import com.whisper2.app.storage.key.SecurePrefs
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Push Notification DI Module
 *
 * Provides push-related services:
 * - FcmTokenManager
 * - PushHandler
 */
@Module
@InstallIn(SingletonComponent::class)
object PushModule {

    @Provides
    @Singleton
    fun provideFcmTokenManager(
        securePrefs: SecurePrefs,
        sessionManager: ISessionManager,
        wsClient: WsClient
    ): FcmTokenManager {
        Logger.info("FcmTokenManager instance created", Logger.Category.PUSH)
        return FcmTokenManager(securePrefs, sessionManager, wsClient)
    }

    @Provides
    @Singleton
    fun provideWsConnectionManager(wsClient: WsClient): WsConnectionManager {
        return object : WsConnectionManager {
            override fun isConnected(): Boolean = wsClient.state == WsState.CONNECTED
            override fun connect() = wsClient.connect()
        }
    }

    @Provides
    @Singleton
    fun providePendingMessageFetcher(
        wsClient: WsClient,
        sessionManager: ISessionManager
    ): PendingMessageFetcher {
        return PendingMessageFetcher {
            // Send fetch_pending message via WebSocket
            val sessionToken = sessionManager.sessionToken
            if (sessionToken != null && wsClient.state == WsState.CONNECTED) {
                val payload = mapOf(
                    "protocolVersion" to com.whisper2.app.core.Constants.PROTOCOL_VERSION,
                    "cryptoVersion" to com.whisper2.app.core.Constants.CRYPTO_VERSION,
                    "sessionToken" to sessionToken,
                    "limit" to 50
                )
                val envelope = mapOf(
                    "type" to "fetch_pending",
                    "payload" to payload
                )
                val json = com.google.gson.Gson().toJson(envelope)
                wsClient.send(json)
                Logger.debug("Sent fetch_pending request", Logger.Category.PUSH)
            } else {
                Logger.warn("Cannot fetch pending: no session or WS not connected", Logger.Category.PUSH)
            }
        }
    }

    @Provides
    @Singleton
    fun provideIncomingCallUi(callUiService: CallUiService): IncomingCallUi {
        return object : IncomingCallUi {
            override fun showIncomingCall(whisperId: String) {
                // Generate a temporary call ID for the push wake
                val callId = "push_wake_${System.currentTimeMillis()}"
                callUiService.showIncomingCall(callId, whisperId, isVideo = false)
                Logger.info("Showing incoming call UI for push wake from $whisperId", Logger.Category.PUSH)
            }
        }
    }

    @Provides
    @Singleton
    fun providePushHandler(
        wsConnectionManager: WsConnectionManager,
        pendingMessageFetcher: PendingMessageFetcher,
        incomingCallUi: IncomingCallUi
    ): PushHandler {
        Logger.info("PushHandler instance created", Logger.Category.PUSH)
        return PushHandler(wsConnectionManager, pendingMessageFetcher, incomingCallUi)
    }
}
