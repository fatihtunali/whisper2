package com.whisper2.app.calls

import com.whisper2.app.network.ws.*
import com.whisper2.app.services.calls.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Gate 4: ICE Candidate Buffering
 *
 * Tests:
 * - ICE candidates before remote description are buffered
 * - Buffered candidates are flushed after remote description is set
 * - ICE candidates after remote description are applied immediately
 * - Buffer is cleared on call end
 */
class IceBufferingTest {

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
    // Gate 4: ICE buffering before remote description
    // ==========================================================================

    @Test
    fun `gate4 ICE candidate before remote description is buffered`() = runTest {
        // Start outgoing call
        callService.initiateCall("WSP-PEER", false)
        val callId = callService.currentCallId!!

        // Ensure remote description not set
        assertFalse(webRtcService.hasRemoteDescription())
        assertEquals(0, webRtcService.addIceCandidateCount)

        // Receive ICE candidate before answer
        val iceEnvelope = createIceCandidateEnvelope(callId, "WSP-PEER", "candidate:1 1 UDP 2130706431 192.168.1.1 50000 typ host")
        callService.onWsMessage(iceEnvelope)

        // Wait a bit
        Thread.sleep(50)

        // ICE candidate should NOT be added yet (buffered)
        assertEquals(
            "ICE candidate should be buffered when remote description not set",
            0,
            webRtcService.addIceCandidateCount
        )
    }

    @Test
    fun `gate4 multiple ICE candidates buffered`() = runTest {
        callService.initiateCall("WSP-PEER", false)
        val callId = callService.currentCallId!!

        // Receive multiple ICE candidates
        for (i in 1..5) {
            val iceEnvelope = createIceCandidateEnvelope(
                callId,
                "WSP-PEER",
                "candidate:$i 1 UDP 2130706431 192.168.1.$i 5000$i typ host"
            )
            callService.onWsMessage(iceEnvelope)
        }

        Thread.sleep(50)

        // None should be added yet
        assertEquals(
            "All ICE candidates should be buffered",
            0,
            webRtcService.addIceCandidateCount
        )
    }

    // ==========================================================================
    // Gate 4: ICE buffer flushed after remote description
    // ==========================================================================

    @Test
    fun `gate4 buffered ICE candidates flushed after remote description`() = runTest {
        callService.initiateCall("WSP-PEER", false)
        val callId = callService.currentCallId!!

        // Receive ICE candidates before answer
        for (i in 1..3) {
            val iceEnvelope = createIceCandidateEnvelope(
                callId,
                "WSP-PEER",
                "candidate:$i 1 UDP 2130706431 192.168.1.$i 5000$i typ host"
            )
            callService.onWsMessage(iceEnvelope)
        }

        assertEquals(0, webRtcService.addIceCandidateCount)

        // Now receive answer (sets remote description)
        val answerEnvelope = createAnswerEnvelope(callId, "WSP-PEER", "v=0\r\no=answer\r\n")
        callService.onWsMessage(answerEnvelope)

        // Wait for flush
        Thread.sleep(100)

        // All 3 buffered candidates should now be added
        assertEquals(
            "All buffered ICE candidates should be flushed after remote description",
            3,
            webRtcService.addIceCandidateCount
        )
    }

    // ==========================================================================
    // Gate 4: ICE candidates after remote description applied immediately
    // ==========================================================================

    @Test
    fun `gate4 ICE candidate after remote description applied immediately`() = runTest {
        callService.initiateCall("WSP-PEER", false)
        val callId = callService.currentCallId!!

        // First receive answer
        val answerEnvelope = createAnswerEnvelope(callId, "WSP-PEER", "v=0\r\no=answer\r\n")
        callService.onWsMessage(answerEnvelope)

        Thread.sleep(50)
        assertTrue(webRtcService.hasRemoteDescription())
        assertEquals(0, webRtcService.addIceCandidateCount)

        // Now receive ICE candidate
        val iceEnvelope = createIceCandidateEnvelope(
            callId,
            "WSP-PEER",
            "candidate:1 1 UDP 2130706431 192.168.1.1 50000 typ host"
        )
        callService.onWsMessage(iceEnvelope)

        Thread.sleep(50)

        // Should be applied immediately
        assertEquals(
            "ICE candidate should be applied immediately when remote description is set",
            1,
            webRtcService.addIceCandidateCount
        )
    }

    @Test
    fun `gate4 multiple ICE candidates after remote description all applied`() = runTest {
        callService.initiateCall("WSP-PEER", false)
        val callId = callService.currentCallId!!

        // First receive answer
        val answerEnvelope = createAnswerEnvelope(callId, "WSP-PEER", "v=0\r\no=answer\r\n")
        callService.onWsMessage(answerEnvelope)

        Thread.sleep(50)

        // Now receive multiple ICE candidates
        for (i in 1..4) {
            val iceEnvelope = createIceCandidateEnvelope(
                callId,
                "WSP-PEER",
                "candidate:$i 1 UDP 2130706431 192.168.1.$i 5000$i typ host"
            )
            callService.onWsMessage(iceEnvelope)
            Thread.sleep(20)
        }

        Thread.sleep(50)

        // All should be applied
        assertEquals(4, webRtcService.addIceCandidateCount)
    }

