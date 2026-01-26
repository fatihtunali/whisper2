package com.whisper2.app.auth

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.whisper2.app.network.ws.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Gate 1: WS Models JSON decode/encode tests
 */
class WsModelsTest {

    private val gson = Gson()

    // ==========================================================================
    // Gate 1: register_challenge decode
    // ==========================================================================

    @Test
    fun `register_challenge decodes correctly`() {
        val json = """
            {
                "type": "register_challenge",
                "payload": {
                    "challengeId": "c12345-uuid-here",
                    "challenge": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                    "expiresAt": 1700000060000
                }
            }
        """.trimIndent()

        val envelope = WsParser.parseRaw(json)

        assertEquals("register_challenge", envelope.type)
        assertNotNull(envelope.payload)

        val payload = WsParser.parsePayload<RegisterChallengePayload>(envelope.payload)
        assertNotNull(payload)
        assertEquals("c12345-uuid-here", payload!!.challengeId)
        assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", payload.challenge)
        assertEquals(1700000060000L, payload.expiresAt)
    }

    @Test
    fun `register_ack success decodes correctly`() {
        val json = """
            {
                "type": "register_ack",
                "payload": {
                    "success": true,
                    "whisperId": "WSP-ABCD-EFGH-IJKL",
                    "sessionToken": "sess_opaque_token_here",
                    "sessionExpiresAt": 1700086400000,
                    "serverTime": 1700000000000
                }
            }
        """.trimIndent()

        val envelope = WsParser.parseRaw(json)

        assertEquals("register_ack", envelope.type)
        assertNotNull(envelope.payload)

        val payload = WsParser.parsePayload<RegisterAckPayload>(envelope.payload)
        assertNotNull(payload)
        assertTrue(payload!!.success)
        assertEquals("WSP-ABCD-EFGH-IJKL", payload.whisperId)
        assertEquals("sess_opaque_token_here", payload.sessionToken)
        assertEquals(1700086400000L, payload.sessionExpiresAt)
        assertEquals(1700000000000L, payload.serverTime)
    }

    @Test
    fun `register_ack failure decodes correctly`() {
        val json = """
            {
                "type": "register_ack",
                "payload": {
                    "success": false,
                    "whisperId": null,
                    "sessionToken": null,
                    "sessionExpiresAt": null,
                    "serverTime": null
                }
            }
        """.trimIndent()

        val envelope = WsParser.parseRaw(json)
        val payload = WsParser.parsePayload<RegisterAckPayload>(envelope.payload)

        assertNotNull(payload)
        assertFalse(payload!!.success)
        assertNull(payload.whisperId)
        assertNull(payload.sessionToken)
    }

    @Test
    fun `error payload decodes correctly`() {
        val json = """
            {
                "type": "error",
                "payload": {
                    "code": "AUTH_FAILED",
                    "message": "Session terminated: new_session",
                    "requestId": "660e8400-e29b-41d4-a716-446655440001"
                }
            }
        """.trimIndent()

        val envelope = WsParser.parseRaw(json)

        assertEquals("error", envelope.type)
        assertNotNull(envelope.payload)

        val payload = WsParser.parsePayload<ErrorPayload>(envelope.payload)
        assertNotNull(payload)
        assertEquals("AUTH_FAILED", payload!!.code)
        assertEquals("Session terminated: new_session", payload.message)
        assertEquals("660e8400-e29b-41d4-a716-446655440001", payload.requestId)
    }

    @Test
    fun `error payload without requestId decodes correctly`() {
        val json = """
            {
                "type": "error",
                "payload": {
                    "code": "RATE_LIMITED",
                    "message": "Too many requests"
                }
            }
        """.trimIndent()

        val envelope = WsParser.parseRaw(json)
        val payload = WsParser.parsePayload<ErrorPayload>(envelope.payload)

        assertNotNull(payload)
        assertEquals("RATE_LIMITED", payload!!.code)
        assertNull(payload.requestId)
    }

    // ==========================================================================
    // Gate 1: register_begin encode
    // ==========================================================================

    @Test
    fun `register_begin encodes correctly`() {
        val payload = RegisterBeginPayload(
            protocolVersion = 1,
            cryptoVersion = 1,
            deviceId = "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
            platform = "android"
        )

        val json = WsParser.createEnvelope(
            type = WsMessageTypes.REGISTER_BEGIN,
            payload = payload,
            requestId = "550e8400-e29b-41d4-a716-446655440000"
        )

        val parsed = JsonParser.parseString(json).asJsonObject

        assertEquals("register_begin", parsed.get("type").asString)
        assertEquals("550e8400-e29b-41d4-a716-446655440000", parsed.get("requestId").asString)

        val payloadObj = parsed.getAsJsonObject("payload")
        assertEquals(1, payloadObj.get("protocolVersion").asInt)
        assertEquals(1, payloadObj.get("cryptoVersion").asInt)
        assertEquals("a1b2c3d4-e5f6-7890-abcd-ef1234567890", payloadObj.get("deviceId").asString)
        assertEquals("android", payloadObj.get("platform").asString)
    }

