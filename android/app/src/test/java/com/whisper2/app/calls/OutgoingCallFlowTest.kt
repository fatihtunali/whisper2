package com.whisper2.app.calls

import com.whisper2.app.network.ws.*
import com.whisper2.app.services.calls.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Gate 3: Outgoing Call Flow
 *
 * Tests:
 * - initiateCall() → TURN request → peer connection → call_initiate sent
 * - call_ringing received → RINGING state
 * - call_answer received → CONNECTING state → remote description set
 * - WebRTC connected → IN_CALL state
 */
class OutgoingCallFlowTest {

    private lateinit var wsSender: FakeWsSender
    private lateinit var uiService: FakeCallUiService
    private lateinit var webRtcService: FakeWebRtcService
    private lateinit var turnService: FakeTurnService
    private lateinit var keyStore: InMemoryKeyStore
    private lateinit var myKeysProvider: TestKeysProvider
    private lateinit var cryptoProvider: FakeCallCryptoProvider
    private lateinit var callService: CallService

    // Test key pairs (fake 32-byte keys for testing)
    private val mySignPrivateKey = ByteArray(32) { (it + 1).toByte() }
    private val mySignPublicKey = ByteArray(32) { (it + 50).toByte() }
    private val myEncPrivateKey = ByteArray(32) { (it + 100).toByte() }
    private val myEncPublicKey = ByteArray(32) { (it + 150).toByte() }
    private val peerSignPrivateKey = ByteArray(32) { (it + 200).toByte() }
    private val peerSignPublicKey = ByteArray(32) { (it + 250).toByte() }
    private val peerEncPrivateKey = ByteArray(32) { (it + 10).toByte() }
    private val peerEncPublicKey = ByteArray(32) { (it + 60).toByte() }

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

        // Store peer's public keys
        keyStore.putSignKey("WSP-PEER", peerSignPublicKey)
        keyStore.putEncKey("WSP-PEER", peerEncPublicKey)

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
    // Gate 3: Initiate call flow
    // ==========================================================================

    @Test
    fun `gate3 initiateCall requests TURN credentials`() = runTest {
        val result = callService.initiateCall("WSP-PEER", false)

        assertTrue("initiateCall should succeed", result.isSuccess)
        assertEquals(1, turnService.requestCount)
    }

    @Test
    fun `gate3 initiateCall creates peer connection with TURN`() = runTest {
        callService.initiateCall("WSP-PEER", false)

        assertEquals(1, webRtcService.createPeerConnectionCount)
        assertNotNull(webRtcService.lastTurnCreds)
    }

    @Test
    fun `gate3 initiateCall creates SDP offer`() = runTest {
        callService.initiateCall("WSP-PEER", false)

        // Wait a bit for coroutine to run
        Thread.sleep(50)

        assertEquals(1, webRtcService.createOfferCount)
    }

    @Test
    fun `gate3 initiateCall sets state to OUTGOING_INITIATING`() = runTest {
        callService.initiateCall("WSP-PEER", false)

        assertEquals(CallService.CallState.OUTGOING_INITIATING, callService.state)
    }

    @Test
    fun `gate3 initiateCall shows outgoing UI`() = runTest {
        callService.initiateCall("WSP-PEER", false)

        assertEquals(1, uiService.outgoingShownCount)
    }

    @Test
    fun `gate3 initiateCall returns callId`() = runTest {
        val result = callService.initiateCall("WSP-PEER", false)

        assertTrue(result.isSuccess)
        val callId = result.getOrNull()
        assertNotNull(callId)
        assertTrue(callId!!.isNotEmpty())
        assertEquals(callId, callService.currentCallId)
    }

    @Test
    fun `gate3 initiateCall sets peerId`() = runTest {
        callService.initiateCall("WSP-PEER", true)

        assertEquals("WSP-PEER", callService.currentPeerId)
        assertTrue(callService.isVideoCall)
    }

    // ==========================================================================
    // Gate 3: Cannot initiate call when not IDLE
    // ==========================================================================

