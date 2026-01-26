package com.whisper2.app.messaging

import com.whisper2.app.core.utils.Base64Strict
import com.whisper2.app.network.ws.PendingMessageItem
import com.whisper2.app.services.messaging.*
import com.whisper2.app.storage.db.entities.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Gate 3: Signature fail / decrypt fail behavior
 *
 * Tests that:
 * - Signature verification failure rejects message
 * - Decryption failure rejects message
 * - No receipt is sent on failure
 * - No persistence on failure
 */
class SignatureDecryptFailTest {

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

    // Valid base64 values
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
    // Gate 3: Signature verification failure
    // ==========================================================================

    @Test
    fun `gate3 signature verification failure rejects message`() {
        val service = createService(signatureValid = false, decryptSuccess = true)

        val item = createValidPendingMessage("msg-bad-sig")
        val result = service.handleInbound(item)

        assertTrue("Should be rejected", result is InboundResult.Rejected)
        assertEquals("Signature verification failed", (result as InboundResult.Rejected).reason)
    }

    @Test
    fun `gate3 signature fail does not persist message`() {
        val service = createService(signatureValid = false, decryptSuccess = true)

        val item = createValidPendingMessage("msg-no-persist")
        service.handleInbound(item)

        assertEquals("No message should be persisted", 0, messageDao.count())
        assertNull("Message should not exist", messageDao.getById("msg-no-persist"))
    }

    @Test
    fun `gate3 signature fail does not send receipt`() {
        val service = createService(signatureValid = false, decryptSuccess = true)

        val item = createValidPendingMessage("msg-no-receipt")
        service.handleInbound(item)

        assertEquals("No receipt should be sent on sig failure", 0, receiptsSent.size)
    }

    @Test
    fun `gate3 signature fail does not update conversation`() {
        val service = createService(signatureValid = false, decryptSuccess = true)

        val item = createValidPendingMessage("msg-no-conv")
        service.handleInbound(item)

        assertEquals("No conversation should be created", 0, conversationDao.count())
        assertNull(conversationDao.getById(senderWhisperId))
    }

    // ==========================================================================
    // Gate 3: Decryption failure
    // ==========================================================================

    @Test
    fun `gate3 decryption failure rejects message`() {
        val service = createService(signatureValid = true, decryptSuccess = false)

        val item = createValidPendingMessage("msg-bad-decrypt")
        val result = service.handleInbound(item)

        assertTrue("Should be rejected", result is InboundResult.Rejected)
        assertEquals("Decryption failed", (result as InboundResult.Rejected).reason)
    }

    @Test
    fun `gate3 decrypt fail does not persist message`() {
        val service = createService(signatureValid = true, decryptSuccess = false)

        val item = createValidPendingMessage("msg-decrypt-no-persist")
        service.handleInbound(item)

        assertEquals("No message should be persisted", 0, messageDao.count())
    }

    @Test
    fun `gate3 decrypt fail does not send receipt`() {
        val service = createService(signatureValid = true, decryptSuccess = false)

        val item = createValidPendingMessage("msg-decrypt-no-receipt")
        service.handleInbound(item)

        assertEquals("No receipt should be sent on decrypt failure", 0, receiptsSent.size)
    }

    @Test
    fun `gate3 decrypt fail does not update conversation`() {
        val service = createService(signatureValid = true, decryptSuccess = false)

        val item = createValidPendingMessage("msg-decrypt-no-conv")
        service.handleInbound(item)

        assertEquals("No conversation should be created", 0, conversationDao.count())
    }

    // ==========================================================================
    // Gate 3: Missing encryption key
    // ==========================================================================

    @Test
    fun `gate3 missing my encryption key rejects message`() {
        val service = MessagingService(
            messageDao = TestMessageDao(messageDao),
            conversationDao = TestConversationDao(conversationDao),
            myEncPrivateKeyProvider = { null }, // Missing key
            peerKeyProvider = createPeerKeyProvider(),
            receiptSender = { id, from, to -> receiptsSent.add(Triple(id, from, to)) },
            myWhisperIdProvider = { myWhisperId },
            signatureVerifier = { _, _, _ -> true },
            messageDecryptor = { _, _, _, _ -> "merhaba".toByteArray() }
        )

        val item = createValidPendingMessage("msg-no-key")
        val result = service.handleInbound(item)

        assertTrue("Should be rejected", result is InboundResult.Rejected)
        assertTrue((result as InboundResult.Rejected).reason.contains("private key"))
    }

