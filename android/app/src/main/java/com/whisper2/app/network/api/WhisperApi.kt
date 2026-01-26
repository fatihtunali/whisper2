package com.whisper2.app.network.api

/**
 * Whisper2 HTTP API Interface
 * Step 7: Key Lookup + Contacts Backup
 *
 * All endpoints require authentication:
 * Authorization: Bearer <sessionToken>
 */

/**
 * API result wrapper
 */
sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val code: String, val message: String, val httpCode: Int) : ApiResult<Nothing>()

    val isSuccess: Boolean get() = this is Success
    val isError: Boolean get() = this is Error

    fun getOrNull(): T? = (this as? Success)?.data
    fun errorOrNull(): Error? = this as? Error
}

/**
 * Session token provider for auth header
 */
fun interface SessionTokenProvider {
    fun getSessionToken(): String?
}

/**
 * Auth failure handler (for 401 responses)
 */
fun interface ApiAuthFailureHandler {
    fun onAuthFailure(reason: String)
}

/**
 * HTTP API client interface
 * Implementations can use OkHttp, Retrofit, or mocks for testing
 */
interface WhisperApi {

    /**
     * GET /users/{whisperId}/keys
     *
     * Lookup a user's public keys.
     *
     * @param whisperId Target user's WhisperID
     * @return User's public keys or error
     */
    suspend fun getUserKeys(whisperId: String): ApiResult<UserKeysResponse>

    /**
     * PUT /backup/contacts
     *
     * Upload encrypted contacts backup.
     *
     * @param request Encrypted backup data
     * @return Success response or error
     */
    suspend fun putContactsBackup(request: ContactsBackupPutRequest): ApiResult<ContactsBackupPutResponse>

    /**
     * GET /backup/contacts
     *
     * Download encrypted contacts backup.
     *
     * @return Encrypted backup data or error
     */
    suspend fun getContactsBackup(): ApiResult<ContactsBackupGetResponse>

    /**
     * DELETE /backup/contacts
     *
     * Delete contacts backup.
     *
     * @return Success or error
     */
    suspend fun deleteContactsBackup(): ApiResult<Unit>
}

/**
 * Mock/Test implementation of WhisperApi
 * For unit testing without network
 */
class MockWhisperApi(
    private val sessionTokenProvider: SessionTokenProvider,
    private val authFailureHandler: ApiAuthFailureHandler? = null
) : WhisperApi {

    // Queued responses for testing
    private val userKeysResponses = mutableListOf<ApiResult<UserKeysResponse>>()
    private val putBackupResponses = mutableListOf<ApiResult<ContactsBackupPutResponse>>()
    private val getBackupResponses = mutableListOf<ApiResult<ContactsBackupGetResponse>>()
    private val deleteBackupResponses = mutableListOf<ApiResult<Unit>>()

    // Request tracking
    var getUserKeysCallCount = 0
        private set
    var putBackupCallCount = 0
        private set
    var getBackupCallCount = 0
        private set
    var deleteBackupCallCount = 0
        private set

    // Last requests for verification
    var lastGetUserKeysWhisperId: String? = null
        private set
    var lastPutBackupRequest: ContactsBackupPutRequest? = null
        private set
    var lastAuthHeader: String? = null
        private set

    fun enqueueUserKeysResponse(response: ApiResult<UserKeysResponse>) {
        userKeysResponses.add(response)
    }

    fun enqueuePutBackupResponse(response: ApiResult<ContactsBackupPutResponse>) {
        putBackupResponses.add(response)
    }

    fun enqueueGetBackupResponse(response: ApiResult<ContactsBackupGetResponse>) {
        getBackupResponses.add(response)
    }

    fun enqueueDeleteBackupResponse(response: ApiResult<Unit>) {
        deleteBackupResponses.add(response)
    }

    private fun checkAuth(): ApiResult.Error? {
        val token = sessionTokenProvider.getSessionToken()
        if (token == null) {
            authFailureHandler?.onAuthFailure("No session token")
            return ApiResult.Error(ApiErrorResponse.AUTH_FAILED, "No session token", 401)
        }
        lastAuthHeader = "Bearer $token"
        return null
    }

    private fun handleAuthError(result: ApiResult<*>) {
        if (result is ApiResult.Error && result.httpCode == 401) {
            authFailureHandler?.onAuthFailure(result.message)
        }
    }

    override suspend fun getUserKeys(whisperId: String): ApiResult<UserKeysResponse> {
        getUserKeysCallCount++
        lastGetUserKeysWhisperId = whisperId

        checkAuth()?.let { return it }

        val response = userKeysResponses.removeFirstOrNull()
            ?: ApiResult.Error(ApiErrorResponse.NOT_FOUND, "No queued response", 404)

        handleAuthError(response)
        return response
    }

    override suspend fun putContactsBackup(request: ContactsBackupPutRequest): ApiResult<ContactsBackupPutResponse> {
        putBackupCallCount++
        lastPutBackupRequest = request

        checkAuth()?.let { return it }

        val response = putBackupResponses.removeFirstOrNull()
            ?: ApiResult.Error(ApiErrorResponse.INTERNAL_ERROR, "No queued response", 500)

        handleAuthError(response)
        return response
    }

    override suspend fun getContactsBackup(): ApiResult<ContactsBackupGetResponse> {
        getBackupCallCount++

        checkAuth()?.let { return it }

        val response = getBackupResponses.removeFirstOrNull()
            ?: ApiResult.Error(ApiErrorResponse.NOT_FOUND, "No queued response", 404)

        handleAuthError(response)
        return response
    }

    override suspend fun deleteContactsBackup(): ApiResult<Unit> {
        deleteBackupCallCount++

        checkAuth()?.let { return it }

        val response = deleteBackupResponses.removeFirstOrNull()
            ?: ApiResult.Error(ApiErrorResponse.NOT_FOUND, "No queued response", 404)

        handleAuthError(response)
        return response
    }

    fun reset() {
        userKeysResponses.clear()
        putBackupResponses.clear()
        getBackupResponses.clear()
        deleteBackupResponses.clear()
        getUserKeysCallCount = 0
        putBackupCallCount = 0
        getBackupCallCount = 0
        deleteBackupCallCount = 0
        lastGetUserKeysWhisperId = null
        lastPutBackupRequest = null
        lastAuthHeader = null
    }
}
