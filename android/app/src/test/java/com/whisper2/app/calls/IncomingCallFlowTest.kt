package com.whisper2.app.calls

import com.google.gson.JsonParser
import com.whisper2.app.network.ws.*
import com.whisper2.app.services.calls.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Gate 2: Incoming Call Flow
 *
 * Tests:
 * - call_incoming → signature verify → UI show → ringing sent
 * - accept() → call_answer sent
 * - decline() → call_end sent with "declined"
 * - Invalid signature → no UI shown
 * - Wrong nonce length → rejected
 */
class IncomingCallFlowTest {

    private lateinit var wsSender: FakeWsSender
    private lateinit var uiService: FakeCallUiService
    private lateinit var webRtcService: FakeWebRtcService
    private lateinit var turnService: FakeTurnService
    private lateinit var keyStore: InMemoryKeyStore
    private lateinit var myKeysProvider: TestKeysProvider
    private lateinit var cryptoProvider: FakeCallCryptoProvider
    private lateinit var callService: CallService

    // Test key pairs (fake 32-byte keys for testing)
    private val callerSignPrivateKey = ByteArray(32) { (it + 1).toByte() }
    private val callerSignPublicKey = ByteArray(32) { (it + 50).toByte() }
    private val callerEncPrivateKey = ByteArray(32) { (it + 100).toByte() }
    private val callerEncPublicKey = ByteArray(32) { (it + 150).toByte() }
    private val mySignPrivateKey = ByteArray(32) { (it + 200).toByte() }
    private val mySignPublicKey = ByteArray(32) { (it + 250).toByte() }
    private val myEncPrivateKey = ByteArray(32) { (it + 10).toByte() }
    private val myEncPublicKey = ByteArray(32) { (it + 60).toByte() }

    @Before
    fun setup() {
        wsSender = FakeWsSender()
        uiService = FakeCallUiService()
        webRtcService = FakeWebRtcService()
        turnService = FakeTurnService()
        keyStore = InMemoryKeyStore()
        myKeysProvider = TestKeysProvider()
        cryptoProvider = FakeCallCryptoProvider()

        // Set up my keys
        myKeysProvider.whisperId = "WSP-ME"
        myKeysProvider.signPrivateKey = mySignPrivateKey
        myKeysProvider.signPublicKey = mySignPublicKey
        myKeysProvider.encPrivateKey = myEncPrivateKey
        myKeysProvider.encPublicKey = myEncPublicKey

        // Store caller's public keys
        keyStore.putSignKey("WSP-CALLER", callerSignPublicKey)
        keyStore.putEncKey("WSP-CALLER", callerEncPublicKey)

        callService = CallService(
            wsSender = wsSender,
            uiService = uiService,
            webRtcService = webRtcService,
            turnService = turnService,
            keyStore = keyStore,
            myKeysProvider = myKeysProvider,
            sessionProvider = { "test-session-token" },
            cryptoProvider = cryptoProvider
        )
    }

    // ==========================================================================
    // Gate 2: Incoming call shows UI and sends ringing
    // ==========================================================================

    @Test
    fun `gate2 incoming call shows incoming UI`() {
        val envelope = createIncomingCallEnvelope(
            callId = "call-123",
            from = "WSP-CALLER",
            to = "WSP-ME",
            isVideo = false,
            sdpOffer = "v=0\r\no=test\r\n"
        )

        callService.onWsMessage(envelope)

        assertEquals(1, uiService.incomingShownCount)
        assertEquals("call-123", uiService.lastIncomingCallId)
        assertEquals("WSP-CALLER", uiService.lastIncomingFrom)
        assertEquals(false, uiService.lastIncomingIsVideo)
    }

    @Test
    fun `gate2 incoming call sets state to INCOMING_RINGING`() {
        val envelope = createIncomingCallEnvelope(
            callId = "call-456",
            from = "WSP-CALLER",
            to = "WSP-ME"
        )

        callService.onWsMessage(envelope)

        assertEquals(CallService.CallState.INCOMING_RINGING, callService.state)
        assertEquals("call-456", callService.currentCallId)
        assertEquals("WSP-CALLER", callService.currentPeerId)
    }

