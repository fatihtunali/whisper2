package com.whisper2.app.services.calls

/**
 * Interface for call crypto operations.
 * Allows mocking in tests without native library dependencies.
 */
interface CallCryptoProvider {
    /**
     * Generate a 24-byte nonce
     */
    fun generateNonce(): ByteArray

    /**
     * Encrypt plaintext using recipient's public key and sender's private key
     *
     * @param plaintext Data to encrypt
     * @param nonce 24-byte nonce
     * @param recipientPublicKey Recipient's X25519 public key (32 bytes)
     * @param senderPrivateKey Sender's X25519 private key (32 bytes)
     * @return Encrypted ciphertext with authentication tag
     */
    fun seal(
        plaintext: ByteArray,
        nonce: ByteArray,
        recipientPublicKey: ByteArray,
        senderPrivateKey: ByteArray
    ): ByteArray

    /**
     * Decrypt ciphertext using sender's public key and recipient's private key
     *
     * @param ciphertext Encrypted data with authentication tag
     * @param nonce 24-byte nonce used for encryption
     * @param senderPublicKey Sender's X25519 public key (32 bytes)
     * @param recipientPrivateKey Recipient's X25519 private key (32 bytes)
     * @return Decrypted plaintext
     * @throws Exception if decryption fails (wrong key or tampered data)
     */
    fun open(
        ciphertext: ByteArray,
        nonce: ByteArray,
        senderPublicKey: ByteArray,
        recipientPrivateKey: ByteArray
    ): ByteArray

    /**
     * Sign canonical message and return base64-encoded signature
     */
    fun signCanonical(
        messageType: String,
        messageId: String,
        from: String,
        to: String,
        timestamp: Long,
        nonce: ByteArray,
        ciphertext: ByteArray,
        privateKey: ByteArray
    ): String

    /**
     * Verify canonical message signature
     */
    fun verifyCanonical(
        signatureB64: String,
        messageType: String,
        messageId: String,
        from: String,
        to: String,
        timestamp: Long,
        nonceB64: String,
        ciphertextB64: String,
        publicKey: ByteArray
    ): Boolean

    /**
     * Decode base64 string with expected length validation
     */
    fun decodeBase64WithLength(encoded: String, expectedLength: Int): ByteArray

    /**
     * Encode bytes to base64
     */
    fun encodeBase64(bytes: ByteArray): String

    /**
     * Decode base64 string
     */
    fun decodeBase64(encoded: String): ByteArray
}
