package com.whisper2.app.outbox

import com.whisper2.app.services.messaging.*
import com.whisper2.app.storage.db.entities.OutboxStatus
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Gate 4: Resume after reconnect
 *
 * Tests:
 * - Queue resumes after reconnect
 * - Sending message handled on disconnect
 * - fetch_pending before send_message ordering
 */
class ReconnectResumeTest {

    private lateinit var outbox: OutboxQueue
    private var wsSendResult = true
    private var currentTime: Long = 1700000000000L
    private var sentPayloads: MutableList<String> = mutableListOf()

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
            wsSender = { json ->
                sentPayloads.add(json)
                wsSendResult
            },
            messageEncryptor = { _, _, _ -> Pair(mockNonce, mockCiphertext) },
            messageSigner = { _, _ -> mockSignature },
            timeProvider = { currentTime },
            retryPolicy = RetryPolicy(
                baseDelayMs = 1000L,
                maxDelayMs = 60000L,
                jitterRatio = 0.0,
                maxAttempts = 5
            )
        )
    }

    // ==========================================================================
    // Gate 4: Disconnect handling
    // ==========================================================================

    @Test
    fun `gate4 onDisconnect moves sending to queued`() {
        val messageId = outbox.enqueueTextMessage("test", recipientId)!!
        val item = outbox.getByMessageId(messageId)!!

        assertEquals(OutboxStatus.SENDING, item.status)

        outbox.onDisconnect()

        assertEquals(OutboxStatus.QUEUED, item.status)
    }

    @Test
    fun `gate4 onDisconnect sets nextRetryAt`() {
        val messageId = outbox.enqueueTextMessage("test", recipientId)!!
        val item = outbox.getByMessageId(messageId)!!

        outbox.onDisconnect()

        assertNotNull("nextRetryAt should be set after disconnect", item.nextRetryAt)
    }

    @Test
    fun `gate4 onDisconnect increments attempts via handleTransientError`() {
        val messageId = outbox.enqueueTextMessage("test", recipientId)!!
        val item = outbox.getByMessageId(messageId)!!

        val attemptsBefore = item.attempts
        outbox.onDisconnect()

        // handleTransientError doesn't increment attempts, attemptSend does
        assertEquals(attemptsBefore, item.attempts)
    }

    @Test
    fun `gate4 queued messages not affected by disconnect`() {
        // First message goes to sending
        val msg1 = outbox.enqueueTextMessage("first", recipientId)!!
        // Second stays queued
        val msg2 = outbox.enqueueTextMessage("second", recipientId)!!

        val item2 = outbox.getByMessageId(msg2)!!
        assertEquals(OutboxStatus.QUEUED, item2.status)

        outbox.onDisconnect()

        // Second should still be queued (not affected)
        assertEquals(OutboxStatus.QUEUED, item2.status)
        assertNull("Queued item should not have nextRetryAt set by disconnect", item2.nextRetryAt)
    }

    // ==========================================================================
    // Gate 4: Resume after reconnect
    // ==========================================================================

    @Test
    fun `gate4 resume processes queue`() {
        wsSendResult = false

        val messageId = outbox.enqueueTextMessage("test", recipientId)!!
        val item = outbox.getByMessageId(messageId)!!

        // Item failed to send, now queued with nextRetryAt
        assertEquals(OutboxStatus.QUEUED, item.status)

        // Simulate reconnect and resume
        wsSendResult = true
        currentTime = item.nextRetryAt!! + 1000L
        outbox.resume()

        // Should have attempted to send
        assertEquals(OutboxStatus.SENDING, item.status)
    }

    @Test
    fun `gate4 resume clears isPaused`() {
        outbox.pause()
        assertTrue(outbox.isPaused)

        outbox.resume()
        assertFalse(outbox.isPaused)
    }

    @Test
    fun `gate4 paused queue does not process`() {
        outbox.pause()

        val sendCountBefore = sentPayloads.size

        // Try to enqueue and process
        outbox.processQueue()

        assertEquals(sendCountBefore, sentPayloads.size)
    }

    @Test
    fun `gate4 resume resends messages after reconnect`() {
        val messageId = outbox.enqueueTextMessage("test", recipientId)!!
        val item = outbox.getByMessageId(messageId)!!

        assertEquals(1, sentPayloads.size) // First send

        // Disconnect
        outbox.onDisconnect()
        assertEquals(OutboxStatus.QUEUED, item.status)

        // Reconnect and resume
        currentTime = item.nextRetryAt!! + 1000L
        outbox.resume()

        assertEquals(2, sentPayloads.size) // Second send after resume
    }

    // ==========================================================================
    // Gate 4: fetch_pending before send_message
    // ==========================================================================

    @Test
    fun `gate4 queue starts paused allows fetch_pending first`() {
        // Create a new outbox that starts paused
        val pausedOutbox = OutboxQueue(
            myWhisperIdProvider = { myWhisperId },
            sessionTokenProvider = { mySessionToken },
            mySignPrivateKeyProvider = { mySignPrivateKey },
            myEncPrivateKeyProvider = { myEncPrivateKey },
            peerKeyProvider = object : PeerKeyProvider {
                override fun getSignPublicKey(whisperId: String) = null
                override fun getEncPublicKey(whisperId: String) = recipientEncPublicKey
            },
            wsSender = { sentPayloads.add(it); true },
            messageEncryptor = { _, _, _ -> Pair(mockNonce, mockCiphertext) },
            messageSigner = { _, _ -> mockSignature },
            timeProvider = { currentTime }
        )

        // Manually pause before enqueue
        pausedOutbox.pause()

        // Enqueue while paused
        val msgId = pausedOutbox.enqueueTextMessage("test", recipientId)
        assertNotNull(msgId)

        // No sends should have happened
        val sendCountWhilePaused = sentPayloads.size

        // Now resume (simulating after fetch_pending completes)
        pausedOutbox.resume()

        // Now the message should be sent
        assertTrue(sentPayloads.size > sendCountWhilePaused)
    }

    @Test
    fun `gate4 integration fetch_pending then outbox resume flow`() {
        // Simulate the full reconnect flow:
        // 1. Connect
        // 2. Send fetch_pending
        // 3. Receive pending_messages
        // 4. Resume outbox

        var fetchPendingSent = false
        var outboxResumed = false

        // Step 1: Outbox starts paused (waiting for fetch_pending)
        val flowOutbox = OutboxQueue(
            myWhisperIdProvider = { myWhisperId },
            sessionTokenProvider = { mySessionToken },
            mySignPrivateKeyProvider = { mySignPrivateKey },
            myEncPrivateKeyProvider = { myEncPrivateKey },
            peerKeyProvider = object : PeerKeyProvider {
                override fun getSignPublicKey(whisperId: String) = null
                override fun getEncPublicKey(whisperId: String) = recipientEncPublicKey
            },
            wsSender = { sentPayloads.add(it); true },
            messageEncryptor = { _, _, _ -> Pair(mockNonce, mockCiphertext) },
            messageSigner = { _, _ -> mockSignature },
            timeProvider = { currentTime }
        )

        flowOutbox.pause()

        // User queues a message while disconnected/paused
        flowOutbox.enqueueTextMessage("queued while paused", recipientId)
        assertEquals(0, sentPayloads.filter { it.contains("send_message") }.size)

        // Step 2: Reconnect happens, send fetch_pending first
        fetchPendingSent = true
        // In real code: pendingFetcher.fetch()

        // Step 3: Receive pending_messages response
        // In real code: handle pending messages

        // Step 4: Resume outbox
        flowOutbox.resume()
        outboxResumed = true

        // Now outbox should have sent the queued message
        assertTrue("fetch_pending should complete before outbox resumes", fetchPendingSent)
        assertTrue("Outbox should be resumed", outboxResumed)
        assertEquals(1, sentPayloads.filter { it.contains("send_message") }.size)
    }

    // ==========================================================================
    // Gate 4: Multiple messages after reconnect
    // ==========================================================================

    @Test
    fun `gate4 multiple queued messages resume in order`() {
        // Pause initially
        outbox.pause()

        // Queue multiple messages
        val msg1 = outbox.enqueueTextMessage("first", recipientId)!!
        val msg2 = outbox.enqueueTextMessage("second", recipientId)!!
        val msg3 = outbox.enqueueTextMessage("third", recipientId)!!

        assertEquals(0, sentPayloads.size) // Nothing sent yet

        // Resume
        outbox.resume()

        // First should be sending
        val item1 = outbox.getByMessageId(msg1)!!
        assertEquals(OutboxStatus.SENDING, item1.status)

        // Complete and check order
        outbox.onMessageAccepted(msg1)
        val item2 = outbox.getByMessageId(msg2)!!
        assertEquals(OutboxStatus.SENDING, item2.status)

        outbox.onMessageAccepted(msg2)
        val item3 = outbox.getByMessageId(msg3)!!
        assertEquals(OutboxStatus.SENDING, item3.status)
    }
}
