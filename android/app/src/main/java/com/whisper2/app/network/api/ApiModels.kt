package com.whisper2.app.network.api

/**
 * HTTP API Models (Retrofit DTOs)
 * Step 7: Key Lookup + Contacts Backup
 */

// =============================================================================
// GET /users/{whisperId}/keys
// =============================================================================

/**
 * Response from GET /users/{whisperId}/keys
 */
data class UserKeysResponse(
    val whisperId: String,
    val encPublicKey: String,   // base64 padded (32 bytes)
    val signPublicKey: String,  // base64 padded (32 bytes)
    val status: String          // "active" | "banned"
) {
    companion object {
        const val STATUS_ACTIVE = "active"
        const val STATUS_BANNED = "banned"
    }

    val isActive: Boolean get() = status == STATUS_ACTIVE
}

// =============================================================================
// PUT /backup/contacts
// =============================================================================

/**
 * Request body for PUT /backup/contacts
 */
data class ContactsBackupPutRequest(
    val nonce: String,      // base64(24 bytes) padded
    val ciphertext: String  // base64 padded, length%4==0
)

/**
 * Response from PUT /backup/contacts
 */
data class ContactsBackupPutResponse(
    val success: Boolean,
    val created: Boolean,
    val sizeBytes: Int,
    val updatedAt: Long
)

// =============================================================================
// GET /backup/contacts
// =============================================================================

/**
 * Response from GET /backup/contacts
 */
data class ContactsBackupGetResponse(
    val nonce: String,
    val ciphertext: String,
    val sizeBytes: Int,
    val updatedAt: Long
)

// =============================================================================
// Error Response
// =============================================================================

/**
 * Standard error response from server
 */
data class ApiErrorResponse(
    val error: String,
    val message: String,
    val retryAfter: Int? = null
) {
    // Convenience alias for accessing error code
    val code: String get() = error

    companion object {
        const val AUTH_FAILED = "AUTH_FAILED"
        const val INVALID_PAYLOAD = "INVALID_PAYLOAD"
        const val NOT_FOUND = "NOT_FOUND"
        const val FORBIDDEN = "FORBIDDEN"
        const val RATE_LIMITED = "RATE_LIMITED"
        const val INTERNAL_ERROR = "INTERNAL_ERROR"
        const val SERVER_ERROR = "SERVER_ERROR"
        const val NETWORK_ERROR = "NETWORK_ERROR"
        const val CONFLICT = "CONFLICT"
    }
}
