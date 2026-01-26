package com.whisper2.app.auth

import com.whisper2.app.core.AuthException
import com.whisper2.app.core.utils.Base64Strict
import com.whisper2.app.crypto.BIP39
import com.whisper2.app.crypto.KeyDerivation
import com.whisper2.app.crypto.Signatures
import com.whisper2.app.network.ws.*
import com.whisper2.app.services.auth.AuthService
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest
import java.util.*

/**
 * Gate 2-5: WS Auth Flow Tests
 *
 * Uses in-memory fake server responses to test the auth flow
 */
class WsAuthFlowTest {

    // Test keys from frozen vectors
    companion object {
        const val TEST_MNEMONIC = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        const val EXPECTED_SIGN_SEED_HEX = "457f5c29bc4ab25ea84b9d076fee560db80b9994725106594400e28672f3e5be"
        const val EXPECTED_ENC_SEED_HEX = "08851144b1bdf8b99c563bd408f4a613943fef2d9120397573932bd9833e0149"

        // Expected public keys (base64)
        const val EXPECTED_ENC_PUBLIC_KEY_B64 = "GcbQ+YaCf46Gpe1KIz8u0clzVVNtDSbErjsrkINpwFA="
        const val EXPECTED_SIGN_PUBLIC_KEY_B64 = "vZMMu/hWu3bywlxkBiopRMxgZbpUua9yQtK/veXXyVs="
    }

    private lateinit var testSessionManager: TestSessionManager
    private lateinit var authService: AuthService
    private lateinit var signPrivateKey: ByteArray
    private lateinit var signPublicKey: ByteArray
    private lateinit var encPublicKey: ByteArray

    @Before
    fun setup() {
        // Derive test keys
        val keys = KeyDerivation.deriveAll(TEST_MNEMONIC, "")

        // For JVM tests, we can't use LazySodium to derive keypairs
        // So we'll use the seeds directly and simulate the public keys
        signPrivateKey = keys.signSeed // 32-byte seed
        signPublicKey = hexToBytes("bd930cbbf856bb76f2c25c64062a2944cc6065ba54b9af7242d2bfbde5d7c95b")
        encPublicKey = hexToBytes("19c6d0f986827f8e86a5ed4a233f2ed1c97355536d0d26c4ae3b2b908369c050")

        testSessionManager = TestSessionManager()

        authService = AuthService(
            sessionManager = testSessionManager,
            signPrivateKey = signPrivateKey,
            signPublicKey = signPublicKey,
            encPublicKey = encPublicKey,
            deviceIdProvider = { "test-device-id-123" },
            pushTokenProvider = { null },
            signer = { hash -> ByteArray(64) { (it * 3).toByte() } } // Fake 64-byte Ed25519 signature for JVM tests
        )
    }

    // ==========================================================================
    // Gate 2: Happy Path Register
    // ==========================================================================

    @Test
    fun `gate2 createRegisterBegin returns valid JSON with requestId`() {
        val (json, requestId) = authService.createRegisterBegin()

        assertNotNull(requestId)
        assertTrue(json.contains("\"type\":\"register_begin\""))
        assertTrue(json.contains("\"requestId\":\"$requestId\""))
        assertTrue(json.contains("\"deviceId\":\"test-device-id-123\""))
        assertTrue(json.contains("\"platform\":\"android\""))
        assertTrue(json.contains("\"protocolVersion\":1"))
        assertTrue(json.contains("\"cryptoVersion\":1"))
    }

    @Test
    fun `gate2 handleChallenge returns valid proof with signature`() {
        val challengeBytes = ByteArray(32) { 7 } // deterministic 32 bytes
        val challengeB64 = Base64Strict.encode(challengeBytes)

        val challengePayload = RegisterChallengePayload(
            challengeId = "test-challenge-id",
            challenge = challengeB64,
            expiresAt = System.currentTimeMillis() + 60000 // expires in 60 seconds
        )

        val (proofJson, requestId) = authService.handleChallenge(challengePayload)

        assertNotNull(requestId)
        assertTrue(proofJson.contains("\"type\":\"register_proof\""))
        assertTrue(proofJson.contains("\"challengeId\":\"test-challenge-id\""))
        assertTrue(proofJson.contains("\"signature\":"))

        // Verify signature is base64 encoded and 64 bytes when decoded
        val envelope = WsParser.parseRaw(proofJson)
        val payload = WsParser.parsePayload<RegisterProofPayload>(envelope.payload)
        assertNotNull(payload)

        val signatureB64 = payload!!.signature
        val signatureBytes = Base64Strict.decode(signatureB64)
        assertEquals("Ed25519 signature must be 64 bytes", 64, signatureBytes.size)

        // Verify signature B64 length (64 bytes -> 88 chars with padding)
        assertEquals("Signature base64 length", 88, signatureB64.length)
    }

