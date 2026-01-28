package com.whisper2.app.crypto

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.interfaces.SecretBox
import com.whisper2.app.core.DecryptionException
import com.whisper2.app.core.EncryptionException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NaCl SecretBox: XSalsa20-Poly1305 symmetric encryption.
 * Used for attachment encryption.
 */
@Singleton
class NaClSecretBox @Inject constructor(
    private val lazySodium: LazySodiumAndroid
) {
    /**
     * Encrypt data with symmetric key.
     */
    fun seal(message: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        val ciphertext = ByteArray(message.size + SecretBox.MACBYTES)

        val success = lazySodium.cryptoSecretBoxEasy(
            ciphertext,
            message,
            message.size.toLong(),
            nonce,
            key
        )

        if (!success) {
            throw EncryptionException("SecretBox seal failed")
        }

        return ciphertext
    }

    /**
     * Decrypt data with symmetric key.
     */
    fun open(ciphertext: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        val message = ByteArray(ciphertext.size - SecretBox.MACBYTES)

        val success = lazySodium.cryptoSecretBoxOpenEasy(
            message,
            ciphertext,
            ciphertext.size.toLong(),
            nonce,
            key
        )

        if (!success) {
            throw DecryptionException("SecretBox open failed")
        }

        return message
    }
}
