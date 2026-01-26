package com.whisper2.app.network.ws

import kotlin.math.min
import kotlin.math.pow

/**
 * Reconnect policy with exponential backoff and jitter
 *
 * Formula: delay = min(baseMs * 2^attempt, maxMs) * (1 + jitter)
 * where jitter = jitterRatio * random[0,1)
 */
class WsReconnectPolicy(
    private val baseMs: Long = 1000,
    private val maxMs: Long = 30_000,
    private val jitterRatio: Double = 0.2,
    private val randomProvider: () -> Double = { Math.random() }
) {
    /**
     * Calculate next delay in milliseconds for given attempt number
     * @param attempt 0-based attempt number (0 = first retry)
     */
    fun nextDelayMs(attempt: Int): Long {
        if (attempt < 0) return 0

        // Exponential backoff: baseMs * 2^attempt
        val exponential = baseMs * 2.0.pow(attempt.toDouble())

        // Cap at maxMs
        val capped = min(exponential, maxMs.toDouble())

        // Add jitter: delay * (1 + jitterRatio * random)
        val jitter = jitterRatio * randomProvider()
        val withJitter = capped * (1 + jitter)

        return withJitter.toLong()
    }

    /**
     * Reset is implicit - just use attempt=0
     */
    companion object {
        /**
         * Policy that never reconnects (for testing)
         */
        fun noReconnect() = WsReconnectPolicy(
            baseMs = Long.MAX_VALUE,
            maxMs = Long.MAX_VALUE,
            jitterRatio = 0.0
        )

        /**
         * Fast reconnect for testing
         */
        fun fast() = WsReconnectPolicy(
            baseMs = 10,
            maxMs = 100,
            jitterRatio = 0.0
        )
    }
}
