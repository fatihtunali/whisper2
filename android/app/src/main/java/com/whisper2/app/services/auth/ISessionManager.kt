package com.whisper2.app.services.auth

/**
 * Session manager interface for dependency injection and testing
 */
interface ISessionManager {
    val isLoggedIn: Boolean
    val sessionToken: String?
    val deviceId: String?
    val whisperId: String?
    val sessionExpiresAt: Long?
    val serverTime: Long?

    fun saveSession(token: String, deviceId: String)
    fun saveFullSession(
        whisperId: String,
        sessionToken: String,
        sessionExpiresAt: Long,
        serverTime: Long
    )
    fun updateToken(newToken: String)
    fun forceLogout(reason: String)
    fun softLogout()
}
