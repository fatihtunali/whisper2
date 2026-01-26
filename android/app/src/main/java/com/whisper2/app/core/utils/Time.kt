package com.whisper2.app.core.utils

/**
 * Time utilities
 * Consistent timestamp handling across the app
 */
object Time {

    /**
     * Get current Unix timestamp in milliseconds
     */
    fun nowMillis(): Long = System.currentTimeMillis()

    /**
     * Get current Unix timestamp in seconds
     */
    fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    /**
     * Check if timestamp is within acceptable range (±5 minutes)
     */
    fun isTimestampValid(timestamp: Long, toleranceMs: Long = 5 * 60 * 1000): Boolean {
        val now = nowMillis()
        return timestamp in (now - toleranceMs)..(now + toleranceMs)
    }

    /**
     * Format timestamp for display (ISO 8601)
     */
    fun formatIso8601(timestampMs: Long): String {
        val instant = java.time.Instant.ofEpochMilli(timestampMs)
        return java.time.format.DateTimeFormatter.ISO_INSTANT.format(instant)
    }
}
