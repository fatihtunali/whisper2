package com.whisper2.app.ws

import com.whisper2.app.network.ws.*
import com.whisper2.app.services.messaging.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Base64

/**
 * Gate 4: pending_messages → persist + receipts + nextCursor
 */
class PendingFetcherTest {

    private lateinit var sessionManager: TestSessionManager
    private lateinit var messageStore: InMemoryMessageStore
    private lateinit var deduper: Deduper
    private lateinit var sentMessages: MutableList<String>
    private lateinit var pendingFetcher: PendingFetcher

    // Valid base64 values for tests
    private val validNonce24 = Base64.getEncoder().encodeToString(ByteArray(24) { 0 })
    private val validSig64 = Base64.getEncoder().encodeToString(ByteArray(64) { 0 })

    @Before
    fun setup() {
        sessionManager = TestSessionManager()
        sessionManager.sessionToken = "sess_test_token"
        sessionManager._whisperId = "WSP-MY-ID"
        messageStore = InMemoryMessageStore()
        deduper = Deduper()
        sentMessages = mutableListOf()

        val messageSender = MessageSender { json ->
            sentMessages.add(json)
            true
        }

        pendingFetcher = PendingFetcher(
            sessionManager = sessionManager,
            messageStore = messageStore,
            deduper = deduper,
            messageSender = messageSender,
            myWhisperId = { sessionManager.whisperId }
        )
    }

    // ==========================================================================
    // Gate 4: Persist messages
    // ==========================================================================

    @Test
    fun `gate4 pending messages are persisted to store`() {
        val payload = PendingMessagesPayload(
            messages = listOf(
                createValidMessage("msg-1", "WSP-SENDER-A"),
                createValidMessage("msg-2", "WSP-SENDER-B")
            ),
            nextCursor = null
        )

        val stored = pendingFetcher.handlePendingMessages(payload)

        assertEquals(2, stored)
        assertEquals(2, messageStore.count())
        assertTrue(messageStore.exists("msg-1"))
        assertTrue(messageStore.exists("msg-2"))
    }

    @Test
    fun `gate4 receipt sent for each persisted message`() {
        val payload = PendingMessagesPayload(
            messages = listOf(
                createValidMessage("msg-1", "WSP-SENDER-A"),
                createValidMessage("msg-2", "WSP-SENDER-B")
            ),
            nextCursor = null
        )

        pendingFetcher.handlePendingMessages(payload)

        assertEquals(2, pendingFetcher.receiptsSent)
        assertEquals(2, sentMessages.size)

        // Verify receipts contain correct data
        assertTrue(sentMessages[0].contains("\"type\":\"delivery_receipt\""))
        assertTrue(sentMessages[0].contains("\"messageId\":\"msg-1\""))
        assertTrue(sentMessages[0].contains("\"status\":\"delivered\""))

        assertTrue(sentMessages[1].contains("\"messageId\":\"msg-2\""))
    }

    @Test
    fun `gate4 receipt from and to are correct`() {
        val payload = PendingMessagesPayload(
            messages = listOf(
                createValidMessage("msg-1", "WSP-SENDER-A")
            ),
            nextCursor = null
        )

        pendingFetcher.handlePendingMessages(payload)

        val receiptJson = sentMessages[0]
        // from = recipient (me), to = original sender
        assertTrue("Receipt from should be my ID", receiptJson.contains("\"from\":\"WSP-MY-ID\""))
        assertTrue("Receipt to should be sender", receiptJson.contains("\"to\":\"WSP-SENDER-A\""))
    }

    // ==========================================================================
    // Gate 4: NextCursor handling
    // ==========================================================================

    @Test
    fun `gate4 nextCursor is saved for pagination`() {
        val payload = PendingMessagesPayload(
            messages = listOf(createValidMessage("msg-1", "WSP-A")),
            nextCursor = "cursor-page-2"
        )

        pendingFetcher.handlePendingMessages(payload)

        assertEquals("cursor-page-2", pendingFetcher.getCurrentCursor())
    }

    @Test
    fun `gate4 next fetch includes cursor`() {
        // First fetch sets cursor
        val payload1 = PendingMessagesPayload(
            messages = listOf(createValidMessage("msg-1", "WSP-A")),
            nextCursor = "cursor-page-2"
        )
        pendingFetcher.handlePendingMessages(payload1)

        // Next fetch should include cursor
        val fetchJson = pendingFetcher.createFetchPending()

        assertTrue("Fetch should include cursor", fetchJson.contains("\"cursor\":\"cursor-page-2\""))
    }

    @Test
    fun `gate4 cursor cleared when null returned`() {
        // First fetch sets cursor
        val payload1 = PendingMessagesPayload(
            messages = listOf(createValidMessage("msg-1", "WSP-A")),
            nextCursor = "cursor-page-2"
        )
        pendingFetcher.handlePendingMessages(payload1)

        // Second fetch clears cursor
        val payload2 = PendingMessagesPayload(
            messages = emptyList(),
            nextCursor = null
        )
        pendingFetcher.handlePendingMessages(payload2)

        assertNull(pendingFetcher.getCurrentCursor())
    }

