package com.whisper2.app.outbox

import com.whisper2.app.messaging.InMemoryMessageDao
import com.whisper2.app.messaging.TestMessageDao
import com.whisper2.app.messaging.InMemoryConversationDao
import com.whisper2.app.messaging.TestConversationDao
import com.whisper2.app.services.messaging.*
import com.whisper2.app.storage.db.entities.MessageEntity
import com.whisper2.app.storage.db.entities.MessageStatus
import com.whisper2.app.storage.db.entities.MessageType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Gate 5 (continued): Delivery Receipt Status Ordering
 *
 * Tests:
 * - delivered → read: OK
 * - read → delivered: NOT OK (cannot go backward)
 * - sent → delivered: OK
 * - delivered → sent: NOT OK
 * - failed is terminal
 */
class DeliveryReceiptTest {

    private lateinit var messageDao: InMemoryMessageDao
    private lateinit var conversationDao: InMemoryConversationDao
    private lateinit var service: MessagingService

    private val myWhisperId = "WSP-RECIPIENT"
    private val myEncPrivateKey = ByteArray(32) { (it + 1).toByte() }
    private val senderWhisperId = "WSP-SENDER"
    private val senderSignPublicKey = ByteArray(32) { (it + 10).toByte() }
    private val senderEncPublicKey = ByteArray(32) { (it + 20).toByte() }

    @Before
    fun setup() {
        messageDao = InMemoryMessageDao()
        conversationDao = InMemoryConversationDao()

        service = MessagingService(
            messageDao = TestMessageDao(messageDao),
            conversationDao = TestConversationDao(conversationDao),
            myEncPrivateKeyProvider = { myEncPrivateKey },
            peerKeyProvider = object : PeerKeyProvider {
                override fun getSignPublicKey(whisperId: String) = senderSignPublicKey
                override fun getEncPublicKey(whisperId: String) = senderEncPublicKey
            },
            receiptSender = { _, _, _ -> },
            myWhisperIdProvider = { myWhisperId }
        )
    }

    // ==========================================================================
    // Gate 5: Status ordering - forward movement only
    // ==========================================================================

    @Test
    fun `gate5 sent to delivered updates status`() {
        val message = createMessage("msg-1", MessageStatus.SENT)
        messageDao.insert(message)

        val result = service.handleDeliveryReceipt("msg-1", MessageStatus.DELIVERED, System.currentTimeMillis())

        assertTrue("Should update sent -> delivered", result)
        assertEquals(MessageStatus.DELIVERED, messageDao.getById("msg-1")?.status)
    }

    @Test
    fun `gate5 delivered to read updates status`() {
        val message = createMessage("msg-1", MessageStatus.DELIVERED)
        messageDao.insert(message)

        val result = service.handleDeliveryReceipt("msg-1", MessageStatus.READ, System.currentTimeMillis())

        assertTrue("Should update delivered -> read", result)
        assertEquals(MessageStatus.READ, messageDao.getById("msg-1")?.status)
    }

    @Test
    fun `gate5 sent to read updates status`() {
        val message = createMessage("msg-1", MessageStatus.SENT)
        messageDao.insert(message)

        val result = service.handleDeliveryReceipt("msg-1", MessageStatus.READ, System.currentTimeMillis())

        assertTrue("Should update sent -> read", result)
        assertEquals(MessageStatus.READ, messageDao.getById("msg-1")?.status)
    }

    // ==========================================================================
    // Gate 5: Backward movement not allowed
    // ==========================================================================

    @Test
    fun `gate5 read to delivered does not update`() {
        val message = createMessage("msg-1", MessageStatus.READ)
        messageDao.insert(message)

        val result = service.handleDeliveryReceipt("msg-1", MessageStatus.DELIVERED, System.currentTimeMillis())

        assertFalse("Should NOT update read -> delivered", result)
        assertEquals(MessageStatus.READ, messageDao.getById("msg-1")?.status)
    }

    @Test
    fun `gate5 delivered to sent does not update`() {
        val message = createMessage("msg-1", MessageStatus.DELIVERED)
        messageDao.insert(message)

        val result = service.handleDeliveryReceipt("msg-1", MessageStatus.SENT, System.currentTimeMillis())

        assertFalse("Should NOT update delivered -> sent", result)
        assertEquals(MessageStatus.DELIVERED, messageDao.getById("msg-1")?.status)
    }

    @Test
    fun `gate5 read to sent does not update`() {
        val message = createMessage("msg-1", MessageStatus.READ)
        messageDao.insert(message)

        val result = service.handleDeliveryReceipt("msg-1", MessageStatus.SENT, System.currentTimeMillis())

        assertFalse("Should NOT update read -> sent", result)
        assertEquals(MessageStatus.READ, messageDao.getById("msg-1")?.status)
    }

