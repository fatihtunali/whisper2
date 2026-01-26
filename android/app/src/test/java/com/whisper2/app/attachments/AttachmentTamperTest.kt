package com.whisper2.app.attachments

import com.whisper2.app.network.api.*
import com.whisper2.app.services.attachments.AttachmentService
import com.whisper2.app.services.attachments.BlobResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Gate 5: Integrity/tamper + cache fallback
 *
 * Tests:
 * - download ciphertext 1 byte corrupted -> decrypt FAIL
 * - tampered fileKeyBox -> decrypt FAIL
 * - tampered fileNonce -> decrypt FAIL
 * - wrong conversation key -> decrypt FAIL
 */
class AttachmentTamperTest {

    private lateinit var api: FakeAttachmentsApi
    private lateinit var blobClient: FakeBlobHttpClient
    private lateinit var cache: FakeAttachmentCache
    private lateinit var service: AttachmentService

    private val conversationKey = ByteArray(32) { (it + 30).toByte() }

    @Before
    fun setup() {
        api = FakeAttachmentsApi()
        blobClient = FakeBlobHttpClient()
        cache = FakeAttachmentCache()

        service = AttachmentService(
            api = api,
            blobClient = blobClient,
            cache = cache,
            keyGenerator = { TestCrypto.generateKey() },
            nonceGenerator = { TestCrypto.generateNonce() },
            encryptor = { plaintext, nonce, key ->
                TestCrypto.encrypt(plaintext, nonce, key)
            },
            decryptor = { ciphertext, nonce, key ->
                TestCrypto.decrypt(ciphertext, nonce, key)
            },
            base64Encoder = { TestBase64.encode(it) },
            base64Decoder = { TestBase64.decode(it) }
        )
    }

    /**
     * Helper to create valid pointer, ciphertext, and individual keys
     */
    private data class TestData(
        val pointer: AttachmentPointer,
        val ciphertext: ByteArray,
        val fileKey: ByteArray,
        val fileNonce: ByteArray,
        val fkNonce: ByteArray,
        val fkCiphertext: ByteArray
    )

    private fun createValidTestData(
        plaintext: ByteArray,
        objectKey: String = "whisper/att/test/uuid.bin"
    ): TestData {
        val fileKey = TestCrypto.generateKey()
        val fileNonce = TestCrypto.generateNonce()
        val ciphertext = TestCrypto.encrypt(plaintext, fileNonce, fileKey)

        val fkNonce = TestCrypto.generateNonce()
        val fkCiphertext = TestCrypto.encrypt(fileKey, fkNonce, conversationKey)

        val pointer = AttachmentPointer(
            objectKey = objectKey,
            contentType = "image/jpeg",
            ciphertextSize = ciphertext.size.toLong(),
            fileNonce = TestBase64.encode(fileNonce),
            fileKeyBox = FileKeyBox(
                nonce = TestBase64.encode(fkNonce),
                ciphertext = TestBase64.encode(fkCiphertext)
            )
        )

        return TestData(pointer, ciphertext, fileKey, fileNonce, fkNonce, fkCiphertext)
    }

    private fun setupDownloadResponse(pointer: AttachmentPointer, ciphertext: ByteArray) {
        api.enqueueDownloadResponse(
            ApiResult.Success(
                PresignDownloadResponse(
                    objectKey = pointer.objectKey,
                    downloadUrl = "https://blob.example.com/download",
                    expiresAtMs = System.currentTimeMillis() + 300000,
                    sizeBytes = ciphertext.size.toLong(),
                    contentType = "image/jpeg"
                )
            )
        )
    }

    // ==========================================================================
    // Gate 5: Tampered ciphertext fails decrypt
    // ==========================================================================

    @Test
    fun `gate5 tampered ciphertext fails decrypt`() = runBlocking {
        val plaintext = ByteArray(64) { 9 }
        val testData = createValidTestData(plaintext)

        // Tamper first byte
        val tamperedCiphertext = testData.ciphertext.copyOf()
        tamperedCiphertext[0] = (tamperedCiphertext[0].toInt() xor 0x01).toByte()

        setupDownloadResponse(testData.pointer, tamperedCiphertext)
        blobClient.enqueueGetResponse(BlobResult.success(tamperedCiphertext))

        try {
            service.downloadAndDecrypt(testData.pointer, conversationKey)
            fail("Should throw DecryptionFailed")
        } catch (e: AttachmentService.AttachmentException.DecryptionFailed) {
            // Expected
        }
    }

