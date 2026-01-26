package com.whisper2.app.attachments

import com.whisper2.app.network.api.*
import com.whisper2.app.services.attachments.AttachmentService
import com.whisper2.app.services.attachments.BlobResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Gate 3: Upload flow end-to-end
 *
 * Tests:
 * - presign upload 200 returns properly
 * - service PUT uploads ciphertext
 * - returned AttachmentPointer has correct fields:
 *   - objectKey matches
 *   - ciphertextSize == uploaded bytes
 *   - fileNonce base64 decodes to 24 bytes
 *   - fileKeyBox nonce decodes to 24 bytes
 */
class AttachmentUploadFlowTest {

    private lateinit var api: FakeAttachmentsApi
    private lateinit var blobClient: FakeBlobHttpClient
    private lateinit var cache: FakeAttachmentCache
    private lateinit var service: AttachmentService

    private val conversationKey = ByteArray(32) { (it + 10).toByte() }

    // Fixed values for predictable tests
    private val fixedFileKey = ByteArray(32) { (it + 1).toByte() }
    private val fixedFileNonce = ByteArray(24) { (it + 2).toByte() }
    private val fixedFkNonce = ByteArray(24) { (it + 3).toByte() }
    private var nonceCallCount = 0

    @Before
    fun setup() {
        api = FakeAttachmentsApi()
        blobClient = FakeBlobHttpClient()
        cache = FakeAttachmentCache()
        nonceCallCount = 0

        service = AttachmentService(
            api = api,
            blobClient = blobClient,
            cache = cache,
            keyGenerator = { fixedFileKey },
            nonceGenerator = {
                if (nonceCallCount++ == 0) fixedFileNonce else fixedFkNonce
            },
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

    // ==========================================================================
    // Gate 3: Presign upload response handling
    // ==========================================================================

    @Test
    fun `gate3 presign upload 200 succeeds`() = runBlocking {
        api.enqueueUploadResponse(
            ApiResult.Success(
                PresignUploadResponse(
                    objectKey = "whisper/att/2024/01/WSP-TEST/uuid.bin",
                    uploadUrl = "https://blob.example.com/upload",
                    expiresAtMs = System.currentTimeMillis() + 300000,
                    headers = mapOf("Content-Type" to "application/octet-stream")
                )
            )
        )
        blobClient.enqueuePutResponse(BlobResult.success())

        val plaintext = ByteArray(1000) { 3 }
        val pointer = service.prepareAndUploadAttachment(plaintext, "image/jpeg", conversationKey)

        assertNotNull(pointer)
    }

    @Test
    fun `gate3 presign upload error throws exception`() = runBlocking {
        api.enqueueUploadResponse(
            ApiResult.Error(ApiErrorResponse.RATE_LIMITED, "Too many requests", 429)
        )

        val plaintext = ByteArray(100) { 1 }

        try {
            service.prepareAndUploadAttachment(plaintext, "image/jpeg", conversationKey)
            fail("Should throw exception")
        } catch (e: AttachmentService.AttachmentException.PresignFailed) {
            assertEquals(ApiErrorResponse.RATE_LIMITED, e.code)
        }
    }

    // ==========================================================================
    // Gate 3: PUT uploads ciphertext
    // ==========================================================================

    @Test
    fun `gate3 service PUT uploads ciphertext`() = runBlocking {
        api.enqueueUploadResponse(
            ApiResult.Success(
                PresignUploadResponse(
                    objectKey = "whisper/att/test/uuid.bin",
                    uploadUrl = "https://blob.example.com/put/uuid.bin",
                    expiresAtMs = System.currentTimeMillis() + 300000,
                    headers = mapOf("Content-Type" to "application/octet-stream")
                )
            )
        )
        blobClient.enqueuePutResponse(BlobResult.success())

        val plaintext = ByteArray(500) { 5 }
        service.prepareAndUploadAttachment(plaintext, "application/pdf", conversationKey)

        assertEquals(1, blobClient.putCallCount)
        assertNotNull(blobClient.lastPutBody)
        // Uploaded should be ciphertext (plaintext + 16 MAC bytes)
        assertEquals(516, blobClient.lastPutBody!!.size)
    }

    @Test
    fun `gate3 PUT failure throws exception`() = runBlocking {
        api.enqueueUploadResponse(
            ApiResult.Success(
                PresignUploadResponse(
                    objectKey = "whisper/att/test/uuid.bin",
                    uploadUrl = "https://blob.example.com/upload",
                    expiresAtMs = System.currentTimeMillis() + 300000,
                    headers = emptyMap()
                )
            )
        )
        blobClient.enqueuePutResponse(BlobResult.failure(500))

        val plaintext = ByteArray(100) { 2 }

        try {
            service.prepareAndUploadAttachment(plaintext, "image/png", conversationKey)
            fail("Should throw exception")
        } catch (e: AttachmentService.AttachmentException.UploadFailed) {
            assertEquals(500, e.httpCode)
        }
    }

    // ==========================================================================
    // Gate 3: AttachmentPointer objectKey
    // ==========================================================================

    @Test
    fun `gate3 pointer objectKey matches presign response`() = runBlocking {
        val expectedObjectKey = "whisper/att/2024/01/WSP-ABCD-EFGH-IJKL/550e8400-uuid.bin"

        api.enqueueUploadResponse(
            ApiResult.Success(
                PresignUploadResponse(
                    objectKey = expectedObjectKey,
                    uploadUrl = "https://blob.example.com/upload",
                    expiresAtMs = System.currentTimeMillis() + 300000,
                    headers = emptyMap()
                )
            )
        )
        blobClient.enqueuePutResponse(BlobResult.success())

        val plaintext = ByteArray(100) { 1 }
        val pointer = service.prepareAndUploadAttachment(plaintext, "image/jpeg", conversationKey)

        assertEquals(expectedObjectKey, pointer.objectKey)
    }

    @Test
    fun `gate3 pointer objectKey contains whisper att path`() = runBlocking {
        api.enqueueUploadResponse(
            ApiResult.Success(
                PresignUploadResponse(
                    objectKey = "whisper/att/2024/01/WSP-TEST/uuid.bin",
                    uploadUrl = "https://blob.example.com/upload",
                    expiresAtMs = System.currentTimeMillis() + 300000,
                    headers = emptyMap()
                )
            )
        )
        blobClient.enqueuePutResponse(BlobResult.success())

        val plaintext = ByteArray(50) { 2 }
        val pointer = service.prepareAndUploadAttachment(plaintext, "video/mp4", conversationKey)

        assertTrue(pointer.objectKey.contains("whisper/att/"))
    }

    // ==========================================================================
    // Gate 3: AttachmentPointer ciphertextSize
    // ==========================================================================

    @Test
    fun `gate3 pointer ciphertextSize equals uploaded bytes`() = runBlocking {
        api.enqueueUploadResponse(
            ApiResult.Success(
                PresignUploadResponse(
                    objectKey = "whisper/att/test/uuid.bin",
                    uploadUrl = "https://blob.example.com/upload",
                    expiresAtMs = System.currentTimeMillis() + 300000,
                    headers = emptyMap()
                )
            )
        )
        blobClient.enqueuePutResponse(BlobResult.success())

        val plaintext = ByteArray(1000) { 3 }
        val pointer = service.prepareAndUploadAttachment(plaintext, "image/jpeg", conversationKey)

        // ciphertextSize should equal uploaded body size
        assertEquals(blobClient.lastPutBody!!.size.toLong(), pointer.ciphertextSize)
        // Which is plaintext + 16 MAC
        assertEquals(1016L, pointer.ciphertextSize)
    }

    @Test
    fun `gate3 pointer ciphertextSize is greater than zero`() = runBlocking {
        api.enqueueUploadResponse(
            ApiResult.Success(
                PresignUploadResponse(
                    objectKey = "whisper/att/test/uuid.bin",
                    uploadUrl = "https://blob.example.com/upload",
                    expiresAtMs = System.currentTimeMillis() + 300000,
                    headers = emptyMap()
                )
            )
        )
        blobClient.enqueuePutResponse(BlobResult.success())

        val plaintext = ByteArray(10) { 4 }
        val pointer = service.prepareAndUploadAttachment(plaintext, "text/plain", conversationKey)

        assertTrue(pointer.ciphertextSize > 0)
    }

    // ==========================================================================
    // Gate 3: AttachmentPointer fileNonce
    // ==========================================================================

    @Test
    fun `gate3 pointer fileNonce base64 decodes to 24 bytes`() = runBlocking {
        api.enqueueUploadResponse(
            ApiResult.Success(
                PresignUploadResponse(
                    objectKey = "whisper/att/test/uuid.bin",
                    uploadUrl = "https://blob.example.com/upload",
                    expiresAtMs = System.currentTimeMillis() + 300000,
                    headers = emptyMap()
                )
            )
        )
        blobClient.enqueuePutResponse(BlobResult.success())

        val plaintext = ByteArray(100) { 5 }
        val pointer = service.prepareAndUploadAttachment(plaintext, "image/gif", conversationKey)

        val fileNonceBytes = TestBase64.decode(pointer.fileNonce)
        assertEquals(24, fileNonceBytes.size)
    }

    @Test
    fun `gate3 pointer fileNonce is valid base64`() = runBlocking {
        api.enqueueUploadResponse(
            ApiResult.Success(
                PresignUploadResponse(
                    objectKey = "whisper/att/test/uuid.bin",
                    uploadUrl = "https://blob.example.com/upload",
                    expiresAtMs = System.currentTimeMillis() + 300000,
                    headers = emptyMap()
                )
            )
        )
        blobClient.enqueuePutResponse(BlobResult.success())

        val plaintext = ByteArray(50) { 6 }
        val pointer = service.prepareAndUploadAttachment(plaintext, "audio/mp3", conversationKey)

        // Should not throw
        val decoded = TestBase64.decode(pointer.fileNonce)
        assertNotNull(decoded)
    }

    // ==========================================================================
    // Gate 3: AttachmentPointer fileKeyBox
    // ==========================================================================

    @Test
    fun `gate3 pointer fileKeyBox nonce decodes to 24 bytes`() = runBlocking {
        api.enqueueUploadResponse(
            ApiResult.Success(
                PresignUploadResponse(
                    objectKey = "whisper/att/test/uuid.bin",
                    uploadUrl = "https://blob.example.com/upload",
                    expiresAtMs = System.currentTimeMillis() + 300000,
                    headers = emptyMap()
                )
            )
        )
        blobClient.enqueuePutResponse(BlobResult.success())

        val plaintext = ByteArray(200) { 7 }
        val pointer = service.prepareAndUploadAttachment(plaintext, "image/png", conversationKey)

        val fkNonceBytes = TestBase64.decode(pointer.fileKeyBox.nonce)
        assertEquals(24, fkNonceBytes.size)
    }

    @Test
    fun `gate3 pointer fileKeyBox ciphertext is valid base64`() = runBlocking {
        api.enqueueUploadResponse(
            ApiResult.Success(
                PresignUploadResponse(
                    objectKey = "whisper/att/test/uuid.bin",
                    uploadUrl = "https://blob.example.com/upload",
                    expiresAtMs = System.currentTimeMillis() + 300000,
                    headers = emptyMap()
                )
            )
        )
        blobClient.enqueuePutResponse(BlobResult.success())

        val plaintext = ByteArray(100) { 8 }
        val pointer = service.prepareAndUploadAttachment(plaintext, "video/webm", conversationKey)

        // Should not throw
        val decoded = TestBase64.decode(pointer.fileKeyBox.ciphertext)
        assertNotNull(decoded)
        assertTrue(decoded.isNotEmpty())
    }

    @Test
    fun `gate3 pointer fileKeyBox ciphertext contains encrypted 32-byte key`() = runBlocking {
        api.enqueueUploadResponse(
            ApiResult.Success(
                PresignUploadResponse(
                    objectKey = "whisper/att/test/uuid.bin",
                    uploadUrl = "https://blob.example.com/upload",
                    expiresAtMs = System.currentTimeMillis() + 300000,
                    headers = emptyMap()
                )
            )
        )
        blobClient.enqueuePutResponse(BlobResult.success())

        val plaintext = ByteArray(100) { 9 }
        val pointer = service.prepareAndUploadAttachment(plaintext, "image/jpeg", conversationKey)

        // Decrypt fileKeyBox with conversationKey
        val fkNonce = TestBase64.decode(pointer.fileKeyBox.nonce)
        val fkCiphertext = TestBase64.decode(pointer.fileKeyBox.ciphertext)
        val decryptedFileKey = TestCrypto.decrypt(fkCiphertext, fkNonce, conversationKey)

        assertEquals(32, decryptedFileKey.size)
    }

    // ==========================================================================
    // Gate 3: AttachmentPointer contentType
    // ==========================================================================

    @Test
    fun `gate3 pointer contentType matches input`() = runBlocking {
        api.enqueueUploadResponse(
            ApiResult.Success(
                PresignUploadResponse(
                    objectKey = "whisper/att/test/uuid.bin",
                    uploadUrl = "https://blob.example.com/upload",
                    expiresAtMs = System.currentTimeMillis() + 300000,
                    headers = emptyMap()
                )
            )
        )
        blobClient.enqueuePutResponse(BlobResult.success())

        val plaintext = ByteArray(100) { 10 }
        val pointer = service.prepareAndUploadAttachment(plaintext, "image/jpeg", conversationKey)

        assertEquals("image/jpeg", pointer.contentType)
    }

    @Test
    fun `gate3 pointer preserves various content types`() = runBlocking {
        val contentTypes = listOf(
            "image/jpeg",
            "image/png",
            "image/gif",
            "video/mp4",
            "audio/mpeg",
            "application/pdf",
            "text/plain"
        )

        for (contentType in contentTypes) {
            nonceCallCount = 0 // Reset nonce counter for each upload
            api.enqueueUploadResponse(
                ApiResult.Success(
                    PresignUploadResponse(
                        objectKey = "whisper/att/test/uuid.bin",
                        uploadUrl = "https://blob.example.com/upload",
                        expiresAtMs = System.currentTimeMillis() + 300000,
                        headers = emptyMap()
                    )
                )
            )
            blobClient.enqueuePutResponse(BlobResult.success())

            val pointer = service.prepareAndUploadAttachment(ByteArray(10), contentType, conversationKey)
            assertEquals("Content type should match: $contentType", contentType, pointer.contentType)
        }
    }

    // ==========================================================================
    // Gate 3: Full flow creates decryptable pointer
    // ==========================================================================

    @Test
    fun `gate3 uploaded attachment can be decrypted with pointer`() = runBlocking {
        api.enqueueUploadResponse(
            ApiResult.Success(
                PresignUploadResponse(
                    objectKey = "whisper/att/test/uuid.bin",
                    uploadUrl = "https://blob.example.com/upload",
                    expiresAtMs = System.currentTimeMillis() + 300000,
                    headers = emptyMap()
                )
            )
        )
        blobClient.enqueuePutResponse(BlobResult.success())

        val originalPlaintext = "Hello, World! This is a test attachment.".toByteArray()
        val pointer = service.prepareAndUploadAttachment(originalPlaintext, "text/plain", conversationKey)

        // Get the uploaded ciphertext
        val uploadedCiphertext = blobClient.lastPutBody!!

        // Decrypt fileKey from pointer
        val fkNonce = TestBase64.decode(pointer.fileKeyBox.nonce)
        val fkCiphertext = TestBase64.decode(pointer.fileKeyBox.ciphertext)
        val fileKey = TestCrypto.decrypt(fkCiphertext, fkNonce, conversationKey)

        // Decrypt attachment
        val fileNonce = TestBase64.decode(pointer.fileNonce)
        val decryptedPlaintext = TestCrypto.decrypt(uploadedCiphertext, fileNonce, fileKey)

        assertArrayEquals(originalPlaintext, decryptedPlaintext)
    }
}
