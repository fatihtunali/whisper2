package com.whisper2.app.messaging

import com.whisper2.app.core.utils.Base64Strict
import com.whisper2.app.crypto.CanonicalSigning
import com.whisper2.app.network.ws.PendingMessageItem
import com.whisper2.app.services.messaging.*
import com.whisper2.app.storage.db.entities.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest

/**
 * Gate 2: Inbound message verify + decrypt + persist
 *
 * Tests the full inbound pipeline with test crypto implementations
 * that can run on JVM without LazySodium native libraries.
 */
class InboundMessageTest {

    private lateinit var messageDao: InMemoryMessageDao
    private lateinit var conversationDao: InMemoryConversationDao
    private lateinit var receiptsSent: MutableList<Triple<String, String, String>>

    // Test identity
    private val myWhisperId = "WSP-RECIPIENT"
    private val myEncPrivateKey = ByteArray(32) { (it + 1).toByte() }

    // Sender identity
    private val senderWhisperId = "WSP-SENDER"
    private val senderSignPublicKey = ByteArray(32) { (it + 10).toByte() }
    private val senderEncPublicKey = ByteArray(32) { (it + 20).toByte() }

    // Valid base64 values (24 byte nonce, 64 byte sig)
    private val validNonce = ByteArray(24) { it.toByte() }
    private val validNonceB64 = Base64Strict.encode(validNonce)
    private val validSig = ByteArray(64) { it.toByte() }
    private val validSigB64 = Base64Strict.encode(validSig)
    private val validCiphertext = ByteArray(100) { it.toByte() }
    private val validCiphertextB64 = Base64Strict.encode(validCiphertext)

    @Before
    fun setup() {
        messageDao = InMemoryMessageDao()
        conversationDao = InMemoryConversationDao()
        receiptsSent = mutableListOf()
    }

    // ==========================================================================
    // Gate 2: Base64 decoding
    // ==========================================================================

    @Test
    fun `gate2 invalid base64 nonce is rejected`() {
        val service = createSuccessfulService()
        val item = createPendingMessage(
            messageId = "msg-bad-nonce",
            nonce = "not-valid-base64!!!"
        )

        val result = service.handleInbound(item)

        assertTrue("Should be rejected", result is InboundResult.Rejected)
        assertEquals("Invalid base64 encoding", (result as InboundResult.Rejected).reason)
        assertEquals(0, messageDao.count())
    }

    @Test
    fun `gate2 invalid base64 ciphertext is rejected`() {
        val service = createSuccessfulService()
        val item = createPendingMessage(
            messageId = "msg-bad-ct",
            ciphertext = "invalid-base64!!!"
        )

        val result = service.handleInbound(item)

        assertTrue("Should be rejected", result is InboundResult.Rejected)
        assertEquals(0, messageDao.count())
    }

    @Test
    fun `gate2 invalid base64 sig is rejected`() {
        val service = createSuccessfulService()
        val item = createPendingMessage(
            messageId = "msg-bad-sig",
            sig = "invalid-base64!!!"
        )

        val result = service.handleInbound(item)

        assertTrue("Should be rejected", result is InboundResult.Rejected)
        assertEquals(0, messageDao.count())
    }

    // ==========================================================================
    // Gate 2: Length validation
    // ==========================================================================

    @Test
    fun `gate2 wrong nonce length is rejected`() {
        val service = createSuccessfulService()
        val shortNonce = ByteArray(16) { it.toByte() } // Should be 24

        val item = createPendingMessage(
            messageId = "msg-short-nonce",
            nonce = Base64Strict.encode(shortNonce)
        )

        val result = service.handleInbound(item)

        assertTrue("Should be rejected", result is InboundResult.Rejected)
        assertTrue("Reason should mention nonce",
            (result as InboundResult.Rejected).reason.contains("nonce"))
    }

    @Test
    fun `gate2 wrong sig length is rejected`() {
        val service = createSuccessfulService()
        val shortSig = ByteArray(32) { it.toByte() } // Should be 64

        val item = createPendingMessage(
            messageId = "msg-short-sig",
            sig = Base64Strict.encode(shortSig)
        )

        val result = service.handleInbound(item)

        assertTrue("Should be rejected", result is InboundResult.Rejected)
        assertTrue("Reason should mention sig",
            (result as InboundResult.Rejected).reason.contains("sig"))
    }

    @Test
    fun `gate2 empty ciphertext is rejected`() {
        val service = createSuccessfulService()
        val item = createPendingMessage(
            messageId = "msg-empty-ct",
            ciphertext = Base64Strict.encode(ByteArray(0))
        )

        val result = service.handleInbound(item)

        assertTrue("Should be rejected", result is InboundResult.Rejected)
        assertTrue("Reason should mention ciphertext",
            (result as InboundResult.Rejected).reason.lowercase().contains("ciphertext"))
    }

