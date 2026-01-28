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

data class PresignUploadRequest(val contentType: String, val size: Long)
data class PresignUploadResponse(val blobId: String, val uploadUrl: String)
data class PresignDownloadRequest(val blobId: String)
data class PresignDownloadResponse(val downloadUrl: String)
