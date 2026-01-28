package com.whisper2.app.crypto

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.interfaces.Sign
import com.whisper2.app.core.SignatureVerificationException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ed25519 signatures.
 * CRITICAL: Server expects SHA256 pre-hash for challenge signing.
 */
@Singleton
class Signatures @Inject constructor(
    private val lazySodium: LazySodiumAndroid
) {
    /**
     * Sign message with Ed25519 private key.
     */
    fun sign(message: ByteArray, privateKey: ByteArray): ByteArray {
        val signature = ByteArray(Sign.BYTES)
        lazySodium.cryptoSignDetached(signature, message, message.size.toLong(), privateKey)
        return signature
    }

    /**
     * Verify Ed25519 signature.
     */
    fun verify(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
        return lazySodium.cryptoSignVerifyDetached(signature, message, message.size, publicKey)
    }

    /**
     * Sign challenge for authentication.
     * Server expects: Ed25519_Sign(SHA256(challengeBytes), privateKey)
     */
    fun signChallenge(challenge: ByteArray, privateKey: ByteArray): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256").digest(challenge)
        return sign(hash, privateKey)
    }

    /**
     * Verify challenge signature.
     */
    fun verifyChallenge(challenge: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
        val hash = MessageDigest.getInstance("SHA-256").digest(challenge)
        return verify(hash, signature, publicKey)
    }
}
