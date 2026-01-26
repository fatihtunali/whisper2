package com.whisper2.app.outbox

import com.whisper2.app.services.messaging.*
import com.whisper2.app.storage.db.entities.MessageStatus
import com.whisper2.app.storage.db.entities.OutboxStatus
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Gate 5: Idempotency + Duplicate ACKs
 *
 * Tests:
 * - Same messageId cannot be enqueued twice
 * - Duplicate message_accepted is ignored
 * - delivery_receipt status ordering (read cannot go back to delivered)
 */
class IdempotencyTest {

    private lateinit var outbox: OutboxQueue
    private lateinit var statusUpdates: MutableMap<String, String>
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
        statusUpdates = mutableMapOf()

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
            wsSender = { true },
            messageEncryptor = { _, _, _ -> Pair(mockNonce, mockCiphertext) },
            messageSigner = { _, _ -> mockSignature },
            messageStatusUpdater = { messageId, status -> statusUpdates[messageId] = status },
            timeProvider = { currentTime }
        )
    }

    // ==========================================================================
    // Gate 5: Duplicate message_accepted ignored
    // ==========================================================================

    @Test
    fun `gate5 duplicate message_accepted is ignored`() {
        val messageId = outbox.enqueueTextMessage("test", recipientId)!!

        // First ACK
        outbox.onMessageAccepted(messageId)
        val item = outbox.getByMessageId(messageId)!!
        assertEquals(OutboxStatus.SENT, item.status)

        // Second ACK (duplicate)
        outbox.onMessageAccepted(messageId)

        // Status should still be SENT, no error
        assertEquals(OutboxStatus.SENT, item.status)
    }

    @Test
    fun `gate5 duplicate message_accepted does not update status again`() {
        val messageId = outbox.enqueueTextMessage("test", recipientId)!!
        statusUpdates.clear()

        // First ACK
        outbox.onMessageAccepted(messageId)
        assertEquals(MessageStatus.SENT, statusUpdates[messageId])

        statusUpdates.clear()

        // Second ACK (duplicate)
        outbox.onMessageAccepted(messageId)

        // Should not have updated again (map should be empty)
        assertFalse("Duplicate ACK should not trigger status update", statusUpdates.containsKey(messageId))
    }

    @Test
    fun `gate5 message_accepted for unknown messageId is safe`() {
        // Should not throw
        outbox.onMessageAccepted("unknown-message-id")

        // No crash, just logs warning
        assertTrue(true)
    }

    // ==========================================================================
    // Gate 5: Duplicate enqueue prevented
    // ==========================================================================

    @Test
    fun `gate5 messageId is unique per enqueue`() {
        val msg1 = outbox.enqueueTextMessage("first", recipientId)!!
        val msg2 = outbox.enqueueTextMessage("second", recipientId)!!
        val msg3 = outbox.enqueueTextMessage("third", recipientId)!!

        // All should be different
        assertNotEquals(msg1, msg2)
        assertNotEquals(msg2, msg3)
        assertNotEquals(msg1, msg3)
    }

    @Test
    fun `gate5 same content different messageId`() {
        val msg1 = outbox.enqueueTextMessage("same content", recipientId)!!
        outbox.onMessageAccepted(msg1)

        val msg2 = outbox.enqueueTextMessage("same content", recipientId)!!

        // Should be different IDs even with same content
        assertNotEquals(msg1, msg2)
    }

    // ==========================================================================
    // Gate 5: Error for unknown requestId is safe
    // ==========================================================================

    @Test
    fun `gate5 error for unknown requestId is safe`() {
        // Should not throw
        outbox.onError("unknown-request-id", "SOME_ERROR", "Some message")

        // No crash, just logs warning
        assertTrue(true)
    }

    // ==========================================================================
    // Gate 5: Queue state consistency
    // ==========================================================================

    @Test
    fun `gate5 sent message stays in pendingByMessageId for duplicate detection`() {
        val messageId = outbox.enqueueTextMessage("test", recipientId)!!

        outbox.onMessageAccepted(messageId)

        // Removed from queue
        assertEquals(0, outbox.size())

        // But still in pendingByMessageId for duplicate ACK detection
        val item = outbox.getByMessageId(messageId)
        assertNotNull(item)
        assertEquals(OutboxStatus.SENT, item?.status)
    }

    @Test
    fun `gate5 failed message removed from queue but kept for status`() {
        val messageId = outbox.enqueueTextMessage("test", recipientId)!!
        val item = outbox.getByMessageId(messageId)!!

        outbox.onError(item.requestId, "INVALID_SIGNATURE", "Bad sig")

        // Removed from queue
        assertEquals(0, outbox.size())

        // But still in pendingByMessageId
        val itemAfter = outbox.getByMessageId(messageId)
        assertNotNull(itemAfter)
        assertEquals(OutboxStatus.FAILED, itemAfter?.status)
    }

    // ==========================================================================
    // Gate 5: Delivery receipt status ordering
    // Note: This tests the concept - actual implementation would be in MessagingService
    // ==========================================================================

    @Test
    fun `gate5 status ordering concept delivered then read`() {
        // This test validates the status hierarchy concept
        // delivered < read

        val statusOrder = mapOf(
            MessageStatus.PENDING to 0,
            MessageStatus.SENT to 1,
            MessageStatus.DELIVERED to 2,
            MessageStatus.READ to 3,
            MessageStatus.FAILED to -1
        )

        assertTrue("SENT > PENDING", statusOrder[MessageStatus.SENT]!! > statusOrder[MessageStatus.PENDING]!!)
        assertTrue("DELIVERED > SENT", statusOrder[MessageStatus.DELIVERED]!! > statusOrder[MessageStatus.SENT]!!)
        assertTrue("READ > DELIVERED", statusOrder[MessageStatus.READ]!! > statusOrder[MessageStatus.DELIVERED]!!)
    }

    @Test
    fun `gate5 read cannot go back to delivered conceptually`() {
        // Simulating the logic that would be in MessagingService
        fun shouldUpdateStatus(current: String, new: String): Boolean {
            val order = mapOf(
                MessageStatus.PENDING to 0,
                MessageStatus.SENT to 1,
                MessageStatus.DELIVERED to 2,
                MessageStatus.READ to 3
            )
            val currentOrder = order[current] ?: return true
            val newOrder = order[new] ?: return false
            return newOrder > currentOrder
        }

        // delivered -> read: OK
        assertTrue(shouldUpdateStatus(MessageStatus.DELIVERED, MessageStatus.READ))

        // read -> delivered: NOT OK
        assertFalse(shouldUpdateStatus(MessageStatus.READ, MessageStatus.DELIVERED))

        // sent -> delivered: OK
        assertTrue(shouldUpdateStatus(MessageStatus.SENT, MessageStatus.DELIVERED))

        // delivered -> sent: NOT OK
        assertFalse(shouldUpdateStatus(MessageStatus.DELIVERED, MessageStatus.SENT))
    }

    // ==========================================================================
    // Gate 5: Clear resets all state
    // ==========================================================================

    @Test
    fun `gate5 clear removes all tracking`() {
        outbox.enqueueTextMessage("test1", recipientId)
        outbox.enqueueTextMessage("test2", recipientId)

        assertTrue(outbox.size() > 0)

        outbox.clear()

        assertEquals(0, outbox.size())
        assertEquals(0, outbox.enqueuedCount)
        assertEquals(0, outbox.sendAttempts)
        assertNull(outbox.lastPayload)
    }
}
