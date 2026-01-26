package com.whisper2.app.crypto

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.SecretBox
import com.whisper2.app.core.CryptoException
import java.security.SecureRandom

/**
 * NaCl SecretBox (XSalsa20-Poly1305) implementation
 * Symmetric authenticated encryption for attachments and backups
 * Uses LazySodium for server-compatible crypto (libsodium)
 */
object NaClSecretBox {

    // MARK: - Lazy Sodium Instance
    private val sodium: LazySodiumAndroid by lazy {
        LazySodiumAndroid(SodiumAndroid())
    }

    // MARK: - Constants

    /** SecretBox overhead (Poly1305 tag) */
    const val BOX_OVERHEAD = SecretBox.MACBYTES // 16

    /** Nonce length for XSalsa20 */
    const val NONCE_LENGTH = SecretBox.NONCEBYTES // 24

    /** Key length */
    const val KEY_LENGTH = SecretBox.KEYBYTES // 32

    // MARK: - Key Generation

    /**
     * Generate random 32-byte symmetric key
     */
    fun generateKey(): ByteArray {
        val key = ByteArray(KEY_LENGTH)
        SecureRandom().nextBytes(key)
        return key
    }

    // MARK: - Nonce Generation

    /**
     * Generate random 24-byte nonce
     */
    fun generateNonce(): ByteArray {
        val nonce = ByteArray(NONCE_LENGTH)
        SecureRandom().nextBytes(nonce)
        return nonce
    }

    // MARK: - Encryption

    /**
     * Encrypt data with symmetric key
     * @param message Plaintext to encrypt
     * @param key 32-byte symmetric key
     * @return Pair of (nonce, ciphertext) where ciphertext includes Poly1305 tag
     */
    fun seal(message: ByteArray, key: ByteArray): Pair<ByteArray, ByteArray> {
        if (key.size != KEY_LENGTH) {
            throw CryptoException.InvalidKeyLength()
        }

        val nonce = generateNonce()
        val ciphertext = seal(message, nonce, key)

        return Pair(nonce, ciphertext)
    }

    /**
     * Encrypt data with symmetric key and provided nonce
     * @param message Plaintext to encrypt
     * @param nonce 24-byte nonce (must be unique per message)
     * @param key 32-byte symmetric key
     * @return Ciphertext with Poly1305 tag appended
     */
    fun seal(message: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        if (key.size != KEY_LENGTH) {
            throw CryptoException.InvalidKeyLength()
        }
        if (nonce.size != NONCE_LENGTH) {
            throw CryptoException.InvalidNonce()
        }

        val ciphertext = ByteArray(message.size + BOX_OVERHEAD)
        val success = sodium.cryptoSecretBoxEasy(
            ciphertext,
            message,
            message.size.toLong(),
            nonce,
            key
        )

        if (!success) {
            throw CryptoException.EncryptionFailed()
        }

        return ciphertext
    }

    // MARK: - Decryption

    /**
     * Decrypt data with symmetric key
     * @param ciphertext Encrypted data with Poly1305 tag
     * @param nonce 24-byte nonce used for encryption
     * @param key 32-byte symmetric key
     * @return Decrypted plaintext
     */
    fun open(ciphertext: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        if (key.size != KEY_LENGTH) {
            throw CryptoException.InvalidKeyLength()
        }
        if (nonce.size != NONCE_LENGTH) {
            throw CryptoException.InvalidNonce()
        }
        if (ciphertext.size < BOX_OVERHEAD) {
            throw CryptoException.DecryptionFailed()
        }

        val plaintext = ByteArray(ciphertext.size - BOX_OVERHEAD)
        val success = sodium.cryptoSecretBoxOpenEasy(
            plaintext,
            ciphertext,
            ciphertext.size.toLong(),
            nonce,
            key
        )

        if (!success) {
            throw CryptoException.DecryptionFailed()
        }

        return plaintext
    }

    // MARK: - Convenience

    /**
     * Encrypt and prepend nonce to ciphertext
     */
    fun sealCombined(message: ByteArray, key: ByteArray): ByteArray {
        val (nonce, ciphertext) = seal(message, key)
        return nonce + ciphertext
    }

    /**
     * Decrypt combined nonce + ciphertext
     */
    fun openCombined(combined: ByteArray, key: ByteArray): ByteArray {
        if (combined.size <= NONCE_LENGTH + BOX_OVERHEAD) {
            throw CryptoException.DecryptionFailed()
        }

        val nonce = combined.copyOfRange(0, NONCE_LENGTH)
        val ciphertext = combined.copyOfRange(NONCE_LENGTH, combined.size)

        return open(ciphertext, nonce, key)
    }
}