    @Test
    fun `gate3 cannot initiate call when already in call`() = runTest {
        // First call
        callService.initiateCall("WSP-PEER", false)

        // Second call should fail
        val result = callService.initiateCall("WSP-OTHER", false)

        assertTrue("second initiateCall should fail", result.isFailure)
    }

    // ==========================================================================
    // Gate 3: TURN failure handling
    // ==========================================================================

    @Test
    fun `gate3 initiateCall fails when TURN fails`() = runTest {
        turnService.shouldFail = true

        val result = callService.initiateCall("WSP-PEER", false)

        assertTrue("initiateCall should fail when TURN fails", result.isFailure)
    }

    // ==========================================================================
    // Gate 3: Not authenticated
    // ==========================================================================

    @Test
    fun `gate3 initiateCall fails without whisperId`() = runTest {
        myKeysProvider.whisperId = null

        val result = callService.initiateCall("WSP-PEER", false)

        assertTrue(result.isFailure)
    }

    @Test
    fun `gate3 initiateCall fails without session`() = runTest {
        val service = CallService(
            wsSender = wsSender,
            uiService = uiService,
            webRtcService = webRtcService,
            turnService = turnService,
            keyStore = keyStore,
            myKeysProvider = myKeysProvider,
            sessionProvider = { null }, // No session
            cryptoProvider = cryptoProvider
        )

        val result = service.initiateCall("WSP-PEER", false)

        assertTrue(result.isFailure)
    }

    // ==========================================================================
    // Gate 3: Receiving call_ringing
    // ==========================================================================

    @Test
    fun `gate3 call_ringing transitions to RINGING`() = runTest {
        callService.initiateCall("WSP-PEER", false)
        val callId = callService.currentCallId!!

        val ringingEnvelope = createRingingEnvelope(callId, "WSP-PEER")
        callService.onWsMessage(ringingEnvelope)

        assertEquals(CallService.CallState.RINGING, callService.state)
        assertEquals(1, uiService.ringingShownCount)
    }

    @Test
    fun `gate3 call_ringing with wrong callId ignored`() = runTest {
        callService.initiateCall("WSP-PEER", false)

        val ringingEnvelope = createRingingEnvelope("wrong-call-id", "WSP-PEER")
        callService.onWsMessage(ringingEnvelope)

        // State should still be OUTGOING_INITIATING
        assertEquals(CallService.CallState.OUTGOING_INITIATING, callService.state)
        assertEquals(0, uiService.ringingShownCount)
    }

    // ==========================================================================
    // Gate 3: Receiving call_answer
    // ==========================================================================

    @Test
    fun `gate3 call_answer transitions to CONNECTING`() = runTest {
        callService.initiateCall("WSP-PEER", false)
        val callId = callService.currentCallId!!

        // Receive ringing first
        val ringingEnvelope = createRingingEnvelope(callId, "WSP-PEER")
        callService.onWsMessage(ringingEnvelope)

        // Then receive answer
        val answerEnvelope = createAnswerEnvelope(callId, "WSP-PEER", "v=0\r\no=answer\r\n")
        callService.onWsMessage(answerEnvelope)

        assertEquals(CallService.CallState.CONNECTING, callService.state)
    }

    @Test
    fun `gate3 call_answer sets remote description`() = runTest {
        callService.initiateCall("WSP-PEER", false)
        val callId = callService.currentCallId!!

        val answerEnvelope = createAnswerEnvelope(callId, "WSP-PEER", "v=0\r\no=answer\r\n")
        callService.onWsMessage(answerEnvelope)

        // Wait for coroutine
        Thread.sleep(50)

        assertEquals(1, webRtcService.setRemoteDescriptionCount)
        assertEquals(WebRtcService.SdpType.ANSWER, webRtcService.lastRemoteSdpType)
    }

    // ==========================================================================
    // Gate 3: WebRTC connected
    // ==========================================================================

