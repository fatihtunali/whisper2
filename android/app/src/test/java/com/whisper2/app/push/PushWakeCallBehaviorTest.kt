package com.whisper2.app.push

import com.whisper2.app.services.push.PushHandler
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Gate 3: reason=call → CallUi trigger
 *
 * Tests:
 * - PushHandler onIncomingCall() is called
 * - Call wake does NOT fetch messages (separate flow)
 */
class PushWakeCallBehaviorTest {

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
    // Gate 3: Call wake triggers incoming call UI
    // ==========================================================================

    @Test
    fun `gate3 call wake triggers incoming call UI`() {
        handler.onWake(type = "wake", reason = "call", whisperId = "WSP-CALLER")

        assertEquals("Call UI should be triggered", 1, callUi.callCount)
        assertEquals("WhisperId should be passed", "WSP-CALLER", callUi.lastWhisperId)
    }

    @Test
    fun `gate3 call wake using onRawPush triggers call UI`() {
        handler.onRawPush(mapOf(
            "type" to "wake",
            "reason" to "call",
            "whisperId" to "WSP-CALLER"
        ))

        assertEquals(1, callUi.callCount)
        assertEquals("WSP-CALLER", callUi.lastWhisperId)
    }

    // ==========================================================================
    // Gate 3: Call wake does NOT fetch messages
    // ==========================================================================

    @Test
    fun `gate3 call wake does not fetch messages`() {
        handler.onWake(type = "wake", reason = "call", whisperId = "WSP-CALLER")

        assertEquals("Should NOT fetch messages", 0, fetcher.fetchCount)
    }

    @Test
    fun `gate3 call wake does not connect WS`() {
        ws.disconnect()

        handler.onWake(type = "wake", reason = "call", whisperId = "WSP-CALLER")

        assertEquals("Should NOT connect WS", 0, ws.connectCount)
    }

    // ==========================================================================
    // Gate 3: Multiple call wakes
    // ==========================================================================

    @Test
    fun `gate3 multiple call wakes all trigger UI`() {
        handler.onWake(type = "wake", reason = "call", whisperId = "WSP-1")
        handler.onWake(type = "wake", reason = "call", whisperId = "WSP-2")
        handler.onWake(type = "wake", reason = "call", whisperId = "WSP-3")

        assertEquals("All 3 calls should trigger UI", 3, callUi.callCount)
        assertEquals("Last caller should be WSP-3", "WSP-3", callUi.lastWhisperId)
    }

    // ==========================================================================
    // Gate 3: Call wake metrics
    // ==========================================================================

    @Test
    fun `gate3 wake call count is tracked`() {
        handler.onWake(type = "wake", reason = "call", whisperId = "WSP-1")
        handler.onWake(type = "wake", reason = "call", whisperId = "WSP-2")

        assertEquals(2, handler.getWakeCallCount())
        assertEquals(0, handler.getWakeMessageCount())
    }

    // ==========================================================================
    // Gate 3: Mixed message and call wakes
    // ==========================================================================

    @Test
    fun `gate3 mixed message and call wakes work independently`() {
        handler.onWake(type = "wake", reason = "message", whisperId = "WSP-MSG")
        handler.onWake(type = "wake", reason = "call", whisperId = "WSP-CALL")
        handler.onWake(type = "wake", reason = "message", whisperId = "WSP-MSG2")

        assertEquals("2 message fetches", 2, fetcher.fetchCount)
        assertEquals("1 call UI trigger", 1, callUi.callCount)
        assertEquals("1 connect for messages", 1, ws.connectCount)
    }

    @Test
    fun `gate3 call followed by message works correctly`() {
        // Call first (no connect, no fetch)
        handler.onWake(type = "wake", reason = "call", whisperId = "WSP-CALL")

        assertEquals(0, ws.connectCount)
        assertEquals(0, fetcher.fetchCount)
        assertEquals(1, callUi.callCount)

        // Message second (connect + fetch)
        handler.onWake(type = "wake", reason = "message", whisperId = "WSP-MSG")

        assertEquals(1, ws.connectCount)
        assertEquals(1, fetcher.fetchCount)
        assertEquals(1, callUi.callCount) // Still 1
    }
}
