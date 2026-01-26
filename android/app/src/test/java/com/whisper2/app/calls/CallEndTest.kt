package com.whisper2.app.calls

import com.whisper2.app.network.ws.*
import com.whisper2.app.services.calls.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Gate 5: Call End - Reasons, Cleanup, and Idempotency
 *
 * Tests:
 * - call_end with all reason types
 * - Cleanup on call end (close WebRTC, dismiss UI, reset state)
 * - Idempotent call_end (duplicate sends ignored)
 * - Receiving call_end from peer
 * - Terminal state handling
 */
class CallEndTest {

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
        keyStore.putSignKey("WSP-PEER", callerSignPublicKey)
        keyStore.putEncKey("WSP-PEER", callerEncPublicKey)

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
    // Gate 5: call_end with different reasons
    // ==========================================================================

    @Test
    fun `gate5 endCall sends call_end with ended reason`() = runTest {
        callService.initiateCall("WSP-PEER", false)

        callService.endCall(CallEndReason.ENDED)

        val endMsg = wsSender.findByType("call_end")
        assertNotNull(endMsg)
        assertTrue(endMsg!!.contains("\"reason\":\"ended\""))
    }

    @Test
    fun `gate5 endCall sends call_end with declined reason`() = runTest {
        callService.initiateCall("WSP-PEER", false)

        callService.endCall(CallEndReason.DECLINED)

        val endMsg = wsSender.findByType("call_end")
        assertNotNull(endMsg)
        assertTrue(endMsg!!.contains("\"reason\":\"declined\""))
    }

    @Test
    fun `gate5 endCall sends call_end with busy reason`() = runTest {
        callService.initiateCall("WSP-PEER", false)

        callService.endCall(CallEndReason.BUSY)

        val endMsg = wsSender.findByType("call_end")
        assertNotNull(endMsg)
        assertTrue(endMsg!!.contains("\"reason\":\"busy\""))
    }

    @Test
    fun `gate5 endCall sends call_end with timeout reason`() = runTest {
        callService.initiateCall("WSP-PEER", false)

        callService.endCall(CallEndReason.TIMEOUT)

        val endMsg = wsSender.findByType("call_end")
        assertNotNull(endMsg)
        assertTrue(endMsg!!.contains("\"reason\":\"timeout\""))
    }

    @Test
    fun `gate5 endCall sends call_end with failed reason`() = runTest {
        callService.initiateCall("WSP-PEER", false)

        callService.endCall(CallEndReason.FAILED)

        val endMsg = wsSender.findByType("call_end")
        assertNotNull(endMsg)
        assertTrue(endMsg!!.contains("\"reason\":\"failed\""))
    }

    // ==========================================================================
    // Gate 5: Cleanup on call end
    // ==========================================================================

    @Test
    fun `gate5 endCall closes WebRTC`() = runTest {
        callService.initiateCall("WSP-PEER", false)

        callService.endCall()

        assertEquals(1, webRtcService.closeCount)
    }

    @Test
    fun `gate5 endCall dismisses UI`() = runTest {
        callService.initiateCall("WSP-PEER", false)

        callService.endCall()

        assertEquals(1, uiService.dismissedCount)
    }

    @Test
    fun `gate5 endCall resets state`() = runTest {
        callService.initiateCall("WSP-PEER", false)
        val callId = callService.currentCallId

        callService.endCall()

        // State should transition to ENDED then IDLE
        // (After delay in cleanup)
        assertEquals(CallService.CallState.ENDED, callService.state)
        assertNull(callService.currentCallId)
        assertNull(callService.currentPeerId)
    }

    @Test
    fun `gate5 endCall clears video flag`() = runTest {
        callService.initiateCall("WSP-PEER", true)
        assertTrue(callService.isVideoCall)

        callService.endCall()

        assertFalse(callService.isVideoCall)
    }

    // ==========================================================================
    // Gate 5: Idempotent call_end (CRITICAL TEST)
    // ==========================================================================

    @Test
    fun `gate5 duplicate endCall only sends one call_end`() = runTest {
        callService.initiateCall("WSP-PEER", false)
        val callId = callService.currentCallId!!

        // End call multiple times
        callService.endCall()
        callService.endCall()
        callService.endCall()

        // Should only send ONE call_end
        val endCount = wsSender.countByType("call_end")
        assertEquals(
            "IDEMPOTENCY: Duplicate endCall should only send ONE call_end message",
            1,
            endCount
        )
    }

