package com.whisper2.app.services.attachments

import android.util.Base64
import com.whisper2.app.crypto.NaClSecretBox
import com.whisper2.app.network.api.*

/**
 * Step 10: Attachment Service
 *
 * Handles upload and download of encrypted attachments.
 *
 * Upload flow:
 * 1. Generate random fileKey (32 bytes) and fileNonce (24 bytes)
 * 2. Encrypt file with secretbox(plaintext, fileNonce, fileKey)
 * 3. Get presigned upload URL
 * 4. PUT ciphertext to uploadUrl with required headers
 * 5. Encrypt fileKey with conversation key -> fileKeyBox
 * 6. Return AttachmentPointer
 *
 * Download flow:
 * 1. Check cache - if present, return cached data
 * 2. Decrypt fileKey from fileKeyBox using conversation key
 * 3. Get presigned download URL
 * 4. GET ciphertext from downloadUrl
 * 5. Decrypt with secretbox_open(ciphertext, fileNonce, fileKey)
 * 6. Cache and return plaintext
 */
class AttachmentService(
    private val api: AttachmentsApi,
    private val blobClient: BlobHttpClient,
    private val cache: AttachmentCache,
    // Injectable crypto functions for testing
    private val keyGenerator: () -> ByteArray = { NaClSecretBox.generateKey() },
    private val nonceGenerator: () -> ByteArray = { NaClSecretBox.generateNonce() },
    private val encryptor: (plaintext: ByteArray, nonce: ByteArray, key: ByteArray) -> ByteArray = { p, n, k ->
        NaClSecretBox.seal(p, n, k)
    },
    private val decryptor: (ciphertext: ByteArray, nonce: ByteArray, key: ByteArray) -> ByteArray = { c, n, k ->
        NaClSecretBox.open(c, n, k)
    },
    // Injectable Base64 functions for testing (android.util.Base64 doesn't work in JVM tests)
    private val base64Encoder: (ByteArray) -> String = { data ->
        Base64.encodeToString(data, Base64.NO_WRAP)
    },
    private val base64Decoder: (String) -> ByteArray = { data ->
        Base64.decode(data, Base64.NO_WRAP)
    }
) {

    /**
     * Exception thrown when attachment operations fail
     */
    sealed class AttachmentException(message: String) : Exception(message) {
        class PresignFailed(val code: String, val msg: String) : AttachmentException("Presign failed: $code - $msg")
        class UploadFailed(val httpCode: Int) : AttachmentException("Upload failed with HTTP $httpCode")
        class DownloadFailed(val httpCode: Int) : AttachmentException("Download failed with HTTP $httpCode")
        class DecryptionFailed : AttachmentException("Decryption failed - wrong key or tampered data")
    }

    /**
     * Prepare and upload an attachment
     *
     * @param plaintext Raw file bytes
     * @param contentType MIME type (e.g., "image/jpeg")
     * @param conversationKey 32-byte shared key for encrypting fileKey
     * @return AttachmentPointer to include in message
     */
    suspend fun prepareAndUploadAttachment(
        plaintext: ByteArray,
        contentType: String,
        conversationKey: ByteArray
    ): AttachmentPointer {
        // 1. Generate random keys
        val fileKey = keyGenerator()
        val fileNonce = nonceGenerator()

        // 2. Encrypt file content
        val ciphertext = encryptor(plaintext, fileNonce, fileKey)

        // 3. Get presigned upload URL
        val presignResult = api.presignUpload(
            PresignUploadRequest(
                contentType = contentType,
                sizeBytes = ciphertext.size.toLong()
            )
        )

        val presignResponse = when (presignResult) {
            is ApiResult.Success -> presignResult.data
            is ApiResult.Error -> throw AttachmentException.PresignFailed(presignResult.code, presignResult.message)
        }

        // 4. Upload ciphertext with headers
        val uploadResult = blobClient.put(
            url = presignResponse.uploadUrl,
            body = ciphertext,
            headers = presignResponse.headers
        )

        if (!uploadResult.isSuccess) {
            throw AttachmentException.UploadFailed(uploadResult.httpCode)
        }

        // 5. Encrypt fileKey with conversation key
        val fkNonce = nonceGenerator()
        val fkCiphertext = encryptor(fileKey, fkNonce, conversationKey)

        val fileKeyBox = FileKeyBox(
            nonce = base64Encoder(fkNonce),
            ciphertext = base64Encoder(fkCiphertext)
        )

        // 6. Return pointer
        return AttachmentPointer(
            objectKey = presignResponse.objectKey,
            contentType = contentType,
            ciphertextSize = ciphertext.size.toLong(),
            fileNonce = base64Encoder(fileNonce),
            fileKeyBox = fileKeyBox
        )
    }

    /**
     * Download and decrypt an attachment
     *
     * @param pointer Attachment pointer from message
     * @param conversationKey 32-byte shared key for decrypting fileKey
     * @return Decrypted file bytes
     */
    suspend fun downloadAndDecrypt(
        pointer: AttachmentPointer,
        conversationKey: ByteArray
    ): ByteArray {
        // 1. Check cache
        cache.getIfPresent(pointer.objectKey)?.let { cached ->
            return cached
        }

        // 2. Decrypt fileKey from fileKeyBox
        val fkNonce = base64Decoder(pointer.fileKeyBox.nonce)
        val fkCiphertext = base64Decoder(pointer.fileKeyBox.ciphertext)
        val fileKey = try {
            decryptor(fkCiphertext, fkNonce, conversationKey)
        } catch (e: Exception) {
            throw AttachmentException.DecryptionFailed()
        }

        // 3. Get presigned download URL
        val presignResult = api.presignDownload(
            PresignDownloadRequest(objectKey = pointer.objectKey)
        )

        val presignResponse = when (presignResult) {
            is ApiResult.Success -> presignResult.data
            is ApiResult.Error -> throw AttachmentException.PresignFailed(presignResult.code, presignResult.message)
        }

        // 4. Download ciphertext
        val downloadResult = blobClient.get(presignResponse.downloadUrl)

        if (!downloadResult.isSuccess) {
            throw AttachmentException.DownloadFailed(downloadResult.httpCode)
        }

        val ciphertext = downloadResult.body
            ?: throw AttachmentException.DownloadFailed(0)

        // 5. Decrypt file content
        val fileNonce = base64Decoder(pointer.fileNonce)
        val plaintext = try {
            decryptor(ciphertext, fileNonce, fileKey)
        } catch (e: Exception) {
            throw AttachmentException.DecryptionFailed()
        }

        // 6. Cache and return
        cache.put(pointer.objectKey, plaintext)
        return plaintext
    }

    /**
     * Clear attachment cache
     */
    fun clearCache() {
        cache.clear()
    }

    /**
     * Invalidate specific cache entry
     */
    fun invalidateCache(objectKey: String) {
        cache.invalidate(objectKey)
    }
}

/**
 * HTTP client for blob operations (upload/download)
 * Separate from API client to handle different base URLs
 */
interface BlobHttpClient {

    /**
     * PUT request for upload
     *
     * @param url Full presigned URL
     * @param body Bytes to upload
     * @param headers Headers to include (e.g., Content-Type)
     * @return Result with success status and HTTP code
     */
    suspend fun put(url: String, body: ByteArray, headers: Map<String, String>): BlobResult

    /**
     * GET request for download
     *
     * @param url Full presigned URL
     * @return Result with body bytes
     */
    suspend fun get(url: String): BlobResult
}

/**
 * Result of blob HTTP operation
 */
data class BlobResult(
    val isSuccess: Boolean,
    val httpCode: Int,
    val body: ByteArray? = null
) {
    companion object {
        fun success(body: ByteArray? = null, httpCode: Int = 200) = BlobResult(true, httpCode, body)
        fun failure(httpCode: Int) = BlobResult(false, httpCode)
    }
}
