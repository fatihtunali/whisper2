package com.whisper2.app.services.attachments

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.MimeTypeMap
import com.whisper2.app.core.Constants
import com.whisper2.app.core.Logger
import com.whisper2.app.crypto.CryptoService
import com.whisper2.app.data.local.prefs.SecureStorage
import com.whisper2.app.data.network.api.AttachmentsApi
import com.whisper2.app.data.network.api.PresignUploadRequest
import com.whisper2.app.data.network.ws.AttachmentPointer
import com.whisper2.app.data.network.ws.FileKeyBox
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for uploading encrypted attachments.
 *
 * Encryption flow:
 * 1. Generate random 32-byte file key
 * 2. Generate random 24-byte nonce
 * 3. Encrypt file content with secretbox(content, nonce, fileKey)
 * 4. Upload encrypted content to S3 via presigned URL
 * 5. Encrypt fileKey to recipient: box(fileKey, fileKeyNonce, recipientPubKey, senderPrivKey)
 * 6. Return AttachmentPointer with all encryption info
 */
@Singleton
class AttachmentService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val attachmentsApi: AttachmentsApi,
    private val cryptoService: CryptoService,
    private val secureStorage: SecureStorage,
    private val httpClient: OkHttpClient
) {
    /**
     * Upload an encrypted attachment for a specific recipient.
     *
     * @param uri Content URI of the file to upload
     * @param recipientPublicKey Recipient's X25519 public key (for encrypting the file key)
     * @return AttachmentPointer containing all info needed to decrypt the file
     */
    suspend fun uploadAttachment(
        uri: Uri,
        recipientPublicKey: ByteArray
    ): AttachmentPointer = withContext(Dispatchers.IO) {
        val myPrivKey = secureStorage.encPrivateKey
            ?: throw IllegalStateException("Not logged in - missing encryption key")
        val sessionToken = secureStorage.sessionToken
            ?: throw IllegalStateException("Not logged in - missing session token")

        // Read file content
        val (content, contentType, fileName) = readFile(uri)
        Logger.d("[AttachmentService] Uploading file: $fileName, size: ${content.size}, type: $contentType")

        // Generate file encryption key and nonce
        val fileKey = cryptoService.generateKey()  // 32 bytes
        val fileNonce = cryptoService.generateNonce()  // 24 bytes

        // Encrypt file content
        val ciphertext = cryptoService.secretBoxSeal(content, fileNonce, fileKey)

        // Prepend nonce to ciphertext (iOS compatibility: upload format is [nonce][ciphertext])
        val encryptedContent = ByteArray(fileNonce.size + ciphertext.size)
        System.arraycopy(fileNonce, 0, encryptedContent, 0, fileNonce.size)
        System.arraycopy(ciphertext, 0, encryptedContent, fileNonce.size, ciphertext.size)
        Logger.d("[AttachmentService] Encrypted size: ${encryptedContent.size} (nonce: ${fileNonce.size}, ciphertext: ${ciphertext.size})")

        // Get presigned upload URL
        val presignResponse = attachmentsApi.presignUpload(
            token = "Bearer $sessionToken",
            request = PresignUploadRequest(
                contentType = "application/octet-stream",  // Always octet-stream for encrypted data
                sizeBytes = encryptedContent.size.toLong()
            )
        )
        Logger.d("[AttachmentService] Got presigned URL for objectKey: ${presignResponse.objectKey}")

        // Upload encrypted content to S3
        uploadToS3(presignResponse.uploadUrl, encryptedContent)
        Logger.d("[AttachmentService] Upload complete")

        // Encrypt file key to recipient (iOS compatibility: encrypt base64 string of key, not raw bytes)
        val fileKeyNonce = cryptoService.generateNonce()
        val fileKeyBase64 = Base64.encodeToString(fileKey, Base64.NO_WRAP)
        val encryptedFileKey = cryptoService.boxSeal(
            fileKeyBase64.toByteArray(Charsets.UTF_8),
            fileKeyNonce,
            recipientPublicKey,
            myPrivKey
        )

        // Return attachment pointer
        AttachmentPointer(
            objectKey = presignResponse.objectKey,
            contentType = contentType,
            ciphertextSize = encryptedContent.size,
            fileNonce = Base64.encodeToString(fileNonce, Base64.NO_WRAP),
            fileKeyBox = FileKeyBox(
                nonce = Base64.encodeToString(fileKeyNonce, Base64.NO_WRAP),
                ciphertext = Base64.encodeToString(encryptedFileKey, Base64.NO_WRAP)
            )
        )
    }

    /**
     * Upload an encrypted attachment from a file path.
     */
    suspend fun uploadAttachment(
        filePath: String,
        recipientPublicKey: ByteArray
    ): AttachmentPointer {
        val file = File(filePath)
        if (!file.exists()) {
            throw IllegalArgumentException("File not found: $filePath")
        }
        return uploadAttachment(Uri.fromFile(file), recipientPublicKey)
    }

    /**
     * Download and decrypt an attachment.
     *
     * @param pointer AttachmentPointer from the message
     * @param senderPublicKey Sender's X25519 public key (for decrypting the file key)
     * @return Decrypted file content
     */
    suspend fun downloadAttachment(
        pointer: AttachmentPointer,
        senderPublicKey: ByteArray
    ): ByteArray = withContext(Dispatchers.IO) {
        val myPrivKey = secureStorage.encPrivateKey
            ?: throw IllegalStateException("Not logged in - missing encryption key")
        val sessionToken = secureStorage.sessionToken
            ?: throw IllegalStateException("Not logged in - missing session token")

        // Get presigned download URL
        val presignResponse = attachmentsApi.presignDownload(
            token = "Bearer $sessionToken",
            request = com.whisper2.app.data.network.api.PresignDownloadRequest(
                objectKey = pointer.objectKey
            )
        )

        // Download encrypted content
        val encryptedContent = downloadFromS3(presignResponse.downloadUrl)
        Logger.d("[AttachmentService] Downloaded ${encryptedContent.size} bytes")

        // Decrypt file key (iOS compatibility: decrypted value is base64 string of actual key)
        val fileKeyNonce = Base64.decode(pointer.fileKeyBox.nonce, Base64.NO_WRAP)
        val encryptedFileKey = Base64.decode(pointer.fileKeyBox.ciphertext, Base64.NO_WRAP)
        val fileKeyBase64Bytes = cryptoService.boxOpen(encryptedFileKey, fileKeyNonce, senderPublicKey, myPrivKey)
        val fileKeyBase64 = String(fileKeyBase64Bytes, Charsets.UTF_8)
        val fileKey = Base64.decode(fileKeyBase64, Base64.NO_WRAP)

        // Extract nonce from encrypted content (iOS compatibility: format is [nonce 24 bytes][ciphertext])
        if (encryptedContent.size <= 24) {
            throw IllegalStateException("Encrypted content too small")
        }
        val fileNonce = encryptedContent.copyOfRange(0, 24)
        val ciphertext = encryptedContent.copyOfRange(24, encryptedContent.size)

        // Decrypt file content
        cryptoService.secretBoxOpen(ciphertext, fileNonce, fileKey)
    }

    /**
     * Save downloaded content to a file.
     */
    suspend fun saveToFile(content: ByteArray, fileName: String): File = withContext(Dispatchers.IO) {
        val attachmentsDir = File(context.filesDir, "attachments")
        if (!attachmentsDir.exists()) {
            attachmentsDir.mkdirs()
        }

        val file = File(attachmentsDir, fileName)
        file.writeBytes(content)
        file
    }

    private fun readFile(uri: Uri): Triple<ByteArray, String, String> {
        val contentResolver = context.contentResolver

        // Get file name
        var fileName = "attachment"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    fileName = cursor.getString(nameIndex) ?: fileName
                }
            }
        }

        // Get content type
        val contentType = contentResolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                fileName.substringAfterLast('.', "")
            )
            ?: "application/octet-stream"

        // Read content
        val content = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Cannot read file: $uri")

        // Check size
        if (content.size > Constants.MAX_ATTACHMENT_SIZE) {
            throw IllegalArgumentException("File too large: ${content.size} bytes (max: ${Constants.MAX_ATTACHMENT_SIZE})")
        }

        return Triple(content, contentType, fileName)
    }

    private suspend fun uploadToS3(uploadUrl: String, content: ByteArray) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(uploadUrl)
            .put(content.toRequestBody("application/octet-stream".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("Upload failed: ${response.code} ${response.message}")
            }
        }
    }

    private suspend fun downloadFromS3(downloadUrl: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(downloadUrl)
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("Download failed: ${response.code} ${response.message}")
            }
            response.body?.bytes() ?: throw RuntimeException("Empty response body")
        }
    }
}
