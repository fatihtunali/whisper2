package com.whisper2.app.crypto

import com.whisper2.app.core.utils.Base64Strict
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.text.Normalizer
import kotlin.math.ceil

/**
 * Kapı 3: Canonical signing birebir mi?
 *
 * signature = Ed25519( SHA256( canonicalString UTF-8 ) )
 * verify PASS / yanlış mesajda verify FAIL
 *
 * NOT: Bu test JVM'de çalışır, LazySodium yerine pure Java SHA256 + test signature kullanır.
 * Gerçek Ed25519 testi için androidTest kullanılmalı.
 */
class CanonicalSigningTest {

    @Test
    fun `canonical string format is correct`() {
        // Test canonical string building
        val canonical = buildCanonicalString(
            messageType = "text",
            messageId = "msg-123",
            from = "WSP-AAAA-BBBB-CCCC",
            to = "WSP-DDDD-EEEE-FFFF",
            timestamp = 1704067200000L,
            nonceB64 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            ciphertextB64 = "dGVzdCBtZXNzYWdl"
        )

        // Must have version header
        assertTrue("Must start with v1", canonical.startsWith("v1\n"))

        // Must have trailing newline
        assertTrue("Must end with newline", canonical.endsWith("\n"))

        // Must contain all fields
        assertTrue(canonical.contains("text"))
        assertTrue(canonical.contains("msg-123"))
        assertTrue(canonical.contains("WSP-AAAA-BBBB-CCCC"))
        assertTrue(canonical.contains("WSP-DDDD-EEEE-FFFF"))
        assertTrue(canonical.contains("1704067200000"))
    }

    @Test
    fun `SHA256 hash of canonical string is deterministic`() {
        val canonical = buildCanonicalString(
            messageType = "text",
            messageId = "msg-123",
            from = "WSP-AAAA-BBBB-CCCC",
            to = "WSP-DDDD-EEEE-FFFF",
            timestamp = 1704067200000L,
            nonceB64 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            ciphertextB64 = "dGVzdA=="
        )

        val hash1 = sha256(canonical.toByteArray(Charsets.UTF_8))
        val hash2 = sha256(canonical.toByteArray(Charsets.UTF_8))

        assertArrayEquals("SHA256 must be deterministic", hash1, hash2)
        assertEquals("SHA256 must be 32 bytes", 32, hash1.size)
    }

    @Test
    fun `different message produces different hash`() {
        val canonical1 = buildCanonicalString(
            messageType = "text",
            messageId = "msg-123",
            from = "WSP-AAAA-BBBB-CCCC",
            to = "WSP-DDDD-EEEE-FFFF",
            timestamp = 1704067200000L,
            nonceB64 = "AAAA",
            ciphertextB64 = "BBBB"
        )

        val canonical2 = buildCanonicalString(
            messageType = "text",
            messageId = "msg-124", // Different message ID
            from = "WSP-AAAA-BBBB-CCCC",
            to = "WSP-DDDD-EEEE-FFFF",
            timestamp = 1704067200000L,
            nonceB64 = "AAAA",
            ciphertextB64 = "BBBB"
        )

        val hash1 = sha256(canonical1.toByteArray(Charsets.UTF_8))
        val hash2 = sha256(canonical2.toByteArray(Charsets.UTF_8))

        assertFalse("Different messages must have different hashes", hash1.contentEquals(hash2))
    }

    @Test
    fun `key derivation produces valid signing keys`() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val keys = KeyDerivation.deriveAll(mnemonic, "")

        // signSeed must be 32 bytes
        assertEquals("signSeed must be 32 bytes", 32, keys.signSeed.size)

        // signSeed must match frozen vector
        val expectedSignSeedHex = "457f5c29bc4ab25ea84b9d076fee560db80b9994725106594400e28672f3e5be"
        assertEquals("signSeed must match frozen vector", expectedSignSeedHex, bytesToHex(keys.signSeed))
    }

    @Test
    fun `CanonicalSigning buildCanonicalString matches expected format`() {
        // Use the actual CanonicalSigning class
        val canonical = CanonicalSigning.buildCanonicalString(
            messageType = "text",
            messageId = "msg-123",
            from = "WSP-AAAA-BBBB-CCCC",
            toOrGroupId = "WSP-DDDD-EEEE-FFFF",
            timestamp = 1704067200000L,
            nonceB64 = "AAAA",
            ciphertextB64 = "BBBB"
        )

        val expected = "v1\ntext\nmsg-123\nWSP-AAAA-BBBB-CCCC\nWSP-DDDD-EEEE-FFFF\n1704067200000\nAAAA\nBBBB\n"
        assertEquals("Canonical string format must match", expected, canonical)
    }

    // ==========================================================================
    // HELPER FUNCTIONS
    // ==========================================================================

    private fun buildCanonicalString(
        messageType: String,
        messageId: String,
        from: String,
        to: String,
        timestamp: Long,
        nonceB64: String,
        ciphertextB64: String
    ): String {
        return listOf(
            "v1",
            messageType,
            messageId,
            from,
            to,
            timestamp.toString(),
            nonceB64,
            ciphertextB64,
            "" // Trailing newline
        ).joinToString("\n")
    }

    private fun sha256(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
