package com.whisper2.app.push

import com.whisper2.app.services.push.PushHandler
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Gate 2: reason=message → WS wake + fetch_pending
 *
 * Tests:
 * - WS disconnected: wake(message) → connect → fetch_pending
 * - WS connected: wake(message) → fetch_pending (no connect)
 */
class PushWakeMessageBehaviorTest {

    private lateinit var ws: FakeWsClient
    private lateinit var fetcher: FakePendingFetcher
    private lateinit var callUi: FakeCallUi
    private lateinit var handler: PushHandler

    @Before
    fun setup() {
        ws = FakeWsClient(connected = false)
        fetcher = FakePendingFetcher()
        callUi = FakeCallUi()
        handler = PushHandler(ws, fetcher, callUi)
    }

    // ==========================================================================
    // Gate 2: WS disconnected → connect + fetch
    // ==========================================================================

    @Test
    fun `gate2 message wake connects if disconnected and fetches`() {
        ws.disconnect() // Ensure disconnected

        handler.onWake(type = "wake", reason = "message", whisperId = "WSP-1")

        assertEquals("Should connect", 1, ws.connectCount)
        assertEquals("Should fetch", 1, fetcher.fetchCount)
    }

    @Test
    fun `gate2 message wake connects and fetches using onRawPush`() {
        ws.disconnect()

        handler.onRawPush(mapOf(
            "type" to "wake",
            "reason" to "message",
            "whisperId" to "WSP-1"
        ))

        assertEquals(1, ws.connectCount)
        assertEquals(1, fetcher.fetchCount)
    }

    // ==========================================================================
    // Gate 2: WS connected → fetch only (no connect)
    // ==========================================================================

    @Test
    fun `gate2 message wake fetches when already connected`() {
        ws = FakeWsClient(connected = true)
        handler = PushHandler(ws, fetcher, callUi)

        handler.onWake(type = "wake", reason = "message", whisperId = "WSP-1")

        assertEquals("Should NOT connect (already connected)", 0, ws.connectCount)
        assertEquals("Should fetch", 1, fetcher.fetchCount)
    }

    @Test
    fun `gate2 message wake fetches when connected using onRawPush`() {
        ws = FakeWsClient(connected = true)
        handler = PushHandler(ws, fetcher, callUi)

        handler.onRawPush(mapOf(
            "type" to "wake",
            "reason" to "message",
            "whisperId" to "WSP-1"
        ))

        assertEquals(0, ws.connectCount)
        assertEquals(1, fetcher.fetchCount)
    }

    // ==========================================================================
    // Gate 2: Multiple message wakes
    // ==========================================================================

    @Test
    fun `gate2 multiple message wakes when disconnected`() {
        ws.disconnect()

        handler.onWake(type = "wake", reason = "message", whisperId = "WSP-1")
        handler.onWake(type = "wake", reason = "message", whisperId = "WSP-2")
        handler.onWake(type = "wake", reason = "message", whisperId = "WSP-3")

        // First wake connects, subsequent wakes don't (already connected after first)
        assertEquals("First wake connects", 1, ws.connectCount)
        assertEquals("All 3 fetches", 3, fetcher.fetchCount)
    }

    @Test
    fun `gate2 message wake does not trigger call UI`() {
        handler.onWake(type = "wake", reason = "message", whisperId = "WSP-1")

        assertEquals(0, callUi.callCount)
    }

    // ==========================================================================
    // Gate 2: Metrics tracking
    // ==========================================================================

    @Test
    fun `gate2 wake message count is tracked`() {
        handler.onWake(type = "wake", reason = "message", whisperId = "WSP-1")
        handler.onWake(type = "wake", reason = "message", whisperId = "WSP-2")

        assertEquals(2, handler.getWakeMessageCount())
    }

    @Test
    fun `gate2 metrics reset works`() {
        handler.onWake(type = "wake", reason = "message", whisperId = "WSP-1")
        assertEquals(1, handler.getWakeMessageCount())

        handler.resetMetrics()

        assertEquals(0, handler.getWakeMessageCount())
    }

    // ==========================================================================
    // Gate 2: Edge cases
    // ==========================================================================

    @Test
    fun `gate2 message wake with different whisperIds all fetch`() {
        ws = FakeWsClient(connected = true)
        handler = PushHandler(ws, fetcher, callUi)

        handler.onWake(type = "wake", reason = "message", whisperId = "WSP-A")
        handler.onWake(type = "wake", reason = "message", whisperId = "WSP-B")
        handler.onWake(type = "wake", reason = "message", whisperId = "WSP-C")

        assertEquals("No connects (already connected)", 0, ws.connectCount)
        assertEquals("All 3 fetches", 3, fetcher.fetchCount)
    }

    @Test
    fun `gate2 message wake fetch happens even if same whisperId`() {
        ws = FakeWsClient(connected = true)
        handler = PushHandler(ws, fetcher, callUi)

        // Same sender multiple times (e.g., rapid messages)
        handler.onWake(type = "wake", reason = "message", whisperId = "WSP-SAME")
        handler.onWake(type = "wake", reason = "message", whisperId = "WSP-SAME")

        assertEquals("Both fetches", 2, fetcher.fetchCount)
    }
}
