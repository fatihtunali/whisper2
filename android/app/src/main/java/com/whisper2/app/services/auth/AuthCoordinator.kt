package com.whisper2.app.services.auth

import android.util.Log
import com.whisper2.app.crypto.CryptoService
import com.whisper2.app.network.ws.*
import com.whisper2.app.storage.key.SecurePrefs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Auth Coordinator
 *
 * Orchestrates the full registration/recovery flow:
 * 1. Initialize keys from mnemonic
 * 2. Connect to server (using shared WsClient)
 * 3. Execute challenge-response protocol
 * 4. Save session on success
 * 5. Keep connection open for messaging
 *
 * Single source of truth for auth flow in UI layer.
 */
@Singleton
class AuthCoordinator @Inject constructor(
    private val cryptoService: CryptoService,
    private val sessionManager: ISessionManager,
    private val securePrefs: SecurePrefs,
    private val wsClient: WsClient,
    private val messageRouter: WsMessageRouter
) {
    companion object {
        private const val TAG = "AuthCoordinator"
        private const val AUTH_TIMEOUT_MS = 30000L // 30 seconds
    }

    // =========================================================================
    // State
    // =========================================================================

    sealed class AuthFlowState {
        object Idle : AuthFlowState()
        object InitializingKeys : AuthFlowState()
        object Connecting : AuthFlowState()
        object WaitingForChallenge : AuthFlowState()
        object SigningChallenge : AuthFlowState()
        object WaitingForAck : AuthFlowState()
        data class Success(val whisperId: String) : AuthFlowState()
        data class Error(val message: String) : AuthFlowState()
    }

    private val _flowState = MutableStateFlow<AuthFlowState>(AuthFlowState.Idle)
    val flowState: StateFlow<AuthFlowState> = _flowState.asStateFlow()

    // Track auth completion callback
    private var authCompletionCallback: ((AuthResult) -> Unit)? = null
    private var currentAuthService: AuthService? = null

    // =========================================================================
    // Auth Flow
    // =========================================================================

    /**
     * Register or recover with mnemonic
     *
     * @param mnemonic 12-word BIP39 mnemonic
     * @param existingWhisperId Optional existing WhisperID for recovery
     * @return Result with success/error
     */
    suspend fun registerWithMnemonic(
        mnemonic: String,
        existingWhisperId: String? = null
    ): AuthResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting registration flow")
        _flowState.value = AuthFlowState.InitializingKeys

        try {
            // Step 1: Initialize keys from mnemonic
            cryptoService.initializeFromMnemonic(mnemonic)
            Log.d(TAG, "Keys initialized from mnemonic")

            // Get derived keys from CryptoService (they're now in memory after initializeFromMnemonic)
            Log.d(TAG, "Getting derived keys from CryptoService...")
            val signPublicKey = cryptoService.signingPublicKey
            if (signPublicKey == null) {
                Log.e(TAG, "signingPublicKey is NULL from CryptoService!")
                return@withContext AuthResult(false, "Failed to derive signing public key")
            }
            Log.d(TAG, "signPublicKey: ${signPublicKey.size} bytes")

            val encPublicKey = cryptoService.encryptionPublicKey
            if (encPublicKey == null) {
                Log.e(TAG, "encryptionPublicKey is NULL from CryptoService!")
                return@withContext AuthResult(false, "Failed to derive encryption public key")
            }
            Log.d(TAG, "encPublicKey: ${encPublicKey.size} bytes")

            Log.d(TAG, "Keys retrieved successfully")

            // Step 2: Create AuthService with CryptoService-backed signer
            Log.d(TAG, "Creating AuthService with CryptoService signer")
            val authService = AuthService(
                sessionManager = sessionManager,
                signPrivateKey = ByteArray(64), // Dummy - not used when signer is provided
                signPublicKey = signPublicKey,
                encPublicKey = encPublicKey,
                deviceIdProvider = { getOrCreateDeviceId() },
                pushTokenProvider = { securePrefs.fcmToken },
                signer = ChallengeSigner { hash -> cryptoService.sign(hash) }
            )
            currentAuthService = authService
            Log.d(TAG, "AuthService created, starting WebSocket flow")

            // Step 3: Execute challenge-response flow via WebSocket
            val result = executeAuthFlow(authService, existingWhisperId)
            Log.d(TAG, "executeAuthFlow returned: success=${result.success}, error=${result.error}")

            if (result.success && result.whisperId != null) {
                // Step 4: Set WhisperID in CryptoService
                cryptoService.setWhisperId(result.whisperId)
                _flowState.value = AuthFlowState.Success(result.whisperId)
                Log.i(TAG, "Registration successful: ${result.whisperId}")
                // NOTE: Connection stays open for messaging!
            } else {
                _flowState.value = AuthFlowState.Error(result.error ?: "Unknown error")
            }

            currentAuthService = null
            result
        } catch (e: Exception) {
            Log.e(TAG, "Registration failed", e)
            val errorMsg = e.message ?: "Registration failed"
            _flowState.value = AuthFlowState.Error(errorMsg)
            currentAuthService = null
            AuthResult(false, errorMsg)
        }
    }

    private suspend fun executeAuthFlow(
        authService: AuthService,
        existingWhisperId: String?
    ): AuthResult = suspendCancellableCoroutine { continuation ->
        Log.d(TAG, "executeAuthFlow started")
        val scope = CoroutineScope(Dispatchers.IO)

        // Track state for timeout
        var completed = false

        // Set up message handler callback
        authCompletionCallback = { result ->
            if (!completed) {
                completed = true
                // Unregister auth handler
                messageRouter.setAuthMessageHandler(null)
                // DO NOT disconnect - keep connection open for messaging!
                continuation.resumeWith(Result.success(result))
            }
        }

        // Register auth message handler with router
        messageRouter.setAuthMessageHandler { text -> handleAuthMessage(text) }

        // Check if already connected
        if (wsClient.isConnected()) {
            Log.d(TAG, "WsClient already connected, sending register_begin")
            _flowState.value = AuthFlowState.WaitingForChallenge
            val (json, _) = authService.createRegisterBegin(existingWhisperId)
            wsClient.send(json)
            Log.d(TAG, "Sent register_begin")
        } else {
            Log.d(TAG, "WsClient not connected, connecting...")
            _flowState.value = AuthFlowState.Connecting

            // Connect and wait for onOpen to send register_begin
            // The onOpen callback in WsClient will be called when connected
            // We need to send register_begin when connection opens
            scope.launch {
                // Wait for connection
                var attempts = 0
                wsClient.connect()
                while (!wsClient.isConnected() && attempts < 100) {
                    delay(100)
                    attempts++
                }

                if (wsClient.isConnected()) {
                    Log.d(TAG, "WsClient connected, sending register_begin")
                    _flowState.value = AuthFlowState.WaitingForChallenge
                    val (json, _) = authService.createRegisterBegin(existingWhisperId)
                    wsClient.send(json)
                    Log.d(TAG, "Sent register_begin")
                } else {
                    Log.e(TAG, "Failed to connect WsClient")
                    if (!completed) {
                        completed = true
                        continuation.resumeWith(Result.success(AuthResult(false, "Connection failed")))
                    }
                }
            }
        }

        // Timeout
        scope.launch {
            delay(AUTH_TIMEOUT_MS)
            if (!completed) {
                completed = true
                authCompletionCallback = null
                continuation.resumeWith(Result.success(
                    AuthResult(false, "Connection timeout")
                ))
            }
        }

        continuation.invokeOnCancellation {
            authCompletionCallback = null
            scope.cancel()
        }
    }

    /**
     * Handle incoming WebSocket message during auth flow
     * Called by the WsClient message router
     */
    fun handleAuthMessage(text: String) {
        val authService = currentAuthService ?: return
        val callback = authCompletionCallback ?: return

        try {
            val envelope = WsParser.parseRaw(text)
            Log.d(TAG, "Received message: type=${envelope.type}")

            when (envelope.type) {
                WsMessageTypes.REGISTER_CHALLENGE -> {
                    _flowState.value = AuthFlowState.SigningChallenge
                    val payload = WsParser.parsePayload<RegisterChallengePayload>(envelope.payload)
                    if (payload == null) {
                        callback(AuthResult(false, "Invalid challenge payload"))
                        return
                    }
                    val (proofJson, _) = authService.handleChallenge(payload)

                    _flowState.value = AuthFlowState.WaitingForAck
                    wsClient.send(proofJson)
                    Log.d(TAG, "Sent register_proof")
                }

                WsMessageTypes.REGISTER_ACK -> {
                    val payload = WsParser.parsePayload<RegisterAckPayload>(envelope.payload)
                    if (payload == null) {
                        callback(AuthResult(false, "Invalid ack payload"))
                        return
                    }
                    val success = authService.handleAck(payload)

                    if (success && payload.whisperId != null) {
                        callback(AuthResult(true, null, payload.whisperId))
                    } else {
                        callback(AuthResult(false, "Registration rejected by server"))
                    }
                }

                WsMessageTypes.ERROR -> {
                    val payload = WsParser.parsePayload<ErrorPayload>(envelope.payload)
                    if (payload != null) {
                        val exception = authService.handleError(payload)
                        callback(AuthResult(false, exception.message))
                    } else {
                        callback(AuthResult(false, "Unknown server error"))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message", e)
            callback(AuthResult(false, "Protocol error: ${e.message}"))
        }
    }

    /**
     * Check if currently in auth flow
     */
    fun isInAuthFlow(): Boolean = currentAuthService != null

    // =========================================================================
    // Device ID
    // =========================================================================

    private fun getOrCreateDeviceId(): String {
        var deviceId = securePrefs.deviceId
        if (deviceId == null) {
            deviceId = java.util.UUID.randomUUID().toString()
            securePrefs.deviceId = deviceId
            Log.d(TAG, "Generated new device ID: $deviceId")
        }
        return deviceId
    }

    // =========================================================================
    // Reset
    // =========================================================================

    fun reset() {
        _flowState.value = AuthFlowState.Idle
        authCompletionCallback = null
        currentAuthService = null
    }
}

/**
 * Result of auth flow
 */
data class AuthResult(
    val success: Boolean,
    val error: String? = null,
    val whisperId: String? = null
)
