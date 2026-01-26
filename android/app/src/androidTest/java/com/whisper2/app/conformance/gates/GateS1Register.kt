package com.whisper2.app.conformance.gates

import android.util.Base64
import com.google.gson.JsonObject
import com.whisper2.app.conformance.*
import com.whisper2.app.crypto.Signatures
import kotlinx.coroutines.delay
import java.security.MessageDigest

/**
 * Gate S1: Register (WS)
 *
 * Flow:
 * 1. register_begin
 * 2. register_challenge
 * 3. register_proof
 * 4. register_ack
 *
 * PASS criteria:
 * - success = true
 * - whisperId set
 * - sessionToken set
 */
class GateS1Register(
    private val identityA: TestIdentity,
    private val identityB: TestIdentity
) {
    private val startTime = System.currentTimeMillis()

    suspend fun run(): GateResult {
        ConformanceLogger.gate("S1", "Starting registration for both identities")

        try {
            // Register Identity A
            val resultA = registerIdentity(identityA)
            if (!resultA.passed) return resultA

            // Small delay between registrations
            delay(500)

            // Register Identity B
            val resultB = registerIdentity(identityB)
            if (!resultB.passed) return resultB

            val duration = System.currentTimeMillis() - startTime
            return GateResult.pass(
                name = "S1-Register",
                details = "Both identities registered. A=${identityA.whisperId}, B=${identityB.whisperId}",
                durationMs = duration,
                artifacts = mapOf(
                    "whisperIdA" to (identityA.whisperId ?: ""),
                    "whisperIdB" to (identityB.whisperId ?: ""),
                    "sessionTokenASet" to (identityA.sessionToken != null),
                    "sessionTokenBSet" to (identityB.sessionToken != null)
                )
            )

        } catch (e: Exception) {
            return GateResult.fail(
                name = "S1-Register",
                reason = "Registration failed: ${e.message}",
                durationMs = System.currentTimeMillis() - startTime,
                error = e
            )
        }
    }

    private suspend fun registerIdentity(identity: TestIdentity): GateResult {
        ConformanceLogger.gate("S1", "Registering ${identity.name}...")

        val ws = ConformanceWsClient()
        try {
            // Connect
            if (!ws.connect()) {
                return fail("${identity.name}: WebSocket connection failed")
            }

            // Step 1: register_begin
            ConformanceLogger.gate("S1", "${identity.name}: Sending register_begin")
            val beginPayload = mapOf(
                "protocolVersion" to 1,
                "cryptoVersion" to 1,
                "deviceId" to identity.deviceId,
                "encPublicKey" to Base64.encodeToString(identity.encPublicKey, Base64.NO_WRAP),
                "signPublicKey" to Base64.encodeToString(identity.signPublicKey, Base64.NO_WRAP)
            )

            ws.send("register_begin", beginPayload)

            // Step 2: Wait for register_challenge
            ConformanceLogger.gate("S1", "${identity.name}: Waiting for register_challenge")
            val challengeMsg = ws.waitForMessage(
                "register_challenge",
                ConformanceConfig.Timeout.REGISTRATION
            )

            val challengePayload = challengeMsg.getAsJsonObject("payload")
            val challengeB64 = challengePayload.get("challenge").asString
            val challenge = Base64.decode(challengeB64, Base64.NO_WRAP)

            if (challenge.size != 32) {
                return fail("${identity.name}: Invalid challenge size: ${challenge.size}")
            }

            ConformanceLogger.gate("S1", "${identity.name}: Received challenge (${challenge.size} bytes)")

            // Step 3: Sign challenge and send register_proof
            ConformanceLogger.gate("S1", "${identity.name}: Signing challenge and sending register_proof")

            // Hash the challenge with SHA256, then sign
            val challengeHash = MessageDigest.getInstance("SHA-256").digest(challenge)
            val signature = Signatures.sign(challengeHash, identity.signPrivateKey)
            val signatureB64 = Base64.encodeToString(signature, Base64.NO_WRAP)

            val proofPayload = mapOf(
                "signature" to signatureB64
            )

            ws.send("register_proof", proofPayload)

            // Step 4: Wait for register_ack
            ConformanceLogger.gate("S1", "${identity.name}: Waiting for register_ack")
            val ackMsg = ws.waitForMessage(
                "register_ack",
                ConformanceConfig.Timeout.REGISTRATION
            )

            val ackPayload = ackMsg.getAsJsonObject("payload")
            val success = ackPayload.get("success")?.asBoolean ?: false
            val whisperId = ackPayload.get("whisperId")?.asString
            val sessionToken = ackPayload.get("sessionToken")?.asString

            if (!success) {
                val error = ackPayload.get("error")?.asString ?: "Unknown error"
                return fail("${identity.name}: Registration failed: $error")
            }

            if (whisperId.isNullOrEmpty()) {
                return fail("${identity.name}: No whisperId in response")
            }

            if (sessionToken.isNullOrEmpty()) {
                return fail("${identity.name}: No sessionToken in response")
            }

            // Update identity
            identity.whisperId = whisperId
            identity.sessionToken = sessionToken

            ConformanceLogger.gate("S1", "${identity.name}: Registered as ${ConformanceLogger.maskPii(whisperId)}")

            return GateResult.pass(
                name = "S1-Register-${identity.name}",
                details = "Registered as $whisperId",
                durationMs = System.currentTimeMillis() - startTime
            )

        } finally {
            ws.disconnect()
        }
    }

    private fun fail(reason: String): GateResult {
        return GateResult.fail(
            name = "S1-Register",
            reason = reason,
            durationMs = System.currentTimeMillis() - startTime
        )
    }
}
