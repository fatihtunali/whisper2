package com.whisper2.app.crypto

/**
 * FROZEN TEST VECTORS - DO NOT MODIFY AFTER LAUNCH
 *
 * These vectors ensure cross-platform recovery compatibility.
 * Same mnemonic on iOS, Android, or any client MUST produce identical keys.
 *
 * Source: server/src/utils/test-vectors.ts
 */
object TestVectors {

    // ==========================================================================
    // TEST MNEMONIC
    // ==========================================================================

    /** Standard BIP39 test mnemonic (12 words) */
    const val TEST_MNEMONIC =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    /** Empty passphrase (standard BIP39 test) */
    const val TEST_PASSPHRASE = ""

    // ==========================================================================
    // BIP39 SEED (64 bytes)
    // PBKDF2-HMAC-SHA512(mnemonic, salt="mnemonic", iterations=2048)
    // ==========================================================================

    const val EXPECTED_BIP39_SEED_HEX =
        "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4"

    // ==========================================================================
    // HKDF-DERIVED SEEDS (32 bytes each)
    // IKM = 64-byte BIP39 seed, salt = "whisper"
    // ==========================================================================

    /** HKDF(seed, salt="whisper", info="whisper/enc") */
    const val EXPECTED_ENC_SEED_HEX =
        "08851144b1bdf8b99c563bd408f4a613943fef2d9120397573932bd9833e0149"

    /** HKDF(seed, salt="whisper", info="whisper/sign") */
    const val EXPECTED_SIGN_SEED_HEX =
        "457f5c29bc4ab25ea84b9d076fee560db80b9994725106594400e28672f3e5be"

    /** HKDF(seed, salt="whisper", info="whisper/contacts") */
    const val EXPECTED_CONTACTS_KEY_HEX =
        "de3d0fda0659df936a71ee48cf6519da84b285344916511b5244d2ac36c23ff2"

    // Base64 versions
    const val EXPECTED_ENC_SEED_B64 = "CIURRLG9+LmcVjvUCPSmE5Q/7y2RIDl1c5Mr2YM+AUk="
    const val EXPECTED_SIGN_SEED_B64 = "RX9cKbxKsl6oS50Hb+5WDbgLmZRyUQZZRADihnLz5b4="
    const val EXPECTED_CONTACTS_KEY_B64 = "3j0P2gZZ35Nqce5Iz2UZ2oSyhTRJFlEbUkTSrDbCP/I="

    // ==========================================================================
    // PUBLIC KEYS (32 bytes each)
    // Derived from seeds using libsodium
    // ==========================================================================

    /** X25519 public key from encSeed */
    const val EXPECTED_ENC_PUBLIC_KEY_HEX =
        "19c6d0f986827f8e86a5ed4a233f2ed1c97355536d0d26c4ae3b2b908369c050"

    /** Ed25519 public key from signSeed */
    const val EXPECTED_SIGN_PUBLIC_KEY_HEX =
        "bd930cbbf856bb76f2c25c64062a2944cc6065ba54b9af7242d2bfbde5d7c95b"

    // Base64 versions (what server stores/receives)
    const val EXPECTED_ENC_PUBLIC_KEY_B64 = "GcbQ+YaCf46Gpe1KIz8u0clzVVNtDSbErjsrkINpwFA="
    const val EXPECTED_SIGN_PUBLIC_KEY_B64 = "vZMMu/hWu3bywlxkBiopRMxgZbpUua9yQtK/veXXyVs="

    // ==========================================================================
    // HKDF PARAMETERS (FROZEN)
    // ==========================================================================

    const val HKDF_SALT = "whisper"
    const val HKDF_INFO_ENCRYPTION = "whisper/enc"
    const val HKDF_INFO_SIGNING = "whisper/sign"
    const val HKDF_INFO_CONTACTS = "whisper/contacts"

    const val BIP39_SEED_LENGTH = 64
    const val DERIVED_KEY_LENGTH = 32

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    fun hexToBytes(hex: String): ByteArray {
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
