package com.whisper2.app.outbox

import com.whisper2.app.network.ws.WsErrorCodes
import com.whisper2.app.services.messaging.*
import com.whisper2.app.storage.db.entities.MessageStatus
import com.whisper2.app.storage.db.entities.OutboxStatus
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Gate 1: State Transitions
 *
 * Tests:
 * - queued → sending → sent (happy path)
 * - queued → sending → failed (permanent errors)
 * - UNAUTHORIZED triggers auth failure + queue pause
 */
class StateTransitionsTest {

    private lateinit var outbox: OutboxQueue
    private lateinit var sentPayloads: MutableList<String>
    private lateinit var statusUpdates: MutableMap<String, String>
    private var authFailureReason: String? = null
    private var currentTime: Long = 1700000000000L

    // My identity
    private val myWhisperId = "WSP-SENDER"
    private val mySessionToken = "sess_test_token"
    private val mySignPrivateKey = ByteArray(64) { (it + 1).toByte() }
    private val myEncPrivateKey = ByteArray(32) { (it + 50).toByte() }

    // Recipient identity
    private val recipientId = "WSP-RECIPIENT"
    private val recipientEncPublicKey = ByteArray(32) { (it + 100).toByte() }

    // Mock crypto results
    private val mockNonce = ByteArray(24) { (it * 2).toByte() }
    private val mockCiphertext = ByteArray(50) { (it * 3).toByte() }
    private val mockSignature = ByteArray(64) { (it * 4).toByte() }

    @Before
    fun setup() {
        sentPayloads = mutableListOf()
        statusUpdates = mutableMapOf()
        authFailureReason = null

        outbox = OutboxQueue(
            myWhisperIdProvider = { myWhisperId },
            sessionTokenProvider = { mySessionToken },
            mySignPrivateKeyProvider = { mySignPrivateKey },
            myEncPrivateKeyProvider = { myEncPrivateKey },
            peerKeyProvider = object : PeerKeyProvider {
                override fun getSignPublicKey(whisperId: String): ByteArray? = null
                override fun getEncPublicKey(whisperId: String): ByteArray? {
                    return if (whisperId == recipientId) recipientEncPublicKey else null
                }
            },
            wsSender = { json ->
                sentPayloads.add(json)
                true // Success
            },
            messageEncryptor = { _, _, _ -> Pair(mockNonce, mockCiphertext) },
            messageSigner = { _, _ -> mockSignature },
            messageStatusUpdater = { messageId, status -> statusUpdates[messageId] = status },
            authFailureHandler = { reason -> authFailureReason = reason },
            timeProvider = { currentTime }
        )
    }

    // ==========================================================================
    // Gate 1: Happy path (queued → sending → sent)
    // ==========================================================================

    @Test
    fun `gate1 enqueue sets status to queued then sending`() {
        // Create outbox that doesn't auto-send
        val holdingOutbox = createHoldingOutbox()

        val messageId = holdingOutbox.enqueueTextMessage("test", recipientId)!!

        val item = holdingOutbox.getByMessageId(messageId)!!
        // After enqueue + processQueue, should be in sending state (WS send succeeded)
        assertEquals(OutboxStatus.SENDING, item.status)
    }

    @Test
    fun `gate1 message_accepted changes status to sent`() {
        val messageId = outbox.enqueueTextMessage("test", recipientId)!!

        // Initially in sending state
        val itemBefore = outbox.getByMessageId(messageId)!!
        assertEquals(OutboxStatus.SENDING, itemBefore.status)

        // Server ACK
        outbox.onMessageAccepted(messageId)

        // Now sent
        val itemAfter = outbox.getByMessageId(messageId)!!
        assertEquals(OutboxStatus.SENT, itemAfter.status)
    }

    @Test
    fun `gate1 message_accepted removes from queue`() {
        val messageId = outbox.enqueueTextMessage("test", recipientId)!!
        assertEquals(1, outbox.size())

        outbox.onMessageAccepted(messageId)

        assertEquals(0, outbox.size())
    }

    @Test
    fun `gate1 message_accepted updates MessageEntity status to SENT`() {
        val messageId = outbox.enqueueTextMessage("test", recipientId)!!
        outbox.onMessageAccepted(messageId)

        assertEquals(MessageStatus.SENT, statusUpdates[messageId])
    }

