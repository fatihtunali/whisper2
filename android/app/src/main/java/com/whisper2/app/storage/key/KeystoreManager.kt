package com.whisper2.app.storage.key

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore manager for AES-GCM encryption
 * Used for encrypting larger blobs (contacts backup cache, etc.)
 *
 * Key aliases are versioned:
 * - whisper2_aes_v1: Main encryption key
 * - whisper2_keywrap_v1: Key wrapping (future)
 */
class KeystoreManager {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }

    // MARK: - Key Management

    /**
     * Get or create the AES-GCM master key
     */
    private fun getOrCreateKey(): SecretKey {
        val existingKey = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existingKey != null) {
            return existingKey.secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    // MARK: - Encryption

    /**
     * Encrypt plaintext using AES-GCM
     * Returns: IV (12 bytes) || ciphertext (includes auth tag)
     */
    fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())

        val iv = cipher.iv // 12 bytes for GCM
        val ciphertext = cipher.doFinal(plaintext)

        // Return IV || ciphertext
        return iv + ciphertext
    }

    /**
     * Decrypt ciphertext using AES-GCM
     * Input format: IV (12 bytes) || ciphertext (includes auth tag)
     * @throws Exception if decryption fails (tampered data, wrong key, etc.)
     */
    fun decrypt(data: ByteArray): ByteArray {
        if (data.size < GCM_IV_LENGTH) {
            throw IllegalArgumentException("Data too short: must be at least $GCM_IV_LENGTH bytes")
        }

        val iv = data.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = data.copyOfRange(GCM_IV_LENGTH, data.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), spec)

        return cipher.doFinal(ciphertext)
    }

    // MARK: - Key Deletion

    /**
     * Delete the encryption key from keystore
     * Called during complete data wipe
     */
    fun deleteKey() {
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS)
        }
    }

    /**
     * Check if encryption key exists
     */
    fun hasKey(): Boolean {
        return keyStore.containsAlias(KEY_ALIAS)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "whisper2_keywrap_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH_BITS = 128
    }
}
