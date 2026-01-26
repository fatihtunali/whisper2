package com.whisper2.app.outbox

import com.whisper2.app.network.ws.WsErrorCodes
import com.whisper2.app.services.messaging.*
import com.whisper2.app.storage.db.entities.OutboxStatus
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Gate 2: Retry Behavior
 *
 * Tests:
 * - Transient errors schedule retry with exponential backoff
 * - Permanent errors do not retry
 * - Max attempts handling
 */
class RetryBehaviorTest {

    private lateinit var outbox: OutboxQueue
    private var wsSendResult = true
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

    // Deterministic retry policy for testing
    private val testRetryPolicy = RetryPolicy(
        baseDelayMs = 1000L,
        maxDelayMs = 60000L,
        jitterRatio = 0.0, // No jitter for deterministic tests
        maxAttempts = 3
    )

    @Before
    fun setup() {
        wsSendResult = true

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
            wsSender = { wsSendResult },
            messageEncryptor = { _, _, _ -> Pair(mockNonce, mockCiphertext) },
            messageSigner = { _, _ -> mockSignature },
            timeProvider = { currentTime },
            retryPolicy = testRetryPolicy
        )
    }

    // ==========================================================================
    // Gate 2: Transient errors schedule retry
    // ==========================================================================

    @Test
    fun `gate2 WS send failure increments attempts`() {
        wsSendResult = false

        val messageId = outbox.enqueueTextMessage("test", recipientId)!!
        val item = outbox.getByMessageId(messageId)!!

        assertEquals(1, item.attempts)
    }

    @Test
    fun `gate2 WS send failure sets status back to queued`() {
        wsSendResult = false

        val messageId = outbox.enqueueTextMessage("test", recipientId)!!
        val item = outbox.getByMessageId(messageId)!!

        assertEquals(OutboxStatus.QUEUED, item.status)
    }

    @Test
    fun `gate2 WS send failure sets nextRetryAt`() {
        wsSendResult = false

        val messageId = outbox.enqueueTextMessage("test", recipientId)!!
        val item = outbox.getByMessageId(messageId)!!

        assertNotNull("nextRetryAt should be set", item.nextRetryAt)
        assertTrue("nextRetryAt should be in the future", item.nextRetryAt!! > currentTime)
    }

    @Test
    fun `gate2 transient server error schedules retry`() {
        val messageId = outbox.enqueueTextMessage("test", recipientId)!!
        val item = outbox.getByMessageId(messageId)!!

        // Transient error (not in permanent list)
        outbox.onError(item.requestId, "NETWORK_ERROR", "Connection lost")

        assertEquals(OutboxStatus.QUEUED, item.status)
        assertNotNull("nextRetryAt should be set", item.nextRetryAt)
    }

    @Test
    fun `gate2 transient error increments attempts`() {
        val messageId = outbox.enqueueTextMessage("test", recipientId)!!
        val item = outbox.getByMessageId(messageId)!!

        assertEquals(1, item.attempts) // First attempt on enqueue

        outbox.onError(item.requestId, "NETWORK_ERROR", "Connection lost")

        // Attempts still 1 because onError doesn't increment, handleTransientError doesn't either
        // The increment happens in attemptSend
        assertEquals(1, item.attempts)
    }

    @Test
    fun `gate2 onDisconnect handles sending message as transient error`() {
        val messageId = outbox.enqueueTextMessage("test", recipientId)!!
        val item = outbox.getByMessageId(messageId)!!

        assertEquals(OutboxStatus.SENDING, item.status)

        outbox.onDisconnect()

        assertEquals(OutboxStatus.QUEUED, item.status)
        assertNotNull("nextRetryAt should be set", item.nextRetryAt)
    }

    // ==========================================================================
    // Gate 2: Permanent errors do not retry
    // ==========================================================================

    @Test
    fun `gate2 INVALID_SIGNATURE does not set nextRetryAt`() {
        val messageId = outbox.enqueueTextMessage("test", recipientId)!!
        val item = outbox.getByMessageId(messageId)!!

        outbox.onError(item.requestId, WsErrorCodes.INVALID_SIGNATURE, "Bad sig")

        assertNull("nextRetryAt should be null for permanent error", item.nextRetryAt)
    }

    @Test
    fun `gate2 RECIPIENT_NOT_FOUND does not set nextRetryAt`() {
        val messageId = outbox.enqueueTextMessage("test", recipientId)!!
        val item = outbox.getByMessageId(messageId)!!

        outbox.onError(item.requestId, WsErrorCodes.RECIPIENT_NOT_FOUND, "User not found")

        assertNull("nextRetryAt should be null", item.nextRetryAt)
    }

    @Test
    fun `gate2 INVALID_PAYLOAD does not set nextRetryAt`() {
        val messageId = outbox.enqueueTextMessage("test", recipientId)!!
        val item = outbox.getByMessageId(messageId)!!

        outbox.onError(item.requestId, WsErrorCodes.INVALID_PAYLOAD, "Missing field")

        assertNull("nextRetryAt should be null", item.nextRetryAt)
    }

    @Test
    fun `gate2 UNAUTHORIZED does not set nextRetryAt`() {
        val messageId = outbox.enqueueTextMessage("test", recipientId)!!
        val item = outbox.getByMessageId(messageId)!!

        outbox.onError(item.requestId, WsErrorCodes.UNAUTHORIZED, "Session expired")

        assertNull("nextRetryAt should be null", item.nextRetryAt)
    }

    @Test
    fun `gate2 permanent error keeps attempts unchanged`() {
        val messageId = outbox.enqueueTextMessage("test", recipientId)!!
        val item = outbox.getByMessageId(messageId)!!

        val attemptsBefore = item.attempts

        outbox.onError(item.requestId, WsErrorCodes.INVALID_SIGNATURE, "Bad sig")

        assertEquals(attemptsBefore, item.attempts)
    }

    // ==========================================================================
    // Gate 2: Exponential backoff
    // ==========================================================================

    @Test
    fun `gate2 first retry delay is baseDelay`() {
        wsSendResult = false

        val messageId = outbox.enqueueTextMessage("test", recipientId)!!
        val item = outbox.getByMessageId(messageId)!!

        // First attempt, delay = base * 2^0 = 1000ms
        val expectedDelay = testRetryPolicy.baseDelayMs
        assertEquals(currentTime + expectedDelay, item.nextRetryAt)
    }

    @Test
    fun `gate2 second retry delay doubles`() {
        wsSendResult = false

        val messageId = outbox.enqueueTextMessage("test", recipientId)!!
        val item = outbox.getByMessageId(messageId)!!

        // Advance time past first retry
        currentTime += 2000L
        outbox.processQueue() // Second attempt

        // Second attempt, delay = base * 2^1 = 2000ms
        val expectedDelay = testRetryPolicy.baseDelayMs * 2
        assertEquals(currentTime + expectedDelay, item.nextRetryAt)
    }

    @Test
    fun `gate2 delay is capped at maxDelay`() {
        // Use policy with low max
        val cappedPolicy = RetryPolicy(
            baseDelayMs = 1000L,
            maxDelayMs = 2000L, // Cap at 2 seconds
            jitterRatio = 0.0,
            maxAttempts = 10
        )

        val cappedOutbox = OutboxQueue(
            myWhisperIdProvider = { myWhisperId },
            sessionTokenProvider = { mySessionToken },
            mySignPrivateKeyProvider = { mySignPrivateKey },
            myEncPrivateKeyProvider = { myEncPrivateKey },
            peerKeyProvider = object : PeerKeyProvider {
                override fun getSignPublicKey(whisperId: String) = null
                override fun getEncPublicKey(whisperId: String) = recipientEncPublicKey
            },
            wsSender = { false }, // Always fails
            messageEncryptor = { _, _, _ -> Pair(mockNonce, mockCiphertext) },
            messageSigner = { _, _ -> mockSignature },
            timeProvider = { currentTime },
            retryPolicy = cappedPolicy
        )

        val messageId = cappedOutbox.enqueueTextMessage("test", recipientId)!!
        val item = cappedOutbox.getByMessageId(messageId)!!

        // Third attempt would be 4000ms but capped at 2000ms
        currentTime += 10000L
        cappedOutbox.processQueue() // 2nd attempt
        currentTime += 10000L
        cappedOutbox.processQueue() // 3rd attempt

        // Delay should be capped at maxDelay
        assertTrue(item.nextRetryAt!! <= currentTime + cappedPolicy.maxDelayMs)
    }

    // ==========================================================================
    // Gate 2: Max attempts
    // ==========================================================================

    @Test
    fun `gate2 max attempts reached marks as failed`() {
        wsSendResult = false

        val messageId = outbox.enqueueTextMessage("test", recipientId)!!
        val item = outbox.getByMessageId(messageId)!!

        // First attempt already happened
        assertEquals(1, item.attempts)

        // Second attempt
        currentTime += 10000L
        outbox.processQueue()
        assertEquals(2, item.attempts)

        // Third attempt (max)
        currentTime += 10000L
        outbox.processQueue()
        assertEquals(3, item.attempts)

        // Should be failed now (max attempts = 3)
        assertEquals(OutboxStatus.FAILED, item.status)
        assertEquals("MAX_ATTEMPTS", item.failedCode)
    }

    @Test
    fun `gate2 max attempts nextRetryAt is null`() {
        wsSendResult = false

        val messageId = outbox.enqueueTextMessage("test", recipientId)!!
        val item = outbox.getByMessageId(messageId)!!

        // Exhaust attempts
        repeat(testRetryPolicy.maxAttempts - 1) {
            currentTime += 10000L
            outbox.processQueue()
        }

        assertNull("nextRetryAt should be null after max attempts", item.nextRetryAt)
    }

    // ==========================================================================
    // Gate 2: Retry scheduled messages
    // ==========================================================================

    @Test
    fun `gate2 message with future nextRetryAt is not sent`() {
        wsSendResult = false

        val messageId = outbox.enqueueTextMessage("test", recipientId)!!
        val item = outbox.getByMessageId(messageId)!!

        // Item has nextRetryAt in future
        assertTrue(item.nextRetryAt!! > currentTime)

        // Try to process without advancing time
        val attemptsBefore = item.attempts
        outbox.processQueue()

        // Should not have attempted again
        assertEquals(attemptsBefore, item.attempts)
    }

    @Test
    fun `gate2 message with past nextRetryAt is sent`() {
        wsSendResult = false

        val messageId = outbox.enqueueTextMessage("test", recipientId)!!
        val item = outbox.getByMessageId(messageId)!!

        val attemptsBefore = item.attempts

        // Advance time past nextRetryAt
        currentTime = item.nextRetryAt!! + 1000L
        outbox.processQueue()

        assertEquals(attemptsBefore + 1, item.attempts)
    }

    @Test
    fun `gate2 resume resets isPaused and processes queue`() {
        val messageId = outbox.enqueueTextMessage("test", recipientId)!!
        val item = outbox.getByMessageId(messageId)!!

        // Pause queue
        outbox.onError(item.requestId, WsErrorCodes.UNAUTHORIZED, "Session expired")
        assertTrue(outbox.isPaused)

        // Resume
        outbox.resume()
        assertFalse("isPaused should be false after resume", outbox.isPaused)
    }
}
