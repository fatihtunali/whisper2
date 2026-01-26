package com.whisper2.app.calls

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64 as JBase64

/**
 * Test-only crypto utilities for call tests.
 * Provides JVM-compatible mock encryption and signing.
 *
 * IMPORTANT: Not cryptographically secure! Only for unit testing.
 */
object CallTestCrypto {

    private val random = SecureRandom()

    // Key sizes (matching libsodium)
    const val SIGN_PUBLIC_KEY_SIZE = 32
    const val SIGN_PRIVATE_KEY_SIZE = 64
    const val ENC_KEY_SIZE = 32
    const val NONCE_SIZE = 24
    const val SIGNATURE_SIZE = 64
    const val BOX_OVERHEAD = 16

    // =========================================================================
    // KEY GENERATION
    // =========================================================================

    data class SigningKeyPair(
        val publicKey: ByteArray,   // 32 bytes
        val privateKey: ByteArray   // 64 bytes
    )

    data class EncKeyPair(
        val publicKey: ByteArray,   // 32 bytes
        val privateKey: ByteArray   // 32 bytes
    )

    fun generateSigningKeyPair(): SigningKeyPair {
        val seed = ByteArray(32)
        random.nextBytes(seed)
        val publicKey = sha256(seed + "public".toByteArray())
        val privateKey = seed + publicKey // 64 bytes
        return SigningKeyPair(publicKey, privateKey)
    }

    fun generateEncKeyPair(): EncKeyPair {
        val privateKey = ByteArray(ENC_KEY_SIZE)
        random.nextBytes(privateKey)
        val publicKey = sha256(privateKey + "enc".toByteArray())
        return EncKeyPair(publicKey, privateKey)
    }

    fun generateNonce(): ByteArray {
        val nonce = ByteArray(NONCE_SIZE)
        random.nextBytes(nonce)
        return nonce
    }

    // =========================================================================
    // SIGNING (MOCK)
    // =========================================================================

    /**
     * Mock sign: HMAC-like construction using SHA256
     * Returns 64-byte "signature"
     */
    fun sign(message: ByteArray, privateKey: ByteArray): ByteArray {
        require(privateKey.size == SIGN_PRIVATE_KEY_SIZE) { "Private key must be 64 bytes" }

        // Mock signature: hash(privateKey + message) repeated to 64 bytes
        val hash1 = sha256(privateKey + message)
        val hash2 = sha256(message + privateKey)
        return hash1 + hash2
    }

    /**
     * Mock verify: Check signature matches expected
     */
    fun verify(signature: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean {
        if (signature.size != SIGNATURE_SIZE) return false
        if (publicKey.size != SIGN_PUBLIC_KEY_SIZE) return false

        // For testing, we need the private key to verify
        // This is intentionally insecure - only for testing!
        // In real tests, we pre-compute valid signatures
        return true // Always return true in mock - actual verification done by matching content
    }

    // =========================================================================
    // ENCRYPTION (MOCK BOX)
    // =========================================================================

    /**
     * Mock seal: XOR with derived stream + MAC
     */
    fun seal(
        plaintext: ByteArray,
        nonce: ByteArray,
        recipientPublicKey: ByteArray,
        senderPrivateKey: ByteArray
    ): ByteArray {
        require(nonce.size == NONCE_SIZE) { "Nonce must be 24 bytes" }

        // Derive keystream
        val keystream = deriveKeystream(recipientPublicKey + senderPrivateKey + nonce, plaintext.size)

        // XOR
        val encrypted = ByteArray(plaintext.size)
        for (i in plaintext.indices) {
            encrypted[i] = (plaintext[i].toInt() xor keystream[i].toInt()).toByte()
        }

        // MAC
        val mac = sha256(nonce + encrypted + recipientPublicKey).copyOf(BOX_OVERHEAD)

        return mac + encrypted
    }

    /**
     * Mock open: Verify MAC and XOR with derived stream
     */
    fun open(
        ciphertext: ByteArray,
        nonce: ByteArray,
        senderPublicKey: ByteArray,
        recipientPrivateKey: ByteArray
    ): ByteArray {
        require(nonce.size == NONCE_SIZE) { "Nonce must be 24 bytes" }
        require(ciphertext.size >= BOX_OVERHEAD) { "Ciphertext too short" }

        val mac = ciphertext.copyOfRange(0, BOX_OVERHEAD)
        val encrypted = ciphertext.copyOfRange(BOX_OVERHEAD, ciphertext.size)

        // Verify MAC
        val expectedMac = sha256(nonce + encrypted + senderPublicKey).copyOf(BOX_OVERHEAD)
        if (!mac.contentEquals(expectedMac)) {
            throw RuntimeException("Decryption failed - tampered or wrong key")
        }

        // Derive keystream (using swapped keys for decryption)
        val keystream = deriveKeystream(senderPublicKey + recipientPrivateKey + nonce, encrypted.size)

        // XOR to decrypt
        val plaintext = ByteArray(encrypted.size)
        for (i in encrypted.indices) {
            plaintext[i] = (encrypted[i].toInt() xor keystream[i].toInt()).toByte()
        }

        return plaintext
    }

    private fun deriveKeystream(seed: ByteArray, length: Int): ByteArray {
        val result = ByteArray(length)
        var position = 0
        var counter = 0

        while (position < length) {
            val block = sha256(seed + intToBytes(counter))
            val toCopy = minOf(block.size, length - position)
            System.arraycopy(block, 0, result, position, toCopy)
            position += toCopy
            counter++
        }

        return result
    }

    // =========================================================================
    // CANONICAL SIGNING (mirrors CanonicalSigning)
    // =========================================================================

    fun buildCanonicalString(
        version: String = "v1",
        messageType: String,
        messageId: String,
        from: String,
        to: String,
        timestamp: Long,
        nonceB64: String,
        ciphertextB64: String
    ): String {
        return buildString {
            append(version).append('\n')
            append(messageType).append('\n')
            append(messageId).append('\n')
            append(from).append('\n')
            append(to).append('\n')
            append(timestamp).append('\n')
            append(nonceB64).append('\n')
            append(ciphertextB64).append('\n')
        }
    }

    fun signCanonical(
        messageType: String,
        messageId: String,
        from: String,
        to: String,
        timestamp: Long,
        nonce: ByteArray,
        ciphertext: ByteArray,
        privateKey: ByteArray
    ): ByteArray {
        val nonceB64 = encode(nonce)
        val ciphertextB64 = encode(ciphertext)

        val canonical = buildCanonicalString(
            messageType = messageType,
            messageId = messageId,
            from = from,
            to = to,
            timestamp = timestamp,
            nonceB64 = nonceB64,
            ciphertextB64 = ciphertextB64
        )

        val hash = sha256(canonical.toByteArray(Charsets.UTF_8))
        return sign(hash, privateKey)
    }

    fun signCanonicalB64(
        messageType: String,
        messageId: String,
        from: String,
        to: String,
        timestamp: Long,
        nonce: ByteArray,
        ciphertext: ByteArray,
        privateKey: ByteArray
    ): String {
        return encode(signCanonical(messageType, messageId, from, to, timestamp, nonce, ciphertext, privateKey))
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    fun sha256(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
    }

    fun encode(data: ByteArray): String = JBase64.getEncoder().encodeToString(data)

    fun decode(data: String): ByteArray = JBase64.getDecoder().decode(data)

    private fun intToBytes(value: Int): ByteArray {
        return byteArrayOf(
            (value shr 24).toByte(),
            (value shr 16).toByte(),
            (value shr 8).toByte(),
            value.toByte()
        )
    }
}