    @Test
    fun `gate5 endCall after call_end received is idempotent`() = runTest {
        callService.initiateCall("WSP-PEER", false)
        val callId = callService.currentCallId!!

        // Receive call_end from peer
        val callEndEnvelope = createCallEndEnvelope(callId, "WSP-PEER", CallEndReason.ENDED)
        callService.onWsMessage(callEndEnvelope)

        wsSender.clear()

        // Our endCall should not send another call_end
        callService.endCall()

        val endCount = wsSender.countByType("call_end")
        assertEquals(
            "IDEMPOTENCY: endCall after receiving call_end should not send another",
            0,
            endCount
        )
    }

    @Test
    fun `gate5 receiving duplicate call_end is idempotent`() = runTest {
        callService.initiateCall("WSP-PEER", false)
        val callId = callService.currentCallId!!

        // Receive multiple call_end from peer
        val callEndEnvelope = createCallEndEnvelope(callId, "WSP-PEER", CallEndReason.ENDED)
        callService.onWsMessage(callEndEnvelope)
        callService.onWsMessage(callEndEnvelope)
        callService.onWsMessage(callEndEnvelope)

        // WebRTC should only be closed once
        assertEquals(
            "IDEMPOTENCY: Duplicate call_end should only close WebRTC once",
            1,
            webRtcService.closeCount
        )

        // UI should only be dismissed once
        assertEquals(
            "IDEMPOTENCY: Duplicate call_end should only dismiss UI once",
            1,
            uiService.dismissedCount
        )
    }

    // ==========================================================================
    // Gate 5: Receiving call_end from peer
    // ==========================================================================

    @Test
    fun `gate5 receiving call_end ends call`() = runTest {
        callService.initiateCall("WSP-PEER", false)
        val callId = callService.currentCallId!!

        val callEndEnvelope = createCallEndEnvelope(callId, "WSP-PEER", CallEndReason.ENDED)
        callService.onWsMessage(callEndEnvelope)

        assertEquals(CallService.CallState.ENDED, callService.state)
        assertEquals(1, webRtcService.closeCount)
        assertEquals(1, uiService.dismissedCount)
    }

    @Test
    fun `gate5 receiving call_end with declined reason`() = runTest {
        callService.initiateCall("WSP-PEER", false)
        val callId = callService.currentCallId!!

        val callEndEnvelope = createCallEndEnvelope(callId, "WSP-PEER", CallEndReason.DECLINED)
        callService.onWsMessage(callEndEnvelope)

        assertEquals("declined", uiService.lastDismissedReason)
    }

    @Test
    fun `gate5 receiving call_end with busy reason`() = runTest {
        callService.initiateCall("WSP-PEER", false)
        val callId = callService.currentCallId!!

        val callEndEnvelope = createCallEndEnvelope(callId, "WSP-PEER", CallEndReason.BUSY)
        callService.onWsMessage(callEndEnvelope)

        assertEquals("busy", uiService.lastDismissedReason)
    }

    @Test
    fun `gate5 receiving call_end for wrong callId ignored`() = runTest {
        callService.initiateCall("WSP-PEER", false)

        val callEndEnvelope = createCallEndEnvelope("wrong-call-id", "WSP-PEER", CallEndReason.ENDED)
        callService.onWsMessage(callEndEnvelope)

        // State should not change
        assertEquals(CallService.CallState.OUTGOING_INITIATING, callService.state)
        assertEquals(0, uiService.dismissedCount)
    }

    // ==========================================================================
    // Gate 5: Terminal state handling
    // ==========================================================================

    @Test
    fun `gate5 endCall in IDLE state does nothing`() {
        // No active call
        assertEquals(CallService.CallState.IDLE, callService.state)

        callService.endCall()

        // No call_end should be sent
        assertEquals(0, wsSender.countByType("call_end"))
        assertEquals(0, webRtcService.closeCount)
    }

    @Test
    fun `gate5 endCall in ENDED state does nothing`() = runTest {
        callService.initiateCall("WSP-PEER", false)
        callService.endCall()
        wsSender.clear()

        // Call again while in ENDED state
        callService.endCall()

        // No additional messages
        assertEquals(0, wsSender.countByType("call_end"))
    }

    // ==========================================================================
    // Gate 5: call_end on incoming call
    // ==========================================================================

    @Test
    fun `gate5 decline incoming call sends call_end`() {
        val envelope = createIncomingCallEnvelope("call-incoming", "WSP-CALLER")
        callService.onWsMessage(envelope)
        wsSender.clear()

        callService.decline("call-incoming")

        val endMsg = wsSender.findByType("call_end")
        assertNotNull(endMsg)
        assertTrue(endMsg!!.contains("\"reason\":\"declined\""))
    }