    @Test
    fun `gate3 webRtcConnected transitions to IN_CALL`() = runTest {
        callService.initiateCall("WSP-PEER", false)
        val callId = callService.currentCallId!!

        // Simulate connection flow
        val answerEnvelope = createAnswerEnvelope(callId, "WSP-PEER", "v=0\r\no=answer\r\n")
        callService.onWsMessage(answerEnvelope)

        Thread.sleep(50)

        // Simulate WebRTC connected
        callService.onWebRtcConnected()

        assertEquals(CallService.CallState.IN_CALL, callService.state)
        assertEquals(1, uiService.ongoingShownCount)
    }

    // ==========================================================================
    // Gate 3: Send call_initiate via callback
    // ==========================================================================

    @Test
    fun `gate3 sendCallInitiate sends signed message`() = runTest {
        callService.initiateCall("WSP-PEER", false)

        // Simulate WebRTC callback with local description
        callService.sendCallInitiate("v=0\r\no=offer\r\n")

        val initiateMsg = wsSender.findByType("call_initiate")
        assertNotNull("call_initiate should be sent", initiateMsg)
        assertTrue(initiateMsg!!.contains("\"from\":\"WSP-ME\""))
        assertTrue(initiateMsg.contains("\"to\":\"WSP-PEER\""))
        assertTrue(initiateMsg.contains("\"sig\":"))
        assertTrue(initiateMsg.contains("\"nonce\":"))
        assertTrue(initiateMsg.contains("\"ciphertext\":"))
    }

    // ==========================================================================
    // Gate 3: End outgoing call
    // ==========================================================================

    @Test
    fun `gate3 endCall during outgoing sends call_end`() = runTest {
        callService.initiateCall("WSP-PEER", false)

        callService.endCall()

        val endMsg = wsSender.findByType("call_end")
        assertNotNull("call_end should be sent", endMsg)
        assertTrue(endMsg!!.contains("\"reason\":\"ended\""))
    }

    @Test
    fun `gate3 endCall closes WebRTC`() = runTest {
        callService.initiateCall("WSP-PEER", false)

        callService.endCall()

        assertEquals(1, webRtcService.closeCount)
        assertEquals(1, uiService.dismissedCount)
    }

    // ==========================================================================
    // Helper methods
    // ==========================================================================

    private fun createRingingEnvelope(callId: String, from: String): WsRawEnvelope {
        val timestamp = System.currentTimeMillis()
        val nonce = cryptoProvider.generateNonce()
        val ciphertext = "ringing".toByteArray()

        val sig = cryptoProvider.signCanonical(
            messageType = "call_ringing",
            messageId = callId,
            from = from,
            to = "WSP-ME",
            timestamp = timestamp,
            nonce = nonce,
            ciphertext = ciphertext,
            privateKey = peerSignPrivateKey
        )

        val payload = CallPayload(
            callId = callId,
            from = from,
            to = "WSP-ME",
            timestamp = timestamp,
            nonce = cryptoProvider.encodeBase64(nonce),
            ciphertext = cryptoProvider.encodeBase64(ciphertext),
            sig = sig
        )

        val json = WsParser.createEnvelope(WsMessageTypes.CALL_RINGING, payload)
        return WsParser.parseRaw(json)
    }

    private fun createAnswerEnvelope(callId: String, from: String, sdpAnswer: String): WsRawEnvelope {
        val timestamp = System.currentTimeMillis()
        val nonce = cryptoProvider.generateNonce()

        val ciphertext = cryptoProvider.seal(
            sdpAnswer.toByteArray(Charsets.UTF_8),
            nonce,
            myEncPublicKey,
            peerEncPrivateKey
        )

        val sig = cryptoProvider.signCanonical(
            messageType = "call_answer",
            messageId = callId,
            from = from,
            to = "WSP-ME",
            timestamp = timestamp,
            nonce = nonce,
            ciphertext = ciphertext,
            privateKey = peerSignPrivateKey
        )

        val payload = CallPayload(
            callId = callId,
            from = from,
            to = "WSP-ME",
            timestamp = timestamp,
            nonce = cryptoProvider.encodeBase64(nonce),
            ciphertext = cryptoProvider.encodeBase64(ciphertext),
            sig = sig
        )

        val json = WsParser.createEnvelope(WsMessageTypes.CALL_ANSWER, payload)
        return WsParser.parseRaw(json)
    }
}
