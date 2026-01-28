package com.whisper2.app.data.network.ws

import com.whisper2.app.core.Constants

class WsReconnectPolicy {
    private var attemptCount = 0
    private var isAuthExpired = false
    private var isNetworkAvailable = true

    fun shouldRetry(): Boolean {
        if (isAuthExpired) return false
        if (!isNetworkAvailable) return false
        return attemptCount < Constants.RECONNECT_MAX_ATTEMPTS
    }

    fun getDelayMs(): Long {
        val delay = Constants.RECONNECT_BASE_DELAY_MS * (1 shl attemptCount)
        attemptCount++
        return minOf(delay, Constants.RECONNECT_MAX_DELAY_MS)
    }

    fun reset() {
        attemptCount = 0
        isAuthExpired = false
    }

    fun markAuthExpired() { isAuthExpired = true }

    fun setNetworkAvailable(available: Boolean) {
        isNetworkAvailable = available
        if (available && !isAuthExpired) attemptCount = 0
    }

    fun isAuthenticationRequired(): Boolean = isAuthExpired
}
