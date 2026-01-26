package com.whisper2.app.observability

import com.whisper2.app.core.Logger
import com.whisper2.app.core.Metrics
import com.whisper2.app.network.ws.WsErrorCodes
import com.whisper2.app.services.messaging.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Step 13.1 Gate 2: AUTH_FAILED triggers log + forceLogout
 *
 * Tests that UNAUTHORIZED error from WebSocket:
 * 1. Logs the auth failure event
 * 2. Triggers forceLogout callback
 * 3. Pauses the outbox queue
 */
class AuthFailureLoggingTest {

    private lateinit var capturedEvents: CopyOnWriteArrayList<Logger.LogEvent>
    private lateinit var wsSender: FakeWsSenderCapture
    private lateinit var outboxQueue: OutboxQueue

    private var forceLogoutTriggered = AtomicBoolean(false)
    private var authFailureReason: String? = null

    @Before
    fun setup() {
        capturedEvents = CopyOnWriteArrayList()
        Logger.androidLogEnabled = false
        Logger.setListener { event -> capturedEvents.add(event) }
        Metrics.resetAll()

        forceLogoutTriggered.set(false)
        authFailureReason = null

        wsSender = FakeWsSenderCapture()

        outboxQueue = OutboxQueue(
            myWhisperIdProvider = { "WSP-ME" },
            sessionTokenProvider = { "test-session" },
            mySignPrivateKeyProvider = { ByteArray(32) { it.toByte() } },
            myEncPrivateKeyProvider = { ByteArray(32) { (it + 100).toByte() } },
            peerKeyProvider = object : PeerKeyProvider {
                override fun getSignPublicKey(whisperId: String): ByteArray? = ByteArray(32)
                override fun getEncPublicKey(whisperId: String): ByteArray? = ByteArray(32)
            },
            wsSender = { json -> wsSender.send(json) },
            messageEncryptor = FakeEncryptor(),
            messageSigner = FakeSigner(),
            authFailureHandler = { reason ->
                forceLogoutTriggered.set(true)
                authFailureReason = reason
                // Log the auth failure
                Logger.error("Auth failure: $reason", Logger.Category.AUTH)
            }
        )
    }

    @After
    fun teardown() {
        Logger.clearListener()
        Logger.androidLogEnabled = true
    }

    // ==========================================================================
    // Gate 2: AUTH_FAILED triggers log + forceLogout
    // ==========================================================================

    @Test
    fun `gate2 UNAUTHORIZED error triggers forceLogout`() {
        // Enqueue a message
        val messageId = outboxQueue.enqueueTextMessage("test", "WSP-PEER")
        assertNotNull(messageId)
        val item = outboxQueue.getByMessageId(messageId!!)!!
        val requestId = item.requestId

        // Simulate UNAUTHORIZED error
        outboxQueue.onError(requestId, WsErrorCodes.UNAUTHORIZED, "Session expired")

        // forceLogout should be triggered
        assertTrue("forceLogout should be triggered", forceLogoutTriggered.get())
        assertTrue(authFailureReason!!.contains("UNAUTHORIZED"))
    }

    @Test
    fun `gate2 UNAUTHORIZED error logs AUTH event`() {
        val messageId = outboxQueue.enqueueTextMessage("test", "WSP-PEER")!!
        val item = outboxQueue.getByMessageId(messageId)!!

        outboxQueue.onError(item.requestId, WsErrorCodes.UNAUTHORIZED, "Invalid token")

        // Should have logged an AUTH error
        val authEvents = capturedEvents.filter { it.category == Logger.Category.AUTH }
        assertTrue("Should have AUTH log event", authEvents.isNotEmpty())
        assertTrue("Should be ERROR level", authEvents.any { it.level == Logger.Level.ERROR })
        assertTrue("Should mention auth failure", authEvents.any { it.message.contains("Auth failure") })
    }

