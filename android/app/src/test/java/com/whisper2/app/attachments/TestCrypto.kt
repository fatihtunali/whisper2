package com.whisper2.app.attachments

import java.security.SecureRandom
import java.util.Base64 as JBase64

/**
 * Test-only Base64 utilities for JVM tests.
 * Wraps java.util.Base64 to avoid android.util.Base64.
 */
object TestBase64 {
    fun encode(data: ByteArray): String = JBase64.getEncoder().encodeToString(data)
    fun decode(data: String): ByteArray = JBase64.getDecoder().decode(data)
}

/**
 * Test-only crypto implementations for unit tests.
 * Simulates secretbox encryption without native libraries.
 *
 * IMPORTANT: These are NOT cryptographically secure!
 * Only use for unit testing.
 */
object TestCrypto {

    const val KEY_SIZE = 32
    const val NONCE_SIZE = 24
    const val MAC_SIZE = 16

    private val random = SecureRandom()

    /**
     * Generate random 32-byte key
     */
    fun generateKey(): ByteArray {
        val key = ByteArray(KEY_SIZE)
        random.nextBytes(key)
        return key
    }

    /**
     * Generate random 24-byte nonce
     */
    fun generateNonce(): ByteArray {
        val nonce = ByteArray(NONCE_SIZE)
        random.nextBytes(nonce)
        return nonce
    }

    /**
     * Mock encryption: XOR plaintext with key/nonce hash + prepend MAC
     * The "MAC" is a simple hash that allows detection of tampering
     */
    fun encrypt(plaintext: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        require(nonce.size == NONCE_SIZE) { "Nonce must be $NONCE_SIZE bytes" }
        require(key.size == KEY_SIZE) { "Key must be $KEY_SIZE bytes" }

        // Generate a "keystream" from key + nonce
        val keystream = deriveKeystream(key, nonce, plaintext.size)

        // XOR plaintext with keystream
        val encrypted = ByteArray(plaintext.size)
        for (i in plaintext.indices) {
            encrypted[i] = (plaintext[i].toInt() xor keystream[i].toInt()).toByte()
        }

        // Create MAC (simple hash of key + nonce + encrypted)
        val mac = computeMac(key, nonce, encrypted)

        // Return MAC + encrypted
        return mac + encrypted
    }

    /**
     * Mock decryption: verify MAC, then XOR with key/nonce hash
     * Throws exception if MAC doesn't match (tampered or wrong key)
     */
    fun decrypt(ciphertext: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        require(nonce.size == NONCE_SIZE) { "Nonce must be $NONCE_SIZE bytes" }
        require(key.size == KEY_SIZE) { "Key must be $KEY_SIZE bytes" }
        require(ciphertext.size >= MAC_SIZE) { "Ciphertext too short" }

        // Split MAC and encrypted data
        val mac = ciphertext.copyOfRange(0, MAC_SIZE)
        val encrypted = ciphertext.copyOfRange(MAC_SIZE, ciphertext.size)

        // Verify MAC
        val expectedMac = computeMac(key, nonce, encrypted)
        if (!mac.contentEquals(expectedMac)) {
            throw RuntimeException("Decryption failed - wrong key or tampered data")
        }

        // Generate keystream and XOR to decrypt
        val keystream = deriveKeystream(key, nonce, encrypted.size)
        val plaintext = ByteArray(encrypted.size)
        for (i in encrypted.indices) {
            plaintext[i] = (encrypted[i].toInt() xor keystream[i].toInt()).toByte()
        }

        return plaintext
    }

    /**
     * Derive a keystream from key + nonce using simple hash expansion
     */
    private fun deriveKeystream(key: ByteArray, nonce: ByteArray, length: Int): ByteArray {
        val result = ByteArray(length)
        var counter = 0
        var position = 0

        while (position < length) {
            // Hash key + nonce + counter
            val block = simpleHash(key + nonce + intToBytes(counter))
            val toCopy = minOf(block.size, length - position)
            System.arraycopy(block, 0, result, position, toCopy)
            position += toCopy
            counter++
        }

        return result
    }

    /**
     * Compute MAC from key + nonce + data
     */
    private fun computeMac(key: ByteArray, nonce: ByteArray, data: ByteArray): ByteArray {
        return simpleHash(key + nonce + data).copyOfRange(0, MAC_SIZE)
    }

    /**
     * Simple hash function for test purposes
     * NOT cryptographically secure!
     */
    private fun simpleHash(input: ByteArray): ByteArray {
        val result = ByteArray(32)

        // Initialize with constants
        for (i in 0 until 32) {
            result[i] = (i * 17 + 31).toByte()
        }

        // Mix in input bytes
        for (i in input.indices) {
            val idx = i % 32
            result[idx] = (result[idx].toInt() xor input[i].toInt() xor ((i * 7 + 13) and 0xFF)).toByte()
            // Rotate
            val nextIdx = (idx + 1) % 32
            result[nextIdx] = (result[nextIdx].toInt() + result[idx].toInt()).toByte()
        }

        // Additional mixing rounds
        repeat(16) { round ->
            for (i in 0 until 32) {
                val prev = result[(i + 31) % 32].toInt() and 0xFF
                val next = result[(i + 1) % 32].toInt() and 0xFF
                result[i] = (result[i].toInt() xor prev xor (next shl 1) xor round).toByte()
            }
        }

        return result
    }

    private fun intToBytes(value: Int): ByteArray {
        return byteArrayOf(
            (value shr 24).toByte(),
            (value shr 16).toByte(),
            (value shr 8).toByte(),
            value.toByte()
        )
    }
}