    @Test
    fun `gate2 handleAck stores session correctly`() {
        val ackPayload = RegisterAckPayload(
            success = true,
            whisperId = "WSP-ABCD-EFGH-IJKL",
            sessionToken = "sess_opaque_token_here",
            sessionExpiresAt = 1700086400000L,
            serverTime = 1700000000000L
        )

        val result = authService.handleAck(ackPayload)

        assertTrue(result)
        assertEquals("WSP-ABCD-EFGH-IJKL", testSessionManager.whisperId)
        assertEquals("sess_opaque_token_here", testSessionManager.sessionToken)
        assertEquals(1700086400000L, testSessionManager.sessionExpiresAt)
        assertEquals(1700000000000L, testSessionManager.serverTime)
    }

    @Test
    fun `gate2 full flow stores all session data`() {
        // Step 1: Create begin
        val (beginJson, beginRequestId) = authService.createRegisterBegin()
        assertNotNull(beginRequestId)

        // Step 2: Handle challenge
        val challengeBytes = ByteArray(32) { (it * 3).toByte() }
        val challengeB64 = Base64Strict.encode(challengeBytes)
        val challengePayload = RegisterChallengePayload(
            challengeId = "full-flow-challenge-id",
            challenge = challengeB64,
            expiresAt = System.currentTimeMillis() + 60000
        )

        val (proofJson, proofRequestId) = authService.handleChallenge(challengePayload)
        assertNotNull(proofRequestId)

        // Step 3: Handle ack
        val ackPayload = RegisterAckPayload(
            success = true,
            whisperId = "WSP-TEST-FLOW-DONE",
            sessionToken = "sess_full_flow_token",
            sessionExpiresAt = 1800000000000L,
            serverTime = 1700000000000L
        )

        val result = authService.handleAck(ackPayload)

        assertTrue("Ack should succeed", result)
        assertEquals("WSP-TEST-FLOW-DONE", testSessionManager.whisperId)
        assertEquals("sess_full_flow_token", testSessionManager.sessionToken)
        assertEquals(1800000000000L, testSessionManager.sessionExpiresAt)
        assertEquals(1700000000000L, testSessionManager.serverTime)
    }

    // ==========================================================================
    // Gate 3: Invalid Signature / Auth Failed
    // ==========================================================================

    @Test
    fun `gate3 handleError returns AuthFailed for AUTH_FAILED code`() {
        val errorPayload = ErrorPayload(
            code = "AUTH_FAILED",
            message = "bad_signature",
            requestId = "test-request-id"
        )

        val exception = authService.handleError(errorPayload)

        assertTrue(exception is AuthException.AuthFailed)
        assertEquals("bad_signature", exception.message)
    }

    @Test
    fun `gate3 handleAck failure does not store session`() {
        val ackPayload = RegisterAckPayload(
            success = false,
            whisperId = null,
            sessionToken = null,
            sessionExpiresAt = null,
            serverTime = null
        )

        val result = authService.handleAck(ackPayload)

        assertFalse("Ack should fail", result)
        assertNull("WhisperId should be null", testSessionManager.whisperId)
        assertNull("SessionToken should be null", testSessionManager.sessionToken)
    }

    @Test
    fun `gate3 handleError returns correct exception types`() {
        // AUTH_FAILED
        val authFailed = authService.handleError(ErrorPayload("AUTH_FAILED", "test"))
        assertTrue(authFailed is AuthException.AuthFailed || authFailed is AuthException.Kicked)

        // INVALID_PAYLOAD
        val invalidPayload = authService.handleError(ErrorPayload("INVALID_PAYLOAD", "test"))
        assertTrue(invalidPayload is AuthException.InvalidPayload)

        // RATE_LIMITED
        val rateLimited = authService.handleError(ErrorPayload("RATE_LIMITED", "test"))
        assertTrue(rateLimited is AuthException.RateLimited)
    }

    // ==========================================================================
    // Gate 4: Replay Protection
    // ==========================================================================

    @Test
    fun `gate4 replay attempt throws ReplayAttempt exception`() {
        val challengeBytes = ByteArray(32) { 42 }
        val challengeB64 = Base64Strict.encode(challengeBytes)
        val challengePayload = RegisterChallengePayload(
            challengeId = "replay-test-challenge-id",
            challenge = challengeB64,
            expiresAt = System.currentTimeMillis() + 60000
        )

        // First call should succeed
        authService.handleChallenge(challengePayload)

        // Second call with same challengeId should throw ReplayAttempt
        try {
            authService.handleChallenge(challengePayload)
            fail("Expected ReplayAttempt exception")
        } catch (e: AuthException.ReplayAttempt) {
            assertEquals("replay-test-challenge-id", e.challengeId)
        }
    }