    @Test
    fun `gate2 UNAUTHORIZED error pauses queue`() {
        val messageId = outboxQueue.enqueueTextMessage("test", "WSP-PEER")!!
        val item = outboxQueue.getByMessageId(messageId)!!

        assertFalse("Queue should not be paused initially", outboxQueue.isPaused)

        outboxQueue.onError(item.requestId, WsErrorCodes.UNAUTHORIZED, "Session expired")

        assertTrue("Queue should be paused after UNAUTHORIZED", outboxQueue.isPaused)
    }

    @Test
    fun `gate2 UNAUTHORIZED marks message as failed`() {
        val messageId = outboxQueue.enqueueTextMessage("test", "WSP-PEER")!!
        val item = outboxQueue.getByMessageId(messageId)!!

        outboxQueue.onError(item.requestId, WsErrorCodes.UNAUTHORIZED, "Session expired")

        assertEquals("failed", item.status)
        assertEquals(WsErrorCodes.UNAUTHORIZED, item.failedCode)
    }

    @Test
    fun `gate2 non-auth error does not trigger forceLogout`() {
        val messageId = outboxQueue.enqueueTextMessage("test", "WSP-PEER")!!
        val item = outboxQueue.getByMessageId(messageId)!!

        // Simulate a different error (transient)
        outboxQueue.onError(item.requestId, "RATE_LIMITED", "Too many requests")

        assertFalse("forceLogout should NOT be triggered for non-auth errors", forceLogoutTriggered.get())
        assertFalse("Queue should NOT be paused for non-auth errors", outboxQueue.isPaused)
    }

    @Test
    fun `gate2 permanent error does not trigger forceLogout`() {
        val messageId = outboxQueue.enqueueTextMessage("test", "WSP-PEER")!!
        val item = outboxQueue.getByMessageId(messageId)!!

        // INVALID_SIGNATURE is permanent but not auth failure
        outboxQueue.onError(item.requestId, WsErrorCodes.INVALID_SIGNATURE, "Bad signature")

        assertFalse("forceLogout should NOT be triggered for signature errors", forceLogoutTriggered.get())
        assertFalse("Queue should NOT be paused for signature errors", outboxQueue.isPaused)
    }

    @Test
    fun `gate2 auth failure logs include reason`() {
        val messageId = outboxQueue.enqueueTextMessage("test", "WSP-PEER")!!
        val item = outboxQueue.getByMessageId(messageId)!!

        outboxQueue.onError(item.requestId, WsErrorCodes.UNAUTHORIZED, "Token expired at 12:00")

        val authEvent = capturedEvents.find {
            it.category == Logger.Category.AUTH && it.level == Logger.Level.ERROR
        }
        assertNotNull("Should have AUTH error event", authEvent)
        assertTrue("Should include reason in log", authEvent!!.message.contains("Token expired"))
    }

    @Test
    fun `gate2 queue resume after re-auth`() {
        val messageId = outboxQueue.enqueueTextMessage("test", "WSP-PEER")!!
        val item = outboxQueue.getByMessageId(messageId)!!

        // Trigger auth failure
        outboxQueue.onError(item.requestId, WsErrorCodes.UNAUTHORIZED, "Session expired")
        assertTrue(outboxQueue.isPaused)

        // Simulate re-authentication success
        outboxQueue.resume()

        assertFalse("Queue should resume after re-auth", outboxQueue.isPaused)
    }

    // ==========================================================================
    // Helper classes
    // ==========================================================================

    class FakeWsSenderCapture {
        val sent = mutableListOf<String>()
        var shouldFail = false

        fun send(json: String): Boolean {
            if (shouldFail) return false
            sent.add(json)
            return true
        }
    }

    class FakeEncryptor : MessageEncryptor {
        override fun encrypt(
            plaintext: ByteArray,
            recipientPublicKey: ByteArray,
            senderPrivateKey: ByteArray
        ): Pair<ByteArray, ByteArray> {
            return Pair(ByteArray(24), plaintext) // Fake nonce + ciphertext
        }
    }

    class FakeSigner : MessageSigner {
        override fun sign(message: ByteArray, privateKey: ByteArray): ByteArray {
            return ByteArray(64) // Fake signature
        }
    }
}
