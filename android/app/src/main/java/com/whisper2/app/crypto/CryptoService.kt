package com.whisper2.app.crypto

import android.util.Base64
import com.goterl.lazysodium.LazySodiumAndroid
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Main crypto orchestrator.
 * Provides unified interface to all crypto operations.
 */
@Singleton
class CryptoService @Inject constructor(
    private val lazySodium: LazySodiumAndroid,
    private val nonceGenerator: NonceGenerator,
    private val naclBox: NaClBox,
    private val naclSecretBox: NaClSecretBox,
    private val signatures: Signatures,
    private val canonicalSigning: CanonicalSigning
) {
    private val keyDerivation = KeyDerivation(lazySodium)
    private val secureRandom = SecureRandom()

    companion object {
        // Base32 alphabet (matches server exactly)
        private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    }

    fun generateMnemonic(): String = BIP39.generateMnemonic(secureRandom)
    fun generateMnemonic24(): String = BIP39.generateMnemonic24(secureRandom)
    fun validateMnemonic(mnemonic: String): Boolean = BIP39.validateMnemonic(mnemonic)
    fun deriveAllKeys(mnemonic: String) = keyDerivation.deriveAllKeys(mnemonic)
    fun deriveKeys(mnemonic: String) = keyDerivation.deriveAllKeys(mnemonic)
    fun generateNonce() = nonceGenerator.generateNonce()
    fun generateKey() = nonceGenerator.generateKey()

    /**
     * Generate a random Whisper ID in format WSP-XXXX-XXXX-XXXX
     * Matches server implementation exactly:
     * - Uses Base32 alphabet (A-Z, 2-7)
     * - Last 2 characters of 3rd block are checksums
     */
    fun generateWhisperId(): String {
        val randomBytes = ByteArray(10)
        secureRandom.nextBytes(randomBytes)

        // Generate 10 data characters
        val dataChars = CharArray(10)
        for (i in 0 until 10) {
            val index = (randomBytes[i].toInt() and 0xFF) % 32
            dataChars[i] = BASE32_ALPHABET[index]
        }

        // Compute checksum1: XOR of all indices % 32
        var checksum1 = 0
        for (i in 0 until 10) {
            checksum1 = checksum1 xor ((randomBytes[i].toInt() and 0xFF) % 32)
        }
        checksum1 = checksum1 % 32

        // Compute checksum2: sum of all bytes % 32
        var checksum2 = 0
        for (i in 0 until 10) {
            checksum2 = (checksum2 + (randomBytes[i].toInt() and 0xFF)) % 32
        }

        // Format: WSP-XXXX-XXXX-XXXX
        // Block1 = chars[0-3], Block2 = chars[4-7], Block3 = chars[8-9] + checksums
        val block1 = String(dataChars, 0, 4)
        val block2 = String(dataChars, 4, 4)
        val block3 = String(dataChars, 8, 2) + BASE32_ALPHABET[checksum1] + BASE32_ALPHABET[checksum2]

        return "WSP-$block1-$block2-$block3"
    }

    /**
     * Derive Whisper ID from public key deterministically.
     * Uses rejection sampling to avoid modulo bias.
     * Format: WSP-XXXX-XXXX-XXXX with Base32 alphabet and checksums.
     */
    fun deriveWhisperIdFromPublicKey(publicKey: ByteArray): String {
        val charCount = 32 // Base32 has 32 characters
        val dataBytes = mutableListOf<Int>()

        var byteIndex = 0
        var currentBytes = publicKey.copyOf()

        // Generate 10 data bytes (with rejection sampling)
        while (dataBytes.size < 10) {
            // If we've exhausted all bytes, extend with hash
            if (byteIndex >= currentBytes.size) {
                val counter = byteIndex / publicKey.size
                val hashInput = ByteArray(publicKey.size + 4)
                System.arraycopy(publicKey, 0, hashInput, 0, publicKey.size)
                hashInput[publicKey.size] = ((counter shr 24) and 0xff).toByte()
                hashInput[publicKey.size + 1] = ((counter shr 16) and 0xff).toByte()
                hashInput[publicKey.size + 2] = ((counter shr 8) and 0xff).toByte()
                hashInput[publicKey.size + 3] = (counter and 0xff).toByte()

                currentBytes = MessageDigest.getInstance("SHA-256").digest(hashInput)
                byteIndex = byteIndex % publicKey.size
            }

            val byte = currentBytes[byteIndex % currentBytes.size].toInt() and 0xFF
            byteIndex++

            // Rejection sampling to avoid modulo bias
            // 256 % 32 = 0, so no bias for Base32, but keep the pattern for consistency
            val limit = 256 - (256 % charCount)
            if (byte >= limit) {
                continue // Reject this byte and try next
            }

            dataBytes.add(byte)
        }

        // Generate 10 data characters
        val dataChars = CharArray(10)
        for (i in 0 until 10) {
            val index = dataBytes[i] % 32
            dataChars[i] = BASE32_ALPHABET[index]
        }

        // Compute checksum1: XOR of all indices % 32
        var checksum1 = 0
        for (i in 0 until 10) {
            checksum1 = checksum1 xor (dataBytes[i] % 32)
        }
        checksum1 = checksum1 % 32

        // Compute checksum2: sum of all bytes % 32
        var checksum2 = 0
        for (i in 0 until 10) {
            checksum2 = (checksum2 + dataBytes[i]) % 32
        }

        // Format: WSP-XXXX-XXXX-XXXX
        // Block1 = chars[0-3], Block2 = chars[4-7], Block3 = chars[8-9] + checksums
        val block1 = String(dataChars, 0, 4)
        val block2 = String(dataChars, 4, 4)
        val block3 = String(dataChars, 8, 2) + BASE32_ALPHABET[checksum1] + BASE32_ALPHABET[checksum2]

        return "WSP-$block1-$block2-$block3"
    }

    /**
     * Derive Whisper ID from base64-encoded public key
     */
    fun deriveWhisperIdFromPublicKeyBase64(publicKeyBase64: String): String {
        return try {
            // Sanitize: URL-decoded + becomes space, fix it back
            val sanitized = publicKeyBase64.replace(" ", "+").trim()
            val publicKeyData = Base64.decode(sanitized, Base64.NO_WRAP)
            deriveWhisperIdFromPublicKey(publicKeyData)
        } catch (e: Exception) {
            generateWhisperId()
        }
    }

    fun boxSeal(message: ByteArray, nonce: ByteArray, recipientPubKey: ByteArray, senderPrivKey: ByteArray) =
        naclBox.seal(message, nonce, recipientPubKey, senderPrivKey)

    fun boxOpen(ciphertext: ByteArray, nonce: ByteArray, senderPubKey: ByteArray, recipientPrivKey: ByteArray) =
        naclBox.open(ciphertext, nonce, senderPubKey, recipientPrivKey)

    fun secretBoxSeal(message: ByteArray, nonce: ByteArray, key: ByteArray) =
        naclSecretBox.seal(message, nonce, key)

    fun secretBoxOpen(ciphertext: ByteArray, nonce: ByteArray, key: ByteArray) =
        naclSecretBox.open(ciphertext, nonce, key)

    fun signChallenge(challenge: ByteArray, privateKey: ByteArray) =
        signatures.signChallenge(challenge, privateKey)

    fun signMessage(
        messageType: String,
        messageId: String,
        from: String,
        toOrGroupId: String,
        timestamp: Long,
        nonce: ByteArray,
        ciphertext: ByteArray,
        privateKey: ByteArray
    ) = canonicalSigning.signMessage(messageType, messageId, from, toOrGroupId, timestamp, nonce, ciphertext, privateKey)

    fun verifyMessage(
        messageType: String,
        messageId: String,
        from: String,
        toOrGroupId: String,
        timestamp: Long,
        nonce: ByteArray,
        ciphertext: ByteArray,
        signature: ByteArray,
        publicKey: ByteArray
    ) = canonicalSigning.verifyMessage(messageType, messageId, from, toOrGroupId, timestamp, nonce, ciphertext, signature, publicKey)
}
