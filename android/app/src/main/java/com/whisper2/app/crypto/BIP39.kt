package com.whisper2.app.crypto

import com.whisper2.app.core.Constants
import com.whisper2.app.core.InvalidMnemonicException
import com.whisper2.app.core.normalizeMnemonic
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * BIP39 Mnemonic generation and seed derivation.
 * MUST match iOS/server implementation exactly.
 */
object BIP39 {

    /**
     * Generate a new 12-word mnemonic using SecureRandom.
     */
    fun generateMnemonic(secureRandom: SecureRandom): String {
        // 128 bits of entropy for 12 words
        val entropy = ByteArray(16)
        secureRandom.nextBytes(entropy)
        return entropyToMnemonic(entropy)
    }

    /**
     * Generate a 24-word mnemonic.
     */
    fun generateMnemonic24(secureRandom: SecureRandom): String {
        // 256 bits of entropy for 24 words
        val entropy = ByteArray(32)
        secureRandom.nextBytes(entropy)
        return entropyToMnemonic(entropy)
    }

    /**
     * Convert entropy bytes to mnemonic words.
     */
    private fun entropyToMnemonic(entropy: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(entropy)
        val checksumBits = entropy.size / 4 // 4 bits for 128-bit entropy, 8 for 256-bit

        // Combine entropy + checksum bits
        val bits = StringBuilder()
        for (b in entropy) {
            bits.append(String.format("%8s", Integer.toBinaryString(b.toInt() and 0xFF)).replace(' ', '0'))
        }
        for (i in 0 until checksumBits) {
            bits.append(if ((hash[0].toInt() and (1 shl (7 - i))) != 0) '1' else '0')
        }

        // Split into 11-bit chunks and map to words
        val words = mutableListOf<String>()
        for (i in 0 until bits.length / 11) {
            val index = bits.substring(i * 11, (i + 1) * 11).toInt(2)
            words.add(BIP39WordList.getWord(index))
        }

        return words.joinToString(" ")
    }

    /**
     * Validate mnemonic phrase.
     */
    fun validateMnemonic(mnemonic: String): Boolean {
        val words = mnemonic.normalizeMnemonic().split(" ")
        if (words.size != 12 && words.size != 24) return false
        return words.all { BIP39WordList.isValidWord(it) }
    }

    /**
     * Derive 64-byte seed from mnemonic using PBKDF2-HMAC-SHA512.
     * Salt = "mnemonic", iterations = 2048
     */
    fun seedFromMnemonic(mnemonic: String): ByteArray {
        if (!validateMnemonic(mnemonic)) {
            throw InvalidMnemonicException()
        }

        val normalized = mnemonic.normalizeMnemonic()
        val password = normalized.toCharArray()
        val salt = Constants.BIP39_SALT.toByteArray(Charsets.UTF_8)

        val spec = PBEKeySpec(password, salt, Constants.BIP39_ITERATIONS, Constants.BIP39_SEED_LENGTH * 8)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        return factory.generateSecret(spec).encoded
    }
}
