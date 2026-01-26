package com.whisper2.app.crypto

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Box
import com.goterl.lazysodium.interfaces.Sign
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.text.Normalizer
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.ceil

/**
 * Crypto Test Vectors - Instrumented Test
 *
 * These tests run on an Android device/emulator and verify that
 * LazySodium produces the exact same keys as the server.
 *
 * Run with: ./gradlew connectedAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class CryptoVectorsInstrumentedTest {

    private lateinit var sodium: LazySodiumAndroid

    @Before
    fun setup() {
        sodium = LazySodiumAndroid(SodiumAndroid())
    }

    // ==========================================================================
    // X25519 PUBLIC KEY TEST
    // ==========================================================================

    @Test
    fun x25519_public_key_derivation_from_encryption_seed() {
        val encSeed = hexToBytes(EXPECTED_ENC_SEED_HEX)

        val publicKey = ByteArray(Box.PUBLICKEYBYTES)
        val secretKey = ByteArray(Box.SECRETKEYBYTES)

        val success = sodium.cryptoBoxSeedKeypair(publicKey, secretKey, encSeed)
        assertTrue("cryptoBoxSeedKeypair must succeed", success)

        assertEquals(
            "X25519 public key hex must match",
            EXPECTED_ENC_PUBLIC_KEY_HEX,
            bytesToHex(publicKey)
        )

        assertEquals(
            "X25519 public key base64 must match",
            EXPECTED_ENC_PUBLIC_KEY_B64,
            Base64.encodeToString(publicKey, Base64.NO_WRAP)
        )
    }

    // ==========================================================================
    // ED25519 PUBLIC KEY TEST
    // ==========================================================================

    @Test
    fun ed25519_public_key_derivation_from_signing_seed() {
        val signSeed = hexToBytes(EXPECTED_SIGN_SEED_HEX)

        val publicKey = ByteArray(Sign.PUBLICKEYBYTES)
        val secretKey = ByteArray(Sign.SECRETKEYBYTES)

        val success = sodium.cryptoSignSeedKeypair(publicKey, secretKey, signSeed)
        assertTrue("cryptoSignSeedKeypair must succeed", success)

        assertEquals(
            "Ed25519 public key hex must match",
            EXPECTED_SIGN_PUBLIC_KEY_HEX,
            bytesToHex(publicKey)
        )

        assertEquals(
            "Ed25519 public key base64 must match",
            EXPECTED_SIGN_PUBLIC_KEY_B64,
            Base64.encodeToString(publicKey, Base64.NO_WRAP)
        )
    }

    // ==========================================================================
    // FULL CHAIN TEST (Mnemonic -> BIP39 -> HKDF -> Keypairs)
    // ==========================================================================

    @Test
    fun full_chain_mnemonic_to_keypairs() {
        // Step 1: BIP39 mnemonic to seed
        val bip39Seed = bip39ToSeed(TEST_MNEMONIC, TEST_PASSPHRASE)
        assertEquals(
            "BIP39 seed must match",
            EXPECTED_BIP39_SEED_HEX,
            bytesToHex(bip39Seed)
        )

        // Step 2: HKDF to derive domain seeds
        val encSeed = hkdfDerive(bip39Seed, HKDF_SALT, HKDF_INFO_ENCRYPTION, 32)
        val signSeed = hkdfDerive(bip39Seed, HKDF_SALT, HKDF_INFO_SIGNING, 32)
        val contactsKey = hkdfDerive(bip39Seed, HKDF_SALT, HKDF_INFO_CONTACTS, 32)

        assertEquals("Enc seed must match", EXPECTED_ENC_SEED_HEX, bytesToHex(encSeed))
        assertEquals("Sign seed must match", EXPECTED_SIGN_SEED_HEX, bytesToHex(signSeed))
        assertEquals("Contacts key must match", EXPECTED_CONTACTS_KEY_HEX, bytesToHex(contactsKey))

        // Step 3: Derive keypairs with LazySodium
        val encPublicKey = ByteArray(Box.PUBLICKEYBYTES)
        val encSecretKey = ByteArray(Box.SECRETKEYBYTES)
        sodium.cryptoBoxSeedKeypair(encPublicKey, encSecretKey, encSeed)

        val signPublicKey = ByteArray(Sign.PUBLICKEYBYTES)
        val signSecretKey = ByteArray(Sign.SECRETKEYBYTES)
        sodium.cryptoSignSeedKeypair(signPublicKey, signSecretKey, signSeed)

        // Verify final public keys
        assertEquals(
            "Full chain enc public key must match",
            EXPECTED_ENC_PUBLIC_KEY_B64,
            Base64.encodeToString(encPublicKey, Base64.NO_WRAP)
        )
        assertEquals(
            "Full chain sign public key must match",
            EXPECTED_SIGN_PUBLIC_KEY_B64,
            Base64.encodeToString(signPublicKey, Base64.NO_WRAP)
        )
    }

    // ==========================================================================
    // CANONICAL SIGNING TEST
    // ==========================================================================

    @Test
    fun canonical_signing_produces_valid_signature() {
        val signSeed = hexToBytes(EXPECTED_SIGN_SEED_HEX)

        // Derive signing keypair
        val publicKey = ByteArray(Sign.PUBLICKEYBYTES)
        val secretKey = ByteArray(Sign.SECRETKEYBYTES)
        sodium.cryptoSignSeedKeypair(publicKey, secretKey, signSeed)

        // Build a canonical message
        val canonicalString = listOf(
            "v1",
            "text",
            "msg-123",
            "WSP-AAAA-BBBB-CCCC",
            "WSP-DDDD-EEEE-FFFF",
            "1704067200000",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", // 24-byte nonce base64
            "dGVzdCBtZXNzYWdl", // "test message" base64
            ""
        ).joinToString("\n")

        // SHA256 hash of canonical string (server does Ed25519_Sign(SHA256(canonical)))
        val canonicalBytes = canonicalString.toByteArray(Charsets.UTF_8)
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(canonicalBytes)

        // Sign the hash
        val signature = ByteArray(Sign.BYTES)
        val success = sodium.cryptoSignDetached(signature, hash, hash.size.toLong(), secretKey)
        assertTrue("Signing must succeed", success)
        assertEquals("Signature must be 64 bytes", 64, signature.size)

        // Verify the signature
        val verified = sodium.cryptoSignVerifyDetached(signature, hash, hash.size, publicKey)
        assertTrue("Signature must verify", verified)
    }

    @Test
    fun box_encryption_roundtrip() {
        val encSeed = hexToBytes(EXPECTED_ENC_SEED_HEX)

        // Generate keypair
        val alicePublic = ByteArray(Box.PUBLICKEYBYTES)
        val aliceSecret = ByteArray(Box.SECRETKEYBYTES)
        sodium.cryptoBoxSeedKeypair(alicePublic, aliceSecret, encSeed)

        // Generate another keypair for Bob
        val bobPublic = ByteArray(Box.PUBLICKEYBYTES)
        val bobSecret = ByteArray(Box.SECRETKEYBYTES)
        sodium.cryptoBoxKeypair(bobPublic, bobSecret)

        // Encrypt message from Alice to Bob
        val message = "Hello, Bob!".toByteArray()
        val nonce = sodium.randomBytesBuf(Box.NONCEBYTES)

        val ciphertext = ByteArray(message.size + Box.MACBYTES)
        val encSuccess = sodium.cryptoBoxEasy(
            ciphertext, message, message.size.toLong(),
            nonce, bobPublic, aliceSecret
        )
        assertTrue("Encryption must succeed", encSuccess)

        // Decrypt message
        val plaintext = ByteArray(ciphertext.size - Box.MACBYTES)
        val decSuccess = sodium.cryptoBoxOpenEasy(
            plaintext, ciphertext, ciphertext.size.toLong(),
            nonce, alicePublic, bobSecret
        )
        assertTrue("Decryption must succeed", decSuccess)

        assertEquals("Decrypted message must match", "Hello, Bob!", String(plaintext))
    }

    // ==========================================================================
    // TEST CONSTANTS
    // ==========================================================================

    companion object {
        const val TEST_MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        const val TEST_PASSPHRASE = ""

        const val EXPECTED_BIP39_SEED_HEX =
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4"

        const val EXPECTED_ENC_SEED_HEX =
            "08851144b1bdf8b99c563bd408f4a613943fef2d9120397573932bd9833e0149"
        const val EXPECTED_SIGN_SEED_HEX =
            "457f5c29bc4ab25ea84b9d076fee560db80b9994725106594400e28672f3e5be"
        const val EXPECTED_CONTACTS_KEY_HEX =
            "de3d0fda0659df936a71ee48cf6519da84b285344916511b5244d2ac36c23ff2"

        const val EXPECTED_ENC_PUBLIC_KEY_HEX =
            "19c6d0f986827f8e86a5ed4a233f2ed1c97355536d0d26c4ae3b2b908369c050"
        const val EXPECTED_SIGN_PUBLIC_KEY_HEX =
            "bd930cbbf856bb76f2c25c64062a2944cc6065ba54b9af7242d2bfbde5d7c95b"

        const val EXPECTED_ENC_PUBLIC_KEY_B64 = "GcbQ+YaCf46Gpe1KIz8u0clzVVNtDSbErjsrkINpwFA="
        const val EXPECTED_SIGN_PUBLIC_KEY_B64 = "vZMMu/hWu3bywlxkBiopRMxgZbpUua9yQtK/veXXyVs="

        const val HKDF_SALT = "whisper"
        const val HKDF_INFO_ENCRYPTION = "whisper/enc"
        const val HKDF_INFO_SIGNING = "whisper/sign"
        const val HKDF_INFO_CONTACTS = "whisper/contacts"
    }

    // ==========================================================================
    // HELPER FUNCTIONS
    // ==========================================================================

    private fun hexToBytes(hex: String): ByteArray {
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun bip39ToSeed(mnemonic: String, passphrase: String): ByteArray {
        val normalizedMnemonic = Normalizer.normalize(mnemonic, Normalizer.Form.NFKD)
        val normalizedPassphrase = Normalizer.normalize(passphrase, Normalizer.Form.NFKD)
        val salt = "mnemonic$normalizedPassphrase"

        val spec = PBEKeySpec(
            normalizedMnemonic.toCharArray(),
            salt.toByteArray(Charsets.UTF_8),
            2048,
            64 * 8
        )

        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        return factory.generateSecret(spec).encoded
    }

    private fun hkdfDerive(ikm: ByteArray, salt: String, info: String, length: Int): ByteArray {
        val saltBytes = salt.toByteArray(Charsets.UTF_8)
        val infoBytes = info.toByteArray(Charsets.UTF_8)

        val prk = hmacSha256(saltBytes, ikm)

        val hashLen = 32
        val n = ceil(length.toDouble() / hashLen).toInt()
        val result = ByteArray(n * hashLen)
        var t = ByteArray(0)

        for (i in 1..n) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t)
            mac.update(infoBytes)
            mac.update(i.toByte())
            t = mac.doFinal()
            System.arraycopy(t, 0, result, (i - 1) * hashLen, hashLen)
        }

        return result.copyOf(length)
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}
