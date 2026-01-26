package com.whisper2.app.attachments

import com.whisper2.app.network.api.*
import com.whisper2.app.services.attachments.AttachmentService
import com.whisper2.app.services.attachments.BlobResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Gate 1: Presign contract + Auth header
 *
 * Tests:
 * - /attachments/presign/upload includes Authorization header
 * - /attachments/presign/download includes Authorization header
 * - upload response headers map is applied to PUT request (Content-Type)
 */
class AttachmentPresignAuthTest {

    private lateinit var api: FakeAttachmentsApi
    private lateinit var blobClient: FakeBlobHttpClient
    private lateinit var cache: FakeAttachmentCache
    private lateinit var service: AttachmentService

    private val conversationKey = ByteArray(32) { it.toByte() }

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
                // First call = fileNonce, second call = fkNonce
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
    // Gate 1: Upload presign has auth header
    // ==========================================================================

    @Test
    fun `gate1 presign upload includes auth header`() = runBlocking {
        api.sessionToken = "sess_abc123"
        api.enqueueUploadResponse(
            ApiResult.Success(
                PresignUploadResponse(
                    objectKey = "whisper/att/test/uuid.bin",
                    uploadUrl = "https://blob.example.com/upload",
                    expiresAtMs = System.currentTimeMillis() + 300000,
                    headers = mapOf("Content-Type" to "application/octet-stream")
                )
            )
        )
        blobClient.enqueuePutResponse(BlobResult.success())

        val plaintext = ByteArray(100) { 1 }
        service.prepareAndUploadAttachment(plaintext, "image/jpeg", conversationKey)

        assertEquals("Bearer sess_abc123", api.lastAuthHeader)
    }

    @Test
    fun `gate1 presign upload fails without session token`() = runBlocking {
        api.sessionToken = null

        val plaintext = ByteArray(100) { 1 }

        try {
            service.prepareAndUploadAttachment(plaintext, "image/jpeg", conversationKey)
            fail("Should throw exception")
        } catch (e: AttachmentService.AttachmentException.PresignFailed) {
            assertEquals(ApiErrorResponse.AUTH_FAILED, e.code)
        }
    }

    @Test
    fun `gate1 presign upload request has correct content type and size`() = runBlocking {
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

        val plaintext = ByteArray(500) { 2 }
        service.prepareAndUploadAttachment(plaintext, "video/mp4", conversationKey)

        assertNotNull(api.lastUploadRequest)
        assertEquals("video/mp4", api.lastUploadRequest?.contentType)
        // Size is ciphertext size (plaintext + 16 MAC bytes)
        assertEquals(516L, api.lastUploadRequest!!.sizeBytes)
    }

    // ==========================================================================
    // Gate 1: Download presign has auth header
    // ==========================================================================

    @Test
    fun `gate1 presign download includes auth header`() = runBlocking {
        api.sessionToken = "sess_download_token"

        val plaintext = "test content".toByteArray()
        val ciphertext = TestCrypto.encrypt(plaintext, fixedFileNonce, fixedFileKey)
        val fkCiphertext = TestCrypto.encrypt(fixedFileKey, fixedFkNonce, conversationKey)

        api.enqueueDownloadResponse(
            ApiResult.Success(
                PresignDownloadResponse(
                    objectKey = "whisper/att/test/uuid.bin",
                    downloadUrl = "https://blob.example.com/download",
                    expiresAtMs = System.currentTimeMillis() + 300000,
                    sizeBytes = ciphertext.size.toLong(),
                    contentType = "image/jpeg"
                )
            )
        )
        blobClient.enqueueGetResponse(BlobResult.success(ciphertext))

        val pointer = AttachmentPointer(
            objectKey = "whisper/att/test/uuid.bin",
            contentType = "image/jpeg",
            ciphertextSize = ciphertext.size.toLong(),
            fileNonce = TestBase64.encode(fixedFileNonce),
            fileKeyBox = FileKeyBox(
                nonce = TestBase64.encode(fixedFkNonce),
                ciphertext = TestBase64.encode(fkCiphertext)
            )
        )

        service.downloadAndDecrypt(pointer, conversationKey)

        assertEquals("Bearer sess_download_token", api.lastAuthHeader)
    }

