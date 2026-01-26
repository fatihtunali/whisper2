package com.whisper2.app.services.auth

import android.util.Log
import com.whisper2.app.storage.key.SecurePrefs

/**
 * Session manager - handles authentication state and forced logout
 *
 * Single-active-device rule:
 * - When server sends force_logout, ALL sensitive data is wiped
 * - No way to recover without re-entering mnemonic
 */
class SessionManager(
    private val securePrefs: SecurePrefs
) : ISessionManager {
    // MARK: - Session State

    override val isLoggedIn: Boolean
        get() = securePrefs.sessionToken != null

    override val sessionToken: String?
        get() = securePrefs.sessionToken

    override val deviceId: String?
        get() = securePrefs.deviceId

    override val whisperId: String?
        get() = securePrefs.getString(com.whisper2.app.core.Constants.StorageKey.WHISPER_ID)

    override val sessionExpiresAt: Long?
        get() = securePrefs.getString(com.whisper2.app.core.Constants.StorageKey.SESSION_EXPIRY)?.toLongOrNull()

    override var serverTime: Long? = null
        private set

    // MARK: - Session Operations

    /**
     * Save session after successful authentication
     */
    override fun saveSession(token: String, deviceId: String) {
        securePrefs.sessionToken = token
        securePrefs.deviceId = deviceId
    }

    /**
     * Save full session data from register_ack
     */
    override fun saveFullSession(
        whisperId: String,
        sessionToken: String,
        sessionExpiresAt: Long,
        serverTime: Long
    ) {
        securePrefs.putString(com.whisper2.app.core.Constants.StorageKey.WHISPER_ID, whisperId)
        securePrefs.sessionToken = sessionToken
        securePrefs.putString(com.whisper2.app.core.Constants.StorageKey.SESSION_EXPIRY, sessionExpiresAt.toString())
        this.serverTime = serverTime
        Log.i(TAG, "Session saved: whisperId=$whisperId, expiresAt=$sessionExpiresAt")
    }

    /**
     * Update session token (after refresh)
     */
    override fun updateToken(newToken: String) {
        securePrefs.sessionToken = newToken
    }

    // MARK: - Logout

    /**
     * Force logout - wipes ALL sensitive material
     *
     * Called when:
     * - Server sends force_logout (another device registered)
     * - User manually logs out
     * - Authentication error (token invalid)
     *
     * Clears:
     * - sessionToken
     * - encPrivateKey / signPrivateKey (public keys too)
     * - contactsKey
     * - deviceId
     * - fcmToken
     * - whisperId
     */
    override fun forceLogout(reason: String) {
        Log.w(TAG, "Force logout triggered: $reason")

        // Wipe everything
        securePrefs.clearAllSensitive()

        Log.i(TAG, "All sensitive data cleared")
    }

    /**
     * Soft logout - clears session but keeps keys
     * Used when user wants to "pause" without losing identity
     * (Not currently used - keeping for future consideration)
     */
    override fun softLogout() {
        securePrefs.sessionToken = null
        securePrefs.deviceId = null
        securePrefs.fcmToken = null
    }

    companion object {
        private const val TAG = "SessionManager"
    }
}
