package com.whisper2.app.conformance

import android.util.Log
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Conformance Test Logger
 *
 * Features:
 * - PII masking (WhisperIDs, session tokens)
 * - WS frame capture (last 50)
 * - HTTP request capture (last 20)
 * - Structured output for each gate
 */
object ConformanceLogger {

    private const val TAG = "Conformance"

    // ==========================================================================
    // Frame/Request Capture
    // ==========================================================================

    data class WsFrame(
        val timestamp: Long,
        val direction: Direction,
        val type: String,
        val requestId: String?,
        val payloadHash: String,
        val raw: String? = null // Only kept for debugging, not in summary
    ) {
        enum class Direction { SENT, RECEIVED }

        fun summary(): String {
            val dir = if (direction == Direction.SENT) "→" else "←"
            val reqId = requestId?.take(8) ?: "--------"
            return "$dir [$type] req=$reqId hash=$payloadHash"
        }
    }

    data class HttpRequest(
        val timestamp: Long,
        val method: String,
        val path: String,
        val status: Int?,
        val durationMs: Long
    ) {
        fun summary(): String {
            val statusStr = status?.toString() ?: "---"
            return "$method $path → $statusStr (${durationMs}ms)"
        }
    }

    private val wsFrames = CopyOnWriteArrayList<WsFrame>()
    private val httpRequests = CopyOnWriteArrayList<HttpRequest>()

    // ==========================================================================
    // Logging Methods
    // ==========================================================================

    fun info(message: String) {
        Log.i(TAG, maskPii(message))
    }

    fun debug(message: String) {
        Log.d(TAG, maskPii(message))
    }

    fun warn(message: String) {
        Log.w(TAG, maskPii(message))
    }

