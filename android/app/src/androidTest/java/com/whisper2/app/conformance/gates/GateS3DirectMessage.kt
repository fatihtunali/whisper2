package com.whisper2.app.conformance.gates

import android.util.Base64
import com.google.gson.JsonObject
import com.whisper2.app.conformance.*
import com.whisper2.app.crypto.NaClBox
import com.whisper2.app.crypto.Signatures
import kotlinx.coroutines.delay
import java.security.MessageDigest
import java.util.UUID

/**
 * Gate S3: Direct Message E2E (WS)
 *
 * Flow:
 * 1. A connects and authenticates
 * 2. B connects and authenticates
 * 3. A sends encrypted message to B
 * 4. B receives message and decrypts
 * 5. B sends delivery receipt
 * 6. A receives delivery receipt
 *
 * PASS criteria:
 * - Message delivered successfully
 * - Decryption successful (plaintext matches)
 * - Delivery receipt received
 */
class GateS3DirectMessage(
    private val identityA: TestIdentity,
    private val identityB: TestIdentity
) {
    private val startTime = System.currentTimeMillis()

    suspend fun run(): GateResult {
        ConformanceLogger.gate("S3", "Starting direct message E2E test")

        try {
            identityA.requireRegistered()
            identityB.requireRegistered()

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

                // A sends message to B
                val messageId = UUID.randomUUID().toString()
                val plaintext = ConformanceConfig.TestData.TEST_MESSAGE_TEXT

                ConformanceLogger.gate("S3", "Encrypting message from A to B")

                // Encrypt message: A -> B
                val (nonce, ciphertext) = NaClBox.seal(
                    message = plaintext.toByteArray(Charsets.UTF_8),
                    recipientPublicKey = identityB.encPublicKey,
                    senderPrivateKey = identityA.encPrivateKey
                )

                val envelope = mapOf(
                    "recipientId" to identityB.whisperId,
                    "messageId" to messageId,
                    "nonce" to Base64.encodeToString(nonce, Base64.NO_WRAP),
                    "ciphertext" to Base64.encodeToString(ciphertext, Base64.NO_WRAP),
                    "senderPublicKey" to Base64.encodeToString(identityA.encPublicKey, Base64.NO_WRAP)
                )

                ConformanceLogger.gate("S3", "A sending message to B...")
                wsA.send("message_send", envelope)

                // Wait for message_sent acknowledgment
                val sentAck = wsA.waitForMessage(
                    "message_sent",
                    ConformanceConfig.Timeout.MESSAGE_DELIVERY
                )
                val sentPayload = sentAck.getAsJsonObject("payload")
                if (sentPayload.get("success")?.asBoolean != true) {
                    val error = sentPayload.get("error")?.asString ?: "Unknown"
                    return fail("message_sent failed: $error")
                }

                ConformanceLogger.gate("S3", "A received message_sent acknowledgment")

                // B waits for message_received
                ConformanceLogger.gate("S3", "B waiting for message...")
                val receivedMsg = wsB.waitForMessage(
                    "message_received",
                    ConformanceConfig.Timeout.MESSAGE_DELIVERY
                )

                val receivedPayload = receivedMsg.getAsJsonObject("payload")
                val receivedMessageId = receivedPayload.get("messageId")?.asString
                val receivedNonceB64 = receivedPayload.get("nonce")?.asString
                val receivedCiphertextB64 = receivedPayload.get("ciphertext")?.asString
                val senderPublicKeyB64 = receivedPayload.get("senderPublicKey")?.asString

                if (receivedMessageId != messageId) {
                    return fail("Message ID mismatch: expected $messageId, got $receivedMessageId")
                }

                // Decrypt message
                ConformanceLogger.gate("S3", "B decrypting message...")
                val receivedNonce = Base64.decode(receivedNonceB64, Base64.NO_WRAP)
                val receivedCiphertext = Base64.decode(receivedCiphertextB64, Base64.NO_WRAP)
                val senderPublicKey = Base64.decode(senderPublicKeyB64, Base64.NO_WRAP)

                val decrypted = try {
                    NaClBox.open(
                        ciphertext = receivedCiphertext,
                        nonce = receivedNonce,
                        senderPublicKey = senderPublicKey,
                        recipientPrivateKey = identityB.encPrivateKey
                    )
                } catch (e: Exception) {
                    return fail("Decryption failed: ${e.message}")
                }

                val decryptedText = String(decrypted, Charsets.UTF_8)
                if (decryptedText != plaintext) {
                    return fail("Decrypted text mismatch: expected '$plaintext', got '$decryptedText'")
                }

                ConformanceLogger.gate("S3", "B successfully decrypted message: '$decryptedText'")

                // B sends delivery receipt
                ConformanceLogger.gate("S3", "B sending delivery receipt...")
                wsB.send("message_delivered", mapOf(
                    "messageId" to messageId,
                    "senderId" to identityA.whisperId
                ))

                // A waits for delivery_receipt
                ConformanceLogger.gate("S3", "A waiting for delivery receipt...")
                val receiptMsg = wsA.waitForMessage(
                    "delivery_receipt",
                    ConformanceConfig.Timeout.MESSAGE_DELIVERY
                )

                val receiptPayload = receiptMsg.getAsJsonObject("payload")
                val receiptMessageId = receiptPayload.get("messageId")?.asString

                if (receiptMessageId != messageId) {
                    return fail("Delivery receipt messageId mismatch")
                }

                ConformanceLogger.gate("S3", "A received delivery receipt")

                val duration = System.currentTimeMillis() - startTime
                return GateResult.pass(
                    name = "S3-DirectMessage",
                    details = "E2E message sent, received, decrypted, and receipt confirmed",
                    durationMs = duration,
                    artifacts = mapOf(
                        "messageId" to messageId,
                        "plaintextLength" to plaintext.length,
                        "ciphertextLength" to ciphertext.size
                    )
                )

            } finally {
                wsA.disconnect()
                wsB.disconnect()
            }

        } catch (e: Exception) {
            return GateResult.fail(
                name = "S3-DirectMessage",
                reason = "Direct message test failed: ${e.message}",
                durationMs = System.currentTimeMillis() - startTime,
                error = e
            )
        }
    }

    private suspend fun authenticate(ws: ConformanceWsClient, identity: TestIdentity): GateResult {
        ConformanceLogger.gate("S3", "${identity.name}: Authenticating...")

        // Send auth_begin
        ws.send("auth_begin", mapOf(
            "whisperId" to identity.whisperId,
            "deviceId" to identity.deviceId
        ))

        // Wait for auth_challenge
        val challengeMsg = ws.waitForMessage(
            "auth_challenge",
            ConformanceConfig.Timeout.WS_MESSAGE
        )

        val challengePayload = challengeMsg.getAsJsonObject("payload")
        val challengeB64 = challengePayload.get("challenge")?.asString
            ?: return fail("${identity.name}: No challenge in auth_challenge")

        val challenge = Base64.decode(challengeB64, Base64.NO_WRAP)

        // Sign challenge
        val challengeHash = MessageDigest.getInstance("SHA-256").digest(challenge)
        val signature = Signatures.sign(challengeHash, identity.signPrivateKey)
        val signatureB64 = Base64.encodeToString(signature, Base64.NO_WRAP)

        // Send auth_proof
        ws.send("auth_proof", mapOf(
            "signature" to signatureB64
        ))

        // Wait for auth_ack
        val ackMsg = ws.waitForMessage(
            "auth_ack",
            ConformanceConfig.Timeout.WS_MESSAGE
        )

        val ackPayload = ackMsg.getAsJsonObject("payload")
        val success = ackPayload.get("success")?.asBoolean ?: false

        if (!success) {
            val error = ackPayload.get("error")?.asString ?: "Unknown"
            return fail("${identity.name}: Authentication failed: $error")
        }

        ConformanceLogger.gate("S3", "${identity.name}: Authenticated successfully")

        return GateResult.pass(
            name = "S3-Auth-${identity.name}",
            details = "Authenticated",
            durationMs = 0
        )
    }

    private fun fail(reason: String): GateResult {
        return GateResult.fail(
            name = "S3-DirectMessage",
            reason = reason,
            durationMs = System.currentTimeMillis() - startTime
        )
    }
}
