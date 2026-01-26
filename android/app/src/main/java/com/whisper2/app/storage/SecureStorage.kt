package com.whisper2.app.storage

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure storage interface for sensitive data
 * Uses EncryptedSharedPreferences backed by Android Keystore
 */
interface SecureStorage {
    suspend fun getString(key: String): String?
    suspend fun setString(key: String, value: String)
    suspend fun getBytes(key: String): ByteArray?
    suspend fun setBytes(key: String, value: ByteArray)
    suspend fun remove(key: String)
    suspend fun clear()
}

/**
 * Implementation using EncryptedSharedPreferences
 * Backed by Android Keystore for encryption
 * Provided via StorageModule as Singleton
 */
class SecureStorageImpl(
    private val context: Context
) : SecureStorage {

    private val masterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val sharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override suspend fun getString(key: String): String? {
        return sharedPreferences.getString(key, null)
    }

    override suspend fun setString(key: String, value: String) {
        sharedPreferences.edit().putString(key, value).apply()
    }

    override suspend fun getBytes(key: String): ByteArray? {
        val encoded = sharedPreferences.getString(key, null) ?: return null
        return try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun setBytes(key: String, value: ByteArray) {
        val encoded = Base64.encodeToString(value, Base64.NO_WRAP)
        sharedPreferences.edit().putString(key, encoded).apply()
    }

    override suspend fun remove(key: String) {
        sharedPreferences.edit().remove(key).apply()
    }

    override suspend fun clear() {
        sharedPreferences.edit().clear().apply()
    }

    companion object {
        private const val PREFS_FILE_NAME = "whisper2_secure_prefs"
    }
}
