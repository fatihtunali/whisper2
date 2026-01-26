package com.whisper2.app.services.calls

import com.whisper2.app.network.ws.TurnCredentialsPayload
import com.whisper2.app.network.ws.GetTurnCredentialsPayload
import com.whisper2.app.network.ws.WsMessageTypes
import com.whisper2.app.network.ws.WsParser
import com.whisper2.app.network.ws.WsRawEnvelope
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/**
 * Step 12: TURN Credentials Service
 *
 * Handles TURN server credential requests via WebSocket.
 * Correlates requests/responses using requestId.
 */
interface TurnService {
    /**
     * Request TURN credentials from server
     *
     * @return TurnCredentialsPayload with server URLs and credentials
     * @throws TurnException on failure
     */
    suspend fun requestTurnCreds(): TurnCredentialsPayload

    /**
     * Handle incoming WebSocket message
     * Should be called for turn_credentials responses
     */
    fun onWsMessage(envelope: WsRawEnvelope)

    /**
     * Get cached credentials if still valid
     */
    fun getCachedCreds(): TurnCredentialsPayload?

    sealed class TurnException(message: String) : Exception(message) {
        class Timeout : TurnException("TURN credentials request timed out")
        class ServerError(val code: String, val msg: String) : TurnException("Server error: $code - $msg")
        class NotConnected : TurnException("WebSocket not connected")
    }
}

/**
 * Default TurnService implementation
 */
class TurnServiceImpl(
    private val wsSender: WsSender,
    private val sessionProvider: () -> String?
) : TurnService {

    /**
     * Interface for sending WebSocket messages
     */
    interface WsSender {
        fun send(message: String): Boolean
    }

    private val pendingRequests = ConcurrentHashMap<String, Continuation<TurnCredentialsPayload>>()
    private var cachedCreds: CachedCredentials? = null

    private data class CachedCredentials(
        val creds: TurnCredentialsPayload,
        val expiresAt: Long
    )

    override suspend fun requestTurnCreds(): TurnCredentialsPayload {
        // Check cache first
        cachedCreds?.let { cached ->
            if (System.currentTimeMillis() < cached.expiresAt) {
                return cached.creds
            }
        }

        val sessionToken = sessionProvider()
            ?: throw TurnService.TurnException.NotConnected()

        val requestId = UUID.randomUUID().toString()
        val payload = GetTurnCredentialsPayload(sessionToken = sessionToken)
        val message = WsParser.createEnvelope(
            WsMessageTypes.GET_TURN_CREDENTIALS,
            payload,
            requestId
        )

        return withTimeout(10_000L) {
            suspendCancellableCoroutine { continuation ->
                pendingRequests[requestId] = continuation

                continuation.invokeOnCancellation {
                    pendingRequests.remove(requestId)
                }

                val sent = wsSender.send(message)
                if (!sent) {
                    pendingRequests.remove(requestId)
                    continuation.resumeWithException(TurnService.TurnException.NotConnected())
                }
            }
        }
    }

    override fun onWsMessage(envelope: WsRawEnvelope) {
        if (envelope.type != WsMessageTypes.TURN_CREDENTIALS) return

        val requestId = envelope.requestId ?: return
        val continuation = pendingRequests.remove(requestId) ?: return

        val payload = WsParser.parsePayload<TurnCredentialsPayload>(envelope.payload)
        if (payload != null) {
            // Cache credentials
            val expiresAt = System.currentTimeMillis() + (payload.ttl * 1000L) - 30_000L // 30s buffer
            cachedCreds = CachedCredentials(payload, expiresAt)

            continuation.resume(payload)
        } else {
            continuation.resumeWithException(
                TurnService.TurnException.ServerError("INVALID_PAYLOAD", "Failed to parse TURN credentials")
            )
        }
    }

    override fun getCachedCreds(): TurnCredentialsPayload? {
        return cachedCreds?.let { cached ->
            if (System.currentTimeMillis() < cached.expiresAt) cached.creds else null
        }
    }
}
