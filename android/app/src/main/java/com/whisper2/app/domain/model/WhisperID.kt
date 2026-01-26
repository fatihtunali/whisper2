package com.whisper2.app.domain.model

import com.whisper2.app.core.CryptoException

/**
 * WhisperID - Unique identifier for Whisper2 users
 * Format: WSP-XXXX-XXXX-XXXX (Base32: A-Z, 2-7)
 * MUST match server implementation in crypto.ts
 *
 * The server generates WhisperIDs during registration.
 * Client does NOT generate WhisperIDs locally - they are received from server.
 */
@JvmInline
value class WhisperID private constructor(val rawValue: String) {

    companion object {
        // MARK: - Constants

        private const val PREFIX = "WSP-"
        private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        private val PATTERN = Regex("^WSP-[A-Z2-7]{4}-[A-Z2-7]{4}-[A-Z2-7]{4}$")

        // MARK: - Factory Methods

        /**
         * Create WhisperID from raw string with validation
         * @throws CryptoException.InvalidWhisperId if validation fails
         */
        fun fromString(value: String): WhisperID {
            if (!isValid(value)) {
                throw CryptoException.InvalidWhisperId()
            }
            return WhisperID(value.uppercase())
        }

        /**
         * Create WhisperID from trusted source (server) without validation
         * Use only when receiving from server
         */
        fun fromTrusted(value: String): WhisperID {
            return WhisperID(value.uppercase())
        }

        /**
         * Try to create WhisperID, returns null if invalid
         */
        fun fromStringOrNull(value: String): WhisperID? {
            return if (isValid(value)) WhisperID(value.uppercase()) else null
        }

        // MARK: - Validation

        /**
         * Validates a WhisperID string
         * Format: WSP-XXXX-XXXX-XXXX where X is Base32 (A-Z, 2-7)
         */
        fun isValid(value: String): Boolean {
            return PATTERN.matches(value.uppercase())
        }
    }

    // MARK: - Display

    /**
     * Returns formatted WhisperID for display
     * Example: "WSP-ABCD-EFGH-IJKL"
     */
    val displayString: String get() = rawValue

    /**
     * Returns compact form without dashes
     * Example: "WSPABCDEFGHIJKL"
     */
    val compact: String get() = rawValue.replace("-", "")

    override fun toString(): String = rawValue
}

// MARK: - String Extension

/**
 * Attempts to convert this string to a WhisperID
 */
val String.asWhisperID: WhisperID?
    get() = WhisperID.fromStringOrNull(this)
