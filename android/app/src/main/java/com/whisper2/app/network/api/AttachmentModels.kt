package com.whisper2.app.network.api

/**
 * Step 10: Attachment API Models
 *
 * DTOs for presigned URL requests and attachment pointers.
 * Matches server contract from AttachmentService.ts
 */

// =============================================================================
// PRESIGN UPLOAD
// =============================================================================

/**
 * Request body for POST /attachments/presign/upload
 */
data class PresignUploadRequest(
    val contentType: String,
    val sizeBytes: Long
)

/**
 * Response from POST /attachments/presign/upload
 */
data class PresignUploadResponse(
    val objectKey: String,
    val uploadUrl: String,
    val expiresAtMs: Long,
    val headers: Map<String, String> = emptyMap()
)

// =============================================================================
// PRESIGN DOWNLOAD
// =============================================================================

/**
 * Request body for POST /attachments/presign/download
 */
data class PresignDownloadRequest(
    val objectKey: String
)

/**
 * Response from POST /attachments/presign/download
 */
data class PresignDownloadResponse(
    val objectKey: String,
    val downloadUrl: String,
    val expiresAtMs: Long,
    val sizeBytes: Long,
    val contentType: String
)

// =============================================================================
// ATTACHMENT POINTER (embedded in messages)
// =============================================================================

/**
 * Encrypted file key box
 * fileKey is encrypted with conversation shared key using secretbox
 */
data class FileKeyBox(
    val nonce: String,      // base64(24 bytes)
    val ciphertext: String  // base64(encrypted 32-byte fileKey)
)

/**
 * Attachment pointer included in encrypted message payload
 * Contains all info needed to download and decrypt attachment
 */
data class AttachmentPointer(
    val objectKey: String,          // S3/Spaces path
    val contentType: String,        // Original MIME type (e.g., "image/jpeg")
    val ciphertextSize: Long,       // Size of encrypted blob
    val fileNonce: String,          // base64(24 bytes) - nonce for file encryption
    val fileKeyBox: FileKeyBox      // Encrypted 32-byte fileKey
)
