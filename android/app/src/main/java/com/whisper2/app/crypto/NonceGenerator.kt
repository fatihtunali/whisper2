package com.whisper2.app.crypto

import com.whisper2.app.core.Constants
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cryptographically secure nonce generation.
 * CRITICAL: Always use SecureRandom, never Random or other PRNGs.
 */
@Singleton
class NonceGenerator @Inject constructor(
    private val secureRandom: SecureRandom
) {
    /**
     * Generate 24-byte nonce for XSalsa20-Poly1305.
     */
    fun generateNonce(): ByteArray {
        val nonce = ByteArray(Constants.NACL_NONCE_SIZE)
        secureRandom.nextBytes(nonce)
        return nonce
    }

    /**
     * Generate 32-byte key for NaCl secretbox.
     */
    fun generateKey(): ByteArray {
        val key = ByteArray(Constants.NACL_KEY_SIZE)
        secureRandom.nextBytes(key)
        return key
    }

    /**
     * Generate random bytes of specified length.
     */
    fun generateBytes(length: Int): ByteArray {
        val bytes = ByteArray(length)
        secureRandom.nextBytes(bytes)
        return bytes
    }
}
