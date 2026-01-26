package com.whisper2.app.calls

import com.google.gson.Gson
import com.whisper2.app.network.ws.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Gate 1: Call JSON Models - Encode/Decode Contract
 *
 * Tests:
 * - CallPayload serialization/deserialization
 * - TurnCredentialsPayload serialization/deserialization
 * - GetTurnCredentialsPayload serialization
 * - CallEndReason constants
 * - WsMessageTypes constants for calls
 */
class CallModelsTest {

    private val gson = Gson()

    // ==========================================================================
    // Gate 1: CallPayload tests
    // ==========================================================================

    @Test
    fun `gate1 CallPayload serializes all fields`() {
        val payload = CallPayload(
            protocolVersion = 1,
            cryptoVersion = 1,
            sessionToken = "session-123",
            callId = "call-abc",
            from = "WSP-ALICE",
            to = "WSP-BOB",
            isVideo = true,
            timestamp = 1704067200000,
            nonce = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            ciphertext = "encrypted-sdp-data",
            sig = "signature-base64"
        )

        val json = gson.toJson(payload)

        assertTrue(json.contains("\"protocolVersion\":1"))
        assertTrue(json.contains("\"cryptoVersion\":1"))
        assertTrue(json.contains("\"sessionToken\":\"session-123\""))
        assertTrue(json.contains("\"callId\":\"call-abc\""))
        assertTrue(json.contains("\"from\":\"WSP-ALICE\""))
        assertTrue(json.contains("\"to\":\"WSP-BOB\""))
        assertTrue(json.contains("\"isVideo\":true"))
        assertTrue(json.contains("\"timestamp\":1704067200000"))
        assertTrue(json.contains("\"nonce\":"))
        assertTrue(json.contains("\"ciphertext\":"))
        assertTrue(json.contains("\"sig\":"))
    }

    @Test
    fun `gate1 CallPayload deserializes from server JSON`() {
        val json = """
        {
            "callId": "call-xyz",
            "from": "WSP-CALLER",
            "to": "WSP-CALLEE",
            "isVideo": false,
            "timestamp": 1704067200000,
            "nonce": "base64nonce",
            "ciphertext": "base64ciphertext",
            "sig": "base64sig"
        }
        """.trimIndent()

        val payload = gson.fromJson(json, CallPayload::class.java)

        assertEquals("call-xyz", payload.callId)
        assertEquals("WSP-CALLER", payload.from)
        assertEquals("WSP-CALLEE", payload.to)
        assertEquals(false, payload.isVideo)
        assertEquals(1704067200000, payload.timestamp)
        assertEquals("base64nonce", payload.nonce)
        assertEquals("base64ciphertext", payload.ciphertext)
        assertEquals("base64sig", payload.sig)
    }

    @Test
    fun `gate1 CallPayload with reason field`() {
        val payload = CallPayload(
            callId = "call-123",
            from = "WSP-A",
            timestamp = 1000L,
            nonce = "nonce",
            ciphertext = "ct",
            sig = "sig",
            reason = "declined"
        )

        val json = gson.toJson(payload)
        assertTrue(json.contains("\"reason\":\"declined\""))

        val deserialized = gson.fromJson(json, CallPayload::class.java)
        assertEquals("declined", deserialized.reason)
    }

    @Test
    fun `gate1 CallPayload optional fields are null when missing`() {
        val json = """
        {
            "callId": "call-minimal",
            "from": "WSP-FROM",
            "timestamp": 12345,
            "nonce": "n",
            "ciphertext": "c",
            "sig": "s"
        }
        """.trimIndent()

        val payload = gson.fromJson(json, CallPayload::class.java)

        assertEquals("call-minimal", payload.callId)
        assertEquals("WSP-FROM", payload.from)
        assertNull(payload.protocolVersion)
        assertNull(payload.cryptoVersion)
        assertNull(payload.sessionToken)
        assertNull(payload.to)
        assertNull(payload.isVideo)
        assertNull(payload.reason)
    }

