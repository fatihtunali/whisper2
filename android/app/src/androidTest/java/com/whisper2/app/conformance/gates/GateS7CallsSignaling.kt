package com.whisper2.app.conformance.gates

import android.util.Base64
import com.whisper2.app.conformance.*
import com.whisper2.app.crypto.Signatures
import kotlinx.coroutines.delay
import java.security.MessageDigest
import java.util.UUID

/**
 * Gate S7: Calls Signaling + TURN (WS + HTTP)
 *
 * Flow:
 * 1. A requests TURN credentials
 * 2. A initiates call to B
 * 3. B receives call offer
 * 4. B answers call
 * 5. A receives answer
 * 6. Exchange ICE candidates (simulated)
 * 7. End call
 *
 * PASS criteria:
 * - TURN credentials returned with valid TTL
 * - Call signaling works both ways
 * - Call can be ended cleanly
 */
class GateS7CallsSignaling(
    private val identityA: TestIdentity,
    private val identityB: TestIdentity
) {
    private val startTime = System.currentTimeMillis()
    private val http = ConformanceHttpClient()

    suspend fun run(): GateResult {
        ConformanceLogger.gate("S7", "Starting calls signaling test")

        try {
            identityA.requireRegistered()
            identityB.requireRegistered()

            // Test TURN credentials
            val turnResult = testTurnCredentials()
            if (!turnResult.passed) return turnResult

            // Test call signaling
            val signalingResult = testCallSignaling()
            if (!signalingResult.passed) return signalingResult

            val duration = System.currentTimeMillis() - startTime
            return GateResult.pass(
                name = "S7-CallsSignaling",
                details = "TURN credentials and call signaling passed",
                durationMs = duration
            )

        } catch (e: Exception) {
            return GateResult.fail(
                name = "S7-CallsSignaling",
                reason = "Calls signaling test failed: ${e.message}",
                durationMs = System.currentTimeMillis() - startTime,
                error = e
            )
        }
    }

    private suspend fun testTurnCredentials(): GateResult {
        ConformanceLogger.gate("S7", "Testing TURN credentials...")

        // Create signed request for TURN credentials
        val timestamp = System.currentTimeMillis()
        val requestData = mapOf(
            "whisperId" to identityA.whisperId,
            "timestamp" to timestamp
        )

        // Sign the request
        val dataToSign = "${identityA.whisperId}:$timestamp:GET:/turn/credentials"
        val signatureB64 = try {
            val signature = Signatures.sign(dataToSign.toByteArray(), identityA.signPrivateKey)
            Base64.encodeToString(signature, Base64.NO_WRAP)
        } catch (e: Exception) {
            ConformanceLogger.gate("S7", "Signing failed: ${e.message}")
            ""
        }

        val response = http.get(
            path = "/turn/credentials",
            sessionToken = identityA.sessionToken,
            headers = mapOf(
                "X-Whisper-Id" to identityA.whisperId!!,
                "X-Timestamp" to timestamp.toString(),
                "X-Signature" to signatureB64
            )
        )

        if (!response.isSuccess) {
            return fail("TURN credentials request failed: ${response.statusCode} - ${response.body}")
        }

        val json = response.json
            ?: return fail("No JSON in TURN credentials response")

        // Validate response structure
        val urls = json.getAsJsonArray("urls")
        if (urls == null || urls.size() == 0) {
            return fail("No TURN/STUN URLs in response")
        }

        val username = json.get("username")?.asString
        val credential = json.get("credential")?.asString
        val ttl = json.get("ttl")?.asInt

        ConformanceLogger.gate("S7", "TURN credentials received:")
        ConformanceLogger.gate("S7", "  URLs: ${urls.size()} server(s)")
        ConformanceLogger.gate("S7", "  Username: ${username?.take(20)}...")
        ConformanceLogger.gate("S7", "  TTL: $ttl seconds")

        if (username.isNullOrEmpty()) {
            return fail("No username in TURN credentials")
        }

        if (credential.isNullOrEmpty()) {
            return fail("No credential in TURN credentials")
        }

        if (ttl == null || ttl <= 0) {
            return fail("Invalid TTL in TURN credentials: $ttl")
        }

        // Verify URLs contain both STUN and TURN
        val urlStrings = (0 until urls.size()).map { urls[it].asString }
        val hasStun = urlStrings.any { it.startsWith("stun:") }
        val hasTurn = urlStrings.any { it.startsWith("turn:") }

        ConformanceLogger.gate("S7", "  Has STUN: $hasStun, Has TURN: $hasTurn")

        if (!hasStun) {
            ConformanceLogger.gate("S7", "Warning: No STUN server in credentials")
        }

        if (!hasTurn) {
            ConformanceLogger.gate("S7", "Warning: No TURN server in credentials")
        }

        return GateResult.pass(
            name = "S7-TURN",
            details = "TURN credentials valid (${urls.size()} servers, TTL=$ttl)",
            durationMs = response.durationMs,
            artifacts = mapOf(
                "serverCount" to urls.size(),
                "ttl" to (ttl ?: 0),
                "hasStun" to hasStun,
                "hasTurn" to hasTurn
            )
        )
    }

    private suspend fun testCallSignaling(): GateResult {
        ConformanceLogger.gate("S7", "Testing call signaling...")

        val wsA = ConformanceWsClient()
        val wsB = ConformanceWsClient()

        try {
            // Connect both
            if (!wsA.connect()) {
                return fail("User A: WebSocket connection failed")
            }
            if (!wsB.connect()) {
                return fail("User B: WebSocket connection failed")
            }

            // Authenticate both
            val authA = authenticate(wsA, identityA)
            if (!authA.passed) return authA

            val authB = authenticate(wsB, identityB)
            if (!authB.passed) return authB

            // A initiates call to B
            val callId = UUID.randomUUID().toString()
            val offerSdp = "v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\na=group:BUNDLE audio\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\nc=IN IP4 0.0.0.0\r\na=rtcp:9 IN IP4 0.0.0.0\r\na=ice-ufrag:test\r\na=ice-pwd:testpassword123456789012\r\na=fingerprint:sha-256 AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99\r\na=setup:actpass\r\na=mid:audio\r\na=sendrecv\r\na=rtpmap:111 opus/48000/2\r\n"

            // Sign the call offer
            val timestamp = System.currentTimeMillis()
            val offerSignature = try {
                val dataToSign = "$callId:${identityA.whisperId}:${identityB.whisperId}:$timestamp:offer"
                Signatures.signBase64(dataToSign.toByteArray(), identityA.signPrivateKey)
            } catch (e: Exception) {
                ConformanceLogger.gate("S7", "Offer signing failed: ${e.message}")
                ""
            }

            ConformanceLogger.gate("S7", "A initiating call to B (callId: ${callId.take(8)}...)")

            wsA.send("call_offer", mapOf(
                "callId" to callId,
                "recipientId" to identityB.whisperId,
                "sdp" to offerSdp,
                "timestamp" to timestamp,
                "signature" to offerSignature
            ))

            // B waits for call offer
            ConformanceLogger.gate("S7", "B waiting for call offer...")
            val offerMsg = wsB.waitForMessage(
                "call_incoming",
                ConformanceConfig.Timeout.MESSAGE_DELIVERY
            )

            val offerPayload = offerMsg.getAsJsonObject("payload")
            val receivedCallId = offerPayload.get("callId")?.asString

            if (receivedCallId != callId) {
                return fail("Call ID mismatch: expected $callId, got $receivedCallId")
            }

            ConformanceLogger.gate("S7", "B received call offer")

            // B answers the call
            val answerSdp = "v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\na=group:BUNDLE audio\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\nc=IN IP4 0.0.0.0\r\na=rtcp:9 IN IP4 0.0.0.0\r\na=ice-ufrag:test2\r\na=ice-pwd:testpassword123456789012\r\na=fingerprint:sha-256 11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00\r\na=setup:active\r\na=mid:audio\r\na=sendrecv\r\na=rtpmap:111 opus/48000/2\r\n"

            val answerTimestamp = System.currentTimeMillis()
            val answerSignature = try {
                val dataToSign = "$callId:${identityB.whisperId}:${identityA.whisperId}:$answerTimestamp:answer"
                Signatures.signBase64(dataToSign.toByteArray(), identityB.signPrivateKey)
            } catch (e: Exception) {
                ""
            }

            ConformanceLogger.gate("S7", "B sending call answer...")

            wsB.send("call_answer", mapOf(
                "callId" to callId,
                "recipientId" to identityA.whisperId,
                "sdp" to answerSdp,
                "timestamp" to answerTimestamp,
                "signature" to answerSignature
            ))

            // A waits for answer
            ConformanceLogger.gate("S7", "A waiting for call answer...")
            val answerMsg = wsA.waitForMessage(
                "call_answered",
                ConformanceConfig.Timeout.MESSAGE_DELIVERY
            )

            val answerPayload = answerMsg.getAsJsonObject("payload")
            val answeredCallId = answerPayload.get("callId")?.asString

            if (answeredCallId != callId) {
                return fail("Answer call ID mismatch")
            }

            ConformanceLogger.gate("S7", "A received call answer")

            // End the call
            ConformanceLogger.gate("S7", "A ending call...")
            wsA.send("call_end", mapOf(
                "callId" to callId,
                "recipientId" to identityB.whisperId,
                "reason" to "test_complete"
            ))

            // B should receive call_ended
            ConformanceLogger.gate("S7", "B waiting for call_ended...")
            val endMsg = wsB.waitForMessage(
                "call_ended",
                ConformanceConfig.Timeout.MESSAGE_DELIVERY
            )

            val endPayload = endMsg.getAsJsonObject("payload")
            val endedCallId = endPayload.get("callId")?.asString

            if (endedCallId != callId) {
                return fail("End call ID mismatch")
            }

            ConformanceLogger.gate("S7", "Call signaling test complete")

            return GateResult.pass(
                name = "S7-CallSignaling",
                details = "Full call signaling cycle passed",
                durationMs = System.currentTimeMillis() - startTime,
                artifacts = mapOf(
                    "callId" to callId
                )
            )

        } finally {
            wsA.disconnect()
            wsB.disconnect()
        }
    }

    private suspend fun authenticate(ws: ConformanceWsClient, identity: TestIdentity): GateResult {
        ConformanceLogger.gate("S7", "${identity.name}: Authenticating...")

        ws.send("auth_begin", mapOf(
            "whisperId" to identity.whisperId,
            "deviceId" to identity.deviceId
        ))

        val challengeMsg = ws.waitForMessage(
            "auth_challenge",
            ConformanceConfig.Timeout.WS_MESSAGE
        )

        val challengePayload = challengeMsg.getAsJsonObject("payload")
        val challengeB64 = challengePayload.get("challenge")?.asString
            ?: return fail("${identity.name}: No challenge")

        val challenge = Base64.decode(challengeB64, Base64.NO_WRAP)
        val challengeHash = MessageDigest.getInstance("SHA-256").digest(challenge)
        val signature = Signatures.sign(challengeHash, identity.signPrivateKey)

        ws.send("auth_proof", mapOf(
            "signature" to Base64.encodeToString(signature, Base64.NO_WRAP)
        ))

        val ackMsg = ws.waitForMessage(
            "auth_ack",
            ConformanceConfig.Timeout.WS_MESSAGE
        )

        val success = ackMsg.getAsJsonObject("payload").get("success")?.asBoolean ?: false
        if (!success) {
            return fail("${identity.name}: Auth failed")
        }

        return GateResult.pass("S7-Auth", "OK", 0)
    }

    private fun fail(reason: String): GateResult {
        return GateResult.fail(
            name = "S7-CallsSignaling",
            reason = reason,
            durationMs = System.currentTimeMillis() - startTime
        )
    }
}
