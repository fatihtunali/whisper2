package com.whisper2.app.ws

import com.whisper2.app.network.ws.WsReconnectPolicy
import org.junit.Assert.*
import org.junit.Test

/**
 * Gate 1: Reconnect Policy Tests
 *
 * Tests exponential backoff with jitter
 */
class WsReconnectPolicyTest {

    @Test
    fun `gate1 backoff increases with attempts`() {
        val policy = WsReconnectPolicy(
            baseMs = 100,
            maxMs = 10_000,
            jitterRatio = 0.0, // no jitter for deterministic test
            randomProvider = { 0.0 }
        )

        val d0 = policy.nextDelayMs(attempt = 0) // 100 * 2^0 = 100
        val d1 = policy.nextDelayMs(attempt = 1) // 100 * 2^1 = 200
        val d2 = policy.nextDelayMs(attempt = 2) // 100 * 2^2 = 400
        val d3 = policy.nextDelayMs(attempt = 3) // 100 * 2^3 = 800

        assertEquals("attempt 0", 100, d0)
        assertEquals("attempt 1", 200, d1)
        assertEquals("attempt 2", 400, d2)
        assertEquals("attempt 3", 800, d3)

        assertTrue("delay increases", d3 > d2)
        assertTrue("delay increases", d2 > d1)
        assertTrue("delay increases", d1 > d0)
    }

    @Test
    fun `gate1 backoff is bounded by maxMs`() {
        val policy = WsReconnectPolicy(
            baseMs = 1000,
            maxMs = 5000,
            jitterRatio = 0.0,
            randomProvider = { 0.0 }
        )

        val d5 = policy.nextDelayMs(attempt = 5) // 1000 * 2^5 = 32000, capped to 5000
        val d10 = policy.nextDelayMs(attempt = 10) // would be huge, capped to 5000

        assertEquals("capped at maxMs", 5000, d5)
        assertEquals("capped at maxMs", 5000, d10)
    }

    @Test
    fun `gate1 jitter adds variation when random is non-zero`() {
        val policyNoJitter = WsReconnectPolicy(
            baseMs = 100,
            maxMs = 10_000,
            jitterRatio = 0.2,
            randomProvider = { 0.0 } // no random
        )

        val policyWithJitter = WsReconnectPolicy(
            baseMs = 100,
            maxMs = 10_000,
            jitterRatio = 0.2,
            randomProvider = { 1.0 } // max random
        )

        val d0NoJitter = policyNoJitter.nextDelayMs(attempt = 0) // 100 * (1 + 0.2 * 0) = 100
        val d0WithJitter = policyWithJitter.nextDelayMs(attempt = 0) // 100 * (1 + 0.2 * 1) = 120

        assertEquals("no jitter", 100, d0NoJitter)
        assertEquals("max jitter", 120, d0WithJitter)
    }

    @Test
    fun `gate1 jitter ratio determines max variation`() {
        val policy = WsReconnectPolicy(
            baseMs = 1000,
            maxMs = 100_000,
            jitterRatio = 0.5, // 50% max jitter
            randomProvider = { 1.0 } // max random
        )

        val d0 = policy.nextDelayMs(attempt = 0) // 1000 * (1 + 0.5 * 1) = 1500

        assertEquals("50% jitter", 1500, d0)
    }

    @Test
    fun `gate1 negative attempt returns zero or base`() {
        val policy = WsReconnectPolicy(
            baseMs = 100,
            maxMs = 10_000,
            jitterRatio = 0.0,
            randomProvider = { 0.0 }
        )

        val dNeg = policy.nextDelayMs(attempt = -1)

        assertEquals("negative attempt", 0, dNeg)
    }

    @Test
    fun `gate1 noReconnect policy returns very large delay`() {
        val policy = WsReconnectPolicy.noReconnect()

        val d0 = policy.nextDelayMs(attempt = 0)

        assertTrue("very large delay", d0 > 1_000_000_000)
    }

    @Test
    fun `gate1 fast policy returns small delays`() {
        val policy = WsReconnectPolicy.fast()

        val d0 = policy.nextDelayMs(attempt = 0)
        val d5 = policy.nextDelayMs(attempt = 5)

        assertTrue("small delay", d0 <= 10)
        assertTrue("bounded", d5 <= 100)
    }
}
