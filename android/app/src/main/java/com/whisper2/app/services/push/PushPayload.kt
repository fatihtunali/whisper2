package com.whisper2.app.services.push

/**
 * Push payload model - wake-only discipline
 *
 * SECURITY: Push payloads contain ONLY wake signals.
 * Any content fields (text, ciphertext, messages, etc.) are IGNORED.
 * All actual message content is fetched via secure WebSocket.
 */
data class PushPayload(
    val type: String?,
    val reason: String?,
    val whisperId: String?
) {
    companion object {
        // Valid type
        const val TYPE_WAKE = "wake"

        // Valid reasons
        const val REASON_MESSAGE = "message"
        const val REASON_CALL = "call"
        const val REASON_SYSTEM = "system"

        /**
         * Parse from raw map (FCM data payload)
         * Only extracts wake-related fields, ignores everything else
         */
        fun fromMap(data: Map<String, String>): PushPayload {
            return PushPayload(
                type = data["type"],
                reason = data["reason"],
                whisperId = data["whisperId"]
            )
        }

        /**
         * Check if reason is valid
         */
        fun isValidReason(reason: String?): Boolean {
            return reason in listOf(REASON_MESSAGE, REASON_CALL, REASON_SYSTEM)
        }
    }

    /**
     * Validate the payload
     * @return true if payload is valid for processing
     */
    fun isValid(): Boolean {
        // type must be "wake"
        if (type != TYPE_WAKE) return false

        // reason must be valid
        if (!isValidReason(reason)) return false

        // whisperId must be present
        if (whisperId.isNullOrBlank()) return false

        return true
    }

    /**
     * Check if this is a message wake
     */
    val isMessageWake: Boolean
        get() = type == TYPE_WAKE && reason == REASON_MESSAGE

    /**
     * Check if this is a call wake
     */
    val isCallWake: Boolean
        get() = type == TYPE_WAKE && reason == REASON_CALL

    /**
     * Check if this is a system wake
     */
    val isSystemWake: Boolean
        get() = type == TYPE_WAKE && reason == REASON_SYSTEM
}
