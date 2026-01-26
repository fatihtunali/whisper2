package com.whisper2.app.push

import com.whisper2.app.services.push.PushHandler
import com.whisper2.app.services.push.PushPayload
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Gate 1: Payload Validation + Ignore Unknown Fields
 *
 * Tests:
 * - type != wake → ignore
 * - reason unknown → ignore
 * - whisperId missing → ignore
 * - extra fields → ignore (no crash)
 */
class PushPayloadValidationTest {

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
    // Gate 1: Valid payload tests
    // ==========================================================================

    @Test
    fun `gate1 valid message payload is processed`() {
        handler.onWake(type = "wake", reason = "message", whisperId = "WSP-1")

        assertEquals(1, fetcher.fetchCount)
        assertEquals(0, handler.getIgnoredPushCount())
    }

    @Test
    fun `gate1 valid call payload is processed`() {
        handler.onWake(type = "wake", reason = "call", whisperId = "WSP-1")

        assertEquals(1, callUi.callCount)
        assertEquals(0, handler.getIgnoredPushCount())
    }

    @Test
    fun `gate1 valid system payload is processed`() {
        handler.onWake(type = "wake", reason = "system", whisperId = "WSP-1")

        assertEquals(0, handler.getIgnoredPushCount())
    }

    // ==========================================================================
    // Gate 1: type != wake is ignored
    // ==========================================================================

    @Test
    fun `gate1 type not wake is ignored`() {
        handler.onWake(type = "notification", reason = "message", whisperId = "WSP-1")

        assertEquals(0, fetcher.fetchCount)
        assertEquals(1, handler.getIgnoredPushCount())
    }

    @Test
    fun `gate1 type null is ignored`() {
        handler.onWake(type = null, reason = "message", whisperId = "WSP-1")

        assertEquals(0, fetcher.fetchCount)
        assertEquals(1, handler.getIgnoredPushCount())
    }

    @Test
    fun `gate1 type empty is ignored`() {
        handler.onWake(type = "", reason = "message", whisperId = "WSP-1")

        assertEquals(0, fetcher.fetchCount)
        assertEquals(1, handler.getIgnoredPushCount())
    }

    // ==========================================================================
    // Gate 1: Unknown reason is ignored
    // ==========================================================================

    @Test
    fun `gate1 reason unknown is ignored`() {
        handler.onWake(type = "wake", reason = "unknown", whisperId = "WSP-1")

        assertEquals(0, fetcher.fetchCount)
        assertEquals(0, callUi.callCount)
        assertEquals(1, handler.getIgnoredPushCount())
    }

    @Test
    fun `gate1 reason null is ignored`() {
        handler.onWake(type = "wake", reason = null, whisperId = "WSP-1")

        assertEquals(0, fetcher.fetchCount)
        assertEquals(1, handler.getIgnoredPushCount())
    }

    @Test
    fun `gate1 reason empty is ignored`() {
        handler.onWake(type = "wake", reason = "", whisperId = "WSP-1")

        assertEquals(0, fetcher.fetchCount)
        assertEquals(1, handler.getIgnoredPushCount())
    }

    // ==========================================================================
    // Gate 1: whisperId missing is ignored
    // ==========================================================================

    @Test
    fun `gate1 whisperId null is ignored`() {
        handler.onWake(type = "wake", reason = "message", whisperId = null)

        assertEquals(0, fetcher.fetchCount)
        assertEquals(1, handler.getIgnoredPushCount())
    }

    @Test
    fun `gate1 whisperId empty is ignored`() {
        handler.onWake(type = "wake", reason = "message", whisperId = "")

        assertEquals(0, fetcher.fetchCount)
        assertEquals(1, handler.getIgnoredPushCount())
    }

    @Test
    fun `gate1 whisperId blank is ignored`() {
        handler.onWake(type = "wake", reason = "message", whisperId = "   ")

        assertEquals(0, fetcher.fetchCount)
        assertEquals(1, handler.getIgnoredPushCount())
    }

    // ==========================================================================
    // Gate 1: Extra fields are ignored (no crash)
    // ==========================================================================

    @Test
    fun `gate1 extra fields in raw push are ignored without crash`() {
        val data = mapOf(
            "type" to "wake",
            "reason" to "message",
            "whisperId" to "WSP-1",
            "extraField1" to "value1",
            "extraField2" to "value2",
            "randomNoise" to "should be ignored"
        )

        handler.onRawPush(data)

        // Should process normally despite extra fields
        assertEquals(1, fetcher.fetchCount)
        assertEquals(0, handler.getIgnoredPushCount())
    }

    @Test
    fun `gate1 PushPayload fromMap ignores unknown fields`() {
        val data = mapOf(
            "type" to "wake",
            "reason" to "message",
            "whisperId" to "WSP-1",
            "unknownField" to "ignored"
        )

        val payload = PushPayload.fromMap(data)

        assertEquals("wake", payload.type)
        assertEquals("message", payload.reason)
        assertEquals("WSP-1", payload.whisperId)
        assertTrue(payload.isValid())
    }

    // ==========================================================================
    // Gate 1: PushPayload validation
    // ==========================================================================

    @Test
    fun `gate1 PushPayload isValid returns true for valid payload`() {
        val payload = PushPayload("wake", "message", "WSP-1")
        assertTrue(payload.isValid())
    }

    @Test
    fun `gate1 PushPayload isValid returns false for invalid type`() {
        val payload = PushPayload("invalid", "message", "WSP-1")
        assertFalse(payload.isValid())
    }

    @Test
    fun `gate1 PushPayload isValid returns false for invalid reason`() {
        val payload = PushPayload("wake", "invalid", "WSP-1")
        assertFalse(payload.isValid())
    }

    @Test
    fun `gate1 PushPayload isMessageWake returns true for message`() {
        val payload = PushPayload("wake", "message", "WSP-1")
        assertTrue(payload.isMessageWake)
        assertFalse(payload.isCallWake)
    }

    @Test
    fun `gate1 PushPayload isCallWake returns true for call`() {
        val payload = PushPayload("wake", "call", "WSP-1")
        assertTrue(payload.isCallWake)
        assertFalse(payload.isMessageWake)
    }
}