    // ==========================================================================
    // Gate 1: Permanent errors
    // ==========================================================================

    @Test
    fun `gate1 INVALID_SIGNATURE error sets status to failed`() {
        val messageId = outbox.enqueueTextMessage("test", recipientId)!!
        val item = outbox.getByMessageId(messageId)!!

        outbox.onError(item.requestId, WsErrorCodes.INVALID_SIGNATURE, "Bad signature")

        assertEquals(OutboxStatus.FAILED, item.status)
        assertEquals(WsErrorCodes.INVALID_SIGNATURE, item.failedCode)
        assertEquals("Bad signature", item.failedMessage)
    }

    @Test
    fun `gate1 INVALID_SIGNATURE error no retry scheduled`() {
        val messageId = outbox.enqueueTextMessage("test", recipientId)!!
        val item = outbox.getByMessageId(messageId)!!

        outbox.onError(item.requestId, WsErrorCodes.INVALID_SIGNATURE, "Bad signature")

        assertNull("nextRetryAt should be null for permanent error", item.nextRetryAt)
        assertEquals(OutboxStatus.FAILED, item.status)
    }

    @Test
    fun `gate1 RECIPIENT_NOT_FOUND error sets status to failed`() {
        val messageId = outbox.enqueueTextMessage("test", recipientId)!!
        val item = outbox.getByMessageId(messageId)!!

        outbox.onError(item.requestId, WsErrorCodes.RECIPIENT_NOT_FOUND, "User not found")

        assertEquals(OutboxStatus.FAILED, item.status)
        assertEquals(WsErrorCodes.RECIPIENT_NOT_FOUND, item.failedCode)
    }

    @Test
    fun `gate1 INVALID_PAYLOAD error sets status to failed`() {
        val messageId = outbox.enqueueTextMessage("test", recipientId)!!
        val item = outbox.getByMessageId(messageId)!!

        outbox.onError(item.requestId, WsErrorCodes.INVALID_PAYLOAD, "Missing field")

        assertEquals(OutboxStatus.FAILED, item.status)
        assertEquals(WsErrorCodes.INVALID_PAYLOAD, item.failedCode)
    }

    @Test
    fun `gate1 permanent error updates MessageEntity status to FAILED`() {
        val messageId = outbox.enqueueTextMessage("test", recipientId)!!
        val item = outbox.getByMessageId(messageId)!!

        outbox.onError(item.requestId, WsErrorCodes.INVALID_SIGNATURE, "Bad signature")

        assertEquals(MessageStatus.FAILED, statusUpdates[messageId])
    }

    @Test
    fun `gate1 permanent error removes from queue`() {
        val messageId = outbox.enqueueTextMessage("test", recipientId)!!
        val item = outbox.getByMessageId(messageId)!!
        assertEquals(1, outbox.size())

        outbox.onError(item.requestId, WsErrorCodes.INVALID_SIGNATURE, "Bad signature")

        assertEquals(0, outbox.size())
    }

    // ==========================================================================
    // Gate 1: UNAUTHORIZED special handling
    // ==========================================================================

    @Test
    fun `gate1 UNAUTHORIZED error sets status to failed`() {
        val messageId = outbox.enqueueTextMessage("test", recipientId)!!
        val item = outbox.getByMessageId(messageId)!!

        outbox.onError(item.requestId, WsErrorCodes.UNAUTHORIZED, "Session expired")

        assertEquals(OutboxStatus.FAILED, item.status)
        assertEquals(WsErrorCodes.UNAUTHORIZED, item.failedCode)
    }

    @Test
    fun `gate1 UNAUTHORIZED error triggers authFailureHandler`() {
        val messageId = outbox.enqueueTextMessage("test", recipientId)!!
        val item = outbox.getByMessageId(messageId)!!

        outbox.onError(item.requestId, WsErrorCodes.UNAUTHORIZED, "Session expired")

        assertNotNull("authFailureHandler should be called", authFailureReason)
        assertTrue(authFailureReason!!.contains("UNAUTHORIZED"))
    }

    @Test
    fun `gate1 UNAUTHORIZED error pauses queue`() {
        val messageId = outbox.enqueueTextMessage("test", recipientId)!!
        val item = outbox.getByMessageId(messageId)!!

        assertFalse("Queue should not be paused initially", outbox.isPaused)

        outbox.onError(item.requestId, WsErrorCodes.UNAUTHORIZED, "Session expired")

        assertTrue("Queue should be paused after UNAUTHORIZED", outbox.isPaused)
    }

