package com.whisper2.app.crypto

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Box
import com.whisper2.app.core.CryptoException
import java.security.SecureRandom

/**
 * NaCl Box (Curve25519-XSalsa20-Poly1305) implementation
 * Public-key authenticated encryption
 * Uses LazySodium for server-compatible crypto (libsodium)
 */
object NaClBox {

    // MARK: - Lazy Sodium Instance
    private val sodium: LazySodiumAndroid by lazy {
        LazySodiumAndroid(SodiumAndroid())
    }

    // MARK: - Constants

    /** Box overhead (Poly1305 tag) */
    const val BOX_OVERHEAD = Box.MACBYTES // 16

    /** Nonce length for XSalsa20 */
    const val NONCE_LENGTH = Box.NONCEBYTES // 24

    /** Public/Secret key length */
    const val KEY_LENGTH = Box.PUBLICKEYBYTES // 32

    // MARK: - Key Types

    data class KeyPair(
        val publicKey: ByteArray,
        val privateKey: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is KeyPair) return false
            return publicKey.contentEquals(other.publicKey) &&
                    privateKey.contentEquals(other.privateKey)
        }

        override fun hashCode(): Int {
            var result = publicKey.contentHashCode()
            result = 31 * result + privateKey.contentHashCode()
            return result
        }
    }

    // MARK: - Key Generation

    /**
     * Generate X25519 key pair from 32-byte seed
     */
    fun keyPairFromSeed(seed: ByteArray): KeyPair {
        if (seed.size < KEY_LENGTH) {
            throw CryptoException.InvalidSeed()
        }

        val publicKey = ByteArray(Box.PUBLICKEYBYTES)
        val privateKey = ByteArray(Box.SECRETKEYBYTES)

        // Use first 32 bytes of seed
        val seedBytes = seed.copyOf(KEY_LENGTH)

        // LazySodium: crypto_box_seed_keypair
        val success = sodium.cryptoBoxSeedKeypair(publicKey, privateKey, seedBytes)
        if (!success) {
            throw CryptoException.KeyDerivationFailed()
        }

        return KeyPair(publicKey, privateKey)
    }

    /**
     * Generate random X25519 key pair
     */
    fun generateKeyPair(): KeyPair {
        val publicKey = ByteArray(Box.PUBLICKEYBYTES)
        val privateKey = ByteArray(Box.SECRETKEYBYTES)

        val success = sodium.cryptoBoxKeypair(publicKey, privateKey)
        if (!success) {
            throw CryptoException.KeyDerivationFailed()
        }

        return KeyPair(publicKey, privateKey)
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
     * Encrypt message using recipient's public key and sender's private key
     * @param message Plaintext to encrypt
     * @param recipientPublicKey Recipient's X25519 public key (32 bytes)
     * @param senderPrivateKey Sender's X25519 private key (32 bytes)
     * @return Pair of (nonce, ciphertext) where ciphertext includes Poly1305 tag
     */
    fun seal(
        message: ByteArray,
        recipientPublicKey: ByteArray,
        senderPrivateKey: ByteArray
    ): Pair<ByteArray, ByteArray> {
        if (recipientPublicKey.size != KEY_LENGTH) {
            throw CryptoException.InvalidPublicKey()
        }
        if (senderPrivateKey.size != KEY_LENGTH) {
            throw CryptoException.InvalidPrivateKey()
        }

        val nonce = generateNonce()
        val ciphertext = sealWithNonce(message, nonce, recipientPublicKey, senderPrivateKey)

        return Pair(nonce, ciphertext)
    }

    /**
     * Encrypt with provided nonce
     */
    fun sealWithNonce(
        message: ByteArray,
        nonce: ByteArray,
        recipientPublicKey: ByteArray,
        senderPrivateKey: ByteArray
    ): ByteArray {
        if (nonce.size != NONCE_LENGTH) {
            throw CryptoException.InvalidNonce()
        }
        if (recipientPublicKey.size != KEY_LENGTH) {
            throw CryptoException.InvalidPublicKey()
        }
        if (senderPrivateKey.size != KEY_LENGTH) {
            throw CryptoException.InvalidPrivateKey()
        }

        val ciphertext = ByteArray(message.size + BOX_OVERHEAD)
        val success = sodium.cryptoBoxEasy(
            ciphertext,
            message,
            message.size.toLong(),
            nonce,
            recipientPublicKey,
            senderPrivateKey
        )

        if (!success) {
            throw CryptoException.EncryptionFailed()
        }

        return ciphertext
    }

    // MARK: - Decryption

    /**
     * Decrypt message using sender's public key and recipient's private key
     * @param ciphertext Encrypted data with Poly1305 tag
     * @param nonce 24-byte nonce used for encryption
     * @param senderPublicKey Sender's X25519 public key (32 bytes)
     * @param recipientPrivateKey Recipient's X25519 private key (32 bytes)
     * @return Decrypted plaintext
     */
    fun open(
        ciphertext: ByteArray,
        nonce: ByteArray,
        senderPublicKey: ByteArray,
        recipientPrivateKey: ByteArray
    ): ByteArray {
        if (nonce.size != NONCE_LENGTH) {
            throw CryptoException.InvalidNonce()
        }
        if (senderPublicKey.size != KEY_LENGTH) {
            throw CryptoException.InvalidPublicKey()
        }
        if (recipientPrivateKey.size != KEY_LENGTH) {
            throw CryptoException.InvalidPrivateKey()
        }
        if (ciphertext.size < BOX_OVERHEAD) {
            throw CryptoException.DecryptionFailed()
        }

        val plaintext = ByteArray(ciphertext.size - BOX_OVERHEAD)
        val success = sodium.cryptoBoxOpenEasy(
            plaintext,
            ciphertext,
            ciphertext.size.toLong(),
            nonce,
            senderPublicKey,
            recipientPrivateKey
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
    fun sealCombined(
        message: ByteArray,
        recipientPublicKey: ByteArray,
        senderPrivateKey: ByteArray
    ): ByteArray {
        val (nonce, ciphertext) = seal(message, recipientPublicKey, senderPrivateKey)
        return nonce + ciphertext
    }

    /**
     * Decrypt combined nonce + ciphertext
     */
    fun openCombined(
        combined: ByteArray,
        senderPublicKey: ByteArray,
        recipientPrivateKey: ByteArray
    ): ByteArray {
        if (combined.size <= NONCE_LENGTH + BOX_OVERHEAD) {
            throw CryptoException.DecryptionFailed()
        }

        val nonce = combined.copyOfRange(0, NONCE_LENGTH)
        val ciphertext = combined.copyOfRange(NONCE_LENGTH, combined.size)

        return open(ciphertext, nonce, senderPublicKey, recipientPrivateKey)
    }
}
