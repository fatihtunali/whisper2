package com.whisper2.app.attachments

import org.junit.Assert.*
import org.junit.Test

/**
 * Gate 2: Encrypt/Decrypt (secretbox) correctness
 *
 * Tests:
 * - fileKey + fileNonce secretbox encrypt/decrypt roundtrip
 * - wrong fileKey -> decrypt FAIL
 * - tampered ciphertext -> decrypt FAIL
 *
 * Uses TestCrypto for JVM-compatible testing without native libraries.
 */
class AttachmentEncryptDecryptTest {

    // ==========================================================================
    // Gate 2: Roundtrip encryption/decryption
    // ==========================================================================

    @Test
    fun `gate2 secretbox file roundtrip`() {
        val fileKey = TestCrypto.generateKey()
        val fileNonce = TestCrypto.generateNonce()
        val plain = ByteArray(1024) { (it % 251).toByte() }

        val ct = TestCrypto.encrypt(plain, fileNonce, fileKey)
        val out = TestCrypto.decrypt(ct, fileNonce, fileKey)

        assertArrayEquals(plain, out)
    }

    @Test
    fun `gate2 roundtrip with small data`() {
        val fileKey = TestCrypto.generateKey()
        val fileNonce = TestCrypto.generateNonce()
        val plain = "hello".toByteArray(Charsets.UTF_8)

        val ct = TestCrypto.encrypt(plain, fileNonce, fileKey)
        val out = TestCrypto.decrypt(ct, fileNonce, fileKey)

        assertEquals("hello", String(out, Charsets.UTF_8))
    }

    @Test
    fun `gate2 roundtrip with large data`() {
        val fileKey = TestCrypto.generateKey()
        val fileNonce = TestCrypto.generateNonce()
        val plain = ByteArray(1024 * 1024) { (it % 256).toByte() } // 1MB

        val ct = TestCrypto.encrypt(plain, fileNonce, fileKey)
        val out = TestCrypto.decrypt(ct, fileNonce, fileKey)

        assertArrayEquals(plain, out)
    }

    @Test
    fun `gate2 roundtrip with empty data`() {
        val fileKey = TestCrypto.generateKey()
        val fileNonce = TestCrypto.generateNonce()
        val plain = ByteArray(0)

        val ct = TestCrypto.encrypt(plain, fileNonce, fileKey)
        val out = TestCrypto.decrypt(ct, fileNonce, fileKey)

        assertArrayEquals(plain, out)
        assertEquals(0, out.size)
    }

    @Test
    fun `gate2 ciphertext is larger than plaintext by MAC size`() {
        val fileKey = TestCrypto.generateKey()
        val fileNonce = TestCrypto.generateNonce()
        val plain = ByteArray(100)

        val ct = TestCrypto.encrypt(plain, fileNonce, fileKey)

        assertEquals(100 + TestCrypto.MAC_SIZE, ct.size)
        assertEquals(100 + 16, ct.size) // 16 = MAC
    }

    // ==========================================================================
    // Gate 2: Wrong key fails
    // ==========================================================================

    @Test
    fun `gate2 wrong key fails`() {
        val fileKey = TestCrypto.generateKey()
        val wrongKey = TestCrypto.generateKey()
        val fileNonce = TestCrypto.generateNonce()
        val plain = "hello".toByteArray()

        val ct = TestCrypto.encrypt(plain, fileNonce, fileKey)

        assertThrows(Exception::class.java) {
            TestCrypto.decrypt(ct, fileNonce, wrongKey)
        }
    }

    @Test
    fun `gate2 wrong key fails with large data`() {
        val fileKey = TestCrypto.generateKey()
        val wrongKey = TestCrypto.generateKey()
        val fileNonce = TestCrypto.generateNonce()
        val plain = ByteArray(10000) { it.toByte() }

        val ct = TestCrypto.encrypt(plain, fileNonce, fileKey)

        assertThrows(Exception::class.java) {
            TestCrypto.decrypt(ct, fileNonce, wrongKey)
        }
    }

    @Test
    fun `gate2 single byte different key fails`() {
        val fileKey = TestCrypto.generateKey()
        val almostKey = fileKey.copyOf()
        almostKey[0] = (almostKey[0].toInt() xor 0x01).toByte() // Flip one bit

        val fileNonce = TestCrypto.generateNonce()
        val plain = "secret data".toByteArray()

        val ct = TestCrypto.encrypt(plain, fileNonce, fileKey)

        assertThrows(Exception::class.java) {
            TestCrypto.decrypt(ct, fileNonce, almostKey)
        }
    }

    // ==========================================================================
    // Gate 2: Wrong nonce fails
    // ==========================================================================

    @Test
    fun `gate2 wrong nonce fails`() {
        val fileKey = TestCrypto.generateKey()
        val fileNonce = TestCrypto.generateNonce()
        val wrongNonce = TestCrypto.generateNonce()
        val plain = "secret".toByteArray()

        val ct = TestCrypto.encrypt(plain, fileNonce, fileKey)

        assertThrows(Exception::class.java) {
            TestCrypto.decrypt(ct, wrongNonce, fileKey)
        }
    }

    // ==========================================================================
    // Gate 2: Tampered ciphertext fails
    // ==========================================================================