    @Test
    fun `gate1 UNAUTHORIZED error stops queue drain`() {
        // Create outbox that tracks sends
        var sendCount = 0
        val trackingOutbox = OutboxQueue(
            myWhisperIdProvider = { myWhisperId },
            sessionTokenProvider = { mySessionToken },
            mySignPrivateKeyProvider = { mySignPrivateKey },
            myEncPrivateKeyProvider = { myEncPrivateKey },
            peerKeyProvider = object : PeerKeyProvider {
                override fun getSignPublicKey(whisperId: String) = null
                override fun getEncPublicKey(whisperId: String) = recipientEncPublicKey
            },
            wsSender = { sendCount++; true },
            messageEncryptor = { _, _, _ -> Pair(mockNonce, mockCiphertext) },
            messageSigner = { _, _ -> mockSignature },
            authFailureHandler = { authFailureReason = it },
            timeProvider = { currentTime }
        )

        // Enqueue first message
        val msg1 = trackingOutbox.enqueueTextMessage("test1", recipientId)!!
        val item1 = trackingOutbox.getByMessageId(msg1)!!
        assertEquals(1, sendCount)

        // Trigger UNAUTHORIZED
        trackingOutbox.onError(item1.requestId, WsErrorCodes.UNAUTHORIZED, "Session expired")

        // Try to process more - should not send because paused
        trackingOutbox.processQueue()
        assertEquals(1, sendCount) // Still 1
    }

    @Test
    fun `gate1 UNAUTHORIZED updates MessageEntity status to FAILED`() {
        val messageId = outbox.enqueueTextMessage("test", recipientId)!!
        val item = outbox.getByMessageId(messageId)!!

        outbox.onError(item.requestId, WsErrorCodes.UNAUTHORIZED, "Session expired")

        assertEquals(MessageStatus.FAILED, statusUpdates[messageId])
    }

    // ==========================================================================
    // Gate 1: Failed reason stored
    // ==========================================================================

    @Test
    fun `gate1 failed item stores error code`() {
        val messageId = outbox.enqueueTextMessage("test", recipientId)!!
        val item = outbox.getByMessageId(messageId)!!

        outbox.onError(item.requestId, WsErrorCodes.INVALID_SIGNATURE, "Verification failed")

        assertEquals(WsErrorCodes.INVALID_SIGNATURE, item.failedCode)
    }

    @Test
    fun `gate1 failed item stores error message`() {
        val messageId = outbox.enqueueTextMessage("test", recipientId)!!
        val item = outbox.getByMessageId(messageId)!!

        outbox.onError(item.requestId, WsErrorCodes.INVALID_SIGNATURE, "Verification failed")

        assertEquals("Verification failed", item.failedMessage)
    }

    @Test
    fun `gate1 failedItems returns only failed items`() {
        val msg1 = outbox.enqueueTextMessage("test1", recipientId)!!
        val item1 = outbox.getByMessageId(msg1)!!

        // First message fails
        outbox.onError(item1.requestId, WsErrorCodes.INVALID_SIGNATURE, "Bad sig")

        // Second message succeeds
        val msg2 = outbox.enqueueTextMessage("test2", recipientId)!!
        outbox.onMessageAccepted(msg2)

        val failed = outbox.failedItems()
        assertEquals(1, failed.size)
        assertEquals(msg1, failed[0].messageId)
    }

    // ==========================================================================
    // Helper
    // ==========================================================================

    private fun createHoldingOutbox(): OutboxQueue {
        return OutboxQueue(
            myWhisperIdProvider = { myWhisperId },
            sessionTokenProvider = { mySessionToken },
            mySignPrivateKeyProvider = { mySignPrivateKey },
            myEncPrivateKeyProvider = { myEncPrivateKey },
            peerKeyProvider = object : PeerKeyProvider {
                override fun getSignPublicKey(whisperId: String) = null
                override fun getEncPublicKey(whisperId: String) = recipientEncPublicKey
            },
            wsSender = { true }, // Returns true but keeps item in sending state
            messageEncryptor = { _, _, _ -> Pair(mockNonce, mockCiphertext) },
            messageSigner = { _, _ -> mockSignature },
            timeProvider = { currentTime }
        )
    }
}
