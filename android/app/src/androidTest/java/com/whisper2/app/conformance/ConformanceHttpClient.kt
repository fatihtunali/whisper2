package com.whisper2.app.conformance

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Conformance HTTP Client
 *
 * Features:
 * - Request/response logging
 * - JSON helpers
 * - Authenticated requests
 */
class ConformanceHttpClient(
    private val baseUrl: String = ConformanceConfig.API_BASE_URL
) {
    private val gson = Gson()
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val OCTET_STREAM_TYPE = "application/octet-stream".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(ConformanceConfig.Timeout.HTTP_REQUEST, TimeUnit.MILLISECONDS)
        .readTimeout(ConformanceConfig.Timeout.HTTP_REQUEST, TimeUnit.MILLISECONDS)
        .writeTimeout(ConformanceConfig.Timeout.HTTP_REQUEST, TimeUnit.MILLISECONDS)
        .build()

    // ==========================================================================
    // HTTP Methods
    // ==========================================================================

    suspend fun get(
        path: String,
        sessionToken: String? = null,
        headers: Map<String, String> = emptyMap()
    ): HttpResult {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .get()
            .apply {
                sessionToken?.let { addHeader("Authorization", "Bearer $it") }
                headers.forEach { (k, v) -> addHeader(k, v) }
            }
            .build()

        return execute(request, "GET", path)
    }

    suspend fun post(
        path: String,
        body: Any,
        sessionToken: String? = null,
        headers: Map<String, String> = emptyMap()
    ): HttpResult {
        val jsonBody = if (body is String) body else gson.toJson(body)
        val request = Request.Builder()
            .url("$baseUrl$path")
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .apply {
                sessionToken?.let { addHeader("Authorization", "Bearer $it") }
                headers.forEach { (k, v) -> addHeader(k, v) }
            }
            .build()

        return execute(request, "POST", path)
    }

    suspend fun put(
        path: String,
        body: Any,
        sessionToken: String? = null,
        headers: Map<String, String> = emptyMap()
    ): HttpResult {
        val jsonBody = if (body is String) body else gson.toJson(body)
        val request = Request.Builder()
            .url("$baseUrl$path")
            .put(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .apply {
                sessionToken?.let { addHeader("Authorization", "Bearer $it") }
                headers.forEach { (k, v) -> addHeader(k, v) }
            }
            .build()

        return execute(request, "PUT", path)
    }

    suspend fun putBytes(
        url: String,
        data: ByteArray,
        contentType: String = "application/octet-stream",
        headers: Map<String, String> = emptyMap()
    ): HttpResult {
        val request = Request.Builder()
            .url(url)
            .put(data.toRequestBody(contentType.toMediaType()))
            .apply {
                headers.forEach { (k, v) -> addHeader(k, v) }
            }
            .build()

        return execute(request, "PUT", url)
    }

    suspend fun getBytes(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): HttpBytesResult {
        val startTime = System.currentTimeMillis()

        val request = Request.Builder()
            .url(url)
            .get()
            .apply {
                headers.forEach { (k, v) -> addHeader(k, v) }
            }
            .build()

        return withContext(Dispatchers.IO) {
            try {
                val response = client.newCall(request).execute()
                val duration = System.currentTimeMillis() - startTime

                ConformanceLogger.captureHttpRequest("GET", url, response.code, duration)

                val bytes = response.body?.bytes()
                HttpBytesResult(
                    statusCode = response.code,
                    bytes = bytes,
                    headers = response.headers.toMap(),
                    durationMs = duration
                )
            } catch (e: Exception) {
                val duration = System.currentTimeMillis() - startTime
                ConformanceLogger.captureHttpRequest("GET", url, null, duration)
                HttpBytesResult(
                    statusCode = -1,
                    bytes = null,
                    headers = emptyMap(),
                    durationMs = duration,
                    error = e
                )
            }
        }
    }

    suspend fun delete(
        path: String,
        sessionToken: String? = null,
        headers: Map<String, String> = emptyMap()
    ): HttpResult {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .delete()
            .apply {
                sessionToken?.let { addHeader("Authorization", "Bearer $it") }
                headers.forEach { (k, v) -> addHeader(k, v) }
            }
            .build()

        return execute(request, "DELETE", path)
    }

    // ==========================================================================
    // Execution
    // ==========================================================================

    private suspend fun execute(request: Request, method: String, path: String): HttpResult {
        val startTime = System.currentTimeMillis()

        return withContext(Dispatchers.IO) {
            try {
                val response = client.newCall(request).execute()
                val duration = System.currentTimeMillis() - startTime

                ConformanceLogger.captureHttpRequest(method, path, response.code, duration)

                val bodyString = response.body?.string()
                val json = try {
                    bodyString?.let { JsonParser.parseString(it).asJsonObject }
                } catch (e: Exception) {
                    null
                }

                HttpResult(
                    statusCode = response.code,
                    body = bodyString,
                    json = json,
                    headers = response.headers.toMap(),
                    durationMs = duration
                )
            } catch (e: Exception) {
                val duration = System.currentTimeMillis() - startTime
                ConformanceLogger.captureHttpRequest(method, path, null, duration)
                ConformanceLogger.error("HTTP $method $path failed: ${e.message}")

                HttpResult(
                    statusCode = -1,
                    body = null,
                    json = null,
                    headers = emptyMap(),
                    durationMs = duration,
                    error = e
                )
            }
        }
    }

    private fun Headers.toMap(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (i in 0 until size) {
            map[name(i)] = value(i)
        }
        return map
    }
}

/**
 * HTTP Response wrapper
 */
data class HttpResult(
    val statusCode: Int,
    val body: String?,
    val json: JsonObject?,
    val headers: Map<String, String>,
    val durationMs: Long,
    val error: Throwable? = null
) {
    val isSuccess: Boolean get() = statusCode in 200..299
    val isNotFound: Boolean get() = statusCode == 404
    val isUnauthorized: Boolean get() = statusCode == 401

    fun requireSuccess(): HttpResult {
        require(isSuccess) { "HTTP request failed with status $statusCode: $body" }
        return this
    }

    fun <T> parseBody(clazz: Class<T>): T? {
        return json?.let { Gson().fromJson(it, clazz) }
    }
}

/**
 * HTTP Bytes Response wrapper
 */
data class HttpBytesResult(
    val statusCode: Int,
    val bytes: ByteArray?,
    val headers: Map<String, String>,
    val durationMs: Long,
    val error: Throwable? = null
) {
    val isSuccess: Boolean get() = statusCode in 200..299

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as HttpBytesResult

        if (statusCode != other.statusCode) return false
        if (bytes != null) {
            if (other.bytes == null) return false
            if (!bytes.contentEquals(other.bytes)) return false
        } else if (other.bytes != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = statusCode
        result = 31 * result + (bytes?.contentHashCode() ?: 0)
        return result
    }
}