    // ==========================================================================
    // Gate 4: Mixed buffered and immediate ICE
    // ==========================================================================

    @Test
    fun `gate4 mixed buffered and immediate ICE candidates`() = runTest {
        callService.initiateCall("WSP-PEER", false)
        val callId = callService.currentCallId!!

        // 2 candidates before answer (buffered)
        for (i in 1..2) {
            val iceEnvelope = createIceCandidateEnvelope(
                callId,
                "WSP-PEER",
                "candidate:pre$i"
            )
            callService.onWsMessage(iceEnvelope)
        }

        assertEquals(0, webRtcService.addIceCandidateCount)

        // Receive answer
        val answerEnvelope = createAnswerEnvelope(callId, "WSP-PEER", "v=0\r\no=answer\r\n")
        callService.onWsMessage(answerEnvelope)

        Thread.sleep(100)

        // 2 buffered should be flushed
        assertEquals(2, webRtcService.addIceCandidateCount)

        // 3 more candidates after answer (immediate)
        for (i in 1..3) {
            val iceEnvelope = createIceCandidateEnvelope(
                callId,
                "WSP-PEER",
                "candidate:post$i"
            )
            callService.onWsMessage(iceEnvelope)
            Thread.sleep(20)
        }

        Thread.sleep(50)

        // Total: 2 buffered + 3 immediate = 5
        assertEquals(5, webRtcService.addIceCandidateCount)
    }

    // ==========================================================================
    // Gate 4: ICE for wrong call ignored
    // ==========================================================================

    @Test
    fun `gate4 ICE candidate for wrong callId ignored`() = runTest {
        callService.initiateCall("WSP-PEER", false)

        val iceEnvelope = createIceCandidateEnvelope(
            "wrong-call-id",
            "WSP-PEER",
            "candidate:1"
        )
        callService.onWsMessage(iceEnvelope)

        Thread.sleep(50)

        // Should not be buffered or added
        assertEquals(0, webRtcService.addIceCandidateCount)
    }

    // ==========================================================================
    // Gate 4: Buffer cleared on call end
    // ==========================================================================

    @Test
    fun `gate4 buffer cleared on call end`() = runTest {
        callService.initiateCall("WSP-PEER", false)
        val callId = callService.currentCallId!!

        // Buffer some candidates
        for (i in 1..3) {
            val iceEnvelope = createIceCandidateEnvelope(
                callId,
                "WSP-PEER",
                "candidate:$i"
            )
            callService.onWsMessage(iceEnvelope)
        }

        // End call (clears buffer)
        callService.endCall()

        Thread.sleep(100)

        // Buffered candidates should NOT have been added
        assertEquals(0, webRtcService.addIceCandidateCount)
    }

    // ==========================================================================
    // Gate 4: ICE signature validation
    // ==========================================================================

    @Test
    fun `gate4 ICE candidate with invalid nonce rejected`() = runTest {
        callService.initiateCall("WSP-PEER", false)
        val callId = callService.currentCallId!!

        // Set remote description first
        val answerEnvelope = createAnswerEnvelope(callId, "WSP-PEER", "v=0\r\no=answer\r\n")
        callService.onWsMessage(answerEnvelope)
        Thread.sleep(50)

        // Create ICE with wrong nonce length
        val timestamp = System.currentTimeMillis()
        val wrongNonce = ByteArray(12) // Should be 24

        val payload = CallPayload(
            callId = callId,
            from = "WSP-PEER",
            to = "WSP-ME",
            timestamp = timestamp,
            nonce = cryptoProvider.encodeBase64(wrongNonce),
            ciphertext = cryptoProvider.encodeBase64("candidate:bad".toByteArray()),
            sig = cryptoProvider.encodeBase64(ByteArray(64))
        )

        val json = WsParser.createEnvelope(WsMessageTypes.CALL_ICE_CANDIDATE, payload)
        val iceEnvelope = WsParser.parseRaw(json)
        callService.onWsMessage(iceEnvelope)

        Thread.sleep(50)

        // Should not be added (invalid nonce)
        assertEquals(0, webRtcService.addIceCandidateCount)
    }

    // ==========================================================================
    // Helper methods
    // ==========================================================================

    private fun createIceCandidateEnvelope(
        callId: String,
        from: String,
        candidate: String
    ): WsRawEnvelope {
        val timestamp = System.currentTimeMillis()
        val nonce = cryptoProvider.generateNonce()

        val ciphertext = cryptoProvider.seal(
            candidate.toByteArray(Charsets.UTF_8),
            nonce,
            myEncPublicKey,
            peerEncPrivateKey
        )

        val sig = cryptoProvider.signCanonical(
            messageType = "call_ice_candidate",
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

        val json = WsParser.createEnvelope(WsMessageTypes.CALL_ICE_CANDIDATE, payload)
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
