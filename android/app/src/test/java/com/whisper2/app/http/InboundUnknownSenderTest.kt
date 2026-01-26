package com.whisper2.app.http

import com.whisper2.app.core.utils.Base64Strict
import com.whisper2.app.messaging.InMemoryConversationDao
import com.whisper2.app.messaging.InMemoryMessageDao
import com.whisper2.app.messaging.TestConversationDao
import com.whisper2.app.messaging.TestMessageDao
import com.whisper2.app.network.api.*
import com.whisper2.app.network.ws.PendingMessageItem
import com.whisper2.app.services.contacts.*
import com.whisper2.app.services.messaging.*
import com.whisper2.app.storage.db.entities.MessageType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Gate 3: Messaging Inbound "Unknown Sender" Flow
 *
 * Tests:
 * - pending_messages with unknown sender
 * - MessagingService → KeyLookupService → keys fetched → verify/decrypt/persist
 * - receipt sent
 * - Second message from same sender uses cache (no HTTP)
 */
class InboundUnknownSenderTest {

    private lateinit var mockApi: MockWhisperApi
    private lateinit var keyCache: InMemoryKeyCache
    private lateinit var keyLookupService: KeyLookupService
    private lateinit var messageDao: InMemoryMessageDao
    private lateinit var conversationDao: InMemoryConversationDao
    private lateinit var receiptsSent: MutableList<Triple<String, String, String>>
    private lateinit var messagingService: MessagingService

    // My identity
    private val myWhisperId = "WSP-MY-ID-HERE"
    private val myEncPrivateKey = ByteArray(32) { (it + 1).toByte() }

    // Sender identity (initially unknown)
    private val senderWhisperId = "WSP-NEW-SENDER"
    private val senderEncPublicKey = ByteArray(32) { (it + 10).toByte() }
    private val senderSignPublicKey = ByteArray(32) { (it + 20).toByte() }

    // Valid message components
    private val validNonce = ByteArray(24) { it.toByte() }
    private val validNonceB64 = Base64Strict.encode(validNonce)
    private val validSig = ByteArray(64) { it.toByte() }
    private val validSigB64 = Base64Strict.encode(validSig)
    private val validCiphertext = ByteArray(100) { it.toByte() }
    private val validCiphertextB64 = Base64Strict.encode(validCiphertext)

    @Before
    fun setup() {
        mockApi = MockWhisperApi(sessionTokenProvider = { "test_token" })
        keyCache = InMemoryKeyCache()
        keyLookupService = KeyLookupService(mockApi, keyCache)
        messageDao = InMemoryMessageDao()
        conversationDao = InMemoryConversationDao()
        receiptsSent = mutableListOf()

        // Create PeerKeyProvider that delegates to KeyLookupService
        val peerKeyProvider = object : PeerKeyProvider {
            override fun getSignPublicKey(whisperId: String): ByteArray? {
                // Use runBlocking since this is sync interface
                return runBlocking {
                    keyLookupService.getKeys(whisperId).getOrNull()?.signPublicKey
                }
            }

            override fun getEncPublicKey(whisperId: String): ByteArray? {
                return runBlocking {
                    keyLookupService.getKeys(whisperId).getOrNull()?.encPublicKey
                }
            }
        }

        messagingService = MessagingService(
            messageDao = TestMessageDao(messageDao),
            conversationDao = TestConversationDao(conversationDao),
            myEncPrivateKeyProvider = { myEncPrivateKey },
            peerKeyProvider = peerKeyProvider,
            receiptSender = { id, from, to -> receiptsSent.add(Triple(id, from, to)) },
            myWhisperIdProvider = { myWhisperId },
            signatureVerifier = { _, _, _ -> true }, // Always pass for this test
            messageDecryptor = { _, _, _, _ -> "merhaba".toByteArray(Charsets.UTF_8) }
        )
    }

    // ==========================================================================
    // Gate 3: Unknown sender triggers key lookup
    // ==========================================================================

    @Test
    fun `gate3 unknown sender triggers key lookup`() {
        // Enqueue key response for new sender
        mockApi.enqueueUserKeysResponse(
            ApiResult.Success(UserKeysResponse(
                whisperId = senderWhisperId,
                encPublicKey = Base64Strict.encode(senderEncPublicKey),
                signPublicKey = Base64Strict.encode(senderSignPublicKey),
                status = "active"
            ))
        )

        val item = createMessage("msg-1", senderWhisperId)
        val result = messagingService.handleInbound(item)

        // Verify key lookup was called
        assertEquals("Key lookup should be called for unknown sender", 1, mockApi.getUserKeysCallCount)
        assertEquals(senderWhisperId, mockApi.lastGetUserKeysWhisperId)

        // Verify message was processed successfully
        assertTrue("Message should be processed", result is InboundResult.Success)
    }

    @Test
    fun `gate3 unknown sender keys cached after lookup`() {
        mockApi.enqueueUserKeysResponse(
            ApiResult.Success(UserKeysResponse(
                whisperId = senderWhisperId,
                encPublicKey = Base64Strict.encode(senderEncPublicKey),
                signPublicKey = Base64Strict.encode(senderSignPublicKey),
                status = "active"
            ))
        )

        val item = createMessage("msg-1", senderWhisperId)
        messagingService.handleInbound(item)

        // Verify keys are cached
        assertNotNull(keyCache.get(senderWhisperId))
    }

    @Test
    fun `gate3 message persisted after key lookup`() {
        mockApi.enqueueUserKeysResponse(
            ApiResult.Success(UserKeysResponse(
                whisperId = senderWhisperId,
                encPublicKey = Base64Strict.encode(senderEncPublicKey),
                signPublicKey = Base64Strict.encode(senderSignPublicKey),
                status = "active"
            ))
        )

        val item = createMessage("msg-1", senderWhisperId)
        messagingService.handleInbound(item)

        // Verify message is persisted
        assertEquals(1, messageDao.count())
        assertNotNull(messageDao.getById("msg-1"))
    }

