package com.whisper2.app.crypto

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.interfaces.Box
import com.goterl.lazysodium.interfaces.Sign
import com.whisper2.app.core.Constants

/**
 * Key derivation from BIP39 seed.
 *
 * Chain:
 * Mnemonic → PBKDF2-HMAC-SHA512 → 64-byte BIP39 Seed
 *         → HKDF-SHA256 (salt="whisper")
 *         ├── info="whisper/enc"      → 32-byte encSeed    → X25519 keypair
 *         ├── info="whisper/sign"     → 32-byte signSeed   → Ed25519 keypair
 *         └── info="whisper/contacts" → 32-byte contactsKey
 */
class KeyDerivation(private val lazySodium: LazySodiumAndroid) {

    data class DerivedKeys(
        val encPublicKey: ByteArray,
        val encPrivateKey: ByteArray,
        val signPublicKey: ByteArray,
        val signPrivateKey: ByteArray,
        val contactsKey: ByteArray
    )

    /**
     * Derive all keys from mnemonic.
     */
    fun deriveAllKeys(mnemonic: String): DerivedKeys {
        // Step 1: Mnemonic → BIP39 Seed (PBKDF2)
        val seed = BIP39.seedFromMnemonic(mnemonic)

        // Step 2: HKDF to derive domain-specific seeds
        val salt = Constants.HKDF_SALT.toByteArray(Charsets.UTF_8)

        val encSeed = HKDF.deriveKey(
            ikm = seed,
            salt = salt,
            info = Constants.ENCRYPTION_DOMAIN.toByteArray(Charsets.UTF_8),
            length = 32
        )

        val signSeed = HKDF.deriveKey(
            ikm = seed,
            salt = salt,
            info = Constants.SIGNING_DOMAIN.toByteArray(Charsets.UTF_8),
            length = 32
        )

        val contactsKey = HKDF.deriveKey(
            ikm = seed,
            salt = salt,
            info = Constants.CONTACTS_DOMAIN.toByteArray(Charsets.UTF_8),
            length = 32
        )

        // Step 3: Generate keypairs from seeds
        val encKeyPair = generateEncryptionKeyPair(encSeed)
        val signKeyPair = generateSigningKeyPair(signSeed)

        return DerivedKeys(
            encPublicKey = encKeyPair.first,
            encPrivateKey = encKeyPair.second,
            signPublicKey = signKeyPair.first,
            signPrivateKey = signKeyPair.second,
            contactsKey = contactsKey
        )
    }

    /**
     * Generate X25519 keypair from 32-byte seed.
     */
    private fun generateEncryptionKeyPair(seed: ByteArray): Pair<ByteArray, ByteArray> {
        val publicKey = ByteArray(Box.PUBLICKEYBYTES)
        val privateKey = ByteArray(Box.SECRETKEYBYTES)

        // Use seed as private key directly for deterministic generation
        System.arraycopy(seed, 0, privateKey, 0, 32)
        // Derive public key from private key using X25519 scalar multiplication
        lazySodium.sodium.crypto_scalarmult_base(publicKey, privateKey)

        return Pair(publicKey, privateKey)
    }

    /**
     * Generate Ed25519 keypair from 32-byte seed.
     */
    private fun generateSigningKeyPair(seed: ByteArray): Pair<ByteArray, ByteArray> {
        val publicKey = ByteArray(Sign.PUBLICKEYBYTES)
        val privateKey = ByteArray(Sign.SECRETKEYBYTES)

        lazySodium.cryptoSignSeedKeypair(publicKey, privateKey, seed)

        return Pair(publicKey, privateKey)
    }
}
