package com.whisper2.app.services.push

import com.whisper2.app.core.Logger

/**
 * WebSocket connection interface for push handler
 */
interface WsConnectionManager {
    /** Check if WebSocket is currently connected */
    fun isConnected(): Boolean

    /** Connect to WebSocket (if not already connected) */
    fun connect()
}

/**
 * Pending message fetcher interface
 */
fun interface PendingMessageFetcher {
    /** Trigger fetch of pending messages */
    fun fetchPending()
}

/**
 * Incoming call UI interface
 */
interface IncomingCallUi {
    /** Show incoming call UI */
    fun showIncomingCall(whisperId: String)
}

/**
 * Push Handler - Wake-Only Discipline
 *
 * Handles FCM wake pushes according to Whisper2 push discipline:
 * - reason=message: Wake WS + fetch_pending
 * - reason=call: Trigger incoming call UI
 * - reason=system: Reserved for future use
 *
 * SECURITY: This handler NEVER processes message content from push.
 * All content fields in push payload are IGNORED.
 */
class PushHandler(
    private val wsManager: WsConnectionManager,
    private val pendingFetcher: PendingMessageFetcher,
    private val callUi: IncomingCallUi
) {

    // Metrics tracking (for testing and monitoring)
    private var wakeMessageCount = 0
    private var wakeCallCount = 0
    private var ignoredPushCount = 0
    private var contentIgnoredCount = 0

    /**
     * Handle wake push with parsed payload
     *
     * @param type Push type (must be "wake")
     * @param reason Wake reason (message, call, system)
     * @param whisperId Sender's WhisperID
     */
    fun onWake(type: String?, reason: String?, whisperId: String?) {
        val payload = PushPayload(type, reason, whisperId)
        handlePayload(payload)
    }

    /**
     * Handle raw push data from FCM
     * Parses and validates, IGNORING any content fields (security)
     *
     * @param data Raw FCM data payload
     */
    fun onRawPush(data: Map<String, String>) {
        // Check for content fields that should be ignored (security)
        val hasContentFields = data.containsKey("text") ||
                data.containsKey("ciphertext") ||
                data.containsKey("messages") ||
                data.containsKey("content") ||
                data.containsKey("body")

        if (hasContentFields) {
            Logger.warn("Push payload contains content fields - IGNORING content (security)", Logger.Category.PUSH)
            contentIgnoredCount++
            // Continue processing as wake-only (don't return, still process wake)
        }

        val payload = PushPayload.fromMap(data)
        handlePayload(payload)
    }

    /**
     * Handle parsed payload
     */
    private fun handlePayload(payload: PushPayload) {
        // Validate payload
        if (!payload.isValid()) {
            Logger.warn("Invalid push payload: type=${payload.type}, reason=${payload.reason}, whisperId=${payload.whisperId}", Logger.Category.PUSH)
            ignoredPushCount++
            return
        }

        Logger.debug("Processing wake push: reason=${payload.reason}, whisperId=${payload.whisperId}", Logger.Category.PUSH)

        when {
            payload.isMessageWake -> handleMessageWake(payload.whisperId!!)
            payload.isCallWake -> handleCallWake(payload.whisperId!!)
            payload.isSystemWake -> handleSystemWake(payload.whisperId!!)
            else -> {
                Logger.warn("Unknown wake reason: ${payload.reason}", Logger.Category.PUSH)
                ignoredPushCount++
            }
        }
    }

    /**
     * Handle message wake
     * 1. Connect WS if needed
     * 2. Fetch pending messages
     */
    private fun handleMessageWake(whisperId: String) {
        Logger.debug("Message wake from $whisperId", Logger.Category.PUSH)
        wakeMessageCount++

        // Connect WS if not already connected
        if (!wsManager.isConnected()) {
            Logger.debug("WS disconnected, connecting...", Logger.Category.PUSH)
            wsManager.connect()
        }

        // Always fetch pending (even if connected, there may be new messages)
        Logger.debug("Fetching pending messages...", Logger.Category.PUSH)
        pendingFetcher.fetchPending()
    }

    /**
     * Handle call wake
     * Trigger incoming call UI
     */
    private fun handleCallWake(whisperId: String) {
        Logger.debug("Call wake from $whisperId", Logger.Category.PUSH)
        wakeCallCount++

        // Show incoming call UI
        callUi.showIncomingCall(whisperId)

        // NOTE: Call wake does NOT fetch messages - call flow is separate
    }

    /**
     * Handle system wake
     * Reserved for future use (sync, settings update, etc.)
     */
    private fun handleSystemWake(whisperId: String) {
        Logger.debug("System wake from $whisperId - reserved for future use", Logger.Category.PUSH)
        // TODO: Implement system wake handling when needed
    }

    // ==========================================================================
    // Metrics (for testing and monitoring)
    // ==========================================================================

    fun getWakeMessageCount(): Int = wakeMessageCount
    fun getWakeCallCount(): Int = wakeCallCount
    fun getIgnoredPushCount(): Int = ignoredPushCount
    fun getContentIgnoredCount(): Int = contentIgnoredCount

    fun resetMetrics() {
        wakeMessageCount = 0
        wakeCallCount = 0
        ignoredPushCount = 0
        contentIgnoredCount = 0
    }
}
