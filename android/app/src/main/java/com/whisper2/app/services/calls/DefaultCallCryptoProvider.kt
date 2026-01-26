package com.whisper2.app.services.calls

import com.whisper2.app.core.utils.Base64Strict
import com.whisper2.app.crypto.CanonicalSigning
import com.whisper2.app.crypto.NaClBox

/**
 * Default implementation of CallCryptoProvider using real crypto libraries.
 */
class DefaultCallCryptoProvider : CallCryptoProvider {

    override fun generateNonce(): ByteArray = NaClBox.generateNonce()

    override fun seal(
        plaintext: ByteArray,
        nonce: ByteArray,
        recipientPublicKey: ByteArray,
        senderPrivateKey: ByteArray
    ): ByteArray {
        return NaClBox.sealWithNonce(plaintext, nonce, recipientPublicKey, senderPrivateKey)
    }

    override fun open(
        ciphertext: ByteArray,
        nonce: ByteArray,
        senderPublicKey: ByteArray,
        recipientPrivateKey: ByteArray
    ): ByteArray {
        return NaClBox.open(ciphertext, nonce, senderPublicKey, recipientPrivateKey)
    }

    override fun signCanonical(
        messageType: String,
        messageId: String,
        from: String,
        to: String,
        timestamp: Long,
        nonce: ByteArray,
        ciphertext: ByteArray,
        privateKey: ByteArray
    ): String {
        return CanonicalSigning.signCanonicalBase64(
            messageType = messageType,
            messageId = messageId,
            from = from,
            to = to,
            timestamp = timestamp,
            nonce = nonce,
            ciphertext = ciphertext,
            privateKey = privateKey
        )
    }

    override fun verifyCanonical(
        signatureB64: String,
        messageType: String,
        messageId: String,
        from: String,
        to: String,
        timestamp: Long,
        nonceB64: String,
        ciphertextB64: String,
        publicKey: ByteArray
    ): Boolean {
        return CanonicalSigning.verifyCanonicalBase64(
            signatureB64 = signatureB64,
            messageType = messageType,
            messageId = messageId,
            from = from,
            to = to,
            timestamp = timestamp,
            nonceB64 = nonceB64,
            ciphertextB64 = ciphertextB64,
            publicKey = publicKey
        )
    }

    override fun decodeBase64WithLength(encoded: String, expectedLength: Int): ByteArray {
        return Base64Strict.decodeWithLength(encoded, expectedLength)
    }

    override fun encodeBase64(bytes: ByteArray): String {
        return Base64Strict.encode(bytes)
    }

    override fun decodeBase64(encoded: String): ByteArray {
        return Base64Strict.decode(encoded)
    }
}
