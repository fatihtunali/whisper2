package com.whisper2.app.attachments

import com.whisper2.app.network.api.*
import com.whisper2.app.services.attachments.AttachmentService
import com.whisper2.app.services.attachments.BlobResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Gate 4: Download flow + cache hit
 *
 * Tests:
 * - presign download -> GET ciphertext -> decrypt
 * - second download call has no extra GET (cache hit)
 */
class AttachmentDownloadCacheTest {

    private lateinit var api: FakeAttachmentsApi
    private lateinit var blobClient: FakeBlobHttpClient
    private lateinit var cache: FakeAttachmentCache
    private lateinit var service: AttachmentService

    private val conversationKey = ByteArray(32) { (it + 20).toByte() }

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
     * Helper to create a valid pointer and its encrypted ciphertext
     */
    private fun createValidPointerAndCiphertext(
        plaintext: ByteArray,
        objectKey: String = "whisper/att/test/uuid.bin"
    ): Pair<AttachmentPointer, ByteArray> {
        // Generate fileKey and fileNonce
        val fileKey = TestCrypto.generateKey()
        val fileNonce = TestCrypto.generateNonce()

        // Encrypt plaintext
        val ciphertext = TestCrypto.encrypt(plaintext, fileNonce, fileKey)

        // Encrypt fileKey with conversation key
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

        return Pair(pointer, ciphertext)
    }

    // ==========================================================================
    // Gate 4: Download flow decrypts correctly
    // ==========================================================================

    @Test
    fun `gate4 download decrypt and cache hit`() = runBlocking {
        val originalPlaintext = "merhaba".toByteArray(Charsets.UTF_8)
        val (pointer, ciphertext) = createValidPointerAndCiphertext(originalPlaintext)

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
        blobClient.enqueueGetResponse(BlobResult.success(ciphertext))

        // First download
        val out1 = service.downloadAndDecrypt(pointer, conversationKey)
        assertEquals("merhaba", String(out1, Charsets.UTF_8))
        assertEquals(1, blobClient.getCallCount)

        // Second download - cache hit, no extra GET
        val out2 = service.downloadAndDecrypt(pointer, conversationKey)
        assertEquals("merhaba", String(out2, Charsets.UTF_8))
        assertEquals(1, blobClient.getCallCount) // Still 1!
    }

    @Test
    fun `gate4 download decrypts large file correctly`() = runBlocking {
        val originalPlaintext = ByteArray(100000) { (it % 256).toByte() }
        val (pointer, ciphertext) = createValidPointerAndCiphertext(originalPlaintext)

        api.enqueueDownloadResponse(
            ApiResult.Success(
                PresignDownloadResponse(
                    objectKey = pointer.objectKey,
                    downloadUrl = "https://blob.example.com/download",
                    expiresAtMs = System.currentTimeMillis() + 300000,
                    sizeBytes = ciphertext.size.toLong(),
                    contentType = "application/octet-stream"
                )
            )
        )
        blobClient.enqueueGetResponse(BlobResult.success(ciphertext))

        val result = service.downloadAndDecrypt(pointer, conversationKey)

        assertArrayEquals(originalPlaintext, result)
    }

    // ==========================================================================
    // Gate 4: Cache behavior
    // ==========================================================================

    @Test
    fun `gate4 cache hit skips presign and GET calls`() = runBlocking {
        val plaintext = "cached content".toByteArray()
        val (pointer, _) = createValidPointerAndCiphertext(plaintext)

        // Pre-populate cache
        cache.put(pointer.objectKey, plaintext)

        // Download should use cache
        val result = service.downloadAndDecrypt(pointer, conversationKey)

        assertEquals("cached content", String(result, Charsets.UTF_8))
        assertEquals(0, api.presignDownloadCallCount) // No API call
        assertEquals(0, blobClient.getCallCount) // No GET call
    }

    @Test
    fun `gate4 cache miss triggers full download flow`() = runBlocking {
        val plaintext = "not cached".toByteArray()
        val (pointer, ciphertext) = createValidPointerAndCiphertext(plaintext)

        api.enqueueDownloadResponse(
            ApiResult.Success(
                PresignDownloadResponse(
                    objectKey = pointer.objectKey,
                    downloadUrl = "https://blob.example.com/download",
                    expiresAtMs = System.currentTimeMillis() + 300000,
                    sizeBytes = ciphertext.size.toLong(),
                    contentType = "text/plain"
                )
            )
        )
        blobClient.enqueueGetResponse(BlobResult.success(ciphertext))

        // Cache is empty
        assertEquals(0, cache.size())

        val result = service.downloadAndDecrypt(pointer, conversationKey)

        assertEquals("not cached", String(result, Charsets.UTF_8))
        assertEquals(1, api.presignDownloadCallCount)
        assertEquals(1, blobClient.getCallCount)
    }

