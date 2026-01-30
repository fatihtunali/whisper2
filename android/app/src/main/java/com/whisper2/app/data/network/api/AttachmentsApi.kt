package com.whisper2.app.data.network.api

import com.google.gson.annotations.SerializedName
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

// Field names must match server protocol exactly - use @SerializedName for ProGuard safety
data class PresignUploadRequest(
    @SerializedName("contentType") val contentType: String,
    @SerializedName("sizeBytes") val sizeBytes: Long
)

data class PresignUploadResponse(
    @SerializedName("objectKey") val objectKey: String,
    @SerializedName("uploadUrl") val uploadUrl: String
)

data class PresignDownloadRequest(
    @SerializedName("objectKey") val objectKey: String
)

data class PresignDownloadResponse(
    @SerializedName("downloadUrl") val downloadUrl: String
)
