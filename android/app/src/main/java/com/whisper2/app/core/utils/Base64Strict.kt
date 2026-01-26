package com.whisper2.app.core.utils

import java.util.Base64

/**
 * Strict Base64 utilities
 * Enforces padded base64 only, matching server validation
 *
 * Rules:
 * - Padded only (length % 4 == 0)
 * - Valid characters only (A-Z, a-z, 0-9, +, /, =)
 * - Roundtrip must be canonical
 */
object Base64Strict {

    private val base64Regex = Regex("^[A-Za-z0-9+/]*={0,2}$")

    /**
     * Encode bytes to padded base64 string
     */
    fun encode(bytes: ByteArray): String {
        return Base64.getEncoder().encodeToString(bytes)
    }

    /**
     * Decode base64 string to bytes with strict validation
     * @throws IllegalArgumentException if invalid (unpadded, wrong length, invalid chars)
     */
    fun decode(encoded: String): ByteArray {
        if (encoded.isEmpty()) return ByteArray(0)

        // Rule 1: Must be padded (length % 4 == 0)
        if (encoded.length % 4 != 0) {
            throw IllegalArgumentException("Base64 must be padded (length % 4 != 0)")
        }

        // Rule 2: Valid characters only
        if (!base64Regex.matches(encoded)) {
            throw IllegalArgumentException("Base64 contains invalid characters")
        }

        return try {
            Base64.getDecoder().decode(encoded)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid base64 encoding", e)
        }
    }

    /**
     * Decode base64 string to bytes, returns null if invalid
     */
    fun decodeOrNull(encoded: String): ByteArray? {
        return try {
            decode(encoded)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * Validate base64 string format
     * - Must be padded (length divisible by 4)
     * - Must contain only valid base64 characters
     */
    fun isValid(encoded: String): Boolean {
        if (encoded.isEmpty()) return true
        if (encoded.length % 4 != 0) return false
        return base64Regex.matches(encoded)
    }

    /**
     * Decode base64 and verify expected length
     * @throws IllegalArgumentException if length doesn't match
     */
    fun decodeWithLength(encoded: String, expectedLength: Int): ByteArray {
        val bytes = decode(encoded)
        if (bytes.size != expectedLength) {
            throw IllegalArgumentException("Expected $expectedLength bytes, got ${bytes.size}")
        }
        return bytes
    }

    /**
     * Assert that decoded bytes are exactly 24 bytes (nonce length)
     * @throws IllegalArgumentException if not 24 bytes
     */
    fun assertNonce24(bytes: ByteArray) {
        if (bytes.size != 24) {
            throw IllegalArgumentException("Nonce must be 24 bytes, got ${bytes.size}")
        }
    }
}