    @Test
    fun `register_proof encodes correctly`() {
        val payload = RegisterProofPayload(
            protocolVersion = 1,
            cryptoVersion = 1,
            challengeId = "c12345-uuid-here",
            deviceId = "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
            platform = "android",
            encPublicKey = "GcbQ+YaCf46Gpe1KIz8u0clzVVNtDSbErjsrkINpwFA=",
            signPublicKey = "vZMMu/hWu3bywlxkBiopRMxgZbpUua9yQtK/veXXyVs=",
            signature = "dGVzdF9zaWduYXR1cmU=",
            pushToken = "fcm_token_123"
        )

        val json = WsParser.createEnvelope(
            type = WsMessageTypes.REGISTER_PROOF,
            payload = payload,
            requestId = "660e8400-e29b-41d4-a716-446655440001"
        )

        val parsed = JsonParser.parseString(json).asJsonObject

        assertEquals("register_proof", parsed.get("type").asString)

        val payloadObj = parsed.getAsJsonObject("payload")
        assertEquals("c12345-uuid-here", payloadObj.get("challengeId").asString)
        assertEquals("GcbQ+YaCf46Gpe1KIz8u0clzVVNtDSbErjsrkINpwFA=", payloadObj.get("encPublicKey").asString)
        assertEquals("vZMMu/hWu3bywlxkBiopRMxgZbpUua9yQtK/veXXyVs=", payloadObj.get("signPublicKey").asString)
        assertEquals("dGVzdF9zaWduYXR1cmU=", payloadObj.get("signature").asString)
        assertEquals("fcm_token_123", payloadObj.get("pushToken").asString)
    }

    // ==========================================================================
    // Gate 1: Invalid/malformed payloads
    // ==========================================================================

    @Test
    fun `missing type field returns null type`() {
        val json = """{"payload": {}}"""

        val envelope = WsParser.parseRaw(json)
        assertNull(envelope.type)
    }

    @Test
    fun `missing payload field returns null payload`() {
        val json = """{"type": "register_challenge"}"""

        val envelope = WsParser.parseRaw(json)
        assertEquals("register_challenge", envelope.type)
        assertNull(envelope.payload)
    }

    @Test
    fun `empty JSON object parses without crash`() {
        val json = """{}"""

        val envelope = WsParser.parseRaw(json)
        assertNull(envelope.type)
        assertNull(envelope.payload)
    }

    @Test
    fun `parsePayload returns null for wrong type`() {
        val json = """
            {
                "type": "error",
                "payload": {
                    "code": "AUTH_FAILED",
                    "message": "test"
                }
            }
        """.trimIndent()

        val envelope = WsParser.parseRaw(json)

        // Try to parse as RegisterAckPayload - should work but have null fields
        val wrongPayload = WsParser.parsePayload<RegisterAckPayload>(envelope.payload)
        // Gson doesn't throw on wrong type, it just has null/default values
        assertNotNull(wrongPayload)
        assertFalse(wrongPayload!!.success) // default for Boolean
    }

    // ==========================================================================
    // Message type constants
    // ==========================================================================

    @Test
    fun `message type constants match protocol`() {
        assertEquals("register_begin", WsMessageTypes.REGISTER_BEGIN)
        assertEquals("register_challenge", WsMessageTypes.REGISTER_CHALLENGE)
        assertEquals("register_proof", WsMessageTypes.REGISTER_PROOF)
        assertEquals("register_ack", WsMessageTypes.REGISTER_ACK)
        assertEquals("error", WsMessageTypes.ERROR)
        assertEquals("ping", WsMessageTypes.PING)
        assertEquals("pong", WsMessageTypes.PONG)
    }

    @Test
    fun `error code constants match protocol`() {
        assertEquals("AUTH_FAILED", WsErrorCodes.AUTH_FAILED)
        assertEquals("INVALID_PAYLOAD", WsErrorCodes.INVALID_PAYLOAD)
        assertEquals("RATE_LIMITED", WsErrorCodes.RATE_LIMITED)
        assertEquals("NOT_REGISTERED", WsErrorCodes.NOT_REGISTERED)
    }
}