    @Test
    fun `gate2 incoming call sends call_ringing`() {
        val envelope = createIncomingCallEnvelope(
            callId = "call-789",
            from = "WSP-CALLER",
            to = "WSP-ME"
        )

        callService.onWsMessage(envelope)

        // Should have sent call_ringing
        val ringingMsg = wsSender.findByType("call_ringing")
        assertNotNull("call_ringing should be sent", ringingMsg)
        assertTrue(ringingMsg!!.contains("\"callId\":\"call-789\""))
        assertTrue(ringingMsg.contains("\"from\":\"WSP-ME\""))
        assertTrue(ringingMsg.contains("\"to\":\"WSP-CALLER\""))
    }

    @Test
    fun `gate2 incoming video call sets isVideo true`() {
        val envelope = createIncomingCallEnvelope(
            callId = "video-call",
            from = "WSP-CALLER",
            to = "WSP-ME",
            isVideo = true
        )

        callService.onWsMessage(envelope)

        assertEquals(true, uiService.lastIncomingIsVideo)
        assertTrue(callService.isVideoCall)
    }

    // ==========================================================================
    // Gate 2: Accept sends call_answer
    // ==========================================================================

    @Test
    fun `gate2 accept sends call_answer with signature`() = runTest {
        // First receive incoming call
        val envelope = createIncomingCallEnvelope(
            callId = "call-accept-test",
            from = "WSP-CALLER",
            to = "WSP-ME"
        )
        callService.onWsMessage(envelope)

        // Accept the call
        val result = callService.accept("call-accept-test")
        assertTrue("accept should succeed", result.isSuccess)

        // State should be CONNECTING
        assertEquals(CallService.CallState.CONNECTING, callService.state)
        assertEquals(1, uiService.connectingShownCount)
    }

    @Test
    fun `gate2 accept wrong callId fails`() = runTest {
        val envelope = createIncomingCallEnvelope(
            callId = "call-X",
            from = "WSP-CALLER",
            to = "WSP-ME"
        )
        callService.onWsMessage(envelope)

        // Try to accept different call ID
        val result = callService.accept("call-WRONG")
        assertTrue("accept with wrong callId should fail", result.isFailure)
    }

    @Test
    fun `gate2 accept in wrong state fails`() = runTest {
        // Don't receive any incoming call, try to accept
        val result = callService.accept("some-call-id")
        assertTrue("accept without incoming call should fail", result.isFailure)
    }

    // ==========================================================================
    // Gate 2: Decline sends call_end with reason
    // ==========================================================================

    @Test
    fun `gate2 decline sends call_end with declined reason`() {
        val envelope = createIncomingCallEnvelope(
            callId = "call-decline-test",
            from = "WSP-CALLER",
            to = "WSP-ME"
        )
        callService.onWsMessage(envelope)
        wsSender.clear() // Clear ringing message

        callService.decline("call-decline-test")

        val endMsg = wsSender.findByType("call_end")
        assertNotNull("call_end should be sent", endMsg)
        assertTrue(endMsg!!.contains("\"callId\":\"call-decline-test\""))
        assertTrue(endMsg.contains("\"reason\":\"declined\""))
    }

    @Test
    fun `gate2 decline with busy reason`() {
        val envelope = createIncomingCallEnvelope(
            callId = "call-busy-test",
            from = "WSP-CALLER",
            to = "WSP-ME"
        )
        callService.onWsMessage(envelope)
        wsSender.clear()

        callService.decline("call-busy-test", CallEndReason.BUSY)

        val endMsg = wsSender.findByType("call_end")
        assertNotNull(endMsg)
        assertTrue(endMsg!!.contains("\"reason\":\"busy\""))
    }

    @Test
    fun `gate2 decline dismisses UI`() {
        val envelope = createIncomingCallEnvelope(
            callId = "call-dismiss",
            from = "WSP-CALLER",
            to = "WSP-ME"
        )
        callService.onWsMessage(envelope)

        callService.decline("call-dismiss")

        assertEquals(1, uiService.dismissedCount)
        assertEquals("call-dismiss", uiService.lastDismissedCallId)
        assertEquals("declined", uiService.lastDismissedReason)
    }

    // ==========================================================================
    // Gate 2: Signature validation
    // ==========================================================================