    // ==========================================================================
    // Gate 4: Deduplication
    // ==========================================================================

    @Test
    fun `gate4 duplicate message not persisted again`() {
        val msg = createValidMessage("msg-dup", "WSP-A")

        val payload1 = PendingMessagesPayload(messages = listOf(msg), nextCursor = null)
        val payload2 = PendingMessagesPayload(messages = listOf(msg), nextCursor = null)

        pendingFetcher.handlePendingMessages(payload1)
        pendingFetcher.handlePendingMessages(payload2)

        assertEquals("Only one copy should be stored", 1, messageStore.count())
        assertEquals("Only one receipt should be sent", 1, pendingFetcher.receiptsSent)
    }

    @Test
    fun `gate4 same messageId from different sender still deduped`() {
        val msg1 = createValidMessage("same-id", "WSP-A")
        val msg2 = createValidMessage("same-id", "WSP-B") // Different sender, same ID

        val payload = PendingMessagesPayload(messages = listOf(msg1, msg2), nextCursor = null)

        val stored = pendingFetcher.handlePendingMessages(payload)

        assertEquals("Only first message should be stored", 1, stored)
    }

    // ==========================================================================
    // Gate 4: Validation - nonce must be 24 bytes
    // ==========================================================================

    @Test
    fun `gate4 message with invalid nonce length is rejected`() {
        val invalidNonce = Base64.getEncoder().encodeToString(ByteArray(16) { 0 }) // 16 bytes, not 24

        val msg = PendingMessageItem(
            messageId = "msg-bad-nonce",
            from = "WSP-SENDER",
            to = "WSP-MY-ID",
            msgType = "text",
            timestamp = System.currentTimeMillis(),
            nonce = invalidNonce,
            ciphertext = "encrypted",
            sig = validSig64
        )

        val payload = PendingMessagesPayload(messages = listOf(msg), nextCursor = null)
        val stored = pendingFetcher.handlePendingMessages(payload)

        assertEquals("Invalid nonce message should be rejected", 0, stored)
        assertEquals("No message should be stored", 0, messageStore.count())
        assertEquals("No receipt should be sent", 0, pendingFetcher.receiptsSent)
    }

    @Test
    fun `gate4 message with invalid nonce base64 is rejected`() {
        val msg = PendingMessageItem(
            messageId = "msg-bad-nonce-b64",
            from = "WSP-SENDER",
            to = "WSP-MY-ID",
            msgType = "text",
            timestamp = System.currentTimeMillis(),
            nonce = "not-valid-base64!!!",
            ciphertext = "encrypted",
            sig = validSig64
        )

        val payload = PendingMessagesPayload(messages = listOf(msg), nextCursor = null)
        val stored = pendingFetcher.handlePendingMessages(payload)

        assertEquals("Invalid base64 nonce should be rejected", 0, stored)
    }

    // ==========================================================================
    // Gate 4: Validation - sig must be 64 bytes
    // ==========================================================================

    @Test
    fun `gate4 message with invalid sig length is rejected`() {
        val invalidSig = Base64.getEncoder().encodeToString(ByteArray(32) { 0 }) // 32 bytes, not 64

        val msg = PendingMessageItem(
            messageId = "msg-bad-sig",
            from = "WSP-SENDER",
            to = "WSP-MY-ID",
            msgType = "text",
            timestamp = System.currentTimeMillis(),
            nonce = validNonce24,
            ciphertext = "encrypted",
            sig = invalidSig
        )

        val payload = PendingMessagesPayload(messages = listOf(msg), nextCursor = null)
        val stored = pendingFetcher.handlePendingMessages(payload)

        assertEquals("Invalid sig message should be rejected", 0, stored)
        assertEquals("No message should be stored", 0, messageStore.count())
        assertEquals("No receipt should be sent", 0, pendingFetcher.receiptsSent)
    }

    @Test
    fun `gate4 message with invalid sig base64 is rejected`() {
        val msg = PendingMessageItem(
            messageId = "msg-bad-sig-b64",
            from = "WSP-SENDER",
            to = "WSP-MY-ID",
            msgType = "text",
            timestamp = System.currentTimeMillis(),
            nonce = validNonce24,
            ciphertext = "encrypted",
            sig = "invalid-base64!!!"
        )

        val payload = PendingMessagesPayload(messages = listOf(msg), nextCursor = null)
        val stored = pendingFetcher.handlePendingMessages(payload)

        assertEquals("Invalid base64 sig should be rejected", 0, stored)
    }

    // ==========================================================================
    // Helper
    // ==========================================================================

    private fun createValidMessage(messageId: String, from: String): PendingMessageItem {
        return PendingMessageItem(
            messageId = messageId,
            from = from,
            to = "WSP-MY-ID",
            msgType = "text",
            timestamp = System.currentTimeMillis(),
            nonce = validNonce24,
            ciphertext = "encrypted_content",
            sig = validSig64
        )
    }
}