    @Test
    fun `gate5 receiving call_end on incoming call dismisses UI`() {
        val envelope = createIncomingCallEnvelope("call-incoming", "WSP-CALLER")
        callService.onWsMessage(envelope)

        val callEndEnvelope = createCallEndEnvelope("call-incoming", "WSP-CALLER", CallEndReason.TIMEOUT)
        callService.onWsMessage(callEndEnvelope)

        assertEquals(1, uiService.dismissedCount)
        assertEquals("timeout", uiService.lastDismissedReason)
    }

    // ==========================================================================
    // Gate 5: call_end signature and fields
    // ==========================================================================

    @Test
    fun `gate5 call_end includes signature`() = runTest {
        callService.initiateCall("WSP-PEER", false)
        val callId = callService.currentCallId!!

        callService.endCall()

        val endMsg = wsSender.findByType("call_end")
        assertNotNull(endMsg)
        assertTrue(endMsg!!.contains("\"sig\":"))
        assertTrue(endMsg.contains("\"nonce\":"))
        assertTrue(endMsg.contains("\"timestamp\":"))
    }

    @Test
    fun `gate5 call_end includes from and to`() = runTest {
        callService.initiateCall("WSP-PEER", false)

        callService.endCall()

        val endMsg = wsSender.findByType("call_end")
        assertNotNull(endMsg)
        assertTrue(endMsg!!.contains("\"from\":\"WSP-ME\""))
        assertTrue(endMsg.contains("\"to\":\"WSP-PEER\""))
    }

    // ==========================================================================
    // Gate 5: WebRTC failure triggers call_end
    // ==========================================================================

    @Test
    fun `gate5 WebRTC failure sends call_end with failed reason`() = runTest {
        callService.initiateCall("WSP-PEER", false)
        wsSender.clear()

        callService.onWebRtcFailed("Connection timeout")

        val endMsg = wsSender.findByType("call_end")
        assertNotNull(endMsg)
        assertTrue(endMsg!!.contains("\"reason\":\"failed\""))
        assertEquals(1, uiService.errorShownCount)
    }

    // ==========================================================================
    // Helper methods
    // ==========================================================================

    private fun createCallEndEnvelope(
        callId: String,
        from: String,
        reason: String
    ): WsRawEnvelope {
        val timestamp = System.currentTimeMillis()
        val nonce = cryptoProvider.generateNonce()
        val ciphertext = reason.toByteArray(Charsets.UTF_8)

        val sig = cryptoProvider.signCanonical(
            messageType = "call_end",
            messageId = callId,
            from = from,
            to = "WSP-ME",
            timestamp = timestamp,
            nonce = nonce,
            ciphertext = ciphertext,
            privateKey = callerSignPrivateKey
        )

        val payload = CallPayload(
            callId = callId,
            from = from,
            to = "WSP-ME",
            timestamp = timestamp,
            nonce = cryptoProvider.encodeBase64(nonce),
            ciphertext = cryptoProvider.encodeBase64(ciphertext),
            sig = sig,
            reason = reason
        )

        val json = WsParser.createEnvelope(WsMessageTypes.CALL_END, payload)
        return WsParser.parseRaw(json)
    }

    private fun createIncomingCallEnvelope(
        callId: String,
        from: String
    ): WsRawEnvelope {
        val timestamp = System.currentTimeMillis()
        val nonce = cryptoProvider.generateNonce()
        val sdpOffer = "v=0\r\no=test\r\n"

        val ciphertext = cryptoProvider.seal(
            sdpOffer.toByteArray(Charsets.UTF_8),
            nonce,
            myEncPublicKey,
            callerEncPrivateKey
        )

        val sig = cryptoProvider.signCanonical(
            messageType = "call_initiate",
            messageId = callId,
            from = from,
            to = "WSP-ME",
            timestamp = timestamp,
            nonce = nonce,
            ciphertext = ciphertext,
            privateKey = callerSignPrivateKey
        )

        val payload = CallPayload(
            callId = callId,
            from = from,
            to = "WSP-ME",
            isVideo = false,
            timestamp = timestamp,
            nonce = cryptoProvider.encodeBase64(nonce),
            ciphertext = cryptoProvider.encodeBase64(ciphertext),
            sig = sig
        )

        val json = WsParser.createEnvelope(WsMessageTypes.CALL_INCOMING, payload)
        return WsParser.parseRaw(json)
    }
}
