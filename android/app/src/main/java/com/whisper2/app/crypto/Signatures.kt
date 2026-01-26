package com.whisper2.app.crypto

import android.util.Base64
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Sign
import com.whisper2.app.core.CryptoException

/**
 * Ed25519 digital signatures
 * Uses LazySodium for server-compatible crypto (libsodium compatible)
 */
object Signatures {

    // MARK: - Lazy Sodium Instance
    private val sodium: LazySodiumAndroid by lazy {
        LazySodiumAndroid(SodiumAndroid())
    }

    // MARK: - Constants

    /** Public key length */
    const val PUBLIC_KEY_LENGTH = Sign.PUBLICKEYBYTES // 32

    /** Secret key length (includes seed + public key for libsodium) */
    const val SECRET_KEY_LENGTH = Sign.SECRETKEYBYTES // 64

    /** Seed length */
    const val SEED_LENGTH = Sign.SEEDBYTES // 32

    /** Signature length */
    const val SIGNATURE_LENGTH = Sign.BYTES // 64

    // MARK: - Key Types

    data class SigningKeyPair(
        val publicKey: ByteArray,   // 32 bytes
        val privateKey: ByteArray   // 64 bytes (seed + public key)
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SigningKeyPair) return false
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
     * Generate Ed25519 key pair from 32-byte seed
     */
    fun keyPairFromSeed(seed: ByteArray): SigningKeyPair {
        if (seed.size < SEED_LENGTH) {
            throw CryptoException.InvalidSeed()
        }

        val publicKey = ByteArray(Sign.PUBLICKEYBYTES)
        val secretKey = ByteArray(Sign.SECRETKEYBYTES)

        // Use first 32 bytes of seed
        val seedBytes = seed.copyOf(SEED_LENGTH)

        val success = sodium.cryptoSignSeedKeypair(publicKey, secretKey, seedBytes)
        if (!success) {
            throw CryptoException.KeyDerivationFailed()
        }

        return SigningKeyPair(publicKey, secretKey)
    }

    /**
     * Generate random Ed25519 signing key pair
     */
    fun generateKeyPair(): SigningKeyPair {
        val publicKey = ByteArray(Sign.PUBLICKEYBYTES)
        val secretKey = ByteArray(Sign.SECRETKEYBYTES)

        val success = sodium.cryptoSignKeypair(publicKey, secretKey)
        if (!success) {
            throw CryptoException.KeyDerivationFailed()
        }

        return SigningKeyPair(publicKey, secretKey)
    }

    // MARK: - Signing

    /**
     * Sign message with Ed25519 private key
     * @param message Data to sign
     * @param privateKey 64-byte Ed25519 secret key
     * @return 64-byte detached signature
     */
    fun sign(message: ByteArray, privateKey: ByteArray): ByteArray {
        if (privateKey.size != SECRET_KEY_LENGTH) {
            throw CryptoException.InvalidPrivateKey()
        }

        val signature = ByteArray(Sign.BYTES)
        val success = sodium.cryptoSignDetached(
            signature,
            message,
            message.size.toLong(),
            privateKey
        )

        if (!success) {
            throw CryptoException.SignatureFailed()
        }

        return signature
    }

    /**
     * Sign message and return base64-encoded signature
     */
    fun signBase64(message: ByteArray, privateKey: ByteArray): String {
        val signature = sign(message, privateKey)
        return Base64.encodeToString(signature, Base64.NO_WRAP)
    }

    // MARK: - Verification

    /**
     * Verify Ed25519 signature
     * @param signature 64-byte signature
     * @param message Original message data
     * @param publicKey 32-byte Ed25519 public key
     * @return true if signature is valid
     */
    fun verify(signature: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean {
        if (publicKey.size != PUBLIC_KEY_LENGTH) {
            return false
        }
        if (signature.size != SIGNATURE_LENGTH) {
            return false
        }

        return sodium.cryptoSignVerifyDetached(
            signature,
            message,
            message.size,
            publicKey
        )
    }

    /**
     * Verify base64-encoded signature
     */
    fun verifyBase64(signatureB64: String, message: ByteArray, publicKey: ByteArray): Boolean {
        return try {
            val signature = Base64.decode(signatureB64, Base64.NO_WRAP)
            verify(signature, message, publicKey)
        } catch (e: Exception) {
            false
        }
    }

    // MARK: - Convenience

    /**
     * Sign string message
     */
    fun sign(message: String, privateKey: ByteArray): ByteArray {
        return sign(message.toByteArray(Charsets.UTF_8), privateKey)
    }

    /**
     * Verify signature for string message
     */
    fun verify(signature: ByteArray, message: String, publicKey: ByteArray): Boolean {
        return verify(signature, message.toByteArray(Charsets.UTF_8), publicKey)
    }
}
