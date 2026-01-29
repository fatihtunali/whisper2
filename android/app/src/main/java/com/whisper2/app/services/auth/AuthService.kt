package com.whisper2.app.services.auth

import android.util.Base64
import com.google.firebase.messaging.FirebaseMessaging
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.whisper2.app.core.Constants
import com.whisper2.app.core.Logger
import com.whisper2.app.crypto.CryptoService
import com.whisper2.app.data.local.prefs.SecureStorage
import com.whisper2.app.data.network.ws.ErrorPayload
import com.whisper2.app.data.network.ws.LogoutPayload
import com.whisper2.app.data.network.ws.RegisterAckPayload
import com.whisper2.app.data.network.ws.RegisterBeginPayload
import com.whisper2.app.data.network.ws.RegisterChallengePayload
import com.whisper2.app.data.network.ws.RegisterProofPayload
import com.whisper2.app.data.network.ws.SessionRefreshAckPayload
import com.whisper2.app.data.network.ws.UpdateTokensPayload
import com.whisper2.app.data.network.ws.WsClientImpl
import com.whisper2.app.data.network.ws.WsConnectionState
import com.whisper2.app.data.network.ws.WsFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed class AuthState {
    object Unauthenticated : AuthState()
    object Authenticating : AuthState()
    data class Authenticated(val whisperId: String) : AuthState()
    data class Error(val message: String) : AuthState()
}

