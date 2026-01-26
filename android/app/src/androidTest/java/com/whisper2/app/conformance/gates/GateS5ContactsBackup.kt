package com.whisper2.app.conformance.gates

import android.util.Base64
import com.whisper2.app.conformance.*
import com.whisper2.app.crypto.NaClSecretBox
import java.security.MessageDigest

/**
 * Gate S5: Contacts Backup (HTTP)
 *
 * Flow:
 * 1. Create contacts data
 * 2. Encrypt with contactsKey
 * 3. Upload to /contacts/backup
 * 4. Fetch from /contacts/backup
 * 5. Decrypt and verify
 *
 * PASS criteria:
 * - Upload succeeds
 * - Fetch succeeds
 * - Decrypted data matches original
 */
class GateS5ContactsBackup(
    private val identityA: TestIdentity,
    private val identityB: TestIdentity
) {
    private val startTime = System.currentTimeMillis()
    private val http = ConformanceHttpClient()

    suspend fun run(): GateResult {
        ConformanceLogger.gate("S5", "Starting contacts backup test")

        try {
            identityA.requireRegistered()

            // Test contacts backup for Identity A
            val resultA = testContactsBackup(identityA)
            if (!resultA.passed) return resultA

            val duration = System.currentTimeMillis() - startTime
            return GateResult.pass(
                name = "S5-ContactsBackup",
                details = "Contacts backup/restore cycle passed",
                durationMs = duration
            )

        } catch (e: Exception) {
            return GateResult.fail(
                name = "S5-ContactsBackup",
                reason = "Contacts backup test failed: ${e.message}",
                durationMs = System.currentTimeMillis() - startTime,
                error = e
            )
        }
    }

    private suspend fun testContactsBackup(identity: TestIdentity): GateResult {
        ConformanceLogger.gate("S5", "${identity.name}: Testing contacts backup")

        // Create test contacts data
        val contactsData = """
            {
                "contacts": [
                    {"whisperId": "WSP-TEST-0001-AAAA", "nickname": "Alice"},
                    {"whisperId": "WSP-TEST-0002-BBBB", "nickname": "Bob"},
                    {"whisperId": "WSP-TEST-0003-CCCC", "nickname": "Charlie"}
                ],
                "version": 1,
                "timestamp": ${System.currentTimeMillis()}
            }
        """.trimIndent()

        val contactsBytes = contactsData.toByteArray(Charsets.UTF_8)
        ConformanceLogger.gate("S5", "${identity.name}: Contacts data size: ${contactsBytes.size} bytes")

        // Encrypt with contactsKey using SecretBox
        val contactsKey = identity.contactsKey
        ConformanceLogger.gate("S5", "${identity.name}: Encrypting with contactsKey...")

        val (nonce, ciphertext) = NaClSecretBox.seal(contactsBytes, contactsKey)
        val encryptedData = nonce + ciphertext // Combined format

        ConformanceLogger.gate("S5", "${identity.name}: Encrypted size: ${encryptedData.size} bytes")

        // Upload
        ConformanceLogger.gate("S5", "${identity.name}: Uploading backup...")
        val uploadResponse = http.put(
            path = "/contacts/backup",
            body = mapOf(
                "encryptedData" to Base64.encodeToString(encryptedData, Base64.NO_WRAP)
            ),
            sessionToken = identity.sessionToken
        )

        if (!uploadResponse.isSuccess) {
            return fail("${identity.name}: Upload failed with status ${uploadResponse.statusCode}: ${uploadResponse.body}")
        }

        ConformanceLogger.gate("S5", "${identity.name}: Upload successful")

        // Fetch
        ConformanceLogger.gate("S5", "${identity.name}: Fetching backup...")
        val fetchResponse = http.get(
            path = "/contacts/backup",
            sessionToken = identity.sessionToken
        )

        if (!fetchResponse.isSuccess) {
            return fail("${identity.name}: Fetch failed with status ${fetchResponse.statusCode}")
        }

        val fetchJson = fetchResponse.json
            ?: return fail("${identity.name}: Fetch response has no JSON body")

        val fetchedDataB64 = fetchJson.get("encryptedData")?.asString
            ?: return fail("${identity.name}: No encryptedData in response")

        val fetchedData = Base64.decode(fetchedDataB64, Base64.NO_WRAP)
        ConformanceLogger.gate("S5", "${identity.name}: Fetched encrypted size: ${fetchedData.size} bytes")

        // Decrypt
        ConformanceLogger.gate("S5", "${identity.name}: Decrypting...")
        val decrypted = try {
            NaClSecretBox.openCombined(fetchedData, contactsKey)
        } catch (e: Exception) {
            return fail("${identity.name}: Decryption failed: ${e.message}")
        }

        val decryptedString = String(decrypted, Charsets.UTF_8)

        // Verify content matches (compare as trimmed JSON)
        if (decryptedString.trim() != contactsData.trim()) {
            ConformanceLogger.gate("S5", "${identity.name}: Content mismatch!")
            ConformanceLogger.gate("S5", "Original: ${contactsData.take(100)}...")
            ConformanceLogger.gate("S5", "Decrypted: ${decryptedString.take(100)}...")
            return fail("${identity.name}: Decrypted data doesn't match original")
        }

        ConformanceLogger.gate("S5", "${identity.name}: Backup/restore verified successfully")

        return GateResult.pass(
            name = "S5-ContactsBackup-${identity.name}",
            details = "Backup and restore verified",
            durationMs = uploadResponse.durationMs + fetchResponse.durationMs,
            artifacts = mapOf(
                "originalSize" to contactsBytes.size,
                "encryptedSize" to encryptedData.size,
                "uploadDurationMs" to uploadResponse.durationMs,
                "fetchDurationMs" to fetchResponse.durationMs
            )
        )
    }

    private fun fail(reason: String): GateResult {
        return GateResult.fail(
            name = "S5-ContactsBackup",
            reason = reason,
            durationMs = System.currentTimeMillis() - startTime
        )
    }
}
