package com.whisper2.app.network.api

/**
 * Step 10: Attachments API Interface
 *
 * HTTP endpoints for presigned URL operations.
 * All endpoints require authentication: Authorization: Bearer <sessionToken>
 */
interface AttachmentsApi {

    /**
     * POST /attachments/presign/upload
     *
     * Get a presigned URL for uploading an attachment.
     *
     * @param request Content type and size information
     * @return Presigned upload URL and headers
     */
    suspend fun presignUpload(request: PresignUploadRequest): ApiResult<PresignUploadResponse>

    /**
     * POST /attachments/presign/download
     *
     * Get a presigned URL for downloading an attachment.
     *
     * @param request Object key to download
     * @return Presigned download URL
     */
    suspend fun presignDownload(request: PresignDownloadRequest): ApiResult<PresignDownloadResponse>
}

/**
 * Mock/Test implementation of AttachmentsApi
 */
class MockAttachmentsApi(
    private val sessionTokenProvider: SessionTokenProvider,
    private val authFailureHandler: ApiAuthFailureHandler? = null
) : AttachmentsApi {

    // Queued responses
    private val uploadResponses = mutableListOf<ApiResult<PresignUploadResponse>>()
    private val downloadResponses = mutableListOf<ApiResult<PresignDownloadResponse>>()

    // Call tracking
    var presignUploadCallCount = 0
        private set
    var presignDownloadCallCount = 0
        private set

    // Last requests
    var lastUploadRequest: PresignUploadRequest? = null
        private set
    var lastDownloadRequest: PresignDownloadRequest? = null
        private set
    var lastAuthHeader: String? = null
        private set

    fun enqueueUploadResponse(response: ApiResult<PresignUploadResponse>) {
        uploadResponses.add(response)
    }

    fun enqueueDownloadResponse(response: ApiResult<PresignDownloadResponse>) {
        downloadResponses.add(response)
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

    override suspend fun presignUpload(request: PresignUploadRequest): ApiResult<PresignUploadResponse> {
        presignUploadCallCount++
        lastUploadRequest = request

        checkAuth()?.let { return it }

        val response = uploadResponses.removeFirstOrNull()
            ?: ApiResult.Error(ApiErrorResponse.INTERNAL_ERROR, "No queued response", 500)

        handleAuthError(response)
        return response
    }

    override suspend fun presignDownload(request: PresignDownloadRequest): ApiResult<PresignDownloadResponse> {
        presignDownloadCallCount++
        lastDownloadRequest = request

        checkAuth()?.let { return it }

        val response = downloadResponses.removeFirstOrNull()
            ?: ApiResult.Error(ApiErrorResponse.INTERNAL_ERROR, "No queued response", 500)

        handleAuthError(response)
        return response
    }

    fun reset() {
        uploadResponses.clear()
        downloadResponses.clear()
        presignUploadCallCount = 0
        presignDownloadCallCount = 0
        lastUploadRequest = null
        lastDownloadRequest = null
        lastAuthHeader = null
    }
}