@Singleton
class AuthService @Inject constructor(
    private val wsClient: WsClientImpl,
    private val secureStorage: SecureStorage,
    private val cryptoService: CryptoService,
    private val gson: Gson
) {
    private val _authState = MutableStateFlow<AuthState>(
        if (secureStorage.isLoggedIn()) AuthState.Authenticated(secureStorage.whisperId!!)
        else AuthState.Unauthenticated
    )
    val authState: StateFlow<AuthState> = _authState

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Pending continuations for auth flow
    private var challengeContinuation: CancellableContinuation<RegisterChallengePayload>? = null
    private var ackContinuation: CancellableContinuation<RegisterAckPayload>? = null

    init {
        // Listen for auth-related messages
        scope.launch {
            wsClient.messages.collect { frame ->
                handleMessage(frame)
            }
        }
    }

    /**
     * Register a new account with the server.
     * Server assigns the WhisperID after verifying the public keys.
     */
    suspend fun registerNewAccount(mnemonic: String): Result<Unit> {
        return try {
            _authState.value = AuthState.Authenticating

            // 1. Derive keys from mnemonic
            val keys = cryptoService.deriveKeys(mnemonic)

            // Store keys securely (needed for signing the challenge)
            secureStorage.mnemonic = mnemonic
            secureStorage.encPublicKey = keys.encPublicKey
            secureStorage.encPrivateKey = keys.encPrivateKey
            secureStorage.signPublicKey = keys.signPublicKey
            secureStorage.signPrivateKey = keys.signPrivateKey
            secureStorage.contactsKey = keys.contactsKey

            // 2. Connect to WebSocket
            wsClient.connect()
            waitForConnection()

            // 3. Perform authentication flow
            val ack = performAuth(
                whisperId = null,  // New registration - server assigns ID
                encPublicKey = keys.encPublicKey,
                signPublicKey = keys.signPublicKey,
                signPrivateKey = keys.signPrivateKey
            )

            // 4. Store server-assigned WhisperID and session
            secureStorage.whisperId = ack.whisperId
            secureStorage.sessionToken = ack.sessionToken
            secureStorage.sessionExpiresAt = ack.sessionExpiresAt

            Logger.auth("Registration successful. WhisperID: ${ack.whisperId}")

            // 5. Update auth state
            _authState.value = AuthState.Authenticated(ack.whisperId)

            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e("Registration failed", e)
            _authState.value = AuthState.Error(e.message ?: "Registration failed")
            Result.failure(e)
        }
    }

    /**
     * Recover an existing account using mnemonic.
     * Server will recognize the public keys and return the existing WhisperID.
     */
    suspend fun recoverAccount(mnemonic: String): Result<Unit> {
        return try {
            _authState.value = AuthState.Authenticating

            // Derive keys from mnemonic
            val keys = cryptoService.deriveKeys(mnemonic)

            // Store keys securely
            secureStorage.mnemonic = mnemonic
            secureStorage.encPublicKey = keys.encPublicKey
            secureStorage.encPrivateKey = keys.encPrivateKey
            secureStorage.signPublicKey = keys.signPublicKey
            secureStorage.signPrivateKey = keys.signPrivateKey
            secureStorage.contactsKey = keys.contactsKey

            // Connect and authenticate
            wsClient.connect()
            waitForConnection()

            // Perform auth - server will return existing WhisperID for these keys
            val ack = performAuth(
                whisperId = null,  // Let server find by public keys
                encPublicKey = keys.encPublicKey,
                signPublicKey = keys.signPublicKey,
                signPrivateKey = keys.signPrivateKey
            )

            // Store session
            secureStorage.whisperId = ack.whisperId
            secureStorage.sessionToken = ack.sessionToken
            secureStorage.sessionExpiresAt = ack.sessionExpiresAt

            Logger.auth("Recovery successful. WhisperID: ${ack.whisperId}")

            _authState.value = AuthState.Authenticated(ack.whisperId)

            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e("Recovery failed", e)
            _authState.value = AuthState.Error(e.message ?: "Recovery failed")
            Result.failure(e)
        }
    }

    /**
     * Reconnect with existing session.
     */
    suspend fun reconnect(): Result<Unit> {
        val encPub = secureStorage.encPublicKey
        val signPub = secureStorage.signPublicKey
        val signPriv = secureStorage.signPrivateKey
        val savedWhisperId = secureStorage.whisperId

        if (encPub == null || signPub == null || signPriv == null || savedWhisperId == null) {
            return Result.failure(AuthException("Not authenticated - missing keys"))
        }

        return try {
            _authState.value = AuthState.Authenticating

            wsClient.connect()
            waitForConnection()

            val ack = performAuth(
                whisperId = savedWhisperId,  // Existing session
                encPublicKey = encPub,
                signPublicKey = signPub,
                signPrivateKey = signPriv
            )

            secureStorage.sessionToken = ack.sessionToken
            secureStorage.sessionExpiresAt = ack.sessionExpiresAt

            Logger.auth("Reconnect successful")

            _authState.value = AuthState.Authenticated(ack.whisperId)

            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e("Reconnect failed", e)
            _authState.value = AuthState.Error(e.message ?: "Reconnect failed")
            Result.failure(e)
        }
    }

    /**
     * Perform the authentication flow:
     * 1. Send register_begin
     * 2. Receive register_challenge
     * 3. Sign challenge
     * 4. Send register_proof
     * 5. Receive register_ack with WhisperID
     */
    private suspend fun performAuth(
        whisperId: String?,
        encPublicKey: ByteArray,
        signPublicKey: ByteArray,
        signPrivateKey: ByteArray
    ): RegisterAckPayload {
        val deviceId = secureStorage.getOrCreateDeviceId()

        // Get FCM token for push notifications
        val fcmToken = getFcmToken()

        // Step 1: Send register_begin
        val beginPayload = RegisterBeginPayload(
            deviceId = deviceId,
            whisperId = whisperId
        )
        wsClient.send(WsFrame(Constants.MsgType.REGISTER_BEGIN, payload = beginPayload))

        // Step 2: Wait for challenge
        val challenge = withTimeout(30_000) {
            suspendCancellableCoroutine { cont ->
                challengeContinuation = cont
            }
        }

        // Step 3: Sign challenge - server expects SHA256(challengeBytes) then sign
        val challengeBytes = Base64.decode(challenge.challenge, Base64.NO_WRAP)
        val signature = cryptoService.signChallenge(challengeBytes, signPrivateKey)

        // Step 4: Send register_proof with FCM token
        val proofPayload = RegisterProofPayload(
            challengeId = challenge.challengeId,
            deviceId = deviceId,
            whisperId = whisperId,
            encPublicKey = Base64.encodeToString(encPublicKey, Base64.NO_WRAP),
            signPublicKey = Base64.encodeToString(signPublicKey, Base64.NO_WRAP),
            signature = Base64.encodeToString(signature, Base64.NO_WRAP),
            pushToken = fcmToken
        )
        wsClient.send(WsFrame(Constants.MsgType.REGISTER_PROOF, payload = proofPayload))

        // Step 5: Wait for ack
        return withTimeout(30_000) {
            suspendCancellableCoroutine { cont ->
                ackContinuation = cont
            }
        }
    }

    /**
     * Get FCM token for push notifications.
     * Returns cached token or fetches fresh one from Firebase.
     */
    private suspend fun getFcmToken(): String? {
        return try {
            // Try cached token first
            secureStorage.fcmToken?.let { return it }

            // Fetch from Firebase
            val token = FirebaseMessaging.getInstance().token.await()
            secureStorage.fcmToken = token
            Logger.auth("FCM token obtained: ${token.take(20)}...")
            token
        } catch (e: Exception) {
            Logger.e("Failed to get FCM token", e)
            null
        }
    }

    /**
     * Sync FCM token with server after successful authentication.
     * This ensures the server has the latest token even after reinstall.
     */
    private fun syncFcmTokenAfterAuth() {
        scope.launch {
            try {
                // Force get fresh token from Firebase
                val token = FirebaseMessaging.getInstance().token.await()
                val sessionToken = secureStorage.sessionToken ?: return@launch

                // Update local cache
                secureStorage.fcmToken = token

                // Send to server
                val payload = UpdateTokensPayload(
                    sessionToken = sessionToken,
                    pushToken = token
                )
                wsClient.send(WsFrame(Constants.MsgType.UPDATE_TOKENS, payload = payload))
                Logger.auth("FCM token synced after auth: ${token.take(20)}...")
            } catch (e: Exception) {
                Logger.e("Failed to sync FCM token after auth", e)
            }
        }
    }

    private suspend fun waitForConnection() {
        var attempts = 0
        while (wsClient.connectionState.value != WsConnectionState.CONNECTED && attempts < 100) {
            delay(100)
            attempts++
        }
        if (wsClient.connectionState.value != WsConnectionState.CONNECTED) {
            throw AuthException("Failed to connect to server")
        }
    }

    private fun handleMessage(frame: WsFrame<JsonElement>) {
        when (frame.type) {
            Constants.MsgType.REGISTER_CHALLENGE -> {
                try {
                    val payload = gson.fromJson(frame.payload, RegisterChallengePayload::class.java)
                    Logger.auth("Received challenge: ${payload.challengeId}")
                    challengeContinuation?.resume(payload)
                    challengeContinuation = null
                } catch (e: Exception) {
                    Logger.e("Failed to parse challenge", e)
                    challengeContinuation?.resumeWithException(e)
                    challengeContinuation = null
                }
            }

            Constants.MsgType.REGISTER_ACK -> {
                try {
                    val payload = gson.fromJson(frame.payload, RegisterAckPayload::class.java)
                    Logger.auth("Received ack: ${payload.whisperId}, success=${payload.success}")
                    if (payload.success) {
                        // Sync FCM token after successful auth
                        syncFcmTokenAfterAuth()
                        ackContinuation?.resume(payload)
                    } else {
                        ackContinuation?.resumeWithException(AuthException("Registration rejected"))
                    }
                    ackContinuation = null
                } catch (e: Exception) {
                    Logger.e("Failed to parse ack", e)
                    ackContinuation?.resumeWithException(e)
                    ackContinuation = null
                }
            }

            Constants.MsgType.ERROR -> {
                try {
                    val payload = gson.fromJson(frame.payload, ErrorPayload::class.java)
                    Logger.e("Auth error: ${payload.code} - ${payload.message}")
                    val exception = AuthException("${payload.code}: ${payload.message}")
                    challengeContinuation?.resumeWithException(exception)
                    ackContinuation?.resumeWithException(exception)
                    challengeContinuation = null
                    ackContinuation = null
                } catch (e: Exception) {
                    Logger.e("Failed to parse error", e)
                }
            }

            Constants.MsgType.SESSION_REFRESH_ACK -> {
                try {
                    val payload = gson.fromJson(frame.payload, SessionRefreshAckPayload::class.java)
                    Logger.auth("Session refreshed: expires at ${payload.sessionExpiresAt}")
                    secureStorage.sessionToken = payload.sessionToken
                    secureStorage.sessionExpiresAt = payload.sessionExpiresAt
                } catch (e: Exception) {
                    Logger.e("Failed to parse session refresh ack", e)
                }
            }
        }
    }

    fun logout() {
        scope.launch {
            try {
                val token = secureStorage.sessionToken
                if (token != null) {
                    // Send logout to server
                    val payload = LogoutPayload(sessionToken = token)
                    wsClient.send(WsFrame(
                        type = Constants.MsgType.LOGOUT,
                        payload = payload
                    ))
                }
            } catch (e: Exception) {
                Logger.e("Logout send failed", e)
            }
        }

        wsClient.disconnect()
        secureStorage.clearAll()
        _authState.value = AuthState.Unauthenticated
    }
}

class AuthException(message: String) : Exception(message)
