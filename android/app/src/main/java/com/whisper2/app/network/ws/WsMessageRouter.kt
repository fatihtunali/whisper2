package com.whisper2.app.network.ws

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WebSocket Message Router
 *
 * Routes incoming WebSocket messages to appropriate handlers:
 * - Auth messages (register_challenge, register_ack) -> AuthCoordinator
 * - Messaging messages -> MessagingService
 * - System messages (pong, error) -> Handled here
 */
@Singleton
class WsMessageRouter @Inject constructor() {
    companion object {
        private const val TAG = "WsMessageRouter"
    }

    // Handler for auth messages
    private var authMessageHandler: ((String) -> Unit)? = null

    // Handler for messaging messages
    private var messagingHandler: ((String) -> Unit)? = null

    /**
     * Set handler for auth messages (register_challenge, register_ack, error during auth)
     */
    fun setAuthMessageHandler(handler: ((String) -> Unit)?) {
        authMessageHandler = handler
    }

    /**
     * Set handler for messaging messages
     */
    fun setMessagingHandler(handler: ((String) -> Unit)?) {
        messagingHandler = handler
    }

    /**
     * Route incoming message to appropriate handler
     */
    fun routeMessage(text: String) {
        try {
            val envelope = WsParser.parseRaw(text)
            Log.d(TAG, "Routing message: type=${envelope.type}")

            when (envelope.type) {
                // Auth messages
                WsMessageTypes.REGISTER_CHALLENGE,
                WsMessageTypes.REGISTER_ACK -> {
                    authMessageHandler?.invoke(text)
                        ?: Log.w(TAG, "No auth handler for ${envelope.type}")
                }

                // Error can be for auth or messaging
                WsMessageTypes.ERROR -> {
                    // If in auth flow, route to auth handler
                    if (authMessageHandler != null) {
                        authMessageHandler?.invoke(text)
                    } else {
                        messagingHandler?.invoke(text)
                    }
                }

                // System messages
                WsMessageTypes.PONG -> {
                    Log.d(TAG, "Received pong")
                    // Could update server time offset here
                }

                "force_logout" -> {
                    Log.w(TAG, "Received force_logout")
                    messagingHandler?.invoke(text)
                }

                // All other messages go to messaging handler
                else -> {
                    messagingHandler?.invoke(text)
                        ?: Log.w(TAG, "No messaging handler for ${envelope.type}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error routing message", e)
        }
    }
}
