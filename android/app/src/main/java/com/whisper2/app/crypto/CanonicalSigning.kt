package com.whisper2.app.crypto

import com.whisper2.app.core.encodeBase64
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Canonical message signing for protocol compliance.
 *
 * Format:
 * v1\n
 * messageType\n
 * messageId\n
 * from\n
 * toOrGroupId\n
 * timestamp\n
 * nonceB64\n
 * ciphertextB64\n
 */
@Singleton
class CanonicalSigning @Inject constructor(
    private val signatures: Signatures
) {
    /**
     * Sign a message using canonical format.
     */
    fun signMessage(
        messageType: String,
        messageId: String,
        from: String,
        toOrGroupId: String,
        timestamp: Long,
        nonce: ByteArray,
        ciphertext: ByteArray,
        privateKey: ByteArray
    ): ByteArray {
        val canonical = buildString {
            append("v1\n")
            append("$messageType\n")
            append("$messageId\n")
            append("$from\n")
            append("$toOrGroupId\n")
            append("$timestamp\n")
            append("${nonce.encodeBase64()}\n")
            append("${ciphertext.encodeBase64()}\n")
        }

        val hash = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))

        return signatures.sign(hash, privateKey)
    }

    /**
     * Verify a message signature.
     */
    fun verifyMessage(
        messageType: String,
        messageId: String,
        from: String,
        toOrGroupId: String,
        timestamp: Long,
        nonce: ByteArray,
        ciphertext: ByteArray,
        signature: ByteArray,
        publicKey: ByteArray
    ): Boolean {
        val canonical = buildString {
            append("v1\n")
            append("$messageType\n")
            append("$messageId\n")
            append("$from\n")
            append("$toOrGroupId\n")
            append("$timestamp\n")
            append("${nonce.encodeBase64()}\n")
            append("${ciphertext.encodeBase64()}\n")
        }

        val hash = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))

        return signatures.verify(hash, signature, publicKey)
    }
}
