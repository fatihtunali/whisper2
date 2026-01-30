package com.whisper2.app.data.network.ws

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import com.whisper2.app.core.Constants
import com.whisper2.app.core.Logger
import com.whisper2.app.di.ApplicationScope
import com.whisper2.app.di.WsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WsClientImpl @Inject constructor(
    @WsClient private val okHttpClient: OkHttpClient,
    private val gson: Gson,
    @ApplicationScope private val scope: CoroutineScope
) {
    private var webSocket: WebSocket? = null

    private val _connectionState = MutableStateFlow(WsConnectionState.DISCONNECTED)
    val connectionState: StateFlow<WsConnectionState> = _connectionState.asStateFlow()

    // Mutex to prevent multiple simultaneous connect attempts
    private val connectMutex = Mutex()

    private val _messages = MutableSharedFlow<WsFrame<JsonElement>>(extraBufferCapacity = 100)
    val messages = _messages.asSharedFlow()

    private val reconnectPolicy = WsReconnectPolicy()

    // Heartbeat tracking
    private var heartbeatJob: Job? = null
    private var lastPongTime: Long = System.currentTimeMillis()
    private val pongTimeout: Long = 60_000  // 60 seconds timeout for pong

    fun connect() {
        // Prevent multiple simultaneous connect attempts
        val currentState = _connectionState.value
        if (currentState == WsConnectionState.CONNECTED ||
            currentState == WsConnectionState.CONNECTING ||
            currentState == WsConnectionState.RECONNECTING) {
            Logger.ws("Skipping connect - already $currentState")
            return
        }

        _connectionState.value = WsConnectionState.CONNECTING
        Logger.ws("Connecting to ${Constants.WS_URL}")

        val request = Request.Builder().url(Constants.WS_URL).build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Logger.ws("Connected")
                _connectionState.value = WsConnectionState.CONNECTED
                reconnectPolicy.reset()
                lastPongTime = System.currentTimeMillis()
                startHeartbeat()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch {
                    try {
                        val frameType = object : TypeToken<WsFrame<JsonElement>>() {}.type
                        val frame: WsFrame<JsonElement> = gson.fromJson(text, frameType)
                        Logger.ws("Received: ${frame.type}")

                        // Handle PING from server - respond with PONG
                        if (frame.type == Constants.MsgType.PING) {
                            handleServerPing(frame.payload)
                            return@launch
                        }

                        // Handle PONG from server - update last pong time
                        if (frame.type == Constants.MsgType.PONG) {
                            lastPongTime = System.currentTimeMillis()
                            Logger.ws("Pong received")
                            return@launch
                        }

                        _messages.emit(frame)
                    } catch (e: Exception) {
                        Logger.e("Failed to parse WS message", e)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Logger.e("WebSocket failure", t)
                stopHeartbeat()
                _connectionState.value = WsConnectionState.DISCONNECTED
                attemptReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Logger.ws("Closed: $code $reason")
                stopHeartbeat()
                _connectionState.value = WsConnectionState.DISCONNECTED
                // Also attempt reconnect on normal close (server may have restarted)
                if (code != 1000) {  // 1000 = normal closure by client
                    attemptReconnect()
                }
            }
        })
    }

    /**
     * Handle PING from server by sending PONG response
     */
    private fun handleServerPing(payload: JsonElement?) {
        try {
            val pingPayload = if (payload != null) {
                gson.fromJson(payload, PingPayload::class.java)
            } else {
                PingPayload(System.currentTimeMillis())
            }

            val pongPayload = PongPayload(
                timestamp = pingPayload.timestamp,
                serverTime = System.currentTimeMillis()
            )
            send(WsFrame(Constants.MsgType.PONG, payload = pongPayload))
            Logger.ws("Sent PONG response to server PING")
        } catch (e: Exception) {
            Logger.e("Failed to send PONG response", e)
        }
    }

    fun <T> send(frame: WsFrame<T>) {
        val json = gson.toJson(frame)
        Logger.ws("Sending: ${frame.type}")
        webSocket?.send(json)
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        lastPongTime = System.currentTimeMillis()

        heartbeatJob = scope.launch {
            while (connectionState.value == WsConnectionState.CONNECTED) {
                delay(Constants.HEARTBEAT_INTERVAL_MS)

                if (connectionState.value == WsConnectionState.CONNECTED) {
                    // Check if we've received a pong recently
                    val timeSinceLastPong = System.currentTimeMillis() - lastPongTime
                    if (timeSinceLastPong > pongTimeout) {
                        Logger.ws("Pong timeout (${timeSinceLastPong}ms since last pong), closing connection")
                        webSocket?.close(1000, "Pong timeout")
                        _connectionState.value = WsConnectionState.DISCONNECTED
                        attemptReconnect()
                        break
                    }

                    // Send ping
                    send(WsFrame(Constants.MsgType.PING, payload = PingPayload(System.currentTimeMillis())))
                    Logger.ws("Ping sent")
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun attemptReconnect() {
        if (reconnectPolicy.shouldRetry()) {
            _connectionState.value = WsConnectionState.RECONNECTING
            scope.launch {
                val delayMs = reconnectPolicy.getDelayMs()
                Logger.ws("Reconnecting in ${delayMs}ms (attempt ${reconnectPolicy.attemptCount})")
                delay(delayMs)
                connect()
            }
        } else {
            Logger.ws("Max reconnect attempts reached or auth expired")
        }
    }

    /**
     * Called when app enters foreground - reconnect if disconnected
     */
    fun handleAppForeground() {
        Logger.ws("App entered foreground, checking connection...")
        if (_connectionState.value == WsConnectionState.DISCONNECTED ||
            _connectionState.value == WsConnectionState.AUTH_EXPIRED) {
            reconnectPolicy.reset()
            connect()
        } else if (_connectionState.value == WsConnectionState.CONNECTED) {
            // Send a ping to verify connection is still alive
            send(WsFrame(Constants.MsgType.PING, payload = PingPayload(System.currentTimeMillis())))
        }
    }

    /**
     * Called when app enters background
     */
    fun handleAppBackground() {
        Logger.ws("App entered background")
        // Don't disconnect - let Android manage the connection
        // But the heartbeat will continue in the coroutine scope
    }

    fun disconnect() {
        stopHeartbeat()
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _connectionState.value = WsConnectionState.DISCONNECTED
    }

    fun markAuthExpired() {
        reconnectPolicy.markAuthExpired()
        _connectionState.value = WsConnectionState.AUTH_EXPIRED
    }

    /**
     * Reset reconnect policy (called after successful auth)
     */
    fun resetReconnectPolicy() {
        reconnectPolicy.reset()
    }
}
