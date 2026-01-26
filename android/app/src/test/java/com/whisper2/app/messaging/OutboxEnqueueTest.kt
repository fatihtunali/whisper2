package com.whisper2.app.messaging

import com.whisper2.app.core.utils.Base64Strict
import com.whisper2.app.services.messaging.*
import com.whisper2.app.storage.db.entities.MessageType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Gate 5: Outbound encrypt + sign → Outbox queued
 *
 * Tests that:
 * - Message is encrypted with recipient's public key
 * - Message is signed with sender's private key
 * - Payload contains correct nonce (24 bytes), ciphertext, sig (64 bytes)
 * - Message is queued and sent immediately
 * - Queue management (ACK removes, failed stays)
 */
class OutboxEnqueueTest {

    private lateinit var outbox: OutboxQueue
    private lateinit var sentPayloads: MutableList<String>

    // My identity
    private val myWhisperId = "WSP-SENDER"
    private val mySessionToken = "sess_test_token"
    private val mySignPrivateKey = ByteArray(64) { (it + 1).toByte() } // 64 bytes for sign private
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
                true // Assume success
            },
            messageEncryptor = { _, _, _ -> Pair(mockNonce, mockCiphertext) },
            messageSigner = { _, _ -> mockSignature }
        )
    }

    // ==========================================================================
    // Gate 5: Basic enqueue
    // ==========================================================================

    @Test
    fun `gate5 enqueue text message returns message ID`() {
        val messageId = outbox.enqueueTextMessage("merhaba", recipientId)

        assertNotNull("Should return message ID", messageId)
        assertTrue("Should be valid UUID format", messageId!!.matches(Regex("[a-f0-9-]{36}")))
    }

    @Test
    fun `gate5 enqueue increments enqueued count`() {
        assertEquals(0, outbox.enqueuedCount)

        outbox.enqueueTextMessage("test", recipientId)

        assertEquals(1, outbox.enqueuedCount)
    }

    @Test
    fun `gate5 enqueue attempts immediate send`() {
        outbox.enqueueTextMessage("test", recipientId)

        assertEquals(1, outbox.sendAttempts)
        assertEquals(1, sentPayloads.size)
    }

    // ==========================================================================
    // Gate 5: Payload structure
    // ==========================================================================

    @Test
    fun `gate5 payload contains correct nonce 24 bytes base64`() {
        outbox.enqueueTextMessage("test", recipientId)

        val payload = outbox.lastPayload!!
        val expectedNonceB64 = Base64Strict.encode(mockNonce)

        assertTrue("Payload should contain nonce", payload.contains("\"nonce\":\"$expectedNonceB64\""))
    }

    @Test
    fun `gate5 payload contains ciphertext base64`() {
        outbox.enqueueTextMessage("test", recipientId)

        val payload = outbox.lastPayload!!
        // Verify ciphertext field exists in nested payload
        assertTrue("Payload should contain ciphertext key", payload.contains("\"ciphertext\":"))

        // Use Gson to parse and verify
        val json = com.google.gson.JsonParser.parseString(payload).asJsonObject
        val innerPayload = json.getAsJsonObject("payload")
        assertNotNull("Inner payload should exist", innerPayload)

        val ctB64 = innerPayload.get("ciphertext").asString
        assertFalse("Ciphertext should not be empty", ctB64.isEmpty())
        assertTrue("Ciphertext should be valid base64", Base64Strict.isValid(ctB64))
    }

    @Test
    fun `gate5 payload contains correct sig 64 bytes base64`() {
        outbox.enqueueTextMessage("test", recipientId)

        val payload = outbox.lastPayload!!
        // Verify sig field exists in nested payload
        assertTrue("Payload should contain sig key", payload.contains("\"sig\":"))

        // Use Gson to parse and verify
        val json = com.google.gson.JsonParser.parseString(payload).asJsonObject
        val innerPayload = json.getAsJsonObject("payload")
        assertNotNull("Inner payload should exist", innerPayload)

        val sigB64 = innerPayload.get("sig").asString
        assertFalse("Sig should not be empty", sigB64.isEmpty())
        assertTrue("Sig should be valid base64", Base64Strict.isValid(sigB64))

        // Verify signature is 64 bytes
        val sigBytes = Base64Strict.decode(sigB64)
        assertEquals("Signature should be 64 bytes", 64, sigBytes.size)
    }

    @Test
    fun `gate5 payload contains send_message type`() {
        outbox.enqueueTextMessage("test", recipientId)

        val payload = outbox.lastPayload!!
        assertTrue("Payload should have send_message type", payload.contains("\"type\":\"send_message\""))
    }

    @Test
    fun `gate5 payload contains from and to`() {
        outbox.enqueueTextMessage("test", recipientId)

        val payload = outbox.lastPayload!!
        assertTrue("Payload should contain from", payload.contains("\"from\":\"$myWhisperId\""))
        assertTrue("Payload should contain to", payload.contains("\"to\":\"$recipientId\""))
    }

    @Test
    fun `gate5 payload contains msgType text`() {
        outbox.enqueueTextMessage("test", recipientId)

        val payload = outbox.lastPayload!!
        assertTrue("Payload should contain msgType", payload.contains("\"msgType\":\"text\""))
    }

    @Test
    fun `gate5 payload contains sessionToken`() {
        outbox.enqueueTextMessage("test", recipientId)

        val payload = outbox.lastPayload!!
        assertTrue("Payload should contain sessionToken", payload.contains("\"sessionToken\":\"$mySessionToken\""))
    }

    @Test
    fun `gate5 payload contains timestamp`() {
        outbox.enqueueTextMessage("test", recipientId)

        val payload = outbox.lastPayload!!
        assertTrue("Payload should contain timestamp", payload.contains("\"timestamp\":"))
    }

    // ==========================================================================
    // Gate 5: Missing credentials
    // ==========================================================================

    @Test
    fun `gate5 missing myWhisperId returns null`() {
        val outboxNoId = createOutbox(myWhisperId = null)

        val messageId = outboxNoId.enqueueTextMessage("test", recipientId)

        assertNull("Should return null when myWhisperId missing", messageId)
    }

    @Test
    fun `gate5 missing sessionToken returns null`() {
        val outboxNoToken = createOutbox(sessionToken = null)

        val messageId = outboxNoToken.enqueueTextMessage("test", recipientId)

        assertNull("Should return null when sessionToken missing", messageId)
    }

    @Test
    fun `gate5 missing signPrivateKey returns null`() {
        val outboxNoKey = createOutbox(signPrivateKey = null)

        val messageId = outboxNoKey.enqueueTextMessage("test", recipientId)

        assertNull("Should return null when sign key missing", messageId)
    }

    @Test
    fun `gate5 missing encPrivateKey returns null`() {
        val outboxNoKey = createOutbox(encPrivateKey = null)

        val messageId = outboxNoKey.enqueueTextMessage("test", recipientId)

        assertNull("Should return null when enc key missing", messageId)
    }

    @Test
    fun `gate5 unknown recipient returns null`() {
        val messageId = outbox.enqueueTextMessage("test", "WSP-UNKNOWN")

        assertNull("Should return null for unknown recipient", messageId)
    }

    // ==========================================================================
    // Gate 5: Queue management
    // ==========================================================================

    @Test
    fun `gate5 successful send keeps in queue until ACK`() {
        val messageId = outbox.enqueueTextMessage("test", recipientId)!!

        // With success=true in wsSender, item stays in sending state until ACK
        assertEquals("Item should stay in queue until ACK", 1, outbox.size())

        // ACK removes from queue
        outbox.onMessageAccepted(messageId)
        assertEquals("ACK should remove from queue", 0, outbox.size())
    }

    @Test
    fun `gate5 failed send keeps in queue`() {
        // Create outbox with failing sender
        val failingOutbox = OutboxQueue(
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
            messageSigner = { _, _ -> mockSignature }
        )

        failingOutbox.enqueueTextMessage("test", recipientId)

        assertEquals("Failed message should stay in queue", 1, failingOutbox.size())
    }

    @Test
    fun `gate5 onMessageAccepted removes from queue`() {
        // Create outbox that keeps items (no auto-remove)
        val holdingOutbox = OutboxQueue(
            myWhisperIdProvider = { myWhisperId },
            sessionTokenProvider = { mySessionToken },
            mySignPrivateKeyProvider = { mySignPrivateKey },
            myEncPrivateKeyProvider = { myEncPrivateKey },
            peerKeyProvider = object : PeerKeyProvider {
                override fun getSignPublicKey(whisperId: String) = null
                override fun getEncPublicKey(whisperId: String) = recipientEncPublicKey
            },
            wsSender = { false }, // Fails to keep in queue
            messageEncryptor = { _, _, _ -> Pair(mockNonce, mockCiphertext) },
            messageSigner = { _, _ -> mockSignature }
        )

        val messageId = holdingOutbox.enqueueTextMessage("test", recipientId)!!
        assertEquals(1, holdingOutbox.size())

        holdingOutbox.onMessageAccepted(messageId)

        assertEquals("ACK should remove from queue", 0, holdingOutbox.size())
    }

    @Test
    fun `gate5 multiple messages queue correctly`() {
        // Create outbox with failing sender to keep items
        val holdingOutbox = OutboxQueue(
            myWhisperIdProvider = { myWhisperId },
            sessionTokenProvider = { mySessionToken },
            mySignPrivateKeyProvider = { mySignPrivateKey },
            myEncPrivateKeyProvider = { myEncPrivateKey },
            peerKeyProvider = object : PeerKeyProvider {
                override fun getSignPublicKey(whisperId: String) = null
                override fun getEncPublicKey(whisperId: String) = recipientEncPublicKey
            },
            wsSender = { false },
            messageEncryptor = { _, _, _ -> Pair(mockNonce, mockCiphertext) },
            messageSigner = { _, _ -> mockSignature }
        )

        holdingOutbox.enqueueTextMessage("msg1", recipientId)
        holdingOutbox.enqueueTextMessage("msg2", recipientId)
        holdingOutbox.enqueueTextMessage("msg3", recipientId)

        assertEquals(3, holdingOutbox.size())
        assertEquals(3, holdingOutbox.enqueuedCount)
    }

    // ==========================================================================
    // Gate 5: Payload example verification
    // ==========================================================================

    @Test
    fun `gate5 full payload example is valid JSON with required fields`() {
        outbox.enqueueTextMessage("merhaba", recipientId)

        val payload = outbox.lastPayload!!

        // Verify it's valid JSON with required envelope fields
        assertTrue(payload.startsWith("{"))
        assertTrue(payload.endsWith("}"))
        assertTrue(payload.contains("\"type\":\"send_message\""))
        assertTrue(payload.contains("\"requestId\":\""))
        assertTrue(payload.contains("\"payload\":{"))

        // Verify nested payload fields
        assertTrue(payload.contains("\"protocolVersion\":"))
        assertTrue(payload.contains("\"cryptoVersion\":"))
        assertTrue(payload.contains("\"messageId\":\""))
        assertTrue(payload.contains("\"nonce\":\""))
        assertTrue(payload.contains("\"ciphertext\":\""))
        assertTrue(payload.contains("\"sig\":\""))
    }

    // ==========================================================================
    // Helper
    // ==========================================================================

    private fun createOutbox(
        myWhisperId: String? = this.myWhisperId,
        sessionToken: String? = this.mySessionToken,
        signPrivateKey: ByteArray? = this.mySignPrivateKey,
        encPrivateKey: ByteArray? = this.myEncPrivateKey
    ): OutboxQueue {
        return OutboxQueue(
            myWhisperIdProvider = { myWhisperId },
            sessionTokenProvider = { sessionToken },
            mySignPrivateKeyProvider = { signPrivateKey },
            myEncPrivateKeyProvider = { encPrivateKey },
            peerKeyProvider = object : PeerKeyProvider {
                override fun getSignPublicKey(whisperId: String) = null
                override fun getEncPublicKey(whisperId: String) = recipientEncPublicKey
            },
            wsSender = { json ->
                sentPayloads.add(json)
                true
            },
            messageEncryptor = { _, _, _ -> Pair(mockNonce, mockCiphertext) },
            messageSigner = { _, _ -> mockSignature }
        )
    }
}