    @Test
    fun `gate3 missing sender encryption key rejects message`() {
        val service = MessagingService(
            messageDao = TestMessageDao(messageDao),
            conversationDao = TestConversationDao(conversationDao),
            myEncPrivateKeyProvider = { myEncPrivateKey },
            peerKeyProvider = object : PeerKeyProvider {
                override fun getSignPublicKey(whisperId: String) = senderSignPublicKey
                override fun getEncPublicKey(whisperId: String) = null // Missing
            },
            receiptSender = { id, from, to -> receiptsSent.add(Triple(id, from, to)) },
            myWhisperIdProvider = { myWhisperId },
            signatureVerifier = { _, _, _ -> true },
            messageDecryptor = { _, _, _, _ -> "merhaba".toByteArray() }
        )

        val item = createValidPendingMessage("msg-no-sender-key")
        val result = service.handleInbound(item)

        assertTrue("Should be rejected", result is InboundResult.Rejected)
        assertTrue((result as InboundResult.Rejected).reason.contains("encryption key"))
    }

    // ==========================================================================
    // Gate 3: Successful path (control test)
    // ==========================================================================

    @Test
    fun `gate3 valid message is accepted and persisted`() {
        val service = createService(signatureValid = true, decryptSuccess = true)

        val item = createValidPendingMessage("msg-success")
        val result = service.handleInbound(item)

        assertTrue("Should be success", result is InboundResult.Success)
        assertEquals(1, messageDao.count())
        assertNotNull(messageDao.getById("msg-success"))
    }

    @Test
    fun `gate3 valid message sends receipt`() {
        val service = createService(signatureValid = true, decryptSuccess = true)

        val item = createValidPendingMessage("msg-receipt-sent")
        service.handleInbound(item)

        assertEquals("Receipt should be sent", 1, receiptsSent.size)
        assertEquals("msg-receipt-sent", receiptsSent[0].first)
        assertEquals(myWhisperId, receiptsSent[0].second) // from = recipient
        assertEquals(senderWhisperId, receiptsSent[0].third) // to = sender
    }

    @Test
    fun `gate3 valid message creates conversation`() {
        val service = createService(signatureValid = true, decryptSuccess = true)

        val item = createValidPendingMessage("msg-conv-created")
        service.handleInbound(item)

        assertEquals(1, conversationDao.count())
        val conv = conversationDao.getById(senderWhisperId)
        assertNotNull(conv)
        assertEquals(ConversationType.DIRECT, conv?.type)
    }

    @Test
    fun `gate3 valid text message extracts plaintext merhaba`() {
        val service = createService(signatureValid = true, decryptSuccess = true)

        val item = createValidPendingMessage(
            messageId = "msg-text",
            msgType = MessageType.TEXT
        )
        val result = service.handleInbound(item)

        assertTrue(result is InboundResult.Success)
        val message = (result as InboundResult.Success).message
        assertEquals("merhaba", message.text) // Our mock decrypts to "merhaba"
    }

    // ==========================================================================
    // Helpers
    // ==========================================================================

    private fun createValidPendingMessage(
        messageId: String,
        msgType: String = MessageType.TEXT
    ): PendingMessageItem {
        return PendingMessageItem(
            messageId = messageId,
            from = senderWhisperId,
            to = myWhisperId,
            msgType = msgType,
            timestamp = System.currentTimeMillis(),
            nonce = validNonceB64,
            ciphertext = validCiphertextB64,
            sig = validSigB64
        )
    }

    private fun createPeerKeyProvider(): PeerKeyProvider {
        return object : PeerKeyProvider {
            override fun getSignPublicKey(whisperId: String): ByteArray? {
                return if (whisperId == senderWhisperId) senderSignPublicKey else null
            }
            override fun getEncPublicKey(whisperId: String): ByteArray? {
                return if (whisperId == senderWhisperId) senderEncPublicKey else null
            }
        }
    }

    private fun createService(signatureValid: Boolean, decryptSuccess: Boolean): MessagingService {
        return MessagingService(
            messageDao = TestMessageDao(messageDao),
            conversationDao = TestConversationDao(conversationDao),
            myEncPrivateKeyProvider = { myEncPrivateKey },
            peerKeyProvider = createPeerKeyProvider(),
            receiptSender = { id, from, to -> receiptsSent.add(Triple(id, from, to)) },
            myWhisperIdProvider = { myWhisperId },
            signatureVerifier = { _, _, _ -> signatureValid },
            messageDecryptor = { _, _, _, _ ->
                if (decryptSuccess) {
                    "merhaba".toByteArray(Charsets.UTF_8)
                } else {
                    throw RuntimeException("Decryption failed")
                }
            }
        )
    }
}