    @Test
    fun `gate2 tampered ciphertext fails`() {
        val fileKey = TestCrypto.generateKey()
        val fileNonce = TestCrypto.generateNonce()
        val plain = ByteArray(64) { 9 }

        val ct = TestCrypto.encrypt(plain, fileNonce, fileKey).toMutableList()
        ct[0] = (ct[0].toInt() xor 0x01).toByte() // Flip first bit

        assertThrows(Exception::class.java) {
            TestCrypto.decrypt(ct.toByteArray(), fileNonce, fileKey)
        }
    }

    @Test
    fun `gate2 tampered last byte fails`() {
        val fileKey = TestCrypto.generateKey()
        val fileNonce = TestCrypto.generateNonce()
        val plain = ByteArray(100) { 5 }

        val ct = TestCrypto.encrypt(plain, fileNonce, fileKey)
        val tampered = ct.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0xFF).toByte()

        assertThrows(Exception::class.java) {
            TestCrypto.decrypt(tampered, fileNonce, fileKey)
        }
    }

    @Test
    fun `gate2 tampered middle byte fails`() {
        val fileKey = TestCrypto.generateKey()
        val fileNonce = TestCrypto.generateNonce()
        val plain = ByteArray(200) { 7 }

        val ct = TestCrypto.encrypt(plain, fileNonce, fileKey)
        val tampered = ct.copyOf()
        tampered[ct.size / 2] = (tampered[ct.size / 2].toInt() xor 0x55).toByte()

        assertThrows(Exception::class.java) {
            TestCrypto.decrypt(tampered, fileNonce, fileKey)
        }
    }

    @Test
    fun `gate2 truncated ciphertext fails`() {
        val fileKey = TestCrypto.generateKey()
        val fileNonce = TestCrypto.generateNonce()
        val plain = ByteArray(100) { 3 }

        val ct = TestCrypto.encrypt(plain, fileNonce, fileKey)
        val truncated = ct.copyOf(ct.size - 1)

        assertThrows(Exception::class.java) {
            TestCrypto.decrypt(truncated, fileNonce, fileKey)
        }
    }

    @Test
    fun `gate2 extended ciphertext fails`() {
        val fileKey = TestCrypto.generateKey()
        val fileNonce = TestCrypto.generateNonce()
        val plain = ByteArray(50) { 2 }

        val ct = TestCrypto.encrypt(plain, fileNonce, fileKey)
        val extended = ct + ByteArray(10) { 0 }

        assertThrows(Exception::class.java) {
            TestCrypto.decrypt(extended, fileNonce, fileKey)
        }
    }

    // ==========================================================================
    // Gate 2: Key and nonce size validation
    // ==========================================================================

    @Test
    fun `gate2 key must be 32 bytes`() {
        val wrongSizeKey = ByteArray(16) // Too short
        val fileNonce = TestCrypto.generateNonce()
        val plain = "test".toByteArray()

        assertThrows(Exception::class.java) {
            TestCrypto.encrypt(plain, fileNonce, wrongSizeKey)
        }
    }

    @Test
    fun `gate2 nonce must be 24 bytes`() {
        val fileKey = TestCrypto.generateKey()
        val wrongSizeNonce = ByteArray(12) // Too short
        val plain = "test".toByteArray()

        assertThrows(Exception::class.java) {
            TestCrypto.encrypt(plain, wrongSizeNonce, fileKey)
        }
    }

    // ==========================================================================
    // Gate 2: Random key generation
    // ==========================================================================

    @Test
    fun `gate2 generated keys are 32 bytes`() {
        val key = TestCrypto.generateKey()
        assertEquals(32, key.size)
    }

    @Test
    fun `gate2 generated nonces are 24 bytes`() {
        val nonce = TestCrypto.generateNonce()
        assertEquals(24, nonce.size)
    }

    @Test
    fun `gate2 generated keys are random`() {
        val key1 = TestCrypto.generateKey()
        val key2 = TestCrypto.generateKey()

        assertFalse("Keys should be different", key1.contentEquals(key2))
    }

    @Test
    fun `gate2 generated nonces are random`() {
        val nonce1 = TestCrypto.generateNonce()
        val nonce2 = TestCrypto.generateNonce()

        assertFalse("Nonces should be different", nonce1.contentEquals(nonce2))
    }

    // ==========================================================================
    // Gate 2: FileKeyBox encryption (fileKey encrypted with conversation key)
    // ==========================================================================

    @Test
    fun `gate2 fileKey encryption roundtrip with conversation key`() {
        val fileKey = TestCrypto.generateKey()
        val conversationKey = TestCrypto.generateKey()
        val fkNonce = TestCrypto.generateNonce()

        // Encrypt fileKey with conversation key (simulating fileKeyBox)
        val fkCiphertext = TestCrypto.encrypt(fileKey, fkNonce, conversationKey)

        // Decrypt fileKey
        val decryptedFileKey = TestCrypto.decrypt(fkCiphertext, fkNonce, conversationKey)

        assertArrayEquals(fileKey, decryptedFileKey)
        assertEquals(32, decryptedFileKey.size)
    }

    @Test
    fun `gate2 fileKey encryption fails with wrong conversation key`() {
        val fileKey = TestCrypto.generateKey()
        val conversationKey = TestCrypto.generateKey()
        val wrongConversationKey = TestCrypto.generateKey()
        val fkNonce = TestCrypto.generateNonce()

        val fkCiphertext = TestCrypto.encrypt(fileKey, fkNonce, conversationKey)

        assertThrows(Exception::class.java) {
            TestCrypto.decrypt(fkCiphertext, fkNonce, wrongConversationKey)
        }
    }
}
