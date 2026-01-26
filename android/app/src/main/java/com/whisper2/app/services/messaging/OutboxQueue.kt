package com.whisper2.app.services.messaging

import android.util.Log
import com.whisper2.app.core.Constants
import com.whisper2.app.core.utils.Base64Strict
import com.whisper2.app.crypto.CanonicalSigning
import com.whisper2.app.crypto.NaClBox
import com.whisper2.app.crypto.Signatures
import com.whisper2.app.network.ws.WsErrorCodes
import com.whisper2.app.network.ws.WsMessageTypes
import com.whisper2.app.network.ws.WsParser
import com.whisper2.app.storage.db.entities.MessageStatus
import com.whisper2.app.storage.db.entities.MessageType
import com.whisper2.app.storage.db.entities.OutboxStatus
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.min
import kotlin.math.pow

/**
 * Outbox item representing a queued outbound message
 */
data class OutboxItem(
    val id: String = UUID.randomUUID().toString(),
    val messageId: String,
    val requestId: String,
    val recipientId: String,
    val payload: String, // JSON payload ready to send
    val createdAt: Long = System.currentTimeMillis(),
    var status: String = OutboxStatus.QUEUED,
    var attempts: Int = 0,
    var lastAttemptAt: Long? = null,
    var nextRetryAt: Long? = null,
    var failedCode: String? = null,
    var failedMessage: String? = null
)

/**
 * Send message payload for WS (matches server protocol.ts SendMessagePayload)
 */
data class SendMessagePayload(
    val protocolVersion: Int,
    val cryptoVersion: Int,
    val sessionToken: String,
    val messageId: String,
    val from: String,
    val to: String,
    val msgType: String,
    val timestamp: Long,
    val nonce: String,
    val ciphertext: String,
    val sig: String,
    val replyTo: String? = null,
    val reactions: Map<String, List<String>>? = null,  // emoji -> whisperId[]
    val attachment: com.whisper2.app.network.api.AttachmentPointer? = null
)

/**
 * Provider for my signing private key
 */
fun interface MySignPrivateKeyProvider {
    fun get(): ByteArray?
}

/**
 * Provider for my encryption private key (for outbound)
 */
fun interface MyEncPrivateKeyProviderOutbox {
    fun get(): ByteArray?
}

/**
 * WS send callback
 */
fun interface WsSender {
    fun send(json: String): Boolean
}

/**
 * Encryptor interface for testability
 */
fun interface MessageEncryptor {
    /** Encrypt plaintext, return (nonce, ciphertext) pair */
    fun encrypt(plaintext: ByteArray, recipientPublicKey: ByteArray, senderPrivateKey: ByteArray): Pair<ByteArray, ByteArray>
}

/**
 * Signer interface for testability
 */
fun interface MessageSigner {
    /** Sign message with private key, return 64-byte signature */
    fun sign(message: ByteArray, privateKey: ByteArray): ByteArray
}

/**
 * Message status updater interface
 */
fun interface MessageStatusUpdater {
    fun updateStatus(messageId: String, status: String)
}

/**
 * Auth failure handler (for UNAUTHORIZED)
 */
fun interface AuthFailureHandler {
    fun onAuthFailure(reason: String)
}

/**
 * Time provider for testability
 */
fun interface TimeProvider {
    fun now(): Long
}

/**
 * Default time provider
 */
object DefaultTimeProvider : TimeProvider {
    override fun now(): Long = System.currentTimeMillis()
}

/**
 * Default encryptor using NaClBox.seal
 */
object DefaultMessageEncryptor : MessageEncryptor {
    override fun encrypt(plaintext: ByteArray, recipientPublicKey: ByteArray, senderPrivateKey: ByteArray): Pair<ByteArray, ByteArray> {
        return NaClBox.seal(plaintext, recipientPublicKey, senderPrivateKey)
    }
}

/**
 * Default signer using Signatures.sign
 */
object DefaultMessageSigner : MessageSigner {
    override fun sign(message: ByteArray, privateKey: ByteArray): ByteArray {
        return Signatures.sign(message, privateKey)
    }
}

/**
 * Retry policy with exponential backoff
 */
data class RetryPolicy(
    val baseDelayMs: Long = 1000L,        // 1 second
    val maxDelayMs: Long = 60000L,        // 1 minute max
    val jitterRatio: Double = 0.1,        // 10% jitter
    val maxAttempts: Int = 10             // Max 10 attempts
)

