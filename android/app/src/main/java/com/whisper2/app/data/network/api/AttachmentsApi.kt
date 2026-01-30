package com.whisper2.app.data.network.api

import retrofit2.http.*

interface AttachmentsApi {
    @POST("attachments/presign/upload")
    suspend fun presignUpload(
        @Header("Authorization") token: String,
        @Body request: PresignUploadRequest
    ): PresignUploadResponse

    @POST("attachments/presign/download")
    suspend fun presignDownload(
        @Header("Authorization") token: String,
        @Body request: PresignDownloadRequest
    ): PresignDownloadResponse
}

// Field names must match server protocol exactly
data class PresignUploadRequest(val contentType: String, val sizeBytes: Long)
data class PresignUploadResponse(val objectKey: String, val uploadUrl: String)
data class PresignDownloadRequest(val objectKey: String)  // Server expects objectKey, not blobId
data class PresignDownloadResponse(val downloadUrl: String)
