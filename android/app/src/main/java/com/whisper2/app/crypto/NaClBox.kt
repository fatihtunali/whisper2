package com.whisper2.app.crypto

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.interfaces.Box
import com.whisper2.app.core.DecryptionException
import com.whisper2.app.core.EncryptionException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NaCl Box: X25519 key exchange + XSalsa20-Poly1305 encryption.
 * Used for E2E encrypted messages between users.
 */
@Singleton
class NaClBox @Inject constructor(
    private val lazySodium: LazySodiumAndroid
) {
    /**
     * Encrypt message to recipient using sender's private key.
     * Output: ciphertext (includes 16-byte MAC)
     */
    fun seal(
        message: ByteArray,
        nonce: ByteArray,
        recipientPublicKey: ByteArray,
        senderPrivateKey: ByteArray
    ): ByteArray {
        val ciphertext = ByteArray(message.size + Box.MACBYTES)

        val success = lazySodium.cryptoBoxEasy(
            ciphertext,
            message,
            message.size.toLong(),
            nonce,
            recipientPublicKey,
            senderPrivateKey
        )

        if (!success) {
            throw EncryptionException("NaCl box seal failed")
        }

        return ciphertext
    }

    /**
     * Decrypt message from sender using recipient's private key.
     */
    fun open(
        ciphertext: ByteArray,
        nonce: ByteArray,
        senderPublicKey: ByteArray,
        recipientPrivateKey: ByteArray
    ): ByteArray {
        val message = ByteArray(ciphertext.size - Box.MACBYTES)

        val success = lazySodium.cryptoBoxOpenEasy(
            message,
            ciphertext,
            ciphertext.size.toLong(),
            nonce,
            senderPublicKey,
            recipientPrivateKey
        )

        if (!success) {
            throw DecryptionException("NaCl box open failed")
        }

        return message
    }
}