    // ==========================================================================
    // Gate 5: Failed is terminal
    // ==========================================================================

    @Test
    fun `gate5 failed to delivered does not update`() {
        val message = createMessage("msg-1", MessageStatus.FAILED)
        messageDao.insert(message)

        val result = service.handleDeliveryReceipt("msg-1", MessageStatus.DELIVERED, System.currentTimeMillis())

        assertFalse("Should NOT update failed -> delivered", result)
        assertEquals(MessageStatus.FAILED, messageDao.getById("msg-1")?.status)
    }

    @Test
    fun `gate5 failed to read does not update`() {
        val message = createMessage("msg-1", MessageStatus.FAILED)
        messageDao.insert(message)

        val result = service.handleDeliveryReceipt("msg-1", MessageStatus.READ, System.currentTimeMillis())

        assertFalse("Should NOT update failed -> read", result)
        assertEquals(MessageStatus.FAILED, messageDao.getById("msg-1")?.status)
    }

    @Test
    fun `gate5 failed to sent does not update`() {
        val message = createMessage("msg-1", MessageStatus.FAILED)
        messageDao.insert(message)

        val result = service.handleDeliveryReceipt("msg-1", MessageStatus.SENT, System.currentTimeMillis())

        assertFalse("Should NOT update failed -> sent", result)
        assertEquals(MessageStatus.FAILED, messageDao.getById("msg-1")?.status)
    }

    // ==========================================================================
    // Gate 5: Same status ignored
    // ==========================================================================

    @Test
    fun `gate5 same status does not update`() {
        val message = createMessage("msg-1", MessageStatus.DELIVERED)
        messageDao.insert(message)

        val result = service.handleDeliveryReceipt("msg-1", MessageStatus.DELIVERED, System.currentTimeMillis())

        assertFalse("Should NOT update delivered -> delivered", result)
    }

    // ==========================================================================
    // Gate 5: Unknown message
    // ==========================================================================

    @Test
    fun `gate5 unknown message returns false`() {
        val result = service.handleDeliveryReceipt("unknown-msg", MessageStatus.DELIVERED, System.currentTimeMillis())

        assertFalse("Should return false for unknown message", result)
    }

    // ==========================================================================
    // Gate 5: Duplicate receipts
    // ==========================================================================

    @Test
    fun `gate5 duplicate delivered receipt is safe`() {
        val message = createMessage("msg-1", MessageStatus.SENT)
        messageDao.insert(message)

        // First receipt
        service.handleDeliveryReceipt("msg-1", MessageStatus.DELIVERED, System.currentTimeMillis())
        assertEquals(MessageStatus.DELIVERED, messageDao.getById("msg-1")?.status)

        // Duplicate receipt
        val result = service.handleDeliveryReceipt("msg-1", MessageStatus.DELIVERED, System.currentTimeMillis())

        assertFalse("Duplicate should return false", result)
        assertEquals(MessageStatus.DELIVERED, messageDao.getById("msg-1")?.status)
    }

    @Test
    fun `gate5 delivered then read sequence works`() {
        val message = createMessage("msg-1", MessageStatus.SENT)
        messageDao.insert(message)

        // First: delivered
        service.handleDeliveryReceipt("msg-1", MessageStatus.DELIVERED, 1700000001000L)
        assertEquals(MessageStatus.DELIVERED, messageDao.getById("msg-1")?.status)

        // Then: read
        service.handleDeliveryReceipt("msg-1", MessageStatus.READ, 1700000002000L)
        assertEquals(MessageStatus.READ, messageDao.getById("msg-1")?.status)
    }

    @Test
    fun `gate5 read first then delivered ignored`() {
        val message = createMessage("msg-1", MessageStatus.SENT)
        messageDao.insert(message)

        // Out of order: read first
        service.handleDeliveryReceipt("msg-1", MessageStatus.READ, 1700000002000L)
        assertEquals(MessageStatus.READ, messageDao.getById("msg-1")?.status)

        // Then delivered (should be ignored)
        service.handleDeliveryReceipt("msg-1", MessageStatus.DELIVERED, 1700000001000L)
        assertEquals(MessageStatus.READ, messageDao.getById("msg-1")?.status) // Still read
    }

    // ==========================================================================
    // Helper
    // ==========================================================================

    private fun createMessage(messageId: String, status: String): MessageEntity {
        return MessageEntity(
            messageId = messageId,
            conversationId = senderWhisperId,
            from = myWhisperId,
            to = senderWhisperId,
            msgType = MessageType.TEXT,
            timestamp = System.currentTimeMillis(),
            nonceB64 = "AAAA",
            ciphertextB64 = "BBBB",
            sigB64 = "CCCC",
            text = "test",
            status = status,
            isOutgoing = true
        )
    }
}
