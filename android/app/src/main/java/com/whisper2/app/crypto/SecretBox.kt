package com.whisper2.app.crypto

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.SecretBox as LSSecretBox

/**
 * SecretBox - Symmetric authenticated encryption
 *
 * Uses XSalsa20-Poly1305 (NaCl secretbox)
 * - 24 byte nonce
 * - 32 byte key
 * - 16 byte MAC (prepended to ciphertext)
 */
object SecretBox {
    private val sodium: LazySodiumAndroid by lazy {
        LazySodiumAndroid(SodiumAndroid())
    }

    const val NONCE_BYTES = 24
    const val KEY_BYTES = 32
    const val MAC_BYTES = 16

    /**
     * Encrypt plaintext with symmetric key
     *
     * @param plaintext Data to encrypt
     * @param nonce 24 byte nonce (must be unique per message)
     * @param key 32 byte symmetric key
     * @return Ciphertext with MAC (plaintext.size + 16 bytes)
     */
    fun seal(plaintext: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        require(nonce.size == NONCE_BYTES) { "Nonce must be $NONCE_BYTES bytes" }
        require(key.size == KEY_BYTES) { "Key must be $KEY_BYTES bytes" }

        val ciphertext = ByteArray(plaintext.size + MAC_BYTES)
        val success = sodium.cryptoSecretBoxEasy(
            ciphertext,
            plaintext,
            plaintext.size.toLong(),
            nonce,
            key
        )

        if (!success) {
            throw RuntimeException("SecretBox encryption failed")
        }

        return ciphertext
    }

    /**
     * Decrypt ciphertext with symmetric key
     *
     * @param ciphertext Encrypted data with MAC
     * @param nonce 24 byte nonce used during encryption
     * @param key 32 byte symmetric key
     * @return Plaintext
     * @throws RuntimeException if decryption fails (wrong key or tampered data)
     */
    fun open(ciphertext: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        require(nonce.size == NONCE_BYTES) { "Nonce must be $NONCE_BYTES bytes" }
        require(key.size == KEY_BYTES) { "Key must be $KEY_BYTES bytes" }
        require(ciphertext.size >= MAC_BYTES) { "Ciphertext too short" }

        val plaintext = ByteArray(ciphertext.size - MAC_BYTES)
        val success = sodium.cryptoSecretBoxOpenEasy(
            plaintext,
            ciphertext,
            ciphertext.size.toLong(),
            nonce,
            key
        )

        if (!success) {
            throw RuntimeException("SecretBox decryption failed - wrong key or tampered data")
        }

        return plaintext
    }
}
