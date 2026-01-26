package com.whisper2.app.conformance.gates

import android.util.Base64
import com.whisper2.app.conformance.*
import com.whisper2.app.crypto.NaClBox
import com.whisper2.app.crypto.NaClSecretBox
import com.whisper2.app.crypto.Signatures
import kotlinx.coroutines.delay
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/**
 * Gate S6: Attachments E2E (HTTP + Spaces)
 *
 * Flow:
 * 1. Generate random attachment data
 * 2. Encrypt with random symmetric key
 * 3. Request upload URL from server
 * 4. Upload encrypted data to Spaces
 * 5. Send message with attachment metadata
 * 6. Recipient fetches and decrypts
 *
 * PASS criteria:
 * - Upload URL generated
 * - Upload to Spaces succeeds
 * - Download succeeds
 * - Decryption produces original data
 */
class GateS6Attachments(
    private val identityA: TestIdentity,
    private val identityB: TestIdentity
) {
    private val startTime = System.currentTimeMillis()
    private val http = ConformanceHttpClient()

    suspend fun run(): GateResult {
        ConformanceLogger.gate("S6", "Starting attachments E2E test")

        try {
            identityA.requireRegistered()
            identityB.requireRegistered()

            // Generate random attachment data
            val attachmentSize = ConformanceConfig.TestData.TEST_ATTACHMENT_SIZE
            val originalData = ByteArray(attachmentSize)
            SecureRandom().nextBytes(originalData)

            ConformanceLogger.gate("S6", "Generated ${attachmentSize} bytes of random data")

            // Generate symmetric key for this attachment
            val attachmentKey = NaClSecretBox.generateKey()

            // Encrypt attachment
            ConformanceLogger.gate("S6", "Encrypting attachment...")
            val (nonce, ciphertext) = NaClSecretBox.seal(originalData, attachmentKey)
            val encryptedAttachment = nonce + ciphertext

            ConformanceLogger.gate("S6", "Encrypted size: ${encryptedAttachment.size} bytes")

            // Calculate hash of original data for verification
            val originalHash = MessageDigest.getInstance("SHA-256").digest(originalData)
            val originalHashB64 = Base64.encodeToString(originalHash, Base64.NO_WRAP)

            // Request upload URL
            ConformanceLogger.gate("S6", "Requesting upload URL...")
            val uploadUrlResponse = http.post(
                path = "/attachments/upload-url",
                body = mapOf(
                    "fileName" to "test-attachment.bin",
                    "fileSize" to encryptedAttachment.size,
                    "contentType" to "application/octet-stream"
                ),
                sessionToken = identityA.sessionToken
            )

            if (!uploadUrlResponse.isSuccess) {
                return fail("Upload URL request failed: ${uploadUrlResponse.statusCode} - ${uploadUrlResponse.body}")
            }

            val urlJson = uploadUrlResponse.json
                ?: return fail("No JSON in upload URL response")

            val uploadUrl = urlJson.get("uploadUrl")?.asString
                ?: return fail("No uploadUrl in response")

            val attachmentId = urlJson.get("attachmentId")?.asString
                ?: return fail("No attachmentId in response")

            ConformanceLogger.gate("S6", "Got upload URL, attachmentId: $attachmentId")

            // Upload to Spaces
            ConformanceLogger.gate("S6", "Uploading to Spaces...")
            val uploadStart = System.currentTimeMillis()
            val uploadResult = http.putBytes(
                url = uploadUrl,
                data = encryptedAttachment,
                contentType = "application/octet-stream"
            )

            val uploadDuration = System.currentTimeMillis() - uploadStart

            if (!uploadResult.isSuccess) {
                return fail("Spaces upload failed: ${uploadResult.statusCode}")
            }

            ConformanceLogger.gate("S6", "Upload complete in ${uploadDuration}ms")

            // Now simulate B downloading the attachment
            // First, get the download URL
            ConformanceLogger.gate("S6", "B requesting download URL...")
            val downloadUrlResponse = http.get(
                path = "/attachments/$attachmentId/download-url",
                sessionToken = identityB.sessionToken
            )

            if (!downloadUrlResponse.isSuccess) {
                return fail("Download URL request failed: ${downloadUrlResponse.statusCode}")
            }

            val downloadJson = downloadUrlResponse.json
                ?: return fail("No JSON in download URL response")

            val downloadUrl = downloadJson.get("downloadUrl")?.asString
                ?: return fail("No downloadUrl in response")

            ConformanceLogger.gate("S6", "Got download URL, downloading...")

            // Download from Spaces
            val downloadStart = System.currentTimeMillis()
            val downloadResult = http.getBytes(downloadUrl)
            val downloadDuration = System.currentTimeMillis() - downloadStart

            if (!downloadResult.isSuccess) {
                return fail("Download failed: ${downloadResult.statusCode}")
            }

            val downloadedData = downloadResult.bytes
                ?: return fail("Download returned no data")

            ConformanceLogger.gate("S6", "Downloaded ${downloadedData.size} bytes in ${downloadDuration}ms")

            // Verify downloaded data matches what we uploaded
            if (!downloadedData.contentEquals(encryptedAttachment)) {
                return fail("Downloaded data doesn't match uploaded data")
            }

            // Decrypt
            ConformanceLogger.gate("S6", "Decrypting attachment...")
            val decrypted = try {
                NaClSecretBox.openCombined(downloadedData, attachmentKey)
            } catch (e: Exception) {
                return fail("Decryption failed: ${e.message}")
            }

            // Verify decrypted matches original
            if (!decrypted.contentEquals(originalData)) {
                return fail("Decrypted data doesn't match original")
            }

            // Verify hash
            val decryptedHash = MessageDigest.getInstance("SHA-256").digest(decrypted)
            if (!decryptedHash.contentEquals(originalHash)) {
                return fail("Hash mismatch after decryption")
            }

            ConformanceLogger.gate("S6", "Attachment E2E verified successfully!")

            val duration = System.currentTimeMillis() - startTime
            return GateResult.pass(
                name = "S6-Attachments",
                details = "Attachment upload/download/decrypt cycle passed",
                durationMs = duration,
                artifacts = mapOf(
                    "originalSize" to attachmentSize,
                    "encryptedSize" to encryptedAttachment.size,
                    "uploadDurationMs" to uploadDuration,
                    "downloadDurationMs" to downloadDuration,
                    "attachmentId" to attachmentId,
                    "hashMatch" to true
                )
            )

        } catch (e: Exception) {
            return GateResult.fail(
                name = "S6-Attachments",
                reason = "Attachments test failed: ${e.message}",
                durationMs = System.currentTimeMillis() - startTime,
                error = e
            )
        }
    }

    private fun fail(reason: String): GateResult {
        return GateResult.fail(
            name = "S6-Attachments",
            reason = reason,
            durationMs = System.currentTimeMillis() - startTime
        )
    }
}
