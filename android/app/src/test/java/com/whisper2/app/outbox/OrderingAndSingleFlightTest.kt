package com.whisper2.app.outbox

import com.whisper2.app.services.messaging.*
import com.whisper2.app.storage.db.entities.OutboxStatus
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Gate 3: Ordering + Single-flight
 *
 * Tests:
 * - FIFO order maintained
 * - Only 1 message sending at a time
 * - Multiple messages queue correctly
 */
class OrderingAndSingleFlightTest {

    private lateinit var sentMessageIds: MutableList<String>
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
        sentMessageIds = mutableListOf()
    }

    // ==========================================================================
    // Gate 3: FIFO order
    // ==========================================================================

    @Test
    fun `gate3 messages sent in FIFO order`() {
        val outbox = createOutbox()

        // Enqueue multiple messages without processing
        val msg1 = outbox.enqueueTextMessage("first", recipientId)!!
        outbox.onMessageAccepted(msg1) // Complete first

        val msg2 = outbox.enqueueTextMessage("second", recipientId)!!
        outbox.onMessageAccepted(msg2) // Complete second

        val msg3 = outbox.enqueueTextMessage("third", recipientId)!!
        outbox.onMessageAccepted(msg3) // Complete third

        // Verify FIFO order
        assertEquals(3, sentMessageIds.size)
        assertEquals(msg1, sentMessageIds[0])
        assertEquals(msg2, sentMessageIds[1])
        assertEquals(msg3, sentMessageIds[2])
    }

    @Test
    fun `gate3 100 messages drain in FIFO order`() {
        val outbox = createOutbox()

        // Enqueue 100 messages
        val messageIds = mutableListOf<String>()
        repeat(100) { i ->
            val msgId = outbox.enqueueTextMessage("message $i", recipientId)!!
            outbox.onMessageAccepted(msgId) // Complete immediately to allow next
            messageIds.add(msgId)
        }

        // Verify all sent in order
        assertEquals(100, sentMessageIds.size)
        messageIds.forEachIndexed { index, expectedId ->
            assertEquals("Message $index should be in correct position", expectedId, sentMessageIds[index])
        }
    }

    // ==========================================================================
    // Gate 3: Single-flight
    // ==========================================================================

    @Test
    fun `gate3 only one message sending at a time`() {
        val outbox = createOutbox()

        // Enqueue first message - goes to sending
        val msg1 = outbox.enqueueTextMessage("first", recipientId)!!
        val item1 = outbox.getByMessageId(msg1)!!
        assertEquals(OutboxStatus.SENDING, item1.status)

        // Enqueue second message - should stay queued
        val msg2 = outbox.enqueueTextMessage("second", recipientId)!!
        val item2 = outbox.getByMessageId(msg2)!!

        // First is still sending, second should be queued
        assertEquals(OutboxStatus.SENDING, item1.status)
        assertEquals(OutboxStatus.QUEUED, item2.status)
    }

    @Test
    fun `gate3 sendingCount returns 1 when sending`() {
        val outbox = createOutbox()

        outbox.enqueueTextMessage("first", recipientId)
        outbox.enqueueTextMessage("second", recipientId)
        outbox.enqueueTextMessage("third", recipientId)

        assertEquals("Only 1 should be sending", 1, outbox.sendingCount())
        assertEquals("2 should be queued", 2, outbox.queuedCount())
    }

    @Test
    fun `gate3 second message sends after first completes`() {
        val outbox = createOutbox()

        val msg1 = outbox.enqueueTextMessage("first", recipientId)!!
        val msg2 = outbox.enqueueTextMessage("second", recipientId)!!

        val item2 = outbox.getByMessageId(msg2)!!
        assertEquals(OutboxStatus.QUEUED, item2.status)

        // Complete first message
        outbox.onMessageAccepted(msg1)

        // Now second should be sending
        assertEquals(OutboxStatus.SENDING, item2.status)
    }

    @Test
    fun `gate3 parallel enqueue does not cause parallel send`() {
        val outbox = createOutbox()

        // Rapidly enqueue multiple messages
        val ids = mutableListOf<String>()
        repeat(10) { i ->
            val id = outbox.enqueueTextMessage("msg$i", recipientId)!!
            ids.add(id)
        }

        // Only first should be sending
        assertEquals(1, outbox.sendingCount())
        assertEquals(9, outbox.queuedCount())
    }

    @Test
    fun `gate3 processQueue while sending does nothing`() {
        val outbox = createOutbox()

        val msg1 = outbox.enqueueTextMessage("first", recipientId)!!
        outbox.enqueueTextMessage("second", recipientId)

        val sendCountBefore = outbox.sendAttempts

        // Try to process again
        outbox.processQueue()
        outbox.processQueue()
        outbox.processQueue()

        // Should not have made additional send attempts
        assertEquals(sendCountBefore, outbox.sendAttempts)
    }

    // ==========================================================================
    // Gate 3: Multiple messages queue correctly
    // ==========================================================================

    @Test
    fun `gate3 multiple messages all queued`() {
        val outbox = createOutbox()

        repeat(5) { i ->
            outbox.enqueueTextMessage("msg$i", recipientId)
        }

        assertEquals(5, outbox.size())
        assertEquals(5, outbox.enqueuedCount)
    }

    @Test
    fun `gate3 queue drains completely on success`() {
        val outbox = createOutbox()

        val ids = mutableListOf<String>()
        repeat(5) { i ->
            val id = outbox.enqueueTextMessage("msg$i", recipientId)!!
            ids.add(id)
        }

        // Complete all
        ids.forEach { outbox.onMessageAccepted(it) }

        assertEquals(0, outbox.size())
    }

    @Test
    fun `gate3 failed message does not block queue`() {
        val outbox = createOutbox()

        val msg1 = outbox.enqueueTextMessage("first", recipientId)!!
        val msg2 = outbox.enqueueTextMessage("second", recipientId)!!

        val item1 = outbox.getByMessageId(msg1)!!
        val item2 = outbox.getByMessageId(msg2)!!

        // Fail first message with permanent error
        outbox.onError(item1.requestId, "INVALID_SIGNATURE", "Bad sig")

        // Second should now be sending
        assertEquals(OutboxStatus.FAILED, item1.status)
        assertEquals(OutboxStatus.SENDING, item2.status)
    }

    // ==========================================================================
    // Helper
    // ==========================================================================

    private fun createOutbox(): OutboxQueue {
        return OutboxQueue(
            myWhisperIdProvider = { myWhisperId },
            sessionTokenProvider = { mySessionToken },
            mySignPrivateKeyProvider = { mySignPrivateKey },
            myEncPrivateKeyProvider = { myEncPrivateKey },
            peerKeyProvider = object : PeerKeyProvider {
                override fun getSignPublicKey(whisperId: String) = null
                override fun getEncPublicKey(whisperId: String) = recipientEncPublicKey
            },
            wsSender = { json ->
                // Extract messageId from payload for tracking
                val msgIdMatch = Regex("\"messageId\":\"([^\"]+)\"").find(json)
                msgIdMatch?.groupValues?.get(1)?.let { sentMessageIds.add(it) }
                true
            },
            messageEncryptor = { _, _, _ -> Pair(mockNonce, mockCiphertext) },
            messageSigner = { _, _ -> mockSignature },
            timeProvider = { currentTime }
        )
    }
}
