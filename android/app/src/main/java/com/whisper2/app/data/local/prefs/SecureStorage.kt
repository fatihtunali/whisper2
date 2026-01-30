package com.whisper2.app.data.local.prefs

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.security.crypto.EncryptedSharedPreferences

import com.whisper2.app.core.Constants
import com.whisper2.app.core.Logger
import com.whisper2.app.core.decodeBase64
import com.whisper2.app.core.encodeBase64
import com.whisper2.app.data.local.keystore.KeystoreManager
import java.io.File
import java.util.UUID

/**
 * Secure storage for cryptographic keys using Keystore wrapping.
 */
class SecureStorage(private val context: Context, private val keystoreManager: KeystoreManager) {

    private val prefs: SharedPreferences = try {
        createEncryptedPrefs()
    } catch (e: Exception) {
        Logger.e("[SecureStorage] Failed to open encrypted prefs, resetting", e)
        // Clear corrupted data and retry
        clearEncryptedPrefsFile()
        keystoreManager.deleteKey()
        createEncryptedPrefs()
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey = androidx.security.crypto.MasterKey.Builder(context)
            .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            Constants.SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun clearEncryptedPrefsFile() {
        val prefsFile = File(context.filesDir.parent, "shared_prefs/${Constants.SECURE_PREFS_NAME}.xml")
        if (prefsFile.exists()) prefsFile.delete()
    }

    var encPrivateKey: ByteArray?
        get() = prefs.getString("enc_priv", null)?.decodeBase64()?.let { keystoreManager.unwrapKey(it) }
        set(value) = prefs.edit().putString("enc_priv", value?.let { keystoreManager.wrapKey(it).encodeBase64() }).apply()

    var encPublicKey: ByteArray?
        get() = prefs.getString("enc_pub", null)?.decodeBase64()
        set(value) = prefs.edit().putString("enc_pub", value?.encodeBase64()).apply()

    var signPrivateKey: ByteArray?
        get() = prefs.getString("sign_priv", null)?.decodeBase64()?.let { keystoreManager.unwrapKey(it) }
        set(value) = prefs.edit().putString("sign_priv", value?.let { keystoreManager.wrapKey(it).encodeBase64() }).apply()

    var signPublicKey: ByteArray?
        get() = prefs.getString("sign_pub", null)?.decodeBase64()
        set(value) = prefs.edit().putString("sign_pub", value?.encodeBase64()).apply()

    var contactsKey: ByteArray?
        get() = prefs.getString("contacts_key", null)?.decodeBase64()?.let { keystoreManager.unwrapKey(it) }
        set(value) = prefs.edit().putString("contacts_key", value?.let { keystoreManager.wrapKey(it).encodeBase64() }).apply()

    var mnemonic: String?
        get() = prefs.getString("mnemonic", null)?.decodeBase64()?.let { String(keystoreManager.unwrapKey(it), Charsets.UTF_8) }
        set(value) = prefs.edit().putString("mnemonic", value?.toByteArray()?.let { keystoreManager.wrapKey(it).encodeBase64() }).apply()

    var sessionToken: String?
        get() = prefs.getString("session_token", null)
        set(value) = prefs.edit().putString("session_token", value).apply()

    var sessionExpiresAt: Long
        get() = prefs.getLong("session_expires_at", 0L)
        set(value) = prefs.edit().putLong("session_expires_at", value).apply()

    var whisperId: String?
        get() = prefs.getString("whisper_id", null)
        set(value) = prefs.edit().putString("whisper_id", value).apply()

    var fcmToken: String?
        get() = prefs.getString("fcm_token", null)
        set(value) = prefs.edit().putString("fcm_token", value).apply()

    val deviceId: String
        get() = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }

    fun getOrCreateDeviceId(): String = deviceId

    // Active call tracking for cleanup on app restart
    var activeCallId: String?
        get() = prefs.getString("active_call_id", null)
        set(value) = prefs.edit().putString("active_call_id", value).apply()

    var activeCallPeerId: String?
        get() = prefs.getString("active_call_peer_id", null)
        set(value) = prefs.edit().putString("active_call_peer_id", value).apply()

    fun clearActiveCall() {
        prefs.edit()
            .remove("active_call_id")
            .remove("active_call_peer_id")
            .apply()
    }

    // Ringing call tracking for dedupe across process death
    // Survives OEM battery killer restarts
    var ringingCallId: String?
        get() = prefs.getString("ringing_call_id", null)
        set(value) = prefs.edit().putString("ringing_call_id", value).apply()

    var ringingStartedAt: Long
        get() = prefs.getLong("ringing_started_at", 0L)
        set(value) = prefs.edit().putLong("ringing_started_at", value).apply()

    /**
     * Check if a call is already ringing (within timeout window).
     * Used to dedupe FCM+WS race even across process restarts.
     * Uses elapsedRealtime for monotonic safety (immune to wall-clock changes).
     */
    fun isCallAlreadyRinging(callId: String, timeoutMs: Long = 60_000): Boolean {
        val storedCallId = ringingCallId ?: return false
        val startedAt = ringingStartedAt
        val now = SystemClock.elapsedRealtime()

        // Same callId and within timeout window
        // Note: elapsedRealtime resets on reboot, but that's fine -
        // if device rebooted, the call is dead anyway
        return storedCallId == callId && (now - startedAt) < timeoutMs
    }

    /**
     * Mark a call as ringing (persist for dedupe across process death).
     * Uses elapsedRealtime for monotonic safety.
     */
    fun setCallRinging(callId: String) {
        prefs.edit()
            .putString("ringing_call_id", callId)
            .putLong("ringing_started_at", SystemClock.elapsedRealtime())
            .apply()
    }

    /**
     * Clear ringing state (on answer/decline/end).
     */
    fun clearRingingCall() {
        prefs.edit()
            .remove("ringing_call_id")
            .remove("ringing_started_at")
            .apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
        keystoreManager.deleteKey()
    }

    fun isLoggedIn(): Boolean = sessionToken != null && whisperId != null
}