    @Test
    fun `gate4 different challenges are allowed`() {
        val challenge1 = RegisterChallengePayload(
            challengeId = "challenge-1",
            challenge = Base64Strict.encode(ByteArray(32) { 1 }),
            expiresAt = System.currentTimeMillis() + 60000
        )

        val challenge2 = RegisterChallengePayload(
            challengeId = "challenge-2",
            challenge = Base64Strict.encode(ByteArray(32) { 2 }),
            expiresAt = System.currentTimeMillis() + 60000
        )

        // Both should succeed
        authService.handleChallenge(challenge1)
        authService.handleChallenge(challenge2)

        assertTrue(authService.isChallengeUsed("challenge-1"))
        assertTrue(authService.isChallengeUsed("challenge-2"))
    }

    @Test
    fun `gate4 expired challenge throws ChallengeExpired`() {
        val challengePayload = RegisterChallengePayload(
            challengeId = "expired-challenge",
            challenge = Base64Strict.encode(ByteArray(32) { 0 }),
            expiresAt = System.currentTimeMillis() - 1000 // already expired
        )

        try {
            authService.handleChallenge(challengePayload)
            fail("Expected ChallengeExpired exception")
        } catch (e: AuthException.ChallengeExpired) {
            // Expected
        }
    }

    @Test
    fun `gate4 invalid challenge length throws InvalidChallenge`() {
        // 16 bytes instead of 32
        val shortChallenge = RegisterChallengePayload(
            challengeId = "short-challenge",
            challenge = Base64Strict.encode(ByteArray(16) { 0 }),
            expiresAt = System.currentTimeMillis() + 60000
        )

        try {
            authService.handleChallenge(shortChallenge)
            fail("Expected InvalidChallenge exception")
        } catch (e: AuthException.InvalidChallenge) {
            assertTrue(e.message!!.contains("32 bytes"))
        }
    }

    // ==========================================================================
    // Gate 5: Kick / Forced Logout
    // ==========================================================================

    @Test
    fun `gate5 kick error triggers Kicked exception`() {
        val errorPayload = ErrorPayload(
            code = "AUTH_FAILED",
            message = "Session terminated: new_session"
        )

        val exception = authService.handleError(errorPayload)

        assertTrue("Should be Kicked exception", exception is AuthException.Kicked)
        assertEquals("Session terminated: new_session", (exception as AuthException.Kicked).reason)
    }

    @Test
    fun `gate5 handleClose with 4001 code triggers forceLogout`() {
        // Setup: add some session data
        testSessionManager.saveFullSession(
            whisperId = "WSP-TO-BE-KICKED",
            sessionToken = "token_to_clear",
            sessionExpiresAt = 1800000000000L,
            serverTime = 1700000000000L
        )

        // Verify session exists
        assertNotNull(testSessionManager.sessionToken)

        // Simulate close with kick code
        authService.handleClose(4001, "new_session")

        // Verify forceLogout was called
        assertTrue("forceLogout should have been called", testSessionManager.forceLogoutCalled)
        assertTrue("forceLogout reason should contain 'kicked'",
            testSessionManager.forceLogoutReason?.contains("kicked") == true)
    }

    @Test
    fun `gate5 handleClose with normal code does not trigger forceLogout`() {
        // Simulate normal close (1000 = normal closure)
        authService.handleClose(1000, "normal closure")

        assertFalse("forceLogout should not be called", testSessionManager.forceLogoutCalled)
    }

    // ==========================================================================
    // Helper functions
    // ==========================================================================

    private fun hexToBytes(hex: String): ByteArray {
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    // ==========================================================================
    // Test doubles
    // ==========================================================================

    /**
     * In-memory test session manager that doesn't depend on Android
     */
    class TestSessionManager : com.whisper2.app.services.auth.ISessionManager {
        override var whisperId: String? = null
        override var sessionToken: String? = null
        override var sessionExpiresAt: Long? = null
        override var serverTime: Long? = null
        override var deviceId: String? = null
        override val isLoggedIn: Boolean get() = sessionToken != null

        var forceLogoutCalled = false
        var forceLogoutReason: String? = null

        override fun saveSession(token: String, deviceId: String) {
            this.sessionToken = token
            this.deviceId = deviceId
        }

        override fun saveFullSession(
            whisperId: String,
            sessionToken: String,
            sessionExpiresAt: Long,
            serverTime: Long
        ) {
            this.whisperId = whisperId
            this.sessionToken = sessionToken
            this.sessionExpiresAt = sessionExpiresAt
            this.serverTime = serverTime
        }

        override fun updateToken(newToken: String) {
            this.sessionToken = newToken
        }

        override fun forceLogout(reason: String) {
            forceLogoutCalled = true
            forceLogoutReason = reason
            whisperId = null
            sessionToken = null
            sessionExpiresAt = null
            serverTime = null
        }

        override fun softLogout() {
            sessionToken = null
            deviceId = null
        }
    }
}