    // ==========================================================================
    // Gate 1: TurnCredentialsPayload tests
    // ==========================================================================

    @Test
    fun `gate1 TurnCredentialsPayload deserializes correctly`() {
        val json = """
        {
            "urls": ["turn:turn.example.com:3478", "turns:turn.example.com:5349"],
            "username": "1706286600:WSP-TEST",
            "credential": "secret-credential",
            "ttl": 600
        }
        """.trimIndent()

        val payload = gson.fromJson(json, TurnCredentialsPayload::class.java)

        assertEquals(2, payload.urls.size)
        assertEquals("turn:turn.example.com:3478", payload.urls[0])
        assertEquals("turns:turn.example.com:5349", payload.urls[1])
        assertEquals("1706286600:WSP-TEST", payload.username)
        assertEquals("secret-credential", payload.credential)
        assertEquals(600, payload.ttl)
    }

    @Test
    fun `gate1 TurnCredentialsPayload serializes correctly`() {
        val payload = TurnCredentialsPayload(
            urls = listOf("turn:server:3478"),
            username = "user",
            credential = "pass",
            ttl = 300
        )

        val json = gson.toJson(payload)

        assertTrue(json.contains("\"urls\":[\"turn:server:3478\"]"))
        assertTrue(json.contains("\"username\":\"user\""))
        assertTrue(json.contains("\"credential\":\"pass\""))
        assertTrue(json.contains("\"ttl\":300"))
    }

    // ==========================================================================
    // Gate 1: GetTurnCredentialsPayload tests
    // ==========================================================================

    @Test
    fun `gate1 GetTurnCredentialsPayload serializes with session token`() {
        val payload = GetTurnCredentialsPayload(
            protocolVersion = 1,
            cryptoVersion = 1,
            sessionToken = "my-session-token"
        )

        val json = gson.toJson(payload)

        assertTrue(json.contains("\"protocolVersion\":1"))
        assertTrue(json.contains("\"cryptoVersion\":1"))
        assertTrue(json.contains("\"sessionToken\":\"my-session-token\""))
    }

    // ==========================================================================
    // Gate 1: WsMessageTypes call constants
    // ==========================================================================

    @Test
    fun `gate1 WsMessageTypes has all call types`() {
        assertEquals("get_turn_credentials", WsMessageTypes.GET_TURN_CREDENTIALS)
        assertEquals("turn_credentials", WsMessageTypes.TURN_CREDENTIALS)
        assertEquals("call_initiate", WsMessageTypes.CALL_INITIATE)
        assertEquals("call_incoming", WsMessageTypes.CALL_INCOMING)
        assertEquals("call_answer", WsMessageTypes.CALL_ANSWER)
        assertEquals("call_ice_candidate", WsMessageTypes.CALL_ICE_CANDIDATE)
        assertEquals("call_end", WsMessageTypes.CALL_END)
        assertEquals("call_ringing", WsMessageTypes.CALL_RINGING)
    }

    // ==========================================================================
    // Gate 1: CallEndReason constants
    // ==========================================================================

    @Test
    fun `gate1 CallEndReason has all reason constants`() {
        assertEquals("ended", CallEndReason.ENDED)
        assertEquals("declined", CallEndReason.DECLINED)
        assertEquals("busy", CallEndReason.BUSY)
        assertEquals("timeout", CallEndReason.TIMEOUT)
        assertEquals("failed", CallEndReason.FAILED)
    }

    // ==========================================================================
    // Gate 1: WsParser with call payloads
    // ==========================================================================

    @Test
    fun `gate1 WsParser creates valid call_initiate envelope`() {
        val payload = CallPayload(
            protocolVersion = 1,
            cryptoVersion = 1,
            sessionToken = "sess",
            callId = "call-1",
            from = "WSP-A",
            to = "WSP-B",
            isVideo = true,
            timestamp = 1000L,
            nonce = "nonce",
            ciphertext = "ct",
            sig = "sig"
        )

        val envelope = WsParser.createEnvelope(WsMessageTypes.CALL_INITIATE, payload)

        assertTrue(envelope.contains("\"type\":\"call_initiate\""))
        assertTrue(envelope.contains("\"payload\":{"))
        assertTrue(envelope.contains("\"callId\":\"call-1\""))
    }

