package com.whisper2.app.push

import com.whisper2.app.services.push.IncomingCallUi
import com.whisper2.app.services.push.PendingMessageFetcher
import com.whisper2.app.services.push.WsConnectionManager

/**
 * Fake WebSocket connection manager for testing
 */
class FakeWsClient(
    private var connected: Boolean = false
) : WsConnectionManager {

    var connectCount = 0
        private set

    override fun isConnected(): Boolean = connected

    override fun connect() {
        connectCount++
        connected = true
    }

    fun disconnect() {
        connected = false
    }

    fun reset() {
        connected = false
        connectCount = 0
    }
}

/**
 * Fake pending message fetcher for testing
 */
class FakePendingFetcher : PendingMessageFetcher {

    var fetchCount = 0
        private set

    override fun fetchPending() {
        fetchCount++
    }

    fun reset() {
        fetchCount = 0
    }
}

/**
 * Fake incoming call UI for testing
 */
class FakeCallUi : IncomingCallUi {

    var callCount = 0
        private set

    var lastWhisperId: String? = null
        private set

    override fun showIncomingCall(whisperId: String) {
        callCount++
        lastWhisperId = whisperId
    }

    fun reset() {
        callCount = 0
        lastWhisperId = null
    }
}

/**
 * Fake message store for security testing
 */
class FakeMessageStore {

    private val messages = mutableListOf<String>()

    fun add(message: String) {
        messages.add(message)
    }

    fun count(): Int = messages.size

    fun clear() {
        messages.clear()
    }
}