    // ==========================================================================
    // Gate 2: Unknown sender
    // ==========================================================================

    @Test
    fun `gate2 unknown sender is rejected`() {
        val service = createSuccessfulService()
        val item = createPendingMessage(
            messageId = "msg-unknown-sender",
            from = "WSP-UNKNOWN"
        )

        val result = service.handleInbound(item)

        assertTrue("Should be rejected", result is InboundResult.Rejected)
        assertEquals("Unknown sender", (result as InboundResult.Rejected).reason)
    }

    // ==========================================================================
    // Gate 2: Duplicate detection
    // ==========================================================================

    @Test
    fun `gate2 duplicate message returns duplicate result`() {
        val service = createSuccessfulService()
        // Pre-insert a message
        messageDao.insert(MessageEntity(
            messageId = "msg-existing",
            conversationId = senderWhisperId,
            from = senderWhisperId,
            to = myWhisperId,
            msgType = MessageType.TEXT,
            timestamp = 1700000000000L,
            nonceB64 = validNonceB64,
            ciphertextB64 = "existing",
            sigB64 = validSigB64,
            text = "existing"
        ))

        val item = createPendingMessage(messageId = "msg-existing")

        val result = service.handleInbound(item)

        assertTrue("Should be duplicate", result is InboundResult.Duplicate)
        assertEquals("msg-existing", (result as InboundResult.Duplicate).messageId)
    }

    @Test
    fun `gate2 duplicate does not increment message count`() {
        val service = createSuccessfulService()
        // Pre-insert a message
        messageDao.insert(MessageEntity(
            messageId = "msg-dup",
            conversationId = senderWhisperId,
            from = senderWhisperId,
            to = myWhisperId,
            msgType = MessageType.TEXT,
            timestamp = 1700000000000L,
            nonceB64 = validNonceB64,
            ciphertextB64 = "existing",
            sigB64 = validSigB64
        ))

        val countBefore = messageDao.count()
        val item = createPendingMessage(messageId = "msg-dup")
        service.handleInbound(item)

        assertEquals("Count should not change", countBefore, messageDao.count())
    }

    @Test
    fun `gate2 duplicate does not send receipt`() {
        val service = createSuccessfulService()
        messageDao.insert(MessageEntity(
            messageId = "msg-dup-rcpt",
            conversationId = senderWhisperId,
            from = senderWhisperId,
            to = myWhisperId,
            msgType = MessageType.TEXT,
            timestamp = 1700000000000L,
            nonceB64 = validNonceB64,
            ciphertextB64 = "existing",
            sigB64 = validSigB64
        ))

        val item = createPendingMessage(messageId = "msg-dup-rcpt")
        service.handleInbound(item)

        assertEquals("No receipt should be sent for duplicate", 0, receiptsSent.size)
    }

    // ==========================================================================
    // Gate 2: Canonical string format
    // ==========================================================================

    @Test
    fun `gate2 canonical string format is correct`() {
        val canonical = CanonicalSigning.buildCanonicalString(
            version = "v1",
            messageType = "send_message",
            messageId = "msg-123",
            from = "WSP-SENDER",
            toOrGroupId = "WSP-RECIPIENT",
            timestamp = 1700000000000L,
            nonceB64 = "bm9uY2U=",
            ciphertextB64 = "Y2lwaGVydGV4dA=="
        )

        val expected = "v1\nsend_message\nmsg-123\nWSP-SENDER\nWSP-RECIPIENT\n1700000000000\nbm9uY2U=\nY2lwaGVydGV4dA==\n"
        assertEquals(expected, canonical)
    }