    @Test
    fun `gate1 WsParser creates envelope with requestId for TURN`() {
        val payload = GetTurnCredentialsPayload(sessionToken = "token")
        val requestId = "req-123"

        val envelope = WsParser.createEnvelope(WsMessageTypes.GET_TURN_CREDENTIALS, payload, requestId)

        assertTrue(envelope.contains("\"type\":\"get_turn_credentials\""))
        assertTrue(envelope.contains("\"requestId\":\"req-123\""))
    }

    @Test
    fun `gate1 WsParser parseRaw extracts type and payload`() {
        val json = """
        {
            "type": "call_incoming",
            "payload": {
                "callId": "c1",
                "from": "WSP-X",
                "timestamp": 123,
                "nonce": "n",
                "ciphertext": "c",
                "sig": "s"
            }
        }
        """.trimIndent()

        val raw = WsParser.parseRaw(json)

        assertEquals("call_incoming", raw.type)
        assertNotNull(raw.payload)
    }

    @Test
    fun `gate1 WsParser parsePayload extracts CallPayload`() {
        val json = """
        {
            "type": "call_incoming",
            "payload": {
                "callId": "c1",
                "from": "WSP-X",
                "timestamp": 123,
                "nonce": "n",
                "ciphertext": "c",
                "sig": "s",
                "isVideo": true
            }
        }
        """.trimIndent()

        val raw = WsParser.parseRaw(json)
        val payload = WsParser.parsePayload<CallPayload>(raw.payload)

        assertNotNull(payload)
        assertEquals("c1", payload!!.callId)
        assertEquals("WSP-X", payload.from)
        assertEquals(123L, payload.timestamp)
        assertEquals(true, payload.isVideo)
    }

    // ==========================================================================
    // Gate 1: Roundtrip tests
    // ==========================================================================

    @Test
    fun `gate1 CallPayload full roundtrip`() {
        val original = CallPayload(
            protocolVersion = 1,
            cryptoVersion = 1,
            sessionToken = "session",
            callId = "call-roundtrip",
            from = "WSP-SENDER",
            to = "WSP-RECEIVER",
            isVideo = false,
            timestamp = System.currentTimeMillis(),
            nonce = CallTestCrypto.encode(ByteArray(24) { it.toByte() }),
            ciphertext = CallTestCrypto.encode("encrypted-data".toByteArray()),
            sig = CallTestCrypto.encode(ByteArray(64) { (it * 2).toByte() }),
            reason = null
        )

        val json = gson.toJson(original)
        val restored = gson.fromJson(json, CallPayload::class.java)

        assertEquals(original.callId, restored.callId)
        assertEquals(original.from, restored.from)
        assertEquals(original.to, restored.to)
        assertEquals(original.isVideo, restored.isVideo)
        assertEquals(original.timestamp, restored.timestamp)
        assertEquals(original.nonce, restored.nonce)
        assertEquals(original.ciphertext, restored.ciphertext)
        assertEquals(original.sig, restored.sig)
    }

    @Test
    fun `gate1 TurnCredentialsPayload full roundtrip`() {
        val original = TurnCredentialsPayload(
            urls = listOf("turn:a:3478", "turns:b:5349", "turn:c:3478?transport=tcp"),
            username = "1234567890:WSP-USER",
            credential = "base64credential",
            ttl = 86400
        )

        val json = gson.toJson(original)
        val restored = gson.fromJson(json, TurnCredentialsPayload::class.java)

        assertEquals(original.urls, restored.urls)
        assertEquals(original.username, restored.username)
        assertEquals(original.credential, restored.credential)
        assertEquals(original.ttl, restored.ttl)
    }
}
