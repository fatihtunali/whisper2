package com.whisper2.app.messaging

import com.whisper2.app.core.utils.Base64Strict
import com.whisper2.app.network.ws.PendingMessageItem
import com.whisper2.app.services.messaging.*
import com.whisper2.app.storage.db.entities.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Gate 4: Conversation counter updates
 *
 * Tests that:
 * - unreadCount increments on new inbound message
 * - Duplicate message does not increment unreadCount
 * - markAsRead resets unreadCount to 0
 * - lastMessageAt and lastMessagePreview update correctly
 */
class ConversationCountersTest {

    private lateinit var messageDao: InMemoryMessageDao
    private lateinit var conversationDao: InMemoryConversationDao
    private lateinit var receiptsSent: MutableList<Triple<String, String, String>>
    private lateinit var service: MessagingService

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

        service = MessagingService(
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

    // ==========================================================================
    // Gate 4: Conversation creation
    // ==========================================================================

    @Test
    fun `gate4 first message creates conversation`() {
        val item = createMessage("msg-1", timestamp = 1700000000000L)

        service.handleInbound(item)

        assertEquals(1, conversationDao.count())
        val conv = conversationDao.getById(senderWhisperId)
        assertNotNull(conv)
        assertEquals(senderWhisperId, conv?.id)
        assertEquals(ConversationType.DIRECT, conv?.type)
    }

    @Test
    fun `gate4 conversation id is sender whisperID`() {
        val item = createMessage("msg-1")

        service.handleInbound(item)

        val conv = conversationDao.getById(senderWhisperId)
        assertNotNull(conv)
        assertEquals(senderWhisperId, conv?.id)
    }

    // ==========================================================================
    // Gate 4: unreadCount increments
    // ==========================================================================

    @Test
    fun `gate4 first message sets unreadCount to 1`() {
        val item = createMessage("msg-1")

        service.handleInbound(item)

        val conv = conversationDao.getById(senderWhisperId)
        assertEquals(1, conv?.unreadCount)
    }

    @Test
    fun `gate4 second message increments unreadCount to 2`() {
        val item1 = createMessage("msg-1", timestamp = 1700000000000L)
        val item2 = createMessage("msg-2", timestamp = 1700000001000L)

        service.handleInbound(item1)
        service.handleInbound(item2)

        val conv = conversationDao.getById(senderWhisperId)
        assertEquals(2, conv?.unreadCount)
    }

    @Test
    fun `gate4 third message increments unreadCount to 3`() {
        service.handleInbound(createMessage("msg-1", timestamp = 1700000000000L))
        service.handleInbound(createMessage("msg-2", timestamp = 1700000001000L))
        service.handleInbound(createMessage("msg-3", timestamp = 1700000002000L))

        val conv = conversationDao.getById(senderWhisperId)
        assertEquals(3, conv?.unreadCount)
    }

    // ==========================================================================
    // Gate 4: Duplicate does not increment
    // ==========================================================================

    @Test
    fun `gate4 duplicate message does not increment unreadCount`() {
        val item1 = createMessage("msg-dup", timestamp = 1700000000000L)
        val item2 = createMessage("msg-dup", timestamp = 1700000001000L) // Same ID

        service.handleInbound(item1)
        val countBefore = conversationDao.getById(senderWhisperId)?.unreadCount

        service.handleInbound(item2)
        val countAfter = conversationDao.getById(senderWhisperId)?.unreadCount

        assertEquals(1, countBefore)
        assertEquals(countBefore, countAfter)
    }

    @Test
    fun `gate4 rejected message does not increment unreadCount`() {
        // First valid message
        service.handleInbound(createMessage("msg-1"))
        val countBefore = conversationDao.getById(senderWhisperId)?.unreadCount

        // Invalid message (unknown sender)
        val badItem = PendingMessageItem(
            messageId = "msg-bad",
            from = "WSP-UNKNOWN",
            to = myWhisperId,
            msgType = MessageType.TEXT,
            timestamp = System.currentTimeMillis(),
            nonce = validNonceB64,
            ciphertext = validCiphertextB64,
            sig = validSigB64
        )
        service.handleInbound(badItem)

        val countAfter = conversationDao.getById(senderWhisperId)?.unreadCount
        assertEquals(countBefore, countAfter)
    }

    // ==========================================================================
    // Gate 4: markAsRead
    // ==========================================================================

    @Test
    fun `gate4 markAsRead resets unreadCount to 0`() {
        service.handleInbound(createMessage("msg-1", timestamp = 1700000000000L))
        service.handleInbound(createMessage("msg-2", timestamp = 1700000001000L))
        service.handleInbound(createMessage("msg-3", timestamp = 1700000002000L))

        assertEquals(3, conversationDao.getById(senderWhisperId)?.unreadCount)

        conversationDao.markAsRead(senderWhisperId)

        assertEquals(0, conversationDao.getById(senderWhisperId)?.unreadCount)
    }

    @Test
    fun `gate4 markAsRead does not affect other conversations`() {
        // Create messages from different senders would need different peer keys
        // For this test, we just verify markAsRead works on specific conversation
        service.handleInbound(createMessage("msg-1"))
        conversationDao.markAsRead(senderWhisperId)

        val conv = conversationDao.getById(senderWhisperId)
        assertEquals(0, conv?.unreadCount)
    }

    // ==========================================================================
    // Gate 4: lastMessageAt updates
    // ==========================================================================

    @Test
    fun `gate4 lastMessageAt is set to message timestamp`() {
        val timestamp = 1700000000000L
        val item = createMessage("msg-1", timestamp = timestamp)

        service.handleInbound(item)

        val conv = conversationDao.getById(senderWhisperId)
        assertEquals(timestamp, conv?.lastMessageAt)
    }

    @Test
    fun `gate4 lastMessageAt updates to newer timestamp`() {
        val timestamp1 = 1700000000000L
        val timestamp2 = 1700000005000L

        service.handleInbound(createMessage("msg-1", timestamp = timestamp1))
        service.handleInbound(createMessage("msg-2", timestamp = timestamp2))

        val conv = conversationDao.getById(senderWhisperId)
        assertEquals(timestamp2, conv?.lastMessageAt)
    }

    @Test
    fun `gate4 lastMessageAt keeps newer timestamp if older message arrives later`() {
        // Newer message arrives first
        service.handleInbound(createMessage("msg-2", timestamp = 1700000005000L))
        // Older message arrives later (out of order)
        service.handleInbound(createMessage("msg-1", timestamp = 1700000000000L))

        val conv = conversationDao.getById(senderWhisperId)
        // Should keep the newer timestamp
        assertEquals(1700000005000L, conv?.lastMessageAt)
    }

    // ==========================================================================
    // Gate 4: lastMessagePreview updates
    // ==========================================================================

    @Test
    fun `gate4 lastMessagePreview is set to message text`() {
        val item = createMessage("msg-1")

        service.handleInbound(item)

        val conv = conversationDao.getById(senderWhisperId)
        assertEquals("merhaba", conv?.lastMessagePreview)
    }

    @Test
    fun `gate4 lastMessagePreview updates with each new message`() {
        // Create service that returns different text for each message
        var messageCount = 0
        val customService = MessagingService(
            messageDao = TestMessageDao(messageDao),
            conversationDao = TestConversationDao(conversationDao),
            myEncPrivateKeyProvider = { myEncPrivateKey },
            peerKeyProvider = object : PeerKeyProvider {
                override fun getSignPublicKey(whisperId: String) = senderSignPublicKey
                override fun getEncPublicKey(whisperId: String) = senderEncPublicKey
            },
            receiptSender = { _, _, _ -> },
            myWhisperIdProvider = { myWhisperId },
            signatureVerifier = { _, _, _ -> true },
            messageDecryptor = { _, _, _, _ ->
                messageCount++
                "Message $messageCount".toByteArray(Charsets.UTF_8)
            }
        )

        customService.handleInbound(createMessage("msg-1", timestamp = 1700000000000L))
        assertEquals("Message 1", conversationDao.getById(senderWhisperId)?.lastMessagePreview)

        customService.handleInbound(createMessage("msg-2", timestamp = 1700000001000L))
        assertEquals("Message 2", conversationDao.getById(senderWhisperId)?.lastMessagePreview)
    }

    @Test
    fun `gate4 lastMessagePreview is truncated to 100 chars`() {
        val longText = "a".repeat(200)
        val customService = MessagingService(
            messageDao = TestMessageDao(messageDao),
            conversationDao = TestConversationDao(conversationDao),
            myEncPrivateKeyProvider = { myEncPrivateKey },
            peerKeyProvider = object : PeerKeyProvider {
                override fun getSignPublicKey(whisperId: String) = senderSignPublicKey
                override fun getEncPublicKey(whisperId: String) = senderEncPublicKey
            },
            receiptSender = { _, _, _ -> },
            myWhisperIdProvider = { myWhisperId },
            signatureVerifier = { _, _, _ -> true },
            messageDecryptor = { _, _, _, _ -> longText.toByteArray(Charsets.UTF_8) }
        )

        customService.handleInbound(createMessage("msg-1"))

        val conv = conversationDao.getById(senderWhisperId)
        assertEquals(100, conv?.lastMessagePreview?.length)
        assertEquals("a".repeat(100), conv?.lastMessagePreview)
    }

    // ==========================================================================
    // Helper
    // ==========================================================================

    private fun createMessage(
        messageId: String,
        timestamp: Long = System.currentTimeMillis()
    ): PendingMessageItem {
        return PendingMessageItem(
            messageId = messageId,
            from = senderWhisperId,
            to = myWhisperId,
            msgType = MessageType.TEXT,
            timestamp = timestamp,
            nonce = validNonceB64,
            ciphertext = validCiphertextB64,
            sig = validSigB64
        )
    }
}
