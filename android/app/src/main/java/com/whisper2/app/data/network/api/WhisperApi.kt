package com.whisper2.app.data.network.api

import com.google.gson.annotations.SerializedName
import retrofit2.http.*

interface WhisperApi {
    @GET("users/{whisperId}/keys")
    suspend fun getUserKeys(
        @Header("Authorization") token: String,
        @Path("whisperId") whisperId: String
    ): UserKeysResponse

    @PUT("backup/contacts")
    suspend fun uploadContactsBackup(
        @Header("Authorization") token: String,
        @Body backup: ContactsBackupRequest
    ): ContactsBackupResponse

    @GET("backup/contacts")
    suspend fun downloadContactsBackup(
        @Header("Authorization") token: String
    ): ContactsBackupResponse

    @DELETE("backup/contacts")
    suspend fun deleteContactsBackup(
        @Header("Authorization") token: String
    ): DeleteResponse

    @GET("health")
    suspend fun healthCheck(): HealthResponse
}

data class UserKeysResponse(
    @SerializedName("whisperId") val whisperId: String,
    @SerializedName("encPublicKey") val encPublicKey: String,
    @SerializedName("signPublicKey") val signPublicKey: String
)

data class ContactsBackupRequest(
    @SerializedName("encryptedData") val encryptedData: String
)

data class ContactsBackupResponse(
    @SerializedName("encryptedData") val encryptedData: String?,
    @SerializedName("updatedAt") val updatedAt: Long?
)

data class DeleteResponse(
    @SerializedName("success") val success: Boolean
)

data class HealthResponse(
    @SerializedName("status") val status: String
)
