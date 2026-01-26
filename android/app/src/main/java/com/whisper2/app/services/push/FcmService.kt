package com.whisper2.app.services.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.whisper2.app.core.Logger
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Firebase Cloud Messaging Service
 *
 * Handles incoming push notifications with wake-only discipline.
 * SECURITY: This service NEVER processes message content from push.
 * All content is fetched via secure WebSocket.
 */
@AndroidEntryPoint
class FcmService : FirebaseMessagingService() {

    /**
     * Entry point for accessing Hilt dependencies in FirebaseMessagingService
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface FcmServiceEntryPoint {
        fun fcmTokenManager(): FcmTokenManager
        fun pushHandler(): PushHandler
    }

    private val entryPoint: FcmServiceEntryPoint by lazy {
        EntryPointAccessors.fromApplication(
            applicationContext,
            FcmServiceEntryPoint::class.java
        )
    }

    /**
     * Called when a message is received.
     *
     * This is called when the app is in foreground or when a data-only
     * message is received (which is what we use for wake-only pushes).
     */
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        Logger.debug("FCM message received from: ${remoteMessage.from}", Logger.Category.PUSH)

        // Get data payload
        val data = remoteMessage.data
        if (data.isEmpty()) {
            Logger.warn("Empty data payload, ignoring", Logger.Category.PUSH)
            return
        }

        Logger.debug("FCM data: type=${data["type"]}, reason=${data["reason"]}", Logger.Category.PUSH)

        // Get PushHandler and handle the wake
        try {
            val pushHandler = entryPoint.pushHandler()
            pushHandler.onRawPush(data)
        } catch (e: Exception) {
            Logger.error("Failed to process push: ${e.message}", Logger.Category.PUSH)
        }
    }

    /**
     * Called when a new token is generated.
     *
     * This is called on initial startup and whenever the token is refreshed.
     * The token should be sent to your server.
     */
    override fun onNewToken(token: String) {
        Logger.info("FCM token refreshed", Logger.Category.PUSH)

        try {
            val tokenManager = entryPoint.fcmTokenManager()
            tokenManager.onTokenRefreshed(token)
        } catch (e: Exception) {
            Logger.error("Failed to handle token refresh: ${e.message}", Logger.Category.PUSH)
        }
    }
}
