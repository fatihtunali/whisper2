package com.whisper2.app.attachments

import com.whisper2.app.network.api.*
import com.whisper2.app.services.attachments.AttachmentCache
import com.whisper2.app.services.attachments.BlobHttpClient
import com.whisper2.app.services.attachments.BlobResult

/**
 * Step 10: Test Fakes for Attachment Tests
 */

// =============================================================================
// FAKE ATTACHMENTS API
// =============================================================================

class FakeAttachmentsApi : AttachmentsApi {

    var sessionToken: String? = "test_session_token"

    private val uploadResponses = mutableListOf<ApiResult<PresignUploadResponse>>()
    private val downloadResponses = mutableListOf<ApiResult<PresignDownloadResponse>>()

    var presignUploadCallCount = 0
        private set
    var presignDownloadCallCount = 0
        private set

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

    override suspend fun presignUpload(request: PresignUploadRequest): ApiResult<PresignUploadResponse> {
        presignUploadCallCount++
        lastUploadRequest = request

        val token = sessionToken
        if (token == null) {
            return ApiResult.Error(ApiErrorResponse.AUTH_FAILED, "No session token", 401)
        }
        lastAuthHeader = "Bearer $token"

        return uploadResponses.removeFirstOrNull()
            ?: ApiResult.Error(ApiErrorResponse.INTERNAL_ERROR, "No queued response", 500)
    }

    override suspend fun presignDownload(request: PresignDownloadRequest): ApiResult<PresignDownloadResponse> {
        presignDownloadCallCount++
        lastDownloadRequest = request

        val token = sessionToken
        if (token == null) {
            return ApiResult.Error(ApiErrorResponse.AUTH_FAILED, "No session token", 401)
        }
        lastAuthHeader = "Bearer $token"

        return downloadResponses.removeFirstOrNull()
            ?: ApiResult.Error(ApiErrorResponse.INTERNAL_ERROR, "No queued response", 500)
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

// =============================================================================
// FAKE BLOB HTTP CLIENT
// =============================================================================

class FakeBlobHttpClient : BlobHttpClient {

    private val putResponses = mutableListOf<BlobResult>()
    private val getResponses = mutableListOf<BlobResult>()

    var putCallCount = 0
        private set
    var getCallCount = 0
        private set

    var lastPutUrl: String? = null
        private set
    var lastPutBody: ByteArray? = null
        private set
    var lastPutHeaders: Map<String, String>? = null
        private set
    var lastGetUrl: String? = null
        private set

    fun enqueuePutResponse(result: BlobResult) {
        putResponses.add(result)
    }

    fun enqueueGetResponse(result: BlobResult) {
        getResponses.add(result)
    }

    override suspend fun put(url: String, body: ByteArray, headers: Map<String, String>): BlobResult {
        putCallCount++
        lastPutUrl = url
        lastPutBody = body
        lastPutHeaders = headers

        return putResponses.removeFirstOrNull()
            ?: BlobResult.failure(500)
    }

    override suspend fun get(url: String): BlobResult {
        getCallCount++
        lastGetUrl = url

        return getResponses.removeFirstOrNull()
            ?: BlobResult.failure(500)
    }

    fun reset() {
        putResponses.clear()
        getResponses.clear()
        putCallCount = 0
        getCallCount = 0
        lastPutUrl = null
        lastPutBody = null
        lastPutHeaders = null
        lastGetUrl = null
    }
}

// =============================================================================
// FAKE ATTACHMENT CACHE
// =============================================================================

class FakeAttachmentCache : AttachmentCache {

    private val cache = mutableMapOf<String, ByteArray>()

    var getCallCount = 0
        private set
    var putCallCount = 0
        private set
    var invalidateCallCount = 0
        private set

    override fun getIfPresent(objectKey: String): ByteArray? {
        getCallCount++
        return cache[objectKey]
    }

    override fun put(objectKey: String, data: ByteArray) {
        putCallCount++
        cache[objectKey] = data
    }

    override fun invalidate(objectKey: String) {
        invalidateCallCount++
        cache.remove(objectKey)
    }

    override fun clear() {
        cache.clear()
    }

    override fun size(): Int = cache.size

    fun reset() {
        cache.clear()
        getCallCount = 0
        putCallCount = 0
        invalidateCallCount = 0
    }
}