/**
 * Outbox Queue
 *
 * Handles outbound message encryption, signing, and queuing with state machine.
 *
 * State Machine:
 * - queued: Waiting in queue
 * - sending: Currently being sent
 * - sent: Successfully sent (removed from queue)
 * - failed: Permanently failed (no retry)
 *
 * Flow:
 * 1. enqueue(plaintext, recipientId) → encrypt + sign → status=queued
 * 2. processQueue() → status=sending + WS send
 * 3. On message_accepted → status=sent, remove from queue
 * 4. On permanent error → status=failed
 * 5. On transient error → status=queued + nextRetryAt set
 */
class OutboxQueue(
    private val myWhisperIdProvider: () -> String?,
    private val sessionTokenProvider: () -> String?,
    private val mySignPrivateKeyProvider: MySignPrivateKeyProvider,
    private val myEncPrivateKeyProvider: MyEncPrivateKeyProviderOutbox,
    private val peerKeyProvider: PeerKeyProvider,
    private val wsSender: WsSender,
    private val messageEncryptor: MessageEncryptor = DefaultMessageEncryptor,
    private val messageSigner: MessageSigner = DefaultMessageSigner,
    private val messageStatusUpdater: MessageStatusUpdater? = null,
    private val authFailureHandler: AuthFailureHandler? = null,
    private val timeProvider: TimeProvider = DefaultTimeProvider,
    private val retryPolicy: RetryPolicy = RetryPolicy()
) {
    companion object {
        private const val TAG = "OutboxQueue"
    }

    private val queue = ConcurrentLinkedQueue<OutboxItem>()

    // Request ID to OutboxItem mapping for correlating responses
    private val pendingByRequestId = ConcurrentHashMap<String, OutboxItem>()

    // MessageId to OutboxItem mapping
    private val pendingByMessageId = ConcurrentHashMap<String, OutboxItem>()

    // Track if currently sending (single-flight)
    @Volatile
    private var currentlySending: OutboxItem? = null

    // Pause flag (for UNAUTHORIZED)
    @Volatile
    var isPaused: Boolean = false
        private set

    // Stats for testing
    var enqueuedCount = 0
        private set
    var sendAttempts = 0
        private set
    var lastPayload: String? = null
        private set

    /**
     * Enqueue a text message for sending
     *
     * @param plaintext Message text
     * @param recipientId Recipient's WhisperID
     * @param replyTo Optional message ID being replied to
     * @return Message ID
     */
    fun enqueueTextMessage(
        plaintext: String,
        recipientId: String,
        replyTo: String? = null
    ): String? {
        return enqueueMessage(
            plaintext = plaintext.toByteArray(Charsets.UTF_8),
            recipientId = recipientId,
            msgType = MessageType.TEXT,
            replyTo = replyTo
        )
    }

    /**
     * Enqueue a message for sending
     *
     * @param plaintext Plaintext bytes
     * @param recipientId Recipient's WhisperID
     * @param msgType Message type
     * @param replyTo Optional message ID being replied to
     * @return Message ID or null if failed
     */
    fun enqueueMessage(
        plaintext: ByteArray,
        recipientId: String,
        msgType: String,
        replyTo: String? = null
    ): String? {
        val myWhisperId = myWhisperIdProvider()
        val sessionToken = sessionTokenProvider()
        val signPrivateKey = mySignPrivateKeyProvider.get()
        val encPrivateKey = myEncPrivateKeyProvider.get()

        if (myWhisperId == null || sessionToken == null || signPrivateKey == null || encPrivateKey == null) {
            Log.w(TAG, "Missing credentials for sending message")
            return null
        }

        // Get recipient's encryption public key
        val recipientEncPublicKey = peerKeyProvider.getEncPublicKey(recipientId)
        if (recipientEncPublicKey == null) {
            Log.w(TAG, "Unknown recipient: $recipientId")
            return null
        }

        // Generate message ID and request ID
        val messageId = UUID.randomUUID().toString()
        val requestId = UUID.randomUUID().toString()
        val timestamp = timeProvider.now()

        // Check for duplicate messageId
        if (pendingByMessageId.containsKey(messageId)) {
            Log.w(TAG, "Duplicate messageId: $messageId")
            return null
        }

        // Encrypt
        val (nonce, ciphertext) = messageEncryptor.encrypt(
            plaintext = plaintext,
            recipientPublicKey = recipientEncPublicKey,
            senderPrivateKey = encPrivateKey
        )

        val nonceB64 = Base64Strict.encode(nonce)
        val ciphertextB64 = Base64Strict.encode(ciphertext)

        // Build canonical string and sign
        val canonicalHash = CanonicalSigning.buildAndHashCanonical(
            version = "v1",
            messageType = CanonicalSigning.MESSAGE_TYPE_SEND,
            messageId = messageId,
            from = myWhisperId,
            toOrGroupId = recipientId,
            timestamp = timestamp,
            nonceB64 = nonceB64,
            ciphertextB64 = ciphertextB64
        )

        val signature = messageSigner.sign(canonicalHash, signPrivateKey)
        val sigB64 = Base64Strict.encode(signature)

        // Build payload
        val payload = SendMessagePayload(
            protocolVersion = Constants.PROTOCOL_VERSION,
            cryptoVersion = Constants.CRYPTO_VERSION,
            sessionToken = sessionToken,
            messageId = messageId,
            from = myWhisperId,
            to = recipientId,
            msgType = msgType,
            timestamp = timestamp,
            nonce = nonceB64,
            ciphertext = ciphertextB64,
            sig = sigB64,
            replyTo = replyTo
        )

        // Create WS envelope JSON with requestId
        val json = WsParser.createEnvelope(
            type = WsMessageTypes.SEND_MESSAGE,
            payload = payload,
            requestId = requestId
        )

        // Queue the item
        val item = OutboxItem(
            messageId = messageId,
            requestId = requestId,
            recipientId = recipientId,
            payload = json,
            status = OutboxStatus.QUEUED
        )

        queue.add(item)
        pendingByRequestId[requestId] = item
        pendingByMessageId[messageId] = item
        enqueuedCount++
        lastPayload = json

        // Update MessageEntity status to queued
        messageStatusUpdater?.updateStatus(messageId, MessageStatus.PENDING)

        Log.d(TAG, "Enqueued message $messageId to $recipientId (requestId=$requestId)")

        // Attempt immediate send
        processQueue()

        return messageId
    }

    /**
     * Process queue and attempt to send pending messages (FIFO, single-flight)
     */
    fun processQueue() {
        if (isPaused) {
            Log.d(TAG, "Queue paused, skipping processQueue")
            return
        }

        // Single-flight: only one message sending at a time
        if (currentlySending != null) {
            Log.d(TAG, "Already sending, skipping")
            return
        }

        val now = timeProvider.now()

        // Find next item ready to send (FIFO order)
        val item = queue.find { it.status == OutboxStatus.QUEUED && (it.nextRetryAt == null || it.nextRetryAt!! <= now) }
            ?: return

        attemptSend(item)
    }

    private fun attemptSend(item: OutboxItem) {
        if (isPaused) return

        item.status = OutboxStatus.SENDING
        item.attempts++
        item.lastAttemptAt = timeProvider.now()
        currentlySending = item
        sendAttempts++

        // Update MessageEntity status to sending
        messageStatusUpdater?.updateStatus(item.messageId, MessageStatus.PENDING)

        val success = wsSender.send(item.payload)
        if (success) {
            Log.d(TAG, "Sent message ${item.messageId}, waiting for ACK")
            // Keep in sending state until we get ACK or error
        } else {
            Log.w(TAG, "Failed to send message ${item.messageId}, scheduling retry")
            // Transient failure (WS send failed)
            handleTransientError(item)
        }
    }

    /**
     * Handle message_accepted from server
     */
    fun onMessageAccepted(messageId: String) {
        val item = pendingByMessageId[messageId]
        if (item == null) {
            Log.w(TAG, "Received ACK for unknown messageId: $messageId")
            return
        }

        // Already sent? Ignore duplicate ACK
        if (item.status == OutboxStatus.SENT) {
            Log.d(TAG, "Duplicate ACK for $messageId, ignoring")
            return
        }

        item.status = OutboxStatus.SENT
        Log.d(TAG, "Message accepted: $messageId")

        // Update MessageEntity status
        messageStatusUpdater?.updateStatus(messageId, MessageStatus.SENT)

        // Clean up
        removeFromQueue(item)

        // Continue processing
        continueProcessing()
    }

    /**
     * Handle error response from server
     */
    fun onError(requestId: String, code: String, message: String) {
        val item = pendingByRequestId[requestId]
        if (item == null) {
            Log.w(TAG, "Received error for unknown requestId: $requestId")
            return
        }

        if (code == WsErrorCodes.UNAUTHORIZED) {
            // Special case: auth failure
            Log.e(TAG, "UNAUTHORIZED error, pausing queue and triggering auth failure")
            item.status = OutboxStatus.FAILED
            item.failedCode = code
            item.failedMessage = message
            isPaused = true
            messageStatusUpdater?.updateStatus(item.messageId, MessageStatus.FAILED)
            authFailureHandler?.onAuthFailure("UNAUTHORIZED: $message")
            currentlySending = null
            return
        }

        if (WsErrorCodes.isPermanent(code)) {
            // Permanent error - no retry
            Log.e(TAG, "Permanent error for ${item.messageId}: $code - $message")
            item.status = OutboxStatus.FAILED
            item.failedCode = code
            item.failedMessage = message
            messageStatusUpdater?.updateStatus(item.messageId, MessageStatus.FAILED)
            removeFromQueue(item)
        } else {
            // Transient error - schedule retry
            Log.w(TAG, "Transient error for ${item.messageId}: $code - $message")
            handleTransientError(item)
        }

        continueProcessing()
    }

    /**
     * Handle transient error (network drop, timeout, etc.)
     */
    fun handleTransientError(item: OutboxItem) {
        if (item.attempts >= retryPolicy.maxAttempts) {
            // Max attempts reached, mark as failed
            Log.e(TAG, "Max attempts reached for ${item.messageId}")
            item.status = OutboxStatus.FAILED
            item.failedCode = "MAX_ATTEMPTS"
            item.failedMessage = "Max retry attempts (${retryPolicy.maxAttempts}) reached"
            item.nextRetryAt = null // Clear retry time for failed items
            messageStatusUpdater?.updateStatus(item.messageId, MessageStatus.FAILED)
            removeFromQueue(item)
            currentlySending = null
            return
        }

        // Calculate next retry time with exponential backoff
        val delay = calculateRetryDelay(item.attempts)
        item.status = OutboxStatus.QUEUED
        item.nextRetryAt = timeProvider.now() + delay

        Log.d(TAG, "Scheduled retry for ${item.messageId} in ${delay}ms (attempt ${item.attempts})")

        currentlySending = null
    }

    /**
     * Handle WS disconnect (mark all sending as queued for retry)
     */
    fun onDisconnect() {
        val sending = currentlySending
        if (sending != null && sending.status == OutboxStatus.SENDING) {
            handleTransientError(sending)
        }
        currentlySending = null
    }

    /**
     * Resume queue after reconnect
     */
    fun resume() {
        isPaused = false
        currentlySending = null
        processQueue()
    }

    /**
     * Pause queue (for UNAUTHORIZED)
     */
    fun pause() {
        isPaused = true
    }

    private fun continueProcessing() {
        currentlySending = null
        processQueue()
    }

    private fun removeFromQueue(item: OutboxItem) {
        queue.remove(item)
        pendingByRequestId.remove(item.requestId)
        // Keep in pendingByMessageId for duplicate ACK detection
    }

    private fun calculateRetryDelay(attempt: Int): Long {
        // Exponential backoff: base * 2^(attempt-1)
        val exponentialDelay = retryPolicy.baseDelayMs * 2.0.pow((attempt - 1).toDouble())
        val cappedDelay = min(exponentialDelay.toLong(), retryPolicy.maxDelayMs)

        // Add jitter (deterministic for testing if needed)
        val jitter = (cappedDelay * retryPolicy.jitterRatio).toLong()
        return cappedDelay + jitter
    }

    /**
     * Get item by messageId (for testing)
     */
    fun getByMessageId(messageId: String): OutboxItem? = pendingByMessageId[messageId]

    /**
     * Get item by requestId (for testing)
     */
    fun getByRequestId(requestId: String): OutboxItem? = pendingByRequestId[requestId]

    /**
     * Get queue size
     */
    fun size(): Int = queue.size

    /**
     * Get queued count (status=queued)
     */
    fun queuedCount(): Int = queue.count { it.status == OutboxStatus.QUEUED }

    /**
     * Get sending count (status=sending)
     */
    fun sendingCount(): Int = queue.count { it.status == OutboxStatus.SENDING }

    /**
     * Get failed items
     */
    fun failedItems(): List<OutboxItem> = pendingByMessageId.values.filter { it.status == OutboxStatus.FAILED }

    /**
     * Clear queue (for testing)
     */
    fun clear() {
        queue.clear()
        pendingByRequestId.clear()
        pendingByMessageId.clear()
        currentlySending = null
        enqueuedCount = 0
        sendAttempts = 0
        lastPayload = null
        isPaused = false
    }

    /**
     * Get all items (for testing)
     */
    fun items(): List<OutboxItem> = queue.toList()
}
