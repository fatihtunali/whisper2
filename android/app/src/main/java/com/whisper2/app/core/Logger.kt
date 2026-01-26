package com.whisper2.app.core

import android.util.Log

/**
 * Step 13: Enhanced Logger with Event Capture
 *
 * Centralized logging with categories and event capture support for testing/observability.
 *
 * Features:
 * - Categorized logging (CRYPTO, NETWORK, AUTH, etc.)
 * - Pluggable LogEventListener for capturing events in tests
 * - PII masking support
 * - Log levels: debug, info, warn, error
 */
object Logger {

    private const val TAG = "Whisper2"

    enum class Category {
        APP,
        CRYPTO,
        NETWORK,
        AUTH,
        MESSAGING,
        STORAGE,
        CALL,
        UI,
        PUSH,
        OUTBOX,
        CLEANUP,
        GENERAL
    }

    enum class Level {
        DEBUG,
        INFO,
        WARN,
        ERROR
    }

    /**
     * Log event data class for capturing and testing
     */
    data class LogEvent(
        val level: Level,
        val category: Category,
        val message: String,
        val throwable: Throwable? = null,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * Listener interface for capturing log events (testing/observability)
     */
    fun interface LogEventListener {
        fun onLogEvent(event: LogEvent)
    }

    // Listener for capturing events (set for testing)
    @Volatile
    private var listener: LogEventListener? = null

    // Enable/disable Android Log output
    @Volatile
    var androidLogEnabled: Boolean = true

    /**
     * Set log event listener (for testing or remote logging)
     */
    fun setListener(newListener: LogEventListener?) {
        listener = newListener
    }

    /**
     * Clear listener
     */
    fun clearListener() {
        listener = null
    }

    fun debug(message: String, category: Category = Category.GENERAL) {
        emit(Level.DEBUG, category, message, null)
        if (androidLogEnabled) {
            Log.d("$TAG/${category.name}", message)
        }
    }

    fun info(message: String, category: Category = Category.GENERAL) {
        emit(Level.INFO, category, message, null)
        if (androidLogEnabled) {
            Log.i("$TAG/${category.name}", message)
        }
    }

    fun warn(message: String, category: Category = Category.GENERAL, throwable: Throwable? = null) {
        emit(Level.WARN, category, message, throwable)
        if (androidLogEnabled) {
            if (throwable != null) {
                Log.w("$TAG/${category.name}", message, throwable)
            } else {
                Log.w("$TAG/${category.name}", message)
            }
        }
    }

    fun error(message: String, category: Category = Category.GENERAL, throwable: Throwable? = null) {
        emit(Level.ERROR, category, message, throwable)
        if (androidLogEnabled) {
            if (throwable != null) {
                Log.e("$TAG/${category.name}", message, throwable)
            } else {
                Log.e("$TAG/${category.name}", message)
            }
        }
    }

    private fun emit(level: Level, category: Category, message: String, throwable: Throwable?) {
        listener?.onLogEvent(LogEvent(level, category, message, throwable))
    }

    /**
     * Mask PII in a string (WhisperIDs, etc.)
     * Useful for logs that might be sent to analytics
     */
    fun maskPii(value: String): String {
        // Mask WhisperID format: WSP-XXXX-XXXX-XXXX → WSP-****-****-XXXX
        return value.replace(Regex("WSP-[A-Z0-9]{4}-[A-Z0-9]{4}-([A-Z0-9]{4})")) {
            "WSP-****-****-${it.groupValues[1]}"
        }
    }

    /**
     * Create masked message with WhisperID
     */
    fun maskedId(whisperId: String?): String {
        if (whisperId == null) return "<null>"
        if (!whisperId.startsWith("WSP-")) return whisperId
        val parts = whisperId.split("-")
        return if (parts.size == 4) {
            "WSP-****-****-${parts[3]}"
        } else {
            whisperId.take(8) + "..."
        }
    }
}

// Convenience extension
fun logger() = Logger