    @Test
    fun `gate2 canonical hash is SHA256 of UTF8 bytes`() {
        val canonical = "v1\nsend_message\nmsg-123\n"
        val expectedHash = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))

        val hash = CanonicalSigning.hashCanonical(canonical.toByteArray(Charsets.UTF_8))

        assertArrayEquals(expectedHash, hash)
    }

    // ==========================================================================
    // Gate 2: Successful persistence with merhaba
    // ==========================================================================

    @Test
    fun `gate2 merhaba message is persisted with correct text`() {
        val service = createSuccessfulService()
        val item = createPendingMessage(messageId = "msg-merhaba")

        val result = service.handleInbound(item)

        assertTrue("Should be success", result is InboundResult.Success)
        val message = (result as InboundResult.Success).message
        assertEquals("merhaba", message.text)
        assertEquals("msg-merhaba", message.messageId)
        assertEquals(senderWhisperId, message.from)
        assertEquals(myWhisperId, message.to)
    }

    @Test
    fun `gate2 message persisted to database`() {
        val service = createSuccessfulService()
        val item = createPendingMessage(messageId = "msg-persisted")

        service.handleInbound(item)

        assertEquals(1, messageDao.count())
        val saved = messageDao.getById("msg-persisted")
        assertNotNull(saved)
        assertEquals("merhaba", saved?.text)
    }

    @Test
    fun `gate2 receipt sent after successful processing`() {
        val service = createSuccessfulService()
        val item = createPendingMessage(messageId = "msg-receipt")

        service.handleInbound(item)

        assertEquals(1, receiptsSent.size)
        assertEquals("msg-receipt", receiptsSent[0].first)
        assertEquals(myWhisperId, receiptsSent[0].second)
        assertEquals(senderWhisperId, receiptsSent[0].third)
    }

    // ==========================================================================
    // Helper
    // ==========================================================================

    private fun createPendingMessage(
        messageId: String = "msg-test",
        from: String = senderWhisperId,
        to: String = myWhisperId,
        msgType: String = MessageType.TEXT,
        timestamp: Long = System.currentTimeMillis(),
        nonce: String = validNonceB64,
        ciphertext: String = validCiphertextB64,
        sig: String = validSigB64
    ): PendingMessageItem {
        return PendingMessageItem(
            messageId = messageId,
            from = from,
            to = to,
            msgType = msgType,
            timestamp = timestamp,
            nonce = nonce,
            ciphertext = ciphertext,
            sig = sig
        )
    }

    private fun createSuccessfulService(): MessagingService {
        return MessagingService(
            messageDao = TestMessageDao(messageDao),
            conversationDao = TestConversationDao(conversationDao),
            myEncPrivateKeyProvider = { myEncPrivateKey },
            peerKeyProvider = object : PeerKeyProvider {
                override fun getSignPublicKey(whisperId: String): ByteArray? {
                    return if (whisperId == senderWhisperId) senderSignPublicKey else null
                }
                override fun getEncPublicKey(whisperId: String): ByteArray? {
                    return if (whisperId == senderWhisperId) senderEncPublicKey else null
                }
            },
            receiptSender = { id, from, to -> receiptsSent.add(Triple(id, from, to)) },
            myWhisperIdProvider = { myWhisperId },
            signatureVerifier = { _, _, _ -> true },
            messageDecryptor = { _, _, _, _ -> "merhaba".toByteArray(Charsets.UTF_8) }
        )
    }
}

// ==========================================================================
// Test DAO implementations wrapping InMemory DAOs
// ==========================================================================

class TestMessageDao(private val inMemory: InMemoryMessageDao) :
    com.whisper2.app.storage.db.dao.MessageDao {

    override fun insert(message: MessageEntity): Long = inMemory.insert(message)
    override fun update(message: MessageEntity) { /* no-op */ }
    override fun getById(messageId: String) = inMemory.getById(messageId)
    override fun getByConversation(conversationId: String) = inMemory.getByConversation(conversationId)
    override fun getByConversationPaged(conversationId: String, limit: Int) =
        inMemory.getByConversation(conversationId).take(limit)
    override fun exists(messageId: String) = inMemory.exists(messageId)
    override fun count() = inMemory.count()
    override fun countByConversation(conversationId: String) =
        inMemory.getByConversation(conversationId).size
    override fun updateStatus(messageId: String, status: String) =
        inMemory.updateStatus(messageId, status)
    override fun delete(messageId: String) { /* no-op */ }
    override fun deleteAll() = inMemory.deleteAll()
}

class TestConversationDao(private val inMemory: InMemoryConversationDao) :
    com.whisper2.app.storage.db.dao.ConversationDao {

    override fun insert(conversation: ConversationEntity): Long = inMemory.insert(conversation)
    override fun update(conversation: ConversationEntity) = inMemory.update(conversation)
    override fun getById(id: String) = inMemory.getById(id)
    override fun getAll(): List<ConversationEntity> = emptyList()
    override fun exists(id: String) = inMemory.exists(id)
    override fun count() = inMemory.count()
    override fun upsertWithNewMessage(
        conversationId: String,
        type: String,
        timestamp: Long,
        preview: String?,
        incrementUnread: Boolean
    ) = inMemory.upsertWithNewMessage(conversationId, type, timestamp, preview, incrementUnread)
    override fun markAsRead(id: String) = inMemory.markAsRead(id)
    override fun setUnreadCount(id: String, count: Int) { /* no-op */ }
    override fun delete(id: String) { /* no-op */ }
    override fun deleteAll() = inMemory.deleteAll()
}
