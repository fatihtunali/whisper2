package com.whisper2.app.services.push

import com.whisper2.app.core.Constants
import com.whisper2.app.core.Logger
import com.whisper2.app.network.ws.UpdateTokensPayload
import com.whisper2.app.network.ws.WsClient
import com.whisper2.app.network.ws.WsEnvelope
import com.whisper2.app.network.ws.WsMessageTypes
import com.whisper2.app.network.ws.WsParser
import com.whisper2.app.services.auth.ISessionManager
import com.whisper2.app.storage.key.SecurePrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FCM Token Manager
 *
 * Handles FCM token storage and registration with the server.
 * Sends update_tokens message when token changes or session refreshes.
 */
@Singleton
class FcmTokenManager @Inject constructor(
    private val securePrefs: SecurePrefs,
    private val sessionManager: ISessionManager,
    private val wsClient: WsClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Called when FCM token is refreshed.
     * Stores locally and sends to server if session is active.
     */
    fun onTokenRefreshed(token: String) {
        Logger.info("FCM token refreshed", Logger.Category.PUSH)

        // Store locally using the fcmToken property
        securePrefs.fcmToken = token

        // Send to server if we have a session
        sendTokenToServer(token)
    }

    /**
     * Get current stored FCM token
     */
    fun getCurrentToken(): String? {
        return securePrefs.fcmToken
    }

    /**
     * Send current token to server (call after login/session refresh)
     */
    fun sendCurrentTokenToServer() {
        val token = getCurrentToken()
        if (token != null) {
            sendTokenToServer(token)
        }
    }

    /**
     * Clear stored token (call on logout)
     */
    fun clearToken() {
        securePrefs.fcmToken = null
        Logger.info("FCM token cleared", Logger.Category.PUSH)
    }

    private fun sendTokenToServer(pushToken: String) {
        val sessionToken = sessionManager.sessionToken
        if (sessionToken == null) {
            Logger.debug("No session token, skipping FCM token update", Logger.Category.PUSH)
            return
        }

        scope.launch {
            try {
                val payload = UpdateTokensPayload(
                    protocolVersion = Constants.PROTOCOL_VERSION,
                    cryptoVersion = Constants.CRYPTO_VERSION,
                    sessionToken = sessionToken,
                    pushToken = pushToken,
                    voipToken = null // Android doesn't use VoIP tokens
                )

                val json = WsParser.createEnvelope(
                    type = WsMessageTypes.UPDATE_TOKENS,
                    payload = payload
                )

                val sent = wsClient.send(json)
                if (sent) {
                    Logger.info("FCM token sent to server", Logger.Category.PUSH)
                } else {
                    Logger.warn("Failed to send FCM token - WS not connected", Logger.Category.PUSH)
                }
            } catch (e: Exception) {
                Logger.error("Failed to send FCM token: ${e.message}", Logger.Category.PUSH)
            }
        }
    }
}