    @Test
    fun `gate1 presign download fails without session token`() = runBlocking {
        api.sessionToken = null

        // Create a pointer that would fail at fileKey decryption before reaching API
        val fkCiphertext = TestCrypto.encrypt(fixedFileKey, fixedFkNonce, conversationKey)

        val pointer = AttachmentPointer(
            objectKey = "whisper/att/test/uuid.bin",
            contentType = "image/jpeg",
            ciphertextSize = 100,
            fileNonce = TestBase64.encode(fixedFileNonce),
            fileKeyBox = FileKeyBox(
                nonce = TestBase64.encode(fixedFkNonce),
                ciphertext = TestBase64.encode(fkCiphertext)
            )
        )

        try {
            service.downloadAndDecrypt(pointer, conversationKey)
            fail("Should throw exception")
        } catch (e: AttachmentService.AttachmentException.PresignFailed) {
            assertEquals(ApiErrorResponse.AUTH_FAILED, e.code)
        }
    }

    // ==========================================================================
    // Gate 1: Upload headers applied to PUT request
    // ==========================================================================

    @Test
    fun `gate1 upload response headers applied to PUT request`() = runBlocking {
        api.enqueueUploadResponse(
            ApiResult.Success(
                PresignUploadResponse(
                    objectKey = "whisper/att/test/uuid.bin",
                    uploadUrl = "https://blob.example.com/upload",
                    expiresAtMs = System.currentTimeMillis() + 300000,
                    headers = mapOf(
                        "Content-Type" to "application/octet-stream",
                        "x-amz-acl" to "private"
                    )
                )
            )
        )
        blobClient.enqueuePutResponse(BlobResult.success())

        val plaintext = ByteArray(50) { 3 }
        service.prepareAndUploadAttachment(plaintext, "image/png", conversationKey)

        assertNotNull(blobClient.lastPutHeaders)
        assertEquals("application/octet-stream", blobClient.lastPutHeaders!!["Content-Type"])
        assertEquals("private", blobClient.lastPutHeaders!!["x-amz-acl"])
    }

    @Test
    fun `gate1 PUT request uses correct upload URL`() = runBlocking {
        val uploadUrl = "https://nyc3.digitaloceanspaces.com/bucket/whisper/att/test/uuid.bin?signed=params"

        api.enqueueUploadResponse(
            ApiResult.Success(
                PresignUploadResponse(
                    objectKey = "whisper/att/test/uuid.bin",
                    uploadUrl = uploadUrl,
                    expiresAtMs = System.currentTimeMillis() + 300000,
                    headers = emptyMap()
                )
            )
        )
        blobClient.enqueuePutResponse(BlobResult.success())

        val plaintext = ByteArray(25) { 4 }
        service.prepareAndUploadAttachment(plaintext, "text/plain", conversationKey)

        assertEquals(uploadUrl, blobClient.lastPutUrl)
    }

    @Test
    fun `gate1 PUT request body is ciphertext not plaintext`() = runBlocking {
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

        val plaintext = ByteArray(100) { 0x42 }
        service.prepareAndUploadAttachment(plaintext, "application/pdf", conversationKey)

        assertNotNull(blobClient.lastPutBody)
        // Ciphertext should be larger (plaintext + 16 MAC)
        assertEquals(116, blobClient.lastPutBody!!.size)
        // Ciphertext should NOT equal plaintext
        assertFalse(blobClient.lastPutBody!!.contentEquals(plaintext))
    }

    // ==========================================================================
    // Gate 1: API call counts
    // ==========================================================================

    @Test
    fun `gate1 upload makes exactly one presign API call`() = runBlocking {
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

        val plaintext = ByteArray(10) { 5 }
        service.prepareAndUploadAttachment(plaintext, "image/gif", conversationKey)

        assertEquals(1, api.presignUploadCallCount)
        assertEquals(0, api.presignDownloadCallCount)
    }

    @Test
    fun `gate1 upload makes exactly one blob PUT call`() = runBlocking {
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

        val plaintext = ByteArray(10) { 6 }
        service.prepareAndUploadAttachment(plaintext, "audio/mp3", conversationKey)

        assertEquals(1, blobClient.putCallCount)
        assertEquals(0, blobClient.getCallCount)
    }
}
