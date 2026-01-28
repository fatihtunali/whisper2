package com.whisper2.app.data.network.api

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
    val whisperId: String,
    val encPublicKey: String,
    val signPublicKey: String
)

data class ContactsBackupRequest(val encryptedData: String)
data class ContactsBackupResponse(val encryptedData: String?, val updatedAt: Long?)
data class DeleteResponse(val success: Boolean)
data class HealthResponse(val status: String)
