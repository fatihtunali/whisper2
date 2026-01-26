package com.whisper2.app.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.whisper2.app.storage.key.KeystoreManager
import com.whisper2.app.storage.key.SecurePrefs
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Gate 1-4: Secure Storage Tests
 * Must PASS before proceeding to Step 3
 */
@RunWith(AndroidJUnit4::class)
class SecureStorageTest {

    private lateinit var securePrefs: SecurePrefs
    private lateinit var ks: KeystoreManager

    @Before
    fun setup() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        securePrefs = SecurePrefs(ctx)
        ks = KeystoreManager()

        // Clean slate for each test
        securePrefs.clear()
    }

    // =========================================================================
    // Gate 1: Persist & Readback
    // =========================================================================

    @Test
    fun gate1_persist_and_readback_keys_and_tokens() {
        val encPriv = ByteArray(32) { it.toByte() }
        val signPriv = ByteArray(64) { (it + 1).toByte() }
        val token = "sess_" + UUID.randomUUID().toString()
        val deviceId = UUID.randomUUID().toString()

        securePrefs.putBytes("encPrivateKey", encPriv)
        securePrefs.putBytes("signPrivateKey", signPriv)
        securePrefs.putString("sessionToken", token)
        securePrefs.putString("deviceId", deviceId)

        assertArrayEquals("encPrivateKey must match", encPriv, securePrefs.getBytes("encPrivateKey"))
        assertArrayEquals("signPrivateKey must match", signPriv, securePrefs.getBytes("signPrivateKey"))
        assertEquals("sessionToken must match", token, securePrefs.getString("sessionToken"))
        assertEquals("deviceId must match", deviceId, securePrefs.getString("deviceId"))

        // Verify deviceId is valid UUID format
        assertNotNull("deviceId must be parseable as UUID", UUID.fromString(deviceId))
    }

    @Test
    fun gate1_convenience_properties_work() {
        val encPriv = ByteArray(32) { 0xAA.toByte() }
        val signPriv = ByteArray(64) { 0xBB.toByte() }

        securePrefs.encPrivateKey = encPriv
        securePrefs.signPrivateKey = signPriv
        securePrefs.sessionToken = "test_token"
        securePrefs.deviceId = "device-123"
        securePrefs.fcmToken = "fcm-xyz"

        assertArrayEquals(encPriv, securePrefs.encPrivateKey)
        assertArrayEquals(signPriv, securePrefs.signPrivateKey)
        assertEquals("test_token", securePrefs.sessionToken)
        assertEquals("device-123", securePrefs.deviceId)
        assertEquals("fcm-xyz", securePrefs.fcmToken)
    }

    // =========================================================================
    // Gate 2: Process Death / Cold Start (simulated via re-instantiation)
    // =========================================================================

    @Test
    fun gate2_values_survive_reinitialization() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        // Write with first instance
        val prefs1 = SecurePrefs(ctx)
        val testBytes = ByteArray(32) { (it * 2).toByte() }
        val testToken = "persistent_token_123"

        prefs1.putBytes("test_key", testBytes)
        prefs1.putString("test_token", testToken)

        // Create new instance (simulates process restart)
        val prefs2 = SecurePrefs(ctx)

        assertArrayEquals("Bytes must survive re-init", testBytes, prefs2.getBytes("test_key"))
        assertEquals("String must survive re-init", testToken, prefs2.getString("test_token"))
    }

    // =========================================================================
    // Gate 3: AES-GCM Encrypt/Decrypt Roundtrip
    // =========================================================================

    @Test
    fun gate3_aes_gcm_encrypt_decrypt_roundtrip() {
        val plaintext = ByteArray(128) { (it * 3).toByte() }
        val ct = ks.encrypt(plaintext)
        val out = ks.decrypt(ct)

        assertArrayEquals("Decrypted must match original", plaintext, out)
    }

    @Test
    fun gate3_aes_gcm_different_plaintexts_different_ciphertexts() {
        val plain1 = ByteArray(32) { 1 }
        val plain2 = ByteArray(32) { 2 }

        val ct1 = ks.encrypt(plain1)
        val ct2 = ks.encrypt(plain2)

        assertFalse("Different plaintexts should produce different ciphertexts",
            ct1.contentEquals(ct2))
    }

    @Test
    fun gate3_aes_gcm_same_plaintext_different_iv() {
        val plaintext = ByteArray(32) { 0x55 }

        val ct1 = ks.encrypt(plaintext)
        val ct2 = ks.encrypt(plaintext)

        // Same plaintext should produce different ciphertext due to random IV
        assertFalse("Same plaintext should produce different ciphertext (random IV)",
            ct1.contentEquals(ct2))

        // But both should decrypt to same plaintext
        assertArrayEquals(plaintext, ks.decrypt(ct1))
        assertArrayEquals(plaintext, ks.decrypt(ct2))
    }

    // =========================================================================
    // Gate 4: Tamper Resistance
    // =========================================================================

    @Test
    fun gate4_tamper_causes_decrypt_failure() {
        val plaintext = ByteArray(64) { 7 }
        val ct = ks.encrypt(plaintext)

        // Flip 1 bit in ciphertext (not IV)
        val tamperedCt = ct.copyOf()
        tamperedCt[tamperedCt.lastIndex] = (tamperedCt.last().toInt() xor 0x01).toByte()

        try {
            ks.decrypt(tamperedCt)
            fail("Expected decrypt to fail on tampered ciphertext")
        } catch (e: Exception) {
            // Expected - GCM authentication failed
            assertTrue("Exception should indicate auth failure",
                e.message?.contains("tag") == true ||
                        e.message?.contains("mac") == true ||
                        e.message?.contains("AEADBadTagException") == true ||
                        e is javax.crypto.AEADBadTagException ||
                        e.cause is javax.crypto.AEADBadTagException)
        }
    }

    @Test
    fun gate4_tamper_iv_causes_decrypt_failure() {
        val plaintext = ByteArray(32) { 0xCC.toByte() }
        val ct = ks.encrypt(plaintext)

        // Flip 1 bit in IV (first 12 bytes)
        val tamperedCt = ct.copyOf()
        tamperedCt[0] = (tamperedCt[0].toInt() xor 0x01).toByte()

        try {
            ks.decrypt(tamperedCt)
            fail("Expected decrypt to fail on tampered IV")
        } catch (e: Exception) {
            // Expected - decryption produces garbage which fails GCM auth
            // This is correct behavior
        }
    }

    @Test
    fun gate4_truncated_data_rejected() {
        val plaintext = ByteArray(32) { 1 }
        val ct = ks.encrypt(plaintext)

        // Truncate to less than IV length
        val truncated = ct.copyOfRange(0, 5)

        try {
            ks.decrypt(truncated)
            fail("Expected decrypt to fail on truncated data")
        } catch (e: IllegalArgumentException) {
            // Expected
            assertTrue(e.message?.contains("too short") == true)
        }
    }
}