    @Test
    fun `gate4 download populates cache`() = runBlocking {
        val plaintext = "will be cached".toByteArray()
        val (pointer, ciphertext) = createValidPointerAndCiphertext(plaintext)

        api.enqueueDownloadResponse(
            ApiResult.Success(
                PresignDownloadResponse(
                    objectKey = pointer.objectKey,
                    downloadUrl = "https://blob.example.com/download",
                    expiresAtMs = System.currentTimeMillis() + 300000,
                    sizeBytes = ciphertext.size.toLong(),
                    contentType = "text/plain"
                )
            )
        )
        blobClient.enqueueGetResponse(BlobResult.success(ciphertext))

        // Cache is empty
        assertEquals(0, cache.size())

        service.downloadAndDecrypt(pointer, conversationKey)

        // Cache should now have the plaintext
        assertEquals(1, cache.size())
        val cached = cache.getIfPresent(pointer.objectKey)
        assertNotNull(cached)
        assertEquals("will be cached", String(cached!!, Charsets.UTF_8))
    }

    @Test
    fun `gate4 multiple downloads of same file use cache`() = runBlocking {
        val plaintext = "reused content".toByteArray()
        val (pointer, ciphertext) = createValidPointerAndCiphertext(plaintext)

        api.enqueueDownloadResponse(
            ApiResult.Success(
                PresignDownloadResponse(
                    objectKey = pointer.objectKey,
                    downloadUrl = "https://blob.example.com/download",
                    expiresAtMs = System.currentTimeMillis() + 300000,
                    sizeBytes = ciphertext.size.toLong(),
                    contentType = "text/plain"
                )
            )
        )
        blobClient.enqueueGetResponse(BlobResult.success(ciphertext))

        // Download 5 times
        repeat(5) {
            val result = service.downloadAndDecrypt(pointer, conversationKey)
            assertEquals("reused content", String(result, Charsets.UTF_8))
        }

        // Only 1 network call
        assertEquals(1, api.presignDownloadCallCount)
        assertEquals(1, blobClient.getCallCount)
    }

    @Test
    fun `gate4 different objects not cached together`() = runBlocking {
        val plaintext1 = "content 1".toByteArray()
        val plaintext2 = "content 2".toByteArray()

        val (pointer1, ciphertext1) = createValidPointerAndCiphertext(plaintext1, "whisper/att/obj1.bin")
        val (pointer2, ciphertext2) = createValidPointerAndCiphertext(plaintext2, "whisper/att/obj2.bin")

        // Setup for first download
        api.enqueueDownloadResponse(
            ApiResult.Success(
                PresignDownloadResponse(
                    objectKey = pointer1.objectKey,
                    downloadUrl = "https://blob.example.com/download/1",
                    expiresAtMs = System.currentTimeMillis() + 300000,
                    sizeBytes = ciphertext1.size.toLong(),
                    contentType = "text/plain"
                )
            )
        )
        blobClient.enqueueGetResponse(BlobResult.success(ciphertext1))

        // Setup for second download
        api.enqueueDownloadResponse(
            ApiResult.Success(
                PresignDownloadResponse(
                    objectKey = pointer2.objectKey,
                    downloadUrl = "https://blob.example.com/download/2",
                    expiresAtMs = System.currentTimeMillis() + 300000,
                    sizeBytes = ciphertext2.size.toLong(),
                    contentType = "text/plain"
                )
            )
        )
        blobClient.enqueueGetResponse(BlobResult.success(ciphertext2))

        val result1 = service.downloadAndDecrypt(pointer1, conversationKey)
        val result2 = service.downloadAndDecrypt(pointer2, conversationKey)

        assertEquals("content 1", String(result1, Charsets.UTF_8))
        assertEquals("content 2", String(result2, Charsets.UTF_8))

        // Both required network calls
        assertEquals(2, api.presignDownloadCallCount)
        assertEquals(2, blobClient.getCallCount)
    }

