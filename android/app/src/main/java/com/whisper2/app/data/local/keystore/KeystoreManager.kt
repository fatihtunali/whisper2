package com.whisper2.app.data.local.keystore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.whisper2.app.core.Constants
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore wrapper for key protection.
 * Provides hardware-backed security when available.
 */
class KeystoreManager {
    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun getOrCreateKey(): SecretKey {
        return if (keyStore.containsAlias(Constants.KEYSTORE_ALIAS)) {
            (keyStore.getEntry(Constants.KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        } else {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            keyGenerator.init(
                KeyGenParameterSpec.Builder(Constants.KEYSTORE_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            keyGenerator.generateKey()
        }
    }

    fun wrapKey(keyToWrap: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(keyToWrap)
        return iv + ciphertext
    }

    fun unwrapKey(wrappedKey: ByteArray): ByteArray {
        val iv = wrappedKey.copyOfRange(0, 12)
        val ciphertext = wrappedKey.copyOfRange(12, wrappedKey.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    fun deleteKey() {
        if (keyStore.containsAlias(Constants.KEYSTORE_ALIAS)) {
            keyStore.deleteEntry(Constants.KEYSTORE_ALIAS)
        }
    }
}
