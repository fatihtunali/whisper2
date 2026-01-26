package com.whisper2.app.network.api

import android.util.Log
import com.google.gson.Gson
import com.whisper2.app.core.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * OkHttp implementation of WhisperApi
 *
 * Real HTTP client for production use.
 */
class OkHttpWhisperApi(
    private val client: OkHttpClient,
    private val gson: Gson,
    private val sessionTokenProvider: SessionTokenProvider,
    private val authFailureHandler: ApiAuthFailureHandler? = null,
    private val baseUrl: String = Constants.Server.httpBaseUrl
) : WhisperApi {

    companion object {
        private const val TAG = "OkHttpWhisperApi"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    // =========================================================================
    // API Endpoints
    // =========================================================================

    override suspend fun getUserKeys(whisperId: String): ApiResult<UserKeysResponse> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl/users/$whisperId/keys")
                .get()
                .addAuthHeader()
                .build()

            executeRequest(request) { body ->
                gson.fromJson(body, UserKeysResponse::class.java)
            }
        }

    override suspend fun putContactsBackup(request: ContactsBackupPutRequest): ApiResult<ContactsBackupPutResponse> =
        withContext(Dispatchers.IO) {
            val jsonBody = gson.toJson(request).toRequestBody(JSON_MEDIA_TYPE)
            val httpRequest = Request.Builder()
                .url("$baseUrl/backup/contacts")
                .put(jsonBody)
                .addAuthHeader()
                .build()

            executeRequest(httpRequest) { body ->
                gson.fromJson(body, ContactsBackupPutResponse::class.java)
            }
        }

    override suspend fun getContactsBackup(): ApiResult<ContactsBackupGetResponse> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl/backup/contacts")
                .get()
                .addAuthHeader()
                .build()

            executeRequest(request) { body ->
                gson.fromJson(body, ContactsBackupGetResponse::class.java)
            }
        }

    override suspend fun deleteContactsBackup(): ApiResult<Unit> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl/backup/contacts")
                .delete()
                .addAuthHeader()
                .build()

            executeRequest(request) { Unit }
        }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun Request.Builder.addAuthHeader(): Request.Builder {
        val token = sessionTokenProvider.getSessionToken()
        if (token != null) {
            addHeader("Authorization", "Bearer $token")
        }
        return this
    }

    private inline fun <T> executeRequest(
        request: Request,
        parseBody: (String) -> T
    ): ApiResult<T> {
        return try {
            val response: Response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            Log.d(TAG, "${request.method} ${request.url}: ${response.code}")

            if (response.isSuccessful) {
                try {
                    ApiResult.Success(parseBody(body))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse response: ${e.message}")
                    ApiResult.Error(
                        code = ApiErrorResponse.INTERNAL_ERROR,
                        message = "Failed to parse response: ${e.message}",
                        httpCode = response.code
                    )
                }
            } else {
                val errorResponse = try {
                    gson.fromJson(body, ApiErrorResponse::class.java)
                } catch (e: Exception) {
                    null
                }

                val errorCode = errorResponse?.code ?: mapHttpCodeToError(response.code)
                val errorMessage = errorResponse?.message ?: response.message

                // Handle auth failure
                if (response.code == 401) {
                    authFailureHandler?.onAuthFailure(errorMessage)
                }

                ApiResult.Error(
                    code = errorCode,
                    message = errorMessage,
                    httpCode = response.code
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Request failed: ${e.message}", e)
            ApiResult.Error(
                code = ApiErrorResponse.NETWORK_ERROR,
                message = e.message ?: "Network error",
                httpCode = 0
            )
        }
    }

    private fun mapHttpCodeToError(httpCode: Int): String {
        return when (httpCode) {
            400 -> ApiErrorResponse.INVALID_PAYLOAD
            401 -> ApiErrorResponse.AUTH_FAILED
            403 -> ApiErrorResponse.FORBIDDEN
            404 -> ApiErrorResponse.NOT_FOUND
            409 -> ApiErrorResponse.CONFLICT
            429 -> ApiErrorResponse.RATE_LIMITED
            else -> ApiErrorResponse.INTERNAL_ERROR
        }
    }
}
