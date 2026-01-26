package com.whisper2.app.crypto

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.whisper2.app.core.Constants
import com.whisper2.app.core.CryptoException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.ceil

/**
 * Key derivation using BIP39 mnemonic and HKDF
 * Matches server implementation in crypto.ts
 *
 * CRITICAL: Uses full 64-byte BIP39 seed + "whisper" salt for cross-platform recovery
 */
object KeyDerivation {

    // MARK: - HKDF-SHA256 Implementation

    /**
     * HKDF-SHA256 Extract step
     */
    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    /**
     * HKDF-SHA256 Expand step
     */
    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val hashLen = 32 // SHA256 output length
        val n = ceil(length.toDouble() / hashLen).toInt()

        val result = ByteArray(n * hashLen)
        var t = ByteArray(0)

        for (i in 1..n) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t)
            mac.update(info)
            mac.update(i.toByte())
            t = mac.doFinal()
            System.arraycopy(t, 0, result, (i - 1) * hashLen, hashLen)
        }

        return result.copyOf(length)
    }

    /**
     * HKDF-SHA256 full derivation
     */
    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hkdfExtract(salt, ikm)
        return hkdfExpand(prk, info, length)
    }

    // MARK: - Derive Key

    /**
     * Derive domain-specific key using HKDF-SHA256
     * CRITICAL: Uses full 64-byte BIP39 seed + "whisper" salt for cross-platform recovery
     *
     * @param seed Full 64-byte BIP39 seed (NOT truncated)
     * @param info Domain string ("whisper/enc", "whisper/sign", "whisper/contacts")
     * @param length Output key length (default 32 bytes)
     */
    fun deriveKey(seed: ByteArray, info: String, length: Int = 32): ByteArray {
        require(seed.size == Constants.Crypto.BIP39_SEED_LENGTH) {
            "BIP39 seed must be ${Constants.Crypto.BIP39_SEED_LENGTH} bytes, got ${seed.size}"
        }

        val salt = Constants.Crypto.HKDF_SALT.toByteArray(Charsets.UTF_8)
        val infoBytes = info.toByteArray(Charsets.UTF_8)

        return hkdf(seed, salt, infoBytes, length)
    }

    // MARK: - Derived Keys

    /**
     * All derived keys from mnemonic
     * Property names match test expectations
     */
    data class DerivedKeys(
        val encSeed: ByteArray,      // For X25519 (32 bytes)
        val signSeed: ByteArray,     // For Ed25519 (32 bytes)
        val contactsKey: ByteArray   // For contacts backup (32 bytes)
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DerivedKeys) return false
            return encSeed.contentEquals(other.encSeed) &&
                    signSeed.contentEquals(other.signSeed) &&
                    contactsKey.contentEquals(other.contactsKey)
        }

        override fun hashCode(): Int {
            var result = encSeed.contentHashCode()
            result = 31 * result + signSeed.contentHashCode()
            result = 31 * result + contactsKey.contentHashCode()
            return result
        }
    }

    /**
     * Derive all keys from mnemonic with optional passphrase
     * This is the main entry point for key derivation
     */
    fun deriveAll(mnemonic: String, passphrase: String = ""): DerivedKeys {
        BIP39.requireValidMnemonic(mnemonic)

        val seed = BIP39.seedFromMnemonic(mnemonic, passphrase)

        return DerivedKeys(
            encSeed = deriveKey(seed, Constants.Crypto.ENCRYPTION_DOMAIN),
            signSeed = deriveKey(seed, Constants.Crypto.SIGNING_DOMAIN),
            contactsKey = deriveKey(seed, Constants.Crypto.CONTACTS_DOMAIN)
        )
    }

    /**
     * Derive all keys from mnemonic (no passphrase)
     * Convenience wrapper
     */
    fun deriveAllKeys(mnemonic: String): DerivedKeys = deriveAll(mnemonic, "")

    /**
     * Generate new mnemonic
     */
    fun generateMnemonic(): String {
        return BIP39.generateMnemonic()
    }

    /**
     * Validate mnemonic
     */
    fun isValidMnemonic(mnemonic: String): Boolean {
        return BIP39.isValidMnemonic(mnemonic)
    }
}
