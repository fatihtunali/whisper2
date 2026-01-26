package com.whisper2.app.crypto

import android.util.Base64
import com.whisper2.app.core.utils.Base64Strict
import java.security.MessageDigest

/**
 * Canonical string builder for message signing
 *
 * Format (must match server + iOS exactly):
 * v1\n
 * messageType\n
 * messageId\n
 * from\n
 * toOrGroupId\n
 * timestamp\n
 * base64(nonce)\n
 * base64(ciphertext)\n
 *
 * Note: Last line also ends with \n
 */
object CanonicalSigning {

    /**
     * Build canonical string for signing/verification
     *
     * @param version Protocol version ("v1")
     * @param messageType Message type ("send_message" for direct messages)
     * @param messageId UUID of the message
     * @param from Sender WhisperID
     * @param toOrGroupId Recipient WhisperID or GroupID
     * @param timestamp Unix timestamp in milliseconds
     * @param nonceB64 Base64 encoded nonce
     * @param ciphertextB64 Base64 encoded ciphertext
     * @return Canonical string with trailing newline
     */
    fun buildCanonicalString(
        version: String = "v1",
        messageType: String,
        messageId: String,
        from: String,
        toOrGroupId: String,
        timestamp: Long,
        nonceB64: String,
        ciphertextB64: String
    ): String {
        return buildString {
            append(version).append('\n')
            append(messageType).append('\n')
            append(messageId).append('\n')
            append(from).append('\n')
            append(toOrGroupId).append('\n')
            append(timestamp).append('\n')
            append(nonceB64).append('\n')
            append(ciphertextB64).append('\n')
        }
    }

    /**
     * Build canonical bytes for signing
     * Returns UTF-8 encoded canonical string
     */
    fun buildCanonicalBytes(
        version: String = "v1",
        messageType: String,
        messageId: String,
        from: String,
        toOrGroupId: String,
        timestamp: Long,
        nonceB64: String,
        ciphertextB64: String
    ): ByteArray {
        return buildCanonicalString(
            version, messageType, messageId, from, toOrGroupId,
            timestamp, nonceB64, ciphertextB64
        ).toByteArray(Charsets.UTF_8)
    }

    /**
     * Hash canonical bytes with SHA256
     * This is what gets signed: SHA256(UTF8(canonicalString))
     */
    fun hashCanonical(canonicalBytes: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(canonicalBytes)
    }

    /**
     * Build and hash canonical string in one step
     * Returns SHA256(UTF8(canonicalString))
     */
    fun buildAndHashCanonical(
        version: String = "v1",
        messageType: String,
        messageId: String,
        from: String,
        toOrGroupId: String,
        timestamp: Long,
        nonceB64: String,
        ciphertextB64: String
    ): ByteArray {
        val canonicalBytes = buildCanonicalBytes(
            version, messageType, messageId, from, toOrGroupId,
            timestamp, nonceB64, ciphertextB64
        )
        return hashCanonical(canonicalBytes)
    }

    /**
     * Message type constants for signing (must match server SignedMessageTypes)
     */
    const val MESSAGE_TYPE_SEND = "send_message"
    const val MESSAGE_TYPE_GROUP_SEND = "group_send_message"

    // Call message types
    const val MESSAGE_TYPE_CALL_INITIATE = "call_initiate"
    const val MESSAGE_TYPE_CALL_ANSWER = "call_answer"
    const val MESSAGE_TYPE_CALL_ICE_CANDIDATE = "call_ice_candidate"
    const val MESSAGE_TYPE_CALL_END = "call_end"
    const val MESSAGE_TYPE_CALL_RINGING = "call_ringing"  // Note: Server SignedMessageTypes doesn't include call_ringing, but it's signed in protocol

    // =========================================================================
    // Convenience methods for CryptoService
    // =========================================================================

    /**
     * Sign canonical message and return base64-encoded signature
     */
    fun signCanonicalBase64(
        messageType: String,
        messageId: String,
        from: String,
        to: String,
        timestamp: Long,
        nonce: ByteArray,
        ciphertext: ByteArray,
        privateKey: ByteArray
    ): String {
        val nonceB64 = Base64Strict.encode(nonce)
        val ciphertextB64 = Base64Strict.encode(ciphertext)

        val hash = buildAndHashCanonical(
            version = "v1",
            messageType = messageType,
            messageId = messageId,
            from = from,
            toOrGroupId = to,
            timestamp = timestamp,
            nonceB64 = nonceB64,
            ciphertextB64 = ciphertextB64
        )

        val signature = Signatures.sign(hash, privateKey)
        return Base64Strict.encode(signature)
    }

    /**
     * Verify canonical message signature
     */
    fun verifyCanonicalBase64(
        signatureB64: String,
        messageType: String,
        messageId: String,
        from: String,
        to: String,
        timestamp: Long,
        nonceB64: String,
        ciphertextB64: String,
        publicKey: ByteArray
    ): Boolean {
        val hash = buildAndHashCanonical(
            version = "v1",
            messageType = messageType,
            messageId = messageId,
            from = from,
            toOrGroupId = to,
            timestamp = timestamp,
            nonceB64 = nonceB64,
            ciphertextB64 = ciphertextB64
        )

        return try {
            val signature = Base64Strict.decode(signatureB64)
            Signatures.verify(signature, hash, publicKey)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Sign authentication challenge
     * Challenge signature: Ed25519(SHA256(base64_decode(challengeB64)))
     */
    fun signChallengeBase64(challengeB64: String, privateKey: ByteArray): String {
        val challengeBytes = Base64Strict.decode(challengeB64)
        val hash = MessageDigest.getInstance("SHA-256").digest(challengeBytes)
        val signature = Signatures.sign(hash, privateKey)
        return Base64Strict.encode(signature)
    }
}
