package com.whisper2.app.crypto

import com.whisper2.app.core.Constants
import com.whisper2.app.core.CryptoException
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * BIP39 Mnemonic implementation
 * Generates and validates mnemonic phrases, converts to seed
 * MUST match server/iOS implementation exactly for cross-platform recovery
 */
object BIP39 {

    // MARK: - Constants

    private const val PBKDF2_ITERATIONS = 2048
    private const val SEED_LENGTH = 64
    private const val ENTROPY_BITS_24_WORDS = 256

    // MARK: - Generate Mnemonic

    /**
     * Generate a new 24-word mnemonic phrase
     */
    fun generateMnemonic(): String {
        val entropy = ByteArray(32) // 256 bits for 24 words
        SecureRandom().nextBytes(entropy)
        return mnemonicFromEntropy(entropy)
    }

    /**
     * Convert entropy to mnemonic words
     */
    private fun mnemonicFromEntropy(entropy: ByteArray): String {
        // SHA256 checksum
        val hash = MessageDigest.getInstance("SHA-256").digest(entropy)
        val hashByte = hash[0]

        // Convert entropy to bit string
        val bits = StringBuilder()
        for (byte in entropy) {
            bits.append(String.format("%8s", Integer.toBinaryString(byte.toInt() and 0xFF)).replace(' ', '0'))
        }

        // Add checksum bits (first entropy.size/4 bits = 8 bits for 32 bytes)
        val checksumBits = String.format("%8s", Integer.toBinaryString(hashByte.toInt() and 0xFF)).replace(' ', '0')
        bits.append(checksumBits.substring(0, entropy.size / 4))

        // Split into 11-bit groups for word indices
        val words = mutableListOf<String>()
        val bitString = bits.toString()
        for (i in bitString.indices step 11) {
            val end = minOf(i + 11, bitString.length)
            val indexBits = bitString.substring(i, end)
            val index = Integer.parseInt(indexBits, 2)
            if (index < BIP39WordList.english.size) {
                words.add(BIP39WordList.english[index])
            }
        }

        return words.joinToString(" ")
    }

    // MARK: - Mnemonic Normalization

    /**
     * Normalize mnemonic for BIP39 compatibility
     * - Trim leading/trailing whitespace
     * - Collapse multiple spaces to single space
     * - Apply NFKD Unicode normalization
     * This prevents "same words, different bytes" issues (e.g., Turkish keyboard)
     */
    private fun normalizeMnemonic(mnemonic: String): String {
        val trimmed = mnemonic.trim()
        val collapsed = trimmed.split(Regex("\\s+")).joinToString(" ")
        return Normalizer.normalize(collapsed, Normalizer.Form.NFKD)
    }

    /**
     * Normalize passphrase for BIP39 compatibility
     */
    private fun normalizePassphrase(passphrase: String): String {
        return Normalizer.normalize(passphrase, Normalizer.Form.NFKD)
    }

    // MARK: - Mnemonic to Seed

    /**
     * Convert mnemonic to 64-byte seed using PBKDF2
     * BIP39: PBKDF2-HMAC-SHA512, salt = "mnemonic" + passphrase, 2048 iterations
     */
    fun seedFromMnemonic(mnemonic: String, passphrase: String = ""): ByteArray {
        val normalizedMnemonic = normalizeMnemonic(mnemonic)
        val normalizedPassphrase = normalizePassphrase(passphrase)

        val salt = "mnemonic$normalizedPassphrase"

        val spec = PBEKeySpec(
            normalizedMnemonic.toCharArray(),
            salt.toByteArray(Charsets.UTF_8),
            PBKDF2_ITERATIONS,
            SEED_LENGTH * 8 // bits
        )

        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        return factory.generateSecret(spec).encoded
    }

    // MARK: - Validation

    /**
     * Validate mnemonic phrase
     * Uses normalized input to match seedFromMnemonic behavior
     */
    fun isValidMnemonic(mnemonic: String): Boolean {
        val normalized = normalizeMnemonic(mnemonic)
        val words = normalized.lowercase().split(" ")

        if (words.size != 12 && words.size != 24) {
            return false
        }

        return words.all { word -> BIP39WordList.english.contains(word) }
    }

    /**
     * Validate mnemonic and throw if invalid
     */
    fun requireValidMnemonic(mnemonic: String) {
        if (!isValidMnemonic(mnemonic)) {
            throw CryptoException.InvalidMnemonic()
        }
    }
}