    @Test
    fun `gate5 tampered last byte of ciphertext fails`() = runBlocking {
        val plaintext = ByteArray(100) { 5 }
        val testData = createValidTestData(plaintext)

        val tamperedCiphertext = testData.ciphertext.copyOf()
        val lastIndex = tamperedCiphertext.size - 1
        tamperedCiphertext[lastIndex] = (tamperedCiphertext[lastIndex].toInt() xor 0xFF).toByte()

        setupDownloadResponse(testData.pointer, tamperedCiphertext)
        blobClient.enqueueGetResponse(BlobResult.success(tamperedCiphertext))

        try {
            service.downloadAndDecrypt(testData.pointer, conversationKey)
            fail("Should throw DecryptionFailed")
        } catch (e: AttachmentService.AttachmentException.DecryptionFailed) {
            // Expected
        }
    }

    @Test
    fun `gate5 tampered middle byte of ciphertext fails`() = runBlocking {
        val plaintext = ByteArray(200) { 7 }
        val testData = createValidTestData(plaintext)

        val tamperedCiphertext = testData.ciphertext.copyOf()
        val midIndex = tamperedCiphertext.size / 2
        tamperedCiphertext[midIndex] = (tamperedCiphertext[midIndex].toInt() xor 0x55).toByte()

        setupDownloadResponse(testData.pointer, tamperedCiphertext)
        blobClient.enqueueGetResponse(BlobResult.success(tamperedCiphertext))

        try {
            service.downloadAndDecrypt(testData.pointer, conversationKey)
            fail("Should throw DecryptionFailed")
        } catch (e: AttachmentService.AttachmentException.DecryptionFailed) {
            // Expected
        }
    }

    @Test
    fun `gate5 truncated ciphertext fails`() = runBlocking {
        val plaintext = ByteArray(100) { 3 }
        val testData = createValidTestData(plaintext)

        val truncatedCiphertext = testData.ciphertext.copyOf(testData.ciphertext.size - 1)

        setupDownloadResponse(testData.pointer, truncatedCiphertext)
        blobClient.enqueueGetResponse(BlobResult.success(truncatedCiphertext))

        try {
            service.downloadAndDecrypt(testData.pointer, conversationKey)
            fail("Should throw DecryptionFailed")
        } catch (e: AttachmentService.AttachmentException.DecryptionFailed) {
            // Expected
        }
    }

    @Test
    fun `gate5 extended ciphertext fails`() = runBlocking {
        val plaintext = ByteArray(50) { 2 }
        val testData = createValidTestData(plaintext)

        val extendedCiphertext = testData.ciphertext + ByteArray(10) { 0 }

        setupDownloadResponse(testData.pointer, extendedCiphertext)
        blobClient.enqueueGetResponse(BlobResult.success(extendedCiphertext))

        try {
            service.downloadAndDecrypt(testData.pointer, conversationKey)
            fail("Should throw DecryptionFailed")
        } catch (e: AttachmentService.AttachmentException.DecryptionFailed) {
            // Expected
        }
    }

    // ==========================================================================
    // Gate 5: Tampered fileKeyBox fails
    // ==========================================================================

    @Test
    fun `gate5 tampered fileKeyBox ciphertext fails`() = runBlocking {
        val plaintext = ByteArray(50) { 4 }
        val testData = createValidTestData(plaintext)

        // Tamper fileKeyBox ciphertext
        val tamperedFkCiphertext = testData.fkCiphertext.copyOf()
        tamperedFkCiphertext[0] = (tamperedFkCiphertext[0].toInt() xor 0x01).toByte()

        val tamperedPointer = testData.pointer.copy(
            fileKeyBox = FileKeyBox(
                nonce = testData.pointer.fileKeyBox.nonce,
                ciphertext = TestBase64.encode(tamperedFkCiphertext)
            )
        )

        // Don't need download response - should fail at fileKey decryption

        try {
            service.downloadAndDecrypt(tamperedPointer, conversationKey)
            fail("Should throw DecryptionFailed")
        } catch (e: AttachmentService.AttachmentException.DecryptionFailed) {
            // Expected - fails at fileKey decryption
        }
    }

    @Test
    fun `gate5 tampered fileKeyBox nonce fails`() = runBlocking {
        val plaintext = ByteArray(50) { 5 }
        val testData = createValidTestData(plaintext)

        // Tamper fileKeyBox nonce
        val tamperedFkNonce = testData.fkNonce.copyOf()
        tamperedFkNonce[0] = (tamperedFkNonce[0].toInt() xor 0x01).toByte()

        val tamperedPointer = testData.pointer.copy(
            fileKeyBox = FileKeyBox(
                nonce = TestBase64.encode(tamperedFkNonce),
                ciphertext = testData.pointer.fileKeyBox.ciphertext
            )
        )

        try {
            service.downloadAndDecrypt(tamperedPointer, conversationKey)
            fail("Should throw DecryptionFailed")
        } catch (e: AttachmentService.AttachmentException.DecryptionFailed) {
            // Expected
        }
    }

    // ==========================================================================
    // Gate 5: Tampered fileNonce fails
    // ==========================================================================

