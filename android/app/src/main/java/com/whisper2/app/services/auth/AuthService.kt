package com.whisper2.app.services.auth

import android.util.Log
import com.whisper2.app.core.AuthException
import com.whisper2.app.core.Constants
import com.whisper2.app.core.utils.Base64Strict
import com.whisper2.app.crypto.Signatures
import com.whisper2.app.network.ws.*
import java.security.MessageDigest
import java.util.*

/**
 * Authentication Service
 * Handles register flow: register_begin → register_challenge → register_proof → register_ack
 *
 * Challenge signature: Ed25519(SHA256(challengeBytes))
 */
/**
 * Signer interface for dependency injection and testing
 */
fun interface ChallengeSigner {
    fun sign(hash: ByteArray): ByteArray
}

class AuthService(
    private val sessionManager: ISessionManager,
    private val signPrivateKey: ByteArray,
    private val signPublicKey: ByteArray,
    private val encPublicKey: ByteArray,
    private val deviceIdProvider: () -> String,
    private val pushTokenProvider: () -> String? = { null },
    private val signer: ChallengeSigner? = null // Optional: for testing without native libs
) {
    companion object {
        private const val TAG = "AuthService"
        private const val CHALLENGE_BYTES_LENGTH = 32
        private const val REPLAY_CACHE_SIZE = 64

        // WebSocket close codes
        const val WS_CLOSE_KICKED = 4001
    }

    // Replay guard: track used challenge IDs
    private val usedChallengeIds = LinkedHashSet<String>()

    // Current registration state
    private var pendingChallengeId: String? = null

    // MARK: - Register Flow

    /**
     * Step 1: Create register_begin message
     */
    fun createRegisterBegin(
        whisperId: String? = null // for recovery
    ): Pair<String, String> { // returns (json, requestId)
        val requestId = UUID.randomUUID().toString()
        val deviceId = deviceIdProvider()

        val payload = RegisterBeginPayload(
            protocolVersion = Constants.PROTOCOL_VERSION,
            cryptoVersion = Constants.CRYPTO_VERSION,
            deviceId = deviceId,
            platform = "android",
            whisperId = whisperId
        )

        val json = WsParser.createEnvelope(
            type = WsMessageTypes.REGISTER_BEGIN,
            payload = payload,
            requestId = requestId
        )

        Log.d(TAG, "Created register_begin: requestId=$requestId, deviceId=$deviceId")
        return Pair(json, requestId)
    }

    /**
     * Step 2: Handle register_challenge from server
     * Returns register_proof message to send
     */
    fun handleChallenge(challengePayload: RegisterChallengePayload): Pair<String, String> {
        val challengeId = challengePayload.challengeId
        val challengeB64 = challengePayload.challenge
        val expiresAt = challengePayload.expiresAt

        Log.d(TAG, "Received challenge: id=$challengeId, expiresAt=$expiresAt")

        // Check expiry
        if (System.currentTimeMillis() > expiresAt) {
            throw AuthException.ChallengeExpired()
        }

        // Replay guard: check if challenge already used
        if (usedChallengeIds.contains(challengeId)) {
            throw AuthException.ReplayAttempt(challengeId)
        }

        // Decode challenge bytes (must be 32 bytes)
        val challengeBytes = try {
            Base64Strict.decode(challengeB64)
        } catch (e: Exception) {
            throw AuthException.InvalidChallenge("Invalid base64: ${e.message}")
        }

        if (challengeBytes.size != CHALLENGE_BYTES_LENGTH) {
            throw AuthException.InvalidChallenge(
                "Challenge must be $CHALLENGE_BYTES_LENGTH bytes, got ${challengeBytes.size}"
            )
        }

        // Add to replay guard BEFORE signing (prevent double-send)
        addToReplayCache(challengeId)
        pendingChallengeId = challengeId

        // Sign challenge: Ed25519(SHA256(challengeBytes))
        val signature = signChallenge(challengeBytes)
        val signatureB64 = Base64Strict.encode(signature)

        Log.d(TAG, "Signed challenge: signatureLength=${signature.size}")

        // Create register_proof
        val requestId = UUID.randomUUID().toString()
        val payload = RegisterProofPayload(
            protocolVersion = Constants.PROTOCOL_VERSION,
            cryptoVersion = Constants.CRYPTO_VERSION,
            challengeId = challengeId,
            deviceId = deviceIdProvider(),
            platform = "android",
            encPublicKey = Base64Strict.encode(encPublicKey),
            signPublicKey = Base64Strict.encode(signPublicKey),
            signature = signatureB64,
            pushToken = pushTokenProvider()
        )

        val json = WsParser.createEnvelope(
            type = WsMessageTypes.REGISTER_PROOF,
            payload = payload,
            requestId = requestId
        )

        return Pair(json, requestId)
    }

    /**
     * Step 3: Handle register_ack from server
     * Saves session to SessionManager
     */
    fun handleAck(ackPayload: RegisterAckPayload): Boolean {
        if (!ackPayload.success) {
            Log.w(TAG, "Register ack success=false")
            pendingChallengeId = null
            return false
        }

        val whisperId = ackPayload.whisperId
            ?: throw AuthException.InvalidResponse("Missing whisperId in ack")
        val sessionToken = ackPayload.sessionToken
            ?: throw AuthException.InvalidResponse("Missing sessionToken in ack")
        val sessionExpiresAt = ackPayload.sessionExpiresAt
            ?: throw AuthException.InvalidResponse("Missing sessionExpiresAt in ack")
        val serverTime = ackPayload.serverTime
            ?: throw AuthException.InvalidResponse("Missing serverTime in ack")

        Log.i(TAG, "Register successful: whisperId=$whisperId")

        // Save session
        sessionManager.saveFullSession(
            whisperId = whisperId,
            sessionToken = sessionToken,
            sessionExpiresAt = sessionExpiresAt,
            serverTime = serverTime
        )

        pendingChallengeId = null
        return true
    }

    /**
     * Handle error from server
     */
    fun handleError(errorPayload: ErrorPayload): AuthException {
        Log.w(TAG, "Auth error: code=${errorPayload.code}, message=${errorPayload.message}")

        pendingChallengeId = null

        return when (errorPayload.code) {
            WsErrorCodes.AUTH_FAILED -> {
                // Check if it's a kick message
                if (errorPayload.message.contains("new_session") ||
                    errorPayload.message.contains("terminated")) {
                    AuthException.Kicked(errorPayload.message)
                } else {
                    AuthException.AuthFailed(errorPayload.message)
                }
            }
            WsErrorCodes.INVALID_PAYLOAD -> AuthException.InvalidPayload(errorPayload.message)
            WsErrorCodes.RATE_LIMITED -> AuthException.RateLimited(errorPayload.message)
            else -> AuthException.AuthFailed(errorPayload.message)
        }
    }

    /**
     * Handle WebSocket close with kick code
     */
    fun handleClose(code: Int, reason: String?) {
        Log.d(TAG, "WS closed: code=$code, reason=$reason")

        if (code == WS_CLOSE_KICKED) {
            val kickReason = reason ?: "unknown"
            Log.w(TAG, "Kicked from server: $kickReason")
            sessionManager.forceLogout("kicked: $kickReason")
        }
    }

    // MARK: - Challenge Signing

    /**
     * Sign challenge bytes: Ed25519(SHA256(challengeBytes))
     */
    private fun signChallenge(challengeBytes: ByteArray): ByteArray {
        // SHA256 hash of challenge
        val hash = MessageDigest.getInstance("SHA-256").digest(challengeBytes)

        // Use injected signer if available (for testing), otherwise use Signatures
        return signer?.sign(hash) ?: Signatures.sign(hash, signPrivateKey)
    }

    // MARK: - Replay Guard

    private fun addToReplayCache(challengeId: String) {
        synchronized(usedChallengeIds) {
            // LRU: if full, remove oldest
            if (usedChallengeIds.size >= REPLAY_CACHE_SIZE) {
                val oldest = usedChallengeIds.first()
                usedChallengeIds.remove(oldest)
            }
            usedChallengeIds.add(challengeId)
        }
    }

    /**
     * Check if challenge was already used (for testing)
     */
    fun isChallengeUsed(challengeId: String): Boolean {
        return usedChallengeIds.contains(challengeId)
    }

    /**
     * Clear replay cache (for testing)
     */
    fun clearReplayCache() {
        usedChallengeIds.clear()
    }
}