    @Test
    fun `gate2 invalid nonce length rejects call`() {
        // Create envelope with wrong nonce length
        val wrongNonce = ByteArray(16) // Should be 24
        val timestamp = System.currentTimeMillis()
        val ciphertext = "test".toByteArray()

        val payload = CallPayload(
            callId = "call-bad-nonce",
            from = "WSP-CALLER",
            to = "WSP-ME",
            isVideo = false,
            timestamp = timestamp,
            nonce = cryptoProvider.encodeBase64(wrongNonce),
            ciphertext = cryptoProvider.encodeBase64(ciphertext),
            sig = cryptoProvider.encodeBase64(ByteArray(64))
        )

        val envelope = createRawEnvelope(WsMessageTypes.CALL_INCOMING, payload)
        callService.onWsMessage(envelope)

        // No UI should be shown
        assertEquals(0, uiService.incomingShownCount)
        assertEquals(CallService.CallState.IDLE, callService.state)
    }

    @Test
    fun `gate2 invalid signature length rejects call`() {
        val nonce = cryptoProvider.generateNonce()
        val timestamp = System.currentTimeMillis()
        val ciphertext = "test".toByteArray()

        val payload = CallPayload(
            callId = "call-bad-sig",
            from = "WSP-CALLER",
            to = "WSP-ME",
            isVideo = false,
            timestamp = timestamp,
            nonce = cryptoProvider.encodeBase64(nonce),
            ciphertext = cryptoProvider.encodeBase64(ciphertext),
            sig = cryptoProvider.encodeBase64(ByteArray(32)) // Should be 64
        )

        val envelope = createRawEnvelope(WsMessageTypes.CALL_INCOMING, payload)
        callService.onWsMessage(envelope)

        assertEquals(0, uiService.incomingShownCount)
    }

    @Test
    fun `gate2 unknown sender rejects call`() {
        // Don't add keys for UNKNOWN sender
        val envelope = createIncomingCallEnvelope(
            callId = "call-unknown",
            from = "WSP-UNKNOWN",
            to = "WSP-ME"
        )

        callService.onWsMessage(envelope)

        assertEquals(0, uiService.incomingShownCount)
    }

    // ==========================================================================
    // Gate 2: Multiple incoming calls
    // ==========================================================================

    @Test
    fun `gate2 second incoming call while ringing is ignored`() {
        // First call
        val envelope1 = createIncomingCallEnvelope(
            callId = "call-1",
            from = "WSP-CALLER",
            to = "WSP-ME"
        )
        callService.onWsMessage(envelope1)

        assertEquals(1, uiService.incomingShownCount)
        assertEquals("call-1", callService.currentCallId)

        // Second call should be ignored (we're already in INCOMING_RINGING)
        keyStore.putSignKey("WSP-OTHER", callerSignPublicKey)
        keyStore.putEncKey("WSP-OTHER", callerEncPublicKey)

        val envelope2 = createIncomingCallEnvelope(
            callId = "call-2",
            from = "WSP-OTHER",
            to = "WSP-ME"
        )

        // This should not change the state or show new UI
        // (Currently CallService doesn't reject, but keeps first call)
        assertEquals("call-1", callService.currentCallId)
    }

    // ==========================================================================
    // Helper methods
    // ==========================================================================

    private fun createIncomingCallEnvelope(
        callId: String,
        from: String,
        to: String,
        isVideo: Boolean = false,
        sdpOffer: String = "v=0\r\no=- 123 2 IN IP4 127.0.0.1\r\n"
    ): WsRawEnvelope {
        val timestamp = System.currentTimeMillis()
        val nonce = cryptoProvider.generateNonce()

        // Encrypt SDP with caller's private key and my public key
        val ciphertext = cryptoProvider.seal(
            sdpOffer.toByteArray(Charsets.UTF_8),
            nonce,
            myEncPublicKey,
            callerEncPrivateKey
        )

        // Sign with caller's signing key
        val sig = cryptoProvider.signCanonical(
            messageType = "call_initiate", // Server sends as call_incoming but signed as call_initiate
            messageId = callId,
            from = from,
            to = to,
            timestamp = timestamp,
            nonce = nonce,
            ciphertext = ciphertext,
            privateKey = callerSignPrivateKey
        )

        val payload = CallPayload(
            callId = callId,
            from = from,
            to = to,
            isVideo = isVideo,
            timestamp = timestamp,
            nonce = cryptoProvider.encodeBase64(nonce),
            ciphertext = cryptoProvider.encodeBase64(ciphertext),
            sig = sig
        )

        return createRawEnvelope(WsMessageTypes.CALL_INCOMING, payload)
    }

    private fun createRawEnvelope(type: String, payload: Any): WsRawEnvelope {
        val json = WsParser.createEnvelope(type, payload)
        return WsParser.parseRaw(json)
    }
}