    @Test
    fun `gate3 receipt sent after successful processing`() {
        mockApi.enqueueUserKeysResponse(
            ApiResult.Success(UserKeysResponse(
                whisperId = senderWhisperId,
                encPublicKey = Base64Strict.encode(senderEncPublicKey),
                signPublicKey = Base64Strict.encode(senderSignPublicKey),
                status = "active"
            ))
        )

        val item = createMessage("msg-1", senderWhisperId)
        messagingService.handleInbound(item)

        // Verify receipt sent
        assertEquals("Receipt should be sent", 1, receiptsSent.size)
        assertEquals("msg-1", receiptsSent[0].first)
        assertEquals(myWhisperId, receiptsSent[0].second)
        assertEquals(senderWhisperId, receiptsSent[0].third)
    }

    // ==========================================================================
    // Gate 3: Second message uses cache
    // ==========================================================================

    @Test
    fun `gate3 second message from same sender uses cache`() {
        // Enqueue only ONE key response
        mockApi.enqueueUserKeysResponse(
            ApiResult.Success(UserKeysResponse(
                whisperId = senderWhisperId,
                encPublicKey = Base64Strict.encode(senderEncPublicKey),
                signPublicKey = Base64Strict.encode(senderSignPublicKey),
                status = "active"
            ))
        )

        // First message - triggers key lookup
        messagingService.handleInbound(createMessage("msg-1", senderWhisperId))

        // Second message - should use cache
        messagingService.handleInbound(createMessage("msg-2", senderWhisperId))

        // Verify only 1 HTTP call
        assertEquals("Only 1 HTTP call for 2 messages", 1, mockApi.getUserKeysCallCount)

        // Verify both messages persisted
        assertEquals(2, messageDao.count())
    }

    @Test
    fun `gate3 multiple messages same sender cache verification`() {
        mockApi.enqueueUserKeysResponse(
            ApiResult.Success(UserKeysResponse(
                whisperId = senderWhisperId,
                encPublicKey = Base64Strict.encode(senderEncPublicKey),
                signPublicKey = Base64Strict.encode(senderSignPublicKey),
                status = "active"
            ))
        )

        // Send 5 messages
        repeat(5) { i ->
            messagingService.handleInbound(createMessage("msg-$i", senderWhisperId))
        }

        // Only 1 HTTP call for all 5 messages
        assertEquals(1, mockApi.getUserKeysCallCount)
        assertEquals(5, messageDao.count())
        assertEquals(5, receiptsSent.size)
    }

    // ==========================================================================
    // Gate 3: Different senders make separate HTTP calls
    // ==========================================================================

    @Test
    fun `gate3 different senders make separate HTTP calls`() {
        val sender2 = "WSP-SENDER-TWO"

        mockApi.enqueueUserKeysResponse(
            ApiResult.Success(UserKeysResponse(
                whisperId = senderWhisperId,
                encPublicKey = Base64Strict.encode(senderEncPublicKey),
                signPublicKey = Base64Strict.encode(senderSignPublicKey),
                status = "active"
            ))
        )
        mockApi.enqueueUserKeysResponse(
            ApiResult.Success(UserKeysResponse(
                whisperId = sender2,
                encPublicKey = Base64Strict.encode(ByteArray(32)),
                signPublicKey = Base64Strict.encode(ByteArray(32)),
                status = "active"
            ))
        )

        messagingService.handleInbound(createMessage("msg-1", senderWhisperId))
        messagingService.handleInbound(createMessage("msg-2", sender2))

        // 2 HTTP calls for 2 different senders
        assertEquals(2, mockApi.getUserKeysCallCount)
        assertEquals(2, messageDao.count())
    }

    // ==========================================================================
    // Gate 3: Key lookup failure
    // ==========================================================================

    @Test
    fun `gate3 key lookup failure rejects message`() {
        mockApi.enqueueUserKeysResponse(
            ApiResult.Error(ApiErrorResponse.NOT_FOUND, "User not found", 404)
        )

        val item = createMessage("msg-1", senderWhisperId)
        val result = messagingService.handleInbound(item)

        // Message should be rejected
        assertTrue("Message should be rejected", result is InboundResult.Rejected)

        // No message persisted
        assertEquals(0, messageDao.count())

        // No receipt sent
        assertEquals(0, receiptsSent.size)
    }

    @Test
    fun `gate3 banned sender rejects message`() {
        mockApi.enqueueUserKeysResponse(
            ApiResult.Success(UserKeysResponse(
                whisperId = senderWhisperId,
                encPublicKey = Base64Strict.encode(senderEncPublicKey),
                signPublicKey = Base64Strict.encode(senderSignPublicKey),
                status = "banned"
            ))
        )

        val item = createMessage("msg-1", senderWhisperId)
        val result = messagingService.handleInbound(item)

        assertTrue("Banned sender should be rejected", result is InboundResult.Rejected)
        assertEquals(0, messageDao.count())
    }

    // ==========================================================================
    // Helper
    // ==========================================================================

    private fun createMessage(
        messageId: String,
        from: String,
        timestamp: Long = System.currentTimeMillis()
    ): PendingMessageItem {
        return PendingMessageItem(
            messageId = messageId,
            from = from,
            to = myWhisperId,
            msgType = MessageType.TEXT,
            timestamp = timestamp,
            nonce = validNonceB64,
            ciphertext = validCiphertextB64,
            sig = validSigB64
        )
    }
}