    // ==========================================================================
    // Gate 4: Error handling
    // ==========================================================================

    @Test
    fun `gate4 presign download error throws exception`() = runBlocking {
        val plaintext = "test".toByteArray()
        val (pointer, _) = createValidPointerAndCiphertext(plaintext)

        api.enqueueDownloadResponse(
            ApiResult.Error(ApiErrorResponse.NOT_FOUND, "Object not found", 404)
        )

        try {
            service.downloadAndDecrypt(pointer, conversationKey)
            fail("Should throw exception")
        } catch (e: AttachmentService.AttachmentException.PresignFailed) {
            assertEquals(ApiErrorResponse.NOT_FOUND, e.code)
        }
    }

    @Test
    fun `gate4 GET download error throws exception`() = runBlocking {
        val plaintext = "test".toByteArray()
        val (pointer, ciphertext) = createValidPointerAndCiphertext(plaintext)

        api.enqueueDownloadResponse(
            ApiResult.Success(
                PresignDownloadResponse(
                    objectKey = pointer.objectKey,
                    downloadUrl = "https://blob.example.com/download",
                    expiresAtMs = System.currentTimeMillis() + 300000,
                    sizeBytes = ciphertext.size.toLong(),
                    contentType = "text/plain"
                )
            )
        )
        blobClient.enqueueGetResponse(BlobResult.failure(503))

        try {
            service.downloadAndDecrypt(pointer, conversationKey)
            fail("Should throw exception")
        } catch (e: AttachmentService.AttachmentException.DownloadFailed) {
            assertEquals(503, e.httpCode)
        }
    }

    @Test
    fun `gate4 download error does not cache`() = runBlocking {
        val plaintext = "test".toByteArray()
        val (pointer, ciphertext) = createValidPointerAndCiphertext(plaintext)

        api.enqueueDownloadResponse(
            ApiResult.Success(
                PresignDownloadResponse(
                    objectKey = pointer.objectKey,
                    downloadUrl = "https://blob.example.com/download",
                    expiresAtMs = System.currentTimeMillis() + 300000,
                    sizeBytes = ciphertext.size.toLong(),
                    contentType = "text/plain"
                )
            )
        )
        blobClient.enqueueGetResponse(BlobResult.failure(500))

        try {
            service.downloadAndDecrypt(pointer, conversationKey)
        } catch (e: Exception) {
            // Expected
        }

        // Should not be cached
        assertEquals(0, cache.size())
        assertNull(cache.getIfPresent(pointer.objectKey))
    }

    // ==========================================================================
    // Gate 4: Cache invalidation
    // ==========================================================================

    @Test
    fun `gate4 cache invalidate forces re-download`() = runBlocking {
        val plaintext = "original".toByteArray()
        val (pointer, ciphertext) = createValidPointerAndCiphertext(plaintext)

        // Pre-populate cache
        cache.put(pointer.objectKey, plaintext)

        // Invalidate
        service.invalidateCache(pointer.objectKey)

        // Setup download response
        api.enqueueDownloadResponse(
            ApiResult.Success(
                PresignDownloadResponse(
                    objectKey = pointer.objectKey,
                    downloadUrl = "https://blob.example.com/download",
                    expiresAtMs = System.currentTimeMillis() + 300000,
                    sizeBytes = ciphertext.size.toLong(),
                    contentType = "text/plain"
                )
            )
        )
        blobClient.enqueueGetResponse(BlobResult.success(ciphertext))

        // Download should hit network
        service.downloadAndDecrypt(pointer, conversationKey)

        assertEquals(1, api.presignDownloadCallCount)
        assertEquals(1, blobClient.getCallCount)
    }

    @Test
    fun `gate4 clearCache forces all re-downloads`() = runBlocking {
        val plaintext1 = "content 1".toByteArray()
        val plaintext2 = "content 2".toByteArray()

        val (pointer1, _) = createValidPointerAndCiphertext(plaintext1, "obj1.bin")
        val (pointer2, _) = createValidPointerAndCiphertext(plaintext2, "obj2.bin")

        // Pre-populate cache
        cache.put(pointer1.objectKey, plaintext1)
        cache.put(pointer2.objectKey, plaintext2)
        assertEquals(2, cache.size())

        // Clear all
        service.clearCache()

        assertEquals(0, cache.size())
    }
}
