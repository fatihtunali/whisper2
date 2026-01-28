package com.whisper2.app.data.network.ws

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import com.whisper2.app.core.Constants
import com.whisper2.app.core.Logger
import com.whisper2.app.di.ApplicationScope
import com.whisper2.app.di.WsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    private val _messages = MutableSharedFlow<WsFrame<JsonElement>>(extraBufferCapacity = 100)
    val messages = _messages.asSharedFlow()

    private val reconnectPolicy = WsReconnectPolicy()

    fun connect() {
        if (_connectionState.value == WsConnectionState.CONNECTED) return

        _connectionState.value = WsConnectionState.CONNECTING
        Logger.ws("Connecting to ${Constants.WS_URL}")

        val request = Request.Builder().url(Constants.WS_URL).build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Logger.ws("Connected")
                _connectionState.value = WsConnectionState.CONNECTED
                reconnectPolicy.reset()
                startHeartbeat()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch {
                    try {
                        val frameType = object : TypeToken<WsFrame<JsonElement>>() {}.type
                        val frame: WsFrame<JsonElement> = gson.fromJson(text, frameType)
                        Logger.ws("Received: ${frame.type}")
                        _messages.emit(frame)
                    } catch (e: Exception) {
                        Logger.e("Failed to parse WS message", e)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Logger.e("WebSocket failure", t)
                _connectionState.value = WsConnectionState.DISCONNECTED
                attemptReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Logger.ws("Closed: $code $reason")
                _connectionState.value = WsConnectionState.DISCONNECTED
            }
        })
    }

    fun <T> send(frame: WsFrame<T>) {
        val json = gson.toJson(frame)
        Logger.ws("Sending: ${frame.type}")
        webSocket?.send(json)
    }

    private fun startHeartbeat() {
        scope.launch {
            while (connectionState.value == WsConnectionState.CONNECTED) {
                delay(Constants.HEARTBEAT_INTERVAL_MS)
                if (connectionState.value == WsConnectionState.CONNECTED) {
                    send(WsFrame(Constants.MsgType.PING, payload = PingPayload(System.currentTimeMillis())))
                }
            }
        }
    }

    private fun attemptReconnect() {
        if (reconnectPolicy.shouldRetry()) {
            _connectionState.value = WsConnectionState.RECONNECTING
            scope.launch {
                delay(reconnectPolicy.getDelayMs())
                connect()
            }
        }
    }

    fun disconnect() {
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _connectionState.value = WsConnectionState.DISCONNECTED
    }

    fun markAuthExpired() {
        reconnectPolicy.markAuthExpired()
        _connectionState.value = WsConnectionState.AUTH_EXPIRED
    }
}
