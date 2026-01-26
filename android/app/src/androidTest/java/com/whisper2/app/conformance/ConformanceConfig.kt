package com.whisper2.app.conformance

import com.whisper2.app.BuildConfig

/**
 * Conformance Test Configuration
 *
 * All configuration values for the conformance test suite.
 * Uses BuildConfig values from stagingConformance flavor.
 */
object ConformanceConfig {

    // ==========================================================================
    // Server URLs
    // ==========================================================================

    val WS_URL: String = BuildConfig.WS_URL
    val API_BASE_URL: String = BuildConfig.API_BASE_URL

    // ==========================================================================
    // Test Mnemonics (FROZEN - DO NOT CHANGE)
    // ==========================================================================

    /** User A: abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about */
    val TEST_MNEMONIC_1: String = BuildConfig.TEST_MNEMONIC_1

    /** User B: zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong */
    val TEST_MNEMONIC_2: String = BuildConfig.TEST_MNEMONIC_2

    // ==========================================================================
    // Device IDs (Fixed for reproducibility)
    // ==========================================================================

    val DEVICE_ID_A: String = BuildConfig.TEST_DEVICE_ID_A
    val DEVICE_ID_B: String = BuildConfig.TEST_DEVICE_ID_B

    // ==========================================================================
    // Timeouts (milliseconds)
    // ==========================================================================

    object Timeout {
        const val WS_CONNECT = 10_000L
        const val WS_MESSAGE = 30_000L
        const val HTTP_REQUEST = 30_000L
        const val GATE_TOTAL = 120_000L // 2 minutes per gate max
        const val REGISTRATION = 60_000L
        const val MESSAGE_DELIVERY = 30_000L
        const val ATTACHMENT_UPLOAD = 60_000L
        const val ATTACHMENT_DOWNLOAD = 60_000L
    }

    // ==========================================================================
    // Logging Configuration
    // ==========================================================================

    object Logging {
        const val MAX_WS_FRAMES = 50
        const val MAX_HTTP_REQUESTS = 20
        const val PAYLOAD_HASH_LENGTH = 8 // First 8 chars of SHA256
    }

    // ==========================================================================
    // Test Data
    // ==========================================================================

    object TestData {
        const val TEST_MESSAGE_TEXT = "merhaba-real-conformance-test"
        const val TEST_ATTACHMENT_SIZE = 50 * 1024 // 50KB
        const val PAGINATION_MESSAGE_COUNT = 60
    }

    // ==========================================================================
    // Validation
    // ==========================================================================

    fun validate(): Boolean {
        return WS_URL.isNotEmpty() &&
               API_BASE_URL.isNotEmpty() &&
               TEST_MNEMONIC_1.isNotEmpty() &&
               TEST_MNEMONIC_2.isNotEmpty() &&
               DEVICE_ID_A.isNotEmpty() &&
               DEVICE_ID_B.isNotEmpty() &&
               BuildConfig.IS_CONFORMANCE_BUILD
    }

    fun requireValid() {
        require(validate()) {
            """
            Conformance test configuration invalid!
            Make sure you're running with stagingConformance flavor.

            WS_URL: $WS_URL
            API_BASE_URL: $API_BASE_URL
            TEST_MNEMONIC_1: ${if (TEST_MNEMONIC_1.isNotEmpty()) "SET" else "EMPTY"}
            TEST_MNEMONIC_2: ${if (TEST_MNEMONIC_2.isNotEmpty()) "SET" else "EMPTY"}
            IS_CONFORMANCE_BUILD: ${BuildConfig.IS_CONFORMANCE_BUILD}
            """.trimIndent()
        }
    }
}
