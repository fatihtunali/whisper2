package com.whisper2.app.http

import com.whisper2.app.network.api.*
import com.whisper2.app.services.contacts.KeyLookupService
import com.whisper2.app.services.contacts.InMemoryKeyCache
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Gate 1: HTTP Contract + Auth Header
 *
 * Tests:
 * - Authorization: Bearer <sessionToken> header is present
 * - 401 (AUTH_FAILED) triggers auth failure handler
 */
class HttpContractTest {

    private lateinit var mockApi: MockWhisperApi
    private var sessionToken: String? = "test_session_token"
    private var authFailureReason: String? = null

    @Before
    fun setup() {
        authFailureReason = null
        sessionToken = "test_session_token"

        mockApi = MockWhisperApi(
            sessionTokenProvider = { sessionToken },
            authFailureHandler = { reason -> authFailureReason = reason }
        )
    }

    // ==========================================================================
    // Gate 1: Auth header present
    // ==========================================================================

    @Test
    fun `gate1 getUserKeys includes auth header`() = runBlocking {
        mockApi.enqueueUserKeysResponse(
            ApiResult.Success(UserKeysResponse(
                whisperId = "WSP-TEST-TEST-TEST",
                encPublicKey = b64_32bytes(),
                signPublicKey = b64_32bytes(),
                status = "active"
            ))
        )

        mockApi.getUserKeys("WSP-TEST-TEST-TEST")

        assertEquals("Bearer test_session_token", mockApi.lastAuthHeader)
    }

    @Test
    fun `gate1 putContactsBackup includes auth header`() = runBlocking {
        mockApi.enqueuePutBackupResponse(
            ApiResult.Success(ContactsBackupPutResponse(
                success = true,
                created = true,
                sizeBytes = 100,
                updatedAt = 1700000000000L
            ))
        )

        mockApi.putContactsBackup(ContactsBackupPutRequest(
            nonce = b64_24bytes(),
            ciphertext = "AAAA"
        ))

        assertEquals("Bearer test_session_token", mockApi.lastAuthHeader)
    }

    @Test
    fun `gate1 getContactsBackup includes auth header`() = runBlocking {
        mockApi.enqueueGetBackupResponse(
            ApiResult.Success(ContactsBackupGetResponse(
                nonce = b64_24bytes(),
                ciphertext = "AAAA",
                sizeBytes = 100,
                updatedAt = 1700000000000L
            ))
        )

        mockApi.getContactsBackup()

        assertEquals("Bearer test_session_token", mockApi.lastAuthHeader)
    }

    // ==========================================================================
    // Gate 1: No session token
    // ==========================================================================

    @Test
    fun `gate1 no session token returns AUTH_FAILED`() = runBlocking {
        sessionToken = null

        val result = mockApi.getUserKeys("WSP-TEST-TEST-TEST")

        assertTrue(result.isError)
        assertEquals(ApiErrorResponse.AUTH_FAILED, result.errorOrNull()?.code)
        assertEquals(401, result.errorOrNull()?.httpCode)
    }

    @Test
    fun `gate1 no session token triggers auth failure handler`() = runBlocking {
        sessionToken = null

        mockApi.getUserKeys("WSP-TEST-TEST-TEST")

        assertNotNull("Auth failure handler should be called", authFailureReason)
    }

    // ==========================================================================
    // Gate 1: 401 response triggers auth failure
    // ==========================================================================

    @Test
    fun `gate1 401 response triggers auth failure handler`() = runBlocking {
        mockApi.enqueueUserKeysResponse(
            ApiResult.Error(ApiErrorResponse.AUTH_FAILED, "Session expired", 401)
        )

        mockApi.getUserKeys("WSP-TEST-TEST-TEST")

        assertNotNull("Auth failure handler should be called on 401", authFailureReason)
    }

    @Test
    fun `gate1 401 on putBackup triggers auth failure handler`() = runBlocking {
        mockApi.enqueuePutBackupResponse(
            ApiResult.Error(ApiErrorResponse.AUTH_FAILED, "Session expired", 401)
        )

        mockApi.putContactsBackup(ContactsBackupPutRequest(b64_24bytes(), "AAAA"))

        assertNotNull("Auth failure handler should be called on 401", authFailureReason)
    }

    @Test
    fun `gate1 401 on getBackup triggers auth failure handler`() = runBlocking {
        mockApi.enqueueGetBackupResponse(
            ApiResult.Error(ApiErrorResponse.AUTH_FAILED, "Session expired", 401)
        )

        mockApi.getContactsBackup()

        assertNotNull("Auth failure handler should be called on 401", authFailureReason)
    }

    @Test
    fun `gate1 non-401 error does not trigger auth failure`() = runBlocking {
        mockApi.enqueueUserKeysResponse(
            ApiResult.Error(ApiErrorResponse.NOT_FOUND, "User not found", 404)
        )

        mockApi.getUserKeys("WSP-TEST-TEST-TEST")

        assertNull("Auth failure handler should NOT be called on 404", authFailureReason)
    }

    // ==========================================================================
    // Gate 1: Integration with KeyLookupService
    // ==========================================================================

    @Test
    fun `gate1 KeyLookupService uses auth header`() = runBlocking {
        mockApi.enqueueUserKeysResponse(
            ApiResult.Success(UserKeysResponse(
                whisperId = "WSP-TEST-TEST-TEST",
                encPublicKey = b64_32bytes(),
                signPublicKey = b64_32bytes(),
                status = "active"
            ))
        )

        val service = KeyLookupService(mockApi, InMemoryKeyCache())
        service.getKeys("WSP-TEST-TEST-TEST")

        assertEquals("Bearer test_session_token", mockApi.lastAuthHeader)
    }

    // ==========================================================================
    // Helper
    // ==========================================================================

    private fun b64_32bytes(): String {
        // 32 bytes = 44 chars in base64 (with padding)
        return "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    }

    private fun b64_24bytes(): String {
        // 24 bytes = 32 chars in base64 (with padding)
        return "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    }
}
