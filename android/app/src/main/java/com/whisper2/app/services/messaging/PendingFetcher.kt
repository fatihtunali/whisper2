package com.whisper2.app.services.messaging

import android.util.Log
import com.whisper2.app.core.Constants
import com.whisper2.app.core.utils.Base64Strict
import com.whisper2.app.network.ws.*
import com.whisper2.app.services.auth.ISessionManager
import java.util.UUID

/**
 * Pending message fetcher
 *
 * Responsibilities:
 * - Send fetch_pending on connect/wake
 * - Process pending_messages response
 * - Validate nonce (24 bytes) and sig (64 bytes)
 * - Dedupe messages
 * - Persist to store
 * - Send delivery_receipt for each message
 * - Track cursor for pagination
 */
class PendingFetcher(
    private val sessionManager: ISessionManager,
    private val messageStore: MessageStore,
    private val deduper: Deduper,
    private val messageSender: MessageSender,
    private val myWhisperId: () -> String?
) {
    companion object {
        private const val TAG = "PendingFetcher"
        private const val NONCE_BYTES = 24
        private const val SIG_BYTES = 64
        private const val DEFAULT_LIMIT = 50
    }

    // Cursor for pagination
    private var cursor: String? = null

    // Stats for testing
    var fetchCount = 0
        private set
    var receiptsSent = 0
        private set

    /**
     * Create fetch_pending JSON message
     */
    fun createFetchPending(): String {
        val sessionToken = sessionManager.sessionToken
            ?: throw IllegalStateException("No session token")

        val payload = FetchPendingPayload(
            protocolVersion = Constants.PROTOCOL_VERSION,
            cryptoVersion = Constants.CRYPTO_VERSION,
            sessionToken = sessionToken,
            cursor = cursor,
            limit = DEFAULT_LIMIT
        )

        val json = WsParser.createEnvelope(
            type = WsMessageTypes.FETCH_PENDING,
            payload = payload,
            requestId = UUID.randomUUID().toString()
        )

        fetchCount++
        Log.d(TAG, "Created fetch_pending: cursor=$cursor, limit=$DEFAULT_LIMIT")
        return json
    }

    /**
     * Handle pending_messages response from server
     * @return number of new messages stored
     */
    fun handlePendingMessages(payload: PendingMessagesPayload): Int {
        Log.d(TAG, "Received ${payload.messages.size} pending messages")

        // Update cursor for next fetch
        cursor = payload.nextCursor

        var stored = 0
        for (message in payload.messages) {
            if (processMessage(message)) {
                stored++
            }
        }

        Log.d(TAG, "Stored $stored new messages, nextCursor=${payload.nextCursor}")
        return stored
    }

    /**
     * Process a single pending message
     * @return true if stored (new), false if rejected or duplicate
     */
    private fun processMessage(message: PendingMessageItem): Boolean {
        // Validate nonce length (24 bytes)
        val nonceBytes = try {
            Base64Strict.decode(message.nonce)
        } catch (e: Exception) {
            Log.w(TAG, "Invalid nonce base64 for ${message.messageId}: ${e.message}")
            return false
        }

        if (nonceBytes.size != NONCE_BYTES) {
            Log.w(TAG, "Invalid nonce length for ${message.messageId}: ${nonceBytes.size} != $NONCE_BYTES")
            return false
        }

        // Validate sig length (64 bytes)
        val sigBytes = try {
            Base64Strict.decode(message.sig)
        } catch (e: Exception) {
            Log.w(TAG, "Invalid sig base64 for ${message.messageId}: ${e.message}")
            return false
        }

        if (sigBytes.size != SIG_BYTES) {
            Log.w(TAG, "Invalid sig length for ${message.messageId}: ${sigBytes.size} != $SIG_BYTES")
            return false
        }

        // Check for duplicate
        if (deduper.isDuplicate(message.messageId)) {
            Log.d(TAG, "Duplicate message: ${message.messageId}")
            return false
        }

        // Store message
        val stored = messageStore.store(message)
        if (!stored) {
            Log.d(TAG, "Message already in store: ${message.messageId}")
            return false
        }

        // Send delivery receipt
        sendDeliveryReceipt(message)

        return true
    }

    /**
     * Send delivery_receipt for a message
     */
    private fun sendDeliveryReceipt(message: PendingMessageItem) {
        val sessionToken = sessionManager.sessionToken ?: return
        val myId = myWhisperId() ?: return

        val payload = DeliveryReceiptPayload(
            protocolVersion = Constants.PROTOCOL_VERSION,
            cryptoVersion = Constants.CRYPTO_VERSION,
            sessionToken = sessionToken,
            messageId = message.messageId,
            from = myId, // recipient sends receipt
            to = message.from, // to original sender
            status = "delivered",
            timestamp = System.currentTimeMillis()
        )

        val json = WsParser.createEnvelope(
            type = WsMessageTypes.DELIVERY_RECEIPT,
            payload = payload,
            requestId = UUID.randomUUID().toString()
        )

        messageSender.send(json)
        receiptsSent++
        Log.d(TAG, "Sent delivery receipt for ${message.messageId}")
    }

    /**
     * Get current cursor (for testing)
     */
    fun getCurrentCursor(): String? = cursor

    /**
     * Reset stats and cursor (for testing)
     */
    fun reset() {
        cursor = null
        fetchCount = 0
        receiptsSent = 0
    }
}

/**
 * Interface for sending messages (abstracts WsClient)
 */
fun interface MessageSender {
    fun send(json: String): Boolean
}
