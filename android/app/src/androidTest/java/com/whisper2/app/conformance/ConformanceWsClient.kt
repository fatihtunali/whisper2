package com.whisper2.app.conformance

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Conformance WebSocket Client
 *
 * Features:
 * - Frame capture for logging
 * - Message waiting with timeout
 * - Request/response correlation
 * - Auto-reconnect disabled (explicit control)
 */
class ConformanceWsClient(
    private val url: String = ConformanceConfig.WS_URL
) {
    private val gson = Gson()
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(ConformanceConfig.Timeout.WS_CONNECT, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // No read timeout for WS
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // Message channels
    private val messageChannel = Channel<JsonObject>(Channel.UNLIMITED)
    private val pendingRequests = mutableMapOf<String, CompletableDeferred<JsonObject>>()
    private val mutex = Mutex()

    // All received messages (for inspection)
    private val receivedMessages = CopyOnWriteArrayList<JsonObject>()

    // Connection state
    @Volatile
    var isConnected: Boolean = false
        private set

    @Volatile
    var connectionError: Throwable? = null
        private set

    private var connectDeferred: CompletableDeferred<Boolean>? = null

    // ==========================================================================
    // Connection Management
    // ==========================================================================

    suspend fun connect(): Boolean {
        if (isConnected) return true

        connectDeferred = CompletableDeferred()

        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                ConformanceLogger.info("WS connected to $url")
                isConnected = true
                connectionError = null
                connectDeferred?.complete(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                ConformanceLogger.info("WS closing: $code - $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ConformanceLogger.info("WS closed: $code - $reason")
                isConnected = false
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ConformanceLogger.error("WS failure: ${t.message}", t)
                isConnected = false
                connectionError = t
                connectDeferred?.complete(false)

                // Cancel all pending requests
                runBlocking {
                    mutex.withLock {
                        pendingRequests.values.forEach {
                            it.completeExceptionally(t)
                        }
                        pendingRequests.clear()
                    }
                }
            }
        })

        return withTimeoutOrNull(ConformanceConfig.Timeout.WS_CONNECT) {
            connectDeferred?.await() ?: false
        } ?: false
    }

    fun disconnect() {
        webSocket?.close(1000, "Test complete")
        webSocket = null
        isConnected = false
    }

    // ==========================================================================
    // Message Handling
    // ==========================================================================

    private fun handleMessage(text: String) {
        try {
            val json = JsonParser.parseString(text).asJsonObject
            val type = json.get("type")?.asString ?: "unknown"
            val requestId = json.get("requestId")?.asString

            // Capture for logging
            ConformanceLogger.captureWsReceived(type, requestId, text)

            // Store for inspection
            receivedMessages.add(json)

            // Check if this is a response to a pending request
            if (requestId != null) {
                runBlocking {
                    mutex.withLock {
                        pendingRequests.remove(requestId)?.complete(json)
                    }
                }
            }

            // Also put in general channel for waitForMessage
            runBlocking {
                messageChannel.send(json)
            }

        } catch (e: Exception) {
            ConformanceLogger.error("Failed to parse WS message: ${e.message}")
        }
    }

    // ==========================================================================
    // Sending Messages
    // ==========================================================================

    suspend fun send(type: String, payload: Any, requestId: String = UUID.randomUUID().toString()): Boolean {
        val envelope = mapOf(
            "type" to type,
            "requestId" to requestId,
            "payload" to payload
        )

        val json = gson.toJson(envelope)
        ConformanceLogger.captureWsSent(type, requestId, json)

        return webSocket?.send(json) ?: false
    }

    /**
     * Send and wait for response with matching requestId
     */
    suspend fun sendAndWait(
        type: String,
        payload: Any,
        requestId: String = UUID.randomUUID().toString(),
        timeoutMs: Long = ConformanceConfig.Timeout.WS_MESSAGE
    ): JsonObject {
        val deferred = CompletableDeferred<JsonObject>()

        mutex.withLock {
            pendingRequests[requestId] = deferred
        }

        if (!send(type, payload, requestId)) {
            mutex.withLock {
                pendingRequests.remove(requestId)
            }
            throw IllegalStateException("Failed to send message")
        }

        return withTimeout(timeoutMs) {
            deferred.await()
        }
    }

    // ==========================================================================
    // Message Waiting
    // ==========================================================================

    /**
     * Wait for a message of specific type
     */
    suspend fun waitForMessage(
        type: String,
        timeoutMs: Long = ConformanceConfig.Timeout.WS_MESSAGE,
        predicate: (JsonObject) -> Boolean = { true }
    ): JsonObject {
        return withTimeout(timeoutMs) {
            while (true) {
                val msg = messageChannel.receive()
                val msgType = msg.get("type")?.asString
                if (msgType == type && predicate(msg)) {
                    return@withTimeout msg
                }
            }
            @Suppress("UNREACHABLE_CODE")
            throw IllegalStateException("Unreachable")
        }
    }

    /**
     * Wait for any of specified message types
     */
    suspend fun waitForAnyMessage(
        types: Set<String>,
        timeoutMs: Long = ConformanceConfig.Timeout.WS_MESSAGE
    ): JsonObject {
        return withTimeout(timeoutMs) {
            while (true) {
                val msg = messageChannel.receive()
                val msgType = msg.get("type")?.asString
                if (msgType in types) {
                    return@withTimeout msg
                }
            }
            @Suppress("UNREACHABLE_CODE")
            throw IllegalStateException("Unreachable")
        }
    }

    // ==========================================================================
    // Inspection
    // ==========================================================================

    fun getReceivedMessages(): List<JsonObject> = receivedMessages.toList()

    fun findMessage(type: String, predicate: (JsonObject) -> Boolean = { true }): JsonObject? {
        return receivedMessages.find {
            it.get("type")?.asString == type && predicate(it)
        }
    }

    fun countMessages(type: String): Int {
        return receivedMessages.count { it.get("type")?.asString == type }
    }

    fun clearReceivedMessages() {
        receivedMessages.clear()
    }

    // ==========================================================================
    // Utilities
    // ==========================================================================

    fun isOpen(): Boolean = isConnected && webSocket != null
}
