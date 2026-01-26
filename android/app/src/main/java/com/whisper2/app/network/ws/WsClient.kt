package com.whisper2.app.network.ws

import android.util.Log

/**
 * WebSocket connection states
 */
enum class WsState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

/**
 * WebSocket event listener
 */
interface WsListener {
    fun onOpen()
    fun onMessage(text: String)
    fun onClose(code: Int, reason: String?)
    fun onError(error: Throwable)
}

/**
 * WebSocket connection interface (for testing)
 */
interface WsConnection {
    fun connect()
    fun send(text: String): Boolean
    fun close(code: Int = 1000, reason: String? = null)
    val isOpen: Boolean
}

/**
 * Factory for creating WsConnection instances
 */
fun interface WsConnectionFactory {
    fun create(url: String, listener: WsListener): WsConnection
}

/**
 * WebSocket Client
 *
 * Features:
 * - Single active connection (connect twice = no-op if already connected/connecting)
 * - State machine: DISCONNECTED → CONNECTING → CONNECTED
 * - Reconnect with backoff policy
 * - onOpen callback for triggering fetch_pending
 */
class WsClient(
    private val url: String,
    private val connectionFactory: WsConnectionFactory,
    private val reconnectPolicy: WsReconnectPolicy = WsReconnectPolicy(),
    private val onOpenCallback: (() -> Unit)? = null,
    private val onMessageCallback: ((String) -> Unit)? = null,
    private val scheduler: Scheduler = DefaultScheduler()
) {
    companion object {
        private const val TAG = "WsClient"
    }

    // State
    @Volatile
    var state: WsState = WsState.DISCONNECTED
        private set

    private var connection: WsConnection? = null
    private var reconnectAttempt = 0
    private var reconnectTask: Runnable? = null

    // Stats for testing
    var openCount = 0
        private set

    private val listener = object : WsListener {
        override fun onOpen() {
            Log.d(TAG, "WebSocket opened")
            synchronized(this@WsClient) {
                state = WsState.CONNECTED
                reconnectAttempt = 0
                openCount++
            }
            onOpenCallback?.invoke()
        }

        override fun onMessage(text: String) {
            onMessageCallback?.invoke(text)
        }

        override fun onClose(code: Int, reason: String?) {
            Log.d(TAG, "WebSocket closed: code=$code, reason=$reason")
            synchronized(this@WsClient) {
                state = WsState.DISCONNECTED
                connection = null
            }

            // Auto-reconnect on unexpected close (not 1000 = normal closure)
            if (code != 1000) {
                scheduleReconnect()
            }
        }

        override fun onError(error: Throwable) {
            Log.e(TAG, "WebSocket error", error)
            synchronized(this@WsClient) {
                state = WsState.DISCONNECTED
                connection = null
            }
            scheduleReconnect()
        }
    }

    /**
     * Connect to WebSocket server
     * No-op if already connected or connecting
     */
    @Synchronized
    fun connect() {
        if (state != WsState.DISCONNECTED) {
            Log.d(TAG, "Already ${state.name}, ignoring connect()")
            return
        }

        Log.d(TAG, "Connecting to $url")
        state = WsState.CONNECTING

        val conn = connectionFactory.create(url, listener)
        connection = conn
        conn.connect()
    }

    /**
     * Disconnect from WebSocket server
     */
    @Synchronized
    fun disconnect() {
        cancelReconnect()
        connection?.close()
        connection = null
        state = WsState.DISCONNECTED
    }

    /**
     * Send message to server
     * @return true if sent, false if not connected
     */
    fun send(text: String): Boolean {
        val conn = connection
        if (conn == null || !conn.isOpen) {
            Log.w(TAG, "Cannot send: not connected")
            return false
        }
        return conn.send(text)
    }

    /**
     * Check if connected
     */
    fun isConnected(): Boolean = state == WsState.CONNECTED

    private fun scheduleReconnect() {
        val delay = reconnectPolicy.nextDelayMs(reconnectAttempt)
        reconnectAttempt++

        Log.d(TAG, "Scheduling reconnect in ${delay}ms (attempt $reconnectAttempt)")

        val task = Runnable {
            synchronized(this) {
                reconnectTask = null
                if (state == WsState.DISCONNECTED) {
                    connect()
                }
            }
        }
        reconnectTask = task
        scheduler.schedule(task, delay)
    }

    private fun cancelReconnect() {
        reconnectTask?.let {
            scheduler.cancel(it)
            reconnectTask = null
        }
    }

    /**
     * Reset stats (for testing)
     */
    fun resetStats() {
        openCount = 0
    }
}

/**
 * Scheduler interface for delayed execution (testable)
 */
interface Scheduler {
    fun schedule(task: Runnable, delayMs: Long)
    fun cancel(task: Runnable)
}

/**
 * Default scheduler using Handler
 */
class DefaultScheduler : Scheduler {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun schedule(task: Runnable, delayMs: Long) {
        handler.postDelayed(task, delayMs)
    }

    override fun cancel(task: Runnable) {
        handler.removeCallbacks(task)
    }
}

/**
 * Immediate scheduler for testing
 */
class ImmediateScheduler : Scheduler {
    private val scheduled = mutableListOf<Runnable>()

    override fun schedule(task: Runnable, delayMs: Long) {
        scheduled.add(task)
    }

    override fun cancel(task: Runnable) {
        scheduled.remove(task)
    }

    fun runAll() {
        val copy = scheduled.toList()
        scheduled.clear()
        copy.forEach { it.run() }
    }
}
