package com.whisper2.app.conformance

/**
 * Result of a conformance gate test
 */
data class GateResult(
    val name: String,
    val passed: Boolean,
    val details: String,
    val durationMs: Long,
    val wsFrames: List<String> = emptyList(),
    val httpRequests: List<String> = emptyList(),
    val artifacts: Map<String, Any> = emptyMap(),
    val error: Throwable? = null
) {
    companion object {
        fun pass(
            name: String,
            details: String,
            durationMs: Long,
            wsFrames: List<String> = emptyList(),
            httpRequests: List<String> = emptyList(),
            artifacts: Map<String, Any> = emptyMap()
        ) = GateResult(
            name = name,
            passed = true,
            details = details,
            durationMs = durationMs,
            wsFrames = wsFrames,
            httpRequests = httpRequests,
            artifacts = artifacts
        )

        fun fail(
            name: String,
            reason: String,
            durationMs: Long,
            wsFrames: List<String> = emptyList(),
            httpRequests: List<String> = emptyList(),
            error: Throwable? = null
        ) = GateResult(
            name = name,
            passed = false,
            details = reason,
            durationMs = durationMs,
            wsFrames = wsFrames,
            httpRequests = httpRequests,
            error = error
        )
    }

    override fun toString(): String {
        val status = if (passed) "✓ PASS" else "✗ FAIL"
        return "[$name] $status - $details (${durationMs}ms)"
    }

    fun toReport(): String = buildString {
        appendLine("=" .repeat(70))
        appendLine("Gate: $name")
        appendLine("Status: ${if (passed) "PASS" else "FAIL"}")
        appendLine("Duration: ${durationMs}ms")
        appendLine("-" .repeat(70))
        appendLine("Details: $details")

        if (error != null) {
            appendLine()
            appendLine("Error: ${error.message}")
            appendLine("Stack: ${error.stackTraceToString().take(500)}")
        }

        if (wsFrames.isNotEmpty()) {
            appendLine()
            appendLine("WS Frames (${wsFrames.size}):")
            wsFrames.takeLast(20).forEach { appendLine("  $it") }
            if (wsFrames.size > 20) {
                appendLine("  ... and ${wsFrames.size - 20} more")
            }
        }

        if (httpRequests.isNotEmpty()) {
            appendLine()
            appendLine("HTTP Requests (${httpRequests.size}):")
            httpRequests.forEach { appendLine("  $it") }
        }

        if (artifacts.isNotEmpty()) {
            appendLine()
            appendLine("Artifacts:")
            artifacts.forEach { (k, v) ->
                val valueStr = when (v) {
                    is ByteArray -> "${v.size} bytes"
                    is String -> if (v.length > 50) "${v.take(50)}..." else v
                    else -> v.toString()
                }
                appendLine("  $k: $valueStr")
            }
        }

        appendLine("=" .repeat(70))
    }
}

/**
 * Aggregate result of all gates
 */
data class ConformanceSuiteResult(
    val gates: List<GateResult>,
    val totalDurationMs: Long
) {
    val passedCount: Int get() = gates.count { it.passed }
    val failedCount: Int get() = gates.count { !it.passed }
    val allPassed: Boolean get() = failedCount == 0

    fun toReport(): String = buildString {
        appendLine()
        appendLine("#".repeat(70))
        appendLine("# CONFORMANCE SUITE RESULTS")
        appendLine("#".repeat(70))
        appendLine()
        appendLine("Total Gates: ${gates.size}")
        appendLine("Passed: $passedCount")
        appendLine("Failed: $failedCount")
        appendLine("Total Duration: ${totalDurationMs}ms")
        appendLine()

        appendLine("Summary:")
        gates.forEach { gate ->
            val status = if (gate.passed) "✓" else "✗"
            appendLine("  $status ${gate.name} (${gate.durationMs}ms)")
        }

        if (failedCount > 0) {
            appendLine()
            appendLine("Failed Gates:")
            gates.filter { !it.passed }.forEach { gate ->
                appendLine()
                appendLine(gate.toReport())
            }
        }

        appendLine()
        appendLine("#".repeat(70))
        appendLine("# ${if (allPassed) "ALL GATES PASSED" else "SOME GATES FAILED"}")
        appendLine("#".repeat(70))
    }
}
