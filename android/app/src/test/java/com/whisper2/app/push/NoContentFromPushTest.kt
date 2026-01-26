package com.whisper2.app.push

import com.whisper2.app.services.push.PushHandler
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Gate 5: No Content Processing (Security Gate)
 *
 * Tests:
 * - Push payload with ciphertext, text, messages[] → IGNORED
 * - DB count = 0 (nothing persisted from push)
 * - Only wake behavior occurs
 */
class NoContentFromPushTest {

    private lateinit var ws: FakeWsClient
    private lateinit var fetcher: FakePendingFetcher
    private lateinit var callUi: FakeCallUi
    private lateinit var messageStore: FakeMessageStore
    private lateinit var handler: PushHandler

    @Before
    fun setup() {
        ws = FakeWsClient(connected = true)
        fetcher = FakePendingFetcher()
        callUi = FakeCallUi()
        messageStore = FakeMessageStore()
        handler = PushHandler(ws, fetcher, callUi)
    }

    // ==========================================================================
    // Gate 5: Push with content fields is ignored
    // ==========================================================================

    @Test
    fun `gate5 push with text is ignored and does not persist`() {
        handler.onRawPush(mapOf(
            "type" to "wake",
            "reason" to "message",
            "whisperId" to "WSP-1",
            "text" to "hacked message content"
        ))

        // Content is ignored
        assertEquals(1, handler.getContentIgnoredCount())

        // But wake still happens
        assertEquals(1, fetcher.fetchCount)

        // Nothing persisted (messageStore not used by handler, but verifies discipline)
        assertEquals(0, messageStore.count())
    }

    @Test
    fun `gate5 push with ciphertext is ignored and does not persist`() {
        handler.onRawPush(mapOf(
            "type" to "wake",
            "reason" to "message",
            "whisperId" to "WSP-1",
            "ciphertext" to "AAAA=="
        ))

        assertEquals(1, handler.getContentIgnoredCount())
        assertEquals(1, fetcher.fetchCount)
        assertEquals(0, messageStore.count())
    }

    @Test
    fun `gate5 push with messages array is ignored and does not persist`() {
        handler.onRawPush(mapOf(
            "type" to "wake",
            "reason" to "message",
            "whisperId" to "WSP-1",
            "messages" to "[{\"id\":\"1\",\"text\":\"hack\"}]"
        ))

        assertEquals(1, handler.getContentIgnoredCount())
        assertEquals(1, fetcher.fetchCount)
        assertEquals(0, messageStore.count())
    }

    @Test
    fun `gate5 push with content field is ignored and does not persist`() {
        handler.onRawPush(mapOf(
            "type" to "wake",
            "reason" to "message",
            "whisperId" to "WSP-1",
            "content" to "malicious content"
        ))

        assertEquals(1, handler.getContentIgnoredCount())
        assertEquals(1, fetcher.fetchCount)
        assertEquals(0, messageStore.count())
    }

    @Test
    fun `gate5 push with body field is ignored and does not persist`() {
        handler.onRawPush(mapOf(
            "type" to "wake",
            "reason" to "message",
            "whisperId" to "WSP-1",
            "body" to "notification body hack attempt"
        ))

        assertEquals(1, handler.getContentIgnoredCount())
        assertEquals(1, fetcher.fetchCount)
        assertEquals(0, messageStore.count())
    }

    // ==========================================================================
    // Gate 5: Multiple content fields
    // ==========================================================================

    @Test
    fun `gate5 push with multiple content fields still only counts once`() {
        handler.onRawPush(mapOf(
            "type" to "wake",
            "reason" to "message",
            "whisperId" to "WSP-1",
            "text" to "hack1",
            "ciphertext" to "AAAA==",
            "content" to "hack2",
            "body" to "hack3"
        ))

        // All content ignored (counted once)
        assertEquals(1, handler.getContentIgnoredCount())

        // Wake still happens
        assertEquals(1, fetcher.fetchCount)
    }

    // ==========================================================================
    // Gate 5: Content fields don't affect wake behavior
    // ==========================================================================

    @Test
    fun `gate5 content fields dont prevent message wake`() {
        ws.disconnect()

        handler.onRawPush(mapOf(
            "type" to "wake",
            "reason" to "message",
            "whisperId" to "WSP-1",
            "text" to "ignored text"
        ))

        // Wake still works
        assertEquals(1, ws.connectCount)
        assertEquals(1, fetcher.fetchCount)
    }

    @Test
    fun `gate5 content fields dont affect call wake`() {
        handler.onRawPush(mapOf(
            "type" to "wake",
            "reason" to "call",
            "whisperId" to "WSP-CALLER",
            "text" to "ignored text"
        ))

        // Call UI still triggered
        assertEquals(1, callUi.callCount)
        assertEquals("WSP-CALLER", callUi.lastWhisperId)
    }

    // ==========================================================================
    // Gate 5: Clean push (no content fields) doesn't increment ignore count
    // ==========================================================================

    @Test
    fun `gate5 clean push does not increment content ignored count`() {
        handler.onRawPush(mapOf(
            "type" to "wake",
            "reason" to "message",
            "whisperId" to "WSP-1"
        ))

        assertEquals(0, handler.getContentIgnoredCount())
        assertEquals(1, fetcher.fetchCount)
    }

    // ==========================================================================
    // Gate 5: Invalid push with content fields
    // ==========================================================================

    @Test
    fun `gate5 invalid push with content fields counts both`() {
        handler.onRawPush(mapOf(
            "type" to "invalid",  // Invalid type
            "reason" to "message",
            "whisperId" to "WSP-1",
            "text" to "hack"
        ))

        // Content was detected
        assertEquals(1, handler.getContentIgnoredCount())

        // But push was also invalid
        assertEquals(1, handler.getIgnoredPushCount())

        // No fetch (invalid push)
        assertEquals(0, fetcher.fetchCount)
    }

    // ==========================================================================
    // Gate 5: Verify DB stays at 0
    // ==========================================================================

    @Test
    fun `gate5 DB count remains 0 after multiple pushes with content`() {
        // Send multiple pushes with various content
        handler.onRawPush(mapOf(
            "type" to "wake",
            "reason" to "message",
            "whisperId" to "WSP-1",
            "text" to "message 1"
        ))

        handler.onRawPush(mapOf(
            "type" to "wake",
            "reason" to "message",
            "whisperId" to "WSP-2",
            "ciphertext" to "encrypted data"
        ))

        handler.onRawPush(mapOf(
            "type" to "wake",
            "reason" to "call",
            "whisperId" to "WSP-3",
            "content" to "call content"
        ))

        // DB count is ALWAYS 0 - push NEVER persists content
        assertEquals("DB count must be 0", 0, messageStore.count())

        // All content detected
        assertEquals(3, handler.getContentIgnoredCount())

        // Wake behaviors still work
        assertEquals(2, fetcher.fetchCount) // 2 message wakes
        assertEquals(1, callUi.callCount)   // 1 call wake
    }
}
