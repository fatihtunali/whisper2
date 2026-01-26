package com.whisper2.app.storage.key

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.whisper2.app.core.Constants

/**
 * Secure preferences wrapper using EncryptedSharedPreferences
 * Backed by Android Keystore for encryption
 *
 * Rules:
 * - Private keys stored as raw bytes (via Base64 internally)
 * - All sensitive data encrypted at rest
 * - Synchronous API for easy testing
 */
class SecurePrefs(context: Context) {

    private val masterKey: MasterKey = MasterKey.Builder(context, MASTER_KEY_ALIAS)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_FILE_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // MARK: - String Operations

    fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).commit()
    }

    fun getString(key: String): String? {
        return prefs.getString(key, null)
    }

    // MARK: - Bytes Operations (stored as Base64)

    fun putBytes(key: String, value: ByteArray) {
        val encoded = Base64.encodeToString(value, Base64.NO_WRAP)
        prefs.edit().putString(key, encoded).commit()
    }

    fun getBytes(key: String): ByteArray {
        val encoded = prefs.getString(key, null)
            ?: throw IllegalStateException("Key not found: $key")
        return Base64.decode(encoded, Base64.NO_WRAP)
    }

    fun getBytesOrNull(key: String): ByteArray? {
        val encoded = prefs.getString(key, null) ?: return null
        return try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    // MARK: - Remove Operations

    fun remove(key: String) {
        prefs.edit().remove(key).commit()
    }

    fun clear() {
        prefs.edit().clear().commit()
    }

    // MARK: - Bulk Operations for Sensitive Data

    /**
     * Clear all sensitive authentication data
     * Called on force logout / kick
     */
    fun clearAllSensitive() {
        prefs.edit()
            .remove(Constants.StorageKey.ENC_PRIVATE_KEY)
            .remove(Constants.StorageKey.ENC_PUBLIC_KEY)
            .remove(Constants.StorageKey.SIGN_PRIVATE_KEY)
            .remove(Constants.StorageKey.SIGN_PUBLIC_KEY)
            .remove(Constants.StorageKey.CONTACTS_KEY)
            .remove(Constants.StorageKey.SESSION_TOKEN)
            .remove(Constants.StorageKey.SESSION_EXPIRY)
            .remove(Constants.StorageKey.DEVICE_ID)
            .remove(Constants.StorageKey.WHISPER_ID)
            .remove(Constants.StorageKey.FCM_TOKEN)
            .commit()
    }

    // MARK: - Convenience Methods for Known Keys

    var encPrivateKey: ByteArray?
        get() = getBytesOrNull(Constants.StorageKey.ENC_PRIVATE_KEY)
        set(value) {
            if (value != null) putBytes(Constants.StorageKey.ENC_PRIVATE_KEY, value)
            else remove(Constants.StorageKey.ENC_PRIVATE_KEY)
        }

    var signPrivateKey: ByteArray?
        get() = getBytesOrNull(Constants.StorageKey.SIGN_PRIVATE_KEY)
        set(value) {
            if (value != null) putBytes(Constants.StorageKey.SIGN_PRIVATE_KEY, value)
            else remove(Constants.StorageKey.SIGN_PRIVATE_KEY)
        }

    var sessionToken: String?
        get() = getString(Constants.StorageKey.SESSION_TOKEN)
        set(value) {
            if (value != null) putString(Constants.StorageKey.SESSION_TOKEN, value)
            else remove(Constants.StorageKey.SESSION_TOKEN)
        }

    var deviceId: String?
        get() = getString(Constants.StorageKey.DEVICE_ID)
        set(value) {
            if (value != null) putString(Constants.StorageKey.DEVICE_ID, value)
            else remove(Constants.StorageKey.DEVICE_ID)
        }

    var fcmToken: String?
        get() = getString(Constants.StorageKey.FCM_TOKEN)
        set(value) {
            if (value != null) putString(Constants.StorageKey.FCM_TOKEN, value)
            else remove(Constants.StorageKey.FCM_TOKEN)
        }

    companion object {
        private const val PREFS_FILE_NAME = "whisper2_secure_prefs_v1"
        private const val MASTER_KEY_ALIAS = "whisper2_aes_v1"
    }
}