    fun error(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(TAG, maskPii(message), throwable)
        } else {
            Log.e(TAG, maskPii(message))
        }
    }

    fun gate(gateName: String, message: String) {
        Log.i(TAG, "[$gateName] ${maskPii(message)}")
    }

    fun gatePass(gateName: String, details: String = "") {
        val msg = if (details.isNotEmpty()) "PASS - $details" else "PASS"
        Log.i(TAG, "✓ [$gateName] ${maskPii(msg)}")
    }

    fun gateFail(gateName: String, reason: String) {
        Log.e(TAG, "✗ [$gateName] FAIL - ${maskPii(reason)}")
    }

    // ==========================================================================
    // WS Frame Capture
    // ==========================================================================

    fun captureWsSent(type: String, requestId: String?, payload: String) {
        val frame = WsFrame(
            timestamp = System.currentTimeMillis(),
            direction = WsFrame.Direction.SENT,
            type = type,
            requestId = requestId,
            payloadHash = hashPayload(payload),
            raw = if (wsFrames.size < 10) payload else null // Keep raw for first 10 only
        )
        addWsFrame(frame)
        debug("WS → $type ${requestId?.let { "req=$it" } ?: ""}")
    }

    fun captureWsReceived(type: String, requestId: String?, payload: String) {
        val frame = WsFrame(
            timestamp = System.currentTimeMillis(),
            direction = WsFrame.Direction.RECEIVED,
            type = type,
            requestId = requestId,
            payloadHash = hashPayload(payload),
            raw = if (wsFrames.size < 10) payload else null
        )
        addWsFrame(frame)
        debug("WS ← $type ${requestId?.let { "req=$it" } ?: ""}")
    }

    private fun addWsFrame(frame: WsFrame) {
        wsFrames.add(frame)
        // Keep only last N frames
        while (wsFrames.size > ConformanceConfig.Logging.MAX_WS_FRAMES) {
            wsFrames.removeAt(0)
        }
    }

    fun getWsFrameSummary(): List<String> {
        return wsFrames.map { it.summary() }
    }

    fun clearWsFrames() {
        wsFrames.clear()
    }

    // ==========================================================================
    // HTTP Request Capture
    // ==========================================================================

    fun captureHttpRequest(method: String, path: String, status: Int?, durationMs: Long) {
        val request = HttpRequest(
            timestamp = System.currentTimeMillis(),
            method = method,
            path = maskPiiInPath(path),
            status = status,
            durationMs = durationMs
        )
        addHttpRequest(request)
        debug("HTTP $method ${maskPiiInPath(path)} → ${status ?: "---"}")
    }

    private fun addHttpRequest(request: HttpRequest) {
        httpRequests.add(request)
        while (httpRequests.size > ConformanceConfig.Logging.MAX_HTTP_REQUESTS) {
            httpRequests.removeAt(0)
        }
    }

    fun getHttpRequestSummary(): List<String> {
        return httpRequests.map { it.summary() }
    }

    fun clearHttpRequests() {
        httpRequests.clear()
    }

    // ==========================================================================
    // PII Masking
    // ==========================================================================

    private val WHISPER_ID_REGEX = Regex("WSP-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}")
    private val SESSION_TOKEN_REGEX = Regex("\"sessionToken\":\"[^\"]+\"")
    private val BASE64_KEY_REGEX = Regex("\"(encPublicKey|signPublicKey|enc|sign)\":\"[A-Za-z0-9+/=]{40,}\"")

    fun maskPii(text: String): String {
        var masked = text

        // Mask WhisperIDs: WSP-XXXX-YYYY-ZZZZ → WSP-****-****-ZZZZ
        masked = WHISPER_ID_REGEX.replace(masked) { match ->
            val parts = match.value.split("-")
            if (parts.size == 4) {
                "WSP-****-****-${parts[3]}"
            } else {
                "WSP-****"
            }
        }

        // Mask session tokens
        masked = SESSION_TOKEN_REGEX.replace(masked, "\"sessionToken\":\"[MASKED]\"")

        // Mask long base64 keys (keep first 8 chars)
        masked = BASE64_KEY_REGEX.replace(masked) { match ->
            val key = match.groupValues[1]
            val valueStart = match.value.indexOf(":\"") + 2
            val value = match.value.substring(valueStart, match.value.length - 1)
            "\"$key\":\"${value.take(8)}...[MASKED]\""
        }

        return masked
    }

    private fun maskPiiInPath(path: String): String {
        return WHISPER_ID_REGEX.replace(path) { match ->
            val parts = match.value.split("-")
            if (parts.size == 4) {
                "WSP-****-****-${parts[3]}"
            } else {
                "WSP-****"
            }
        }
    }

    // ==========================================================================
    // Utilities
    // ==========================================================================

    private fun hashPayload(payload: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(payload.toByteArray())
        return hash.take(ConformanceConfig.Logging.PAYLOAD_HASH_LENGTH / 2)
            .joinToString("") { "%02x".format(it) }
    }

    // ==========================================================================
    // Gate Report Generation
    // ==========================================================================

    fun generateGateReport(gateName: String, passed: Boolean, details: String): String {
        val status = if (passed) "PASS" else "FAIL"
        val wsFrameSummary = getWsFrameSummary()
        val httpSummary = getHttpRequestSummary()

        return buildString {
            appendLine("=" .repeat(60))
            appendLine("Gate: $gateName - $status")
            appendLine("=" .repeat(60))
            appendLine()
            appendLine("Details: ${maskPii(details)}")
            appendLine()

            if (wsFrameSummary.isNotEmpty()) {
                appendLine("WS Frames (last ${wsFrameSummary.size}):")
                wsFrameSummary.forEach { appendLine("  $it") }
                appendLine()
            }

            if (httpSummary.isNotEmpty()) {
                appendLine("HTTP Requests (last ${httpSummary.size}):")
                httpSummary.forEach { appendLine("  $it") }
                appendLine()
            }

            appendLine("=" .repeat(60))
        }
    }

    fun reset() {
        wsFrames.clear()
        httpRequests.clear()
    }
}