    @Test
    fun `gate5 tampered fileNonce fails`() = runBlocking {
        val plaintext = ByteArray(60) { 6 }
        val testData = createValidTestData(plaintext)

        // Tamper fileNonce
        val tamperedFileNonce = testData.fileNonce.copyOf()
        tamperedFileNonce[0] = (tamperedFileNonce[0].toInt() xor 0x01).toByte()

        val tamperedPointer = testData.pointer.copy(
            fileNonce = TestBase64.encode(tamperedFileNonce)
        )

        setupDownloadResponse(tamperedPointer, testData.ciphertext)
        blobClient.enqueueGetResponse(BlobResult.success(testData.ciphertext))

        try {
            service.downloadAndDecrypt(tamperedPointer, conversationKey)
            fail("Should throw DecryptionFailed")
        } catch (e: AttachmentService.AttachmentException.DecryptionFailed) {
            // Expected
        }
    }

    // ==========================================================================
    // Gate 5: Wrong conversation key fails
    // ==========================================================================

    @Test
    fun `gate5 wrong conversation key fails`() = runBlocking {
        val plaintext = ByteArray(70) { 8 }
        val testData = createValidTestData(plaintext)

        val wrongConversationKey = ByteArray(32) { (it + 100).toByte() }

        try {
            service.downloadAndDecrypt(testData.pointer, wrongConversationKey)
            fail("Should throw DecryptionFailed")
        } catch (e: AttachmentService.AttachmentException.DecryptionFailed) {
            // Expected - can't decrypt fileKey
        }
    }

    @Test
    fun `gate5 single bit different conversation key fails`() = runBlocking {
        val plaintext = ByteArray(50) { 9 }
        val testData = createValidTestData(plaintext)

        val almostKey = conversationKey.copyOf()
        almostKey[0] = (almostKey[0].toInt() xor 0x01).toByte()

        try {
            service.downloadAndDecrypt(testData.pointer, almostKey)
            fail("Should throw DecryptionFailed")
        } catch (e: AttachmentService.AttachmentException.DecryptionFailed) {
            // Expected
        }
    }

    // ==========================================================================
    // Gate 5: Tamper failure does not cache
    // ==========================================================================

    @Test
    fun `gate5 tamper failure does not cache bad data`() = runBlocking {
        val plaintext = ByteArray(100) { 10 }
        val testData = createValidTestData(plaintext)

        val tamperedCiphertext = testData.ciphertext.copyOf()
        tamperedCiphertext[0] = (tamperedCiphertext[0].toInt() xor 0x01).toByte()

        setupDownloadResponse(testData.pointer, tamperedCiphertext)
        blobClient.enqueueGetResponse(BlobResult.success(tamperedCiphertext))

        try {
            service.downloadAndDecrypt(testData.pointer, conversationKey)
        } catch (e: AttachmentService.AttachmentException.DecryptionFailed) {
            // Expected
        }

        // Should NOT be cached
        assertEquals(0, cache.size())
        assertNull(cache.getIfPresent(testData.pointer.objectKey))
    }

    // ==========================================================================
    // Gate 5: Standalone secretbox tamper tests
    // ==========================================================================

    @Test
    fun `gate5 secretbox tampered ciphertext fails decrypt standalone`() {
        val fileKey = TestCrypto.generateKey()
        val fileNonce = TestCrypto.generateNonce()
        val plain = ByteArray(64) { 9 }

        val ct = TestCrypto.encrypt(plain, fileNonce, fileKey).toMutableList()
        ct[0] = (ct[0].toInt() xor 0x01).toByte()

        assertThrows(Exception::class.java) {
            TestCrypto.decrypt(ct.toByteArray(), fileNonce, fileKey)
        }
    }

    @Test
    fun `gate5 empty ciphertext fails`() {
        val fileKey = TestCrypto.generateKey()
        val fileNonce = TestCrypto.generateNonce()

        assertThrows(Exception::class.java) {
            TestCrypto.decrypt(ByteArray(0), fileNonce, fileKey)
        }
    }

    @Test
    fun `gate5 ciphertext too short fails`() {
        val fileKey = TestCrypto.generateKey()
        val fileNonce = TestCrypto.generateNonce()

        // Less than MAC size (16 bytes)
        assertThrows(Exception::class.java) {
            TestCrypto.decrypt(ByteArray(10), fileNonce, fileKey)
        }
    }

    // ==========================================================================
    // Gate 5: Valid pointer + ciphertext succeeds (control test)
    // ==========================================================================

    @Test
    fun `gate5 valid data decrypts successfully`() = runBlocking {
        val plaintext = "valid test data".toByteArray()
        val testData = createValidTestData(plaintext)

        setupDownloadResponse(testData.pointer, testData.ciphertext)
        blobClient.enqueueGetResponse(BlobResult.success(testData.ciphertext))

        val result = service.downloadAndDecrypt(testData.pointer, conversationKey)

        assertEquals("valid test data", String(result, Charsets.UTF_8))
    }
}
