package com.whisper2.app.services.messaging

import android.util.Log
import com.whisper2.app.core.utils.Base64Strict
import com.whisper2.app.crypto.CanonicalSigning
import com.whisper2.app.crypto.NaClBox
import com.whisper2.app.crypto.Signatures
import com.whisper2.app.network.ws.PendingMessageItem
import com.whisper2.app.storage.db.dao.ConversationDao
import com.whisper2.app.storage.db.dao.MessageDao
import com.whisper2.app.storage.db.entities.ConversationEntity
import com.whisper2.app.storage.db.entities.ConversationType
import com.whisper2.app.storage.db.entities.MessageEntity
import com.whisper2.app.storage.db.entities.MessageStatus
import com.whisper2.app.storage.db.entities.MessageType

/**
 * Provider for peer public keys
 */
interface PeerKeyProvider {
    /** Get peer's Ed25519 signing public key (32 bytes) */
    fun getSignPublicKey(whisperId: String): ByteArray?

    /** Get peer's X25519 encryption public key (32 bytes) */
    fun getEncPublicKey(whisperId: String): ByteArray?
}

/**
 * Provider for my encryption private key
 */
fun interface MyEncPrivateKeyProvider {
    fun get(): ByteArray?
}

/**
 * Receipt sender callback
 */
fun interface ReceiptSender {
    fun sendDeliveryReceipt(messageId: String, from: String, to: String)
}

/**
 * Signature verifier interface for testability
 */
fun interface SignatureVerifier {
    fun verify(signature: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean
}

/**
 * Decryptor interface for testability
 */
fun interface MessageDecryptor {
    fun decrypt(ciphertext: ByteArray, nonce: ByteArray, senderPublicKey: ByteArray, recipientPrivateKey: ByteArray): ByteArray
}

/**
 * Default signature verifier using Signatures.verify
 */
object DefaultSignatureVerifier : SignatureVerifier {
    override fun verify(signature: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean {
        return Signatures.verify(signature, message, publicKey)
    }
}

/**
 * Default decryptor using NaClBox.open
 */
object DefaultMessageDecryptor : MessageDecryptor {
    override fun decrypt(ciphertext: ByteArray, nonce: ByteArray, senderPublicKey: ByteArray, recipientPrivateKey: ByteArray): ByteArray {
        return NaClBox.open(ciphertext, nonce, senderPublicKey, recipientPrivateKey)
    }
}

/**
 * Inbound message result
 */
sealed class InboundResult {
    data class Success(val message: MessageEntity) : InboundResult()
    data class Rejected(val reason: String) : InboundResult()
    data class Duplicate(val messageId: String) : InboundResult()
}

/**
 * Messaging Service
 *
 * Handles inbound message processing:
 * 1. Base64 decode (nonce, ciphertext, sig)
 * 2. Signature verification
 * 3. Decryption
 * 4. Persistence to Room
 * 5. Conversation update
 * 6. Receipt sending
 */
class MessagingService(
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val myEncPrivateKeyProvider: MyEncPrivateKeyProvider,
    private val peerKeyProvider: PeerKeyProvider,
    private val receiptSender: ReceiptSender,
    private val myWhisperIdProvider: () -> String?,
    private val signatureVerifier: SignatureVerifier = DefaultSignatureVerifier,
    private val messageDecryptor: MessageDecryptor = DefaultMessageDecryptor
) {
    companion object {
        private const val TAG = "MessagingService"
        private const val NONCE_BYTES = 24
        private const val SIG_BYTES = 64
    }

    /**
     * Handle inbound pending message
     * Full pipeline: verify → decrypt → persist → receipt
     */
    fun handleInbound(item: PendingMessageItem): InboundResult {
        Log.d(TAG, "Processing inbound message: ${item.messageId} from ${item.from}")

        // Check for duplicate first
        if (messageDao.exists(item.messageId)) {
            Log.d(TAG, "Duplicate message: ${item.messageId}")
            return InboundResult.Duplicate(item.messageId)
        }

        // Step 1: Base64 decode
        val nonceBytes: ByteArray
        val ciphertextBytes: ByteArray
        val sigBytes: ByteArray

        try {
            nonceBytes = Base64Strict.decode(item.nonce)
            ciphertextBytes = Base64Strict.decode(item.ciphertext)
            sigBytes = Base64Strict.decode(item.sig)
        } catch (e: Exception) {
            Log.w(TAG, "Base64 decode failed for ${item.messageId}: ${e.message}")
            return InboundResult.Rejected("Invalid base64 encoding")
        }

        // Step 2: Validate lengths
        if (nonceBytes.size != NONCE_BYTES) {
            Log.w(TAG, "Invalid nonce length: ${nonceBytes.size}")
            return InboundResult.Rejected("Invalid nonce length: ${nonceBytes.size}")
        }
        if (sigBytes.size != SIG_BYTES) {
            Log.w(TAG, "Invalid sig length: ${sigBytes.size}")
            return InboundResult.Rejected("Invalid sig length: ${sigBytes.size}")
        }
        if (ciphertextBytes.isEmpty()) {
            Log.w(TAG, "Empty ciphertext")
            return InboundResult.Rejected("Empty ciphertext")
        }

        // Step 3: Get sender's signing public key
        val senderSignPublicKey = peerKeyProvider.getSignPublicKey(item.from)
        if (senderSignPublicKey == null) {
            Log.w(TAG, "Unknown sender: ${item.from}")
            return InboundResult.Rejected("Unknown sender")
        }

        // Step 4: Verify signature
        val canonicalHash = CanonicalSigning.buildAndHashCanonical(
            version = "v1",
            messageType = CanonicalSigning.MESSAGE_TYPE_SEND,
            messageId = item.messageId,
            from = item.from,
            toOrGroupId = item.to,
            timestamp = item.timestamp,
            nonceB64 = item.nonce,
            ciphertextB64 = item.ciphertext
        )

        val sigValid = signatureVerifier.verify(sigBytes, canonicalHash, senderSignPublicKey)
        if (!sigValid) {
            Log.w(TAG, "Signature verification failed for ${item.messageId}")
            return InboundResult.Rejected("Signature verification failed")
        }

        Log.d(TAG, "Signature verified for ${item.messageId}")

        // Step 5: Decrypt
        val senderEncPublicKey = peerKeyProvider.getEncPublicKey(item.from)
        if (senderEncPublicKey == null) {
            Log.w(TAG, "No encryption key for sender: ${item.from}")
            return InboundResult.Rejected("No encryption key for sender")
        }

        val myEncPrivateKey = myEncPrivateKeyProvider.get()
        if (myEncPrivateKey == null) {
            Log.w(TAG, "No encryption private key available")
            return InboundResult.Rejected("No encryption private key")
        }

        val plaintext: ByteArray
        try {
            plaintext = messageDecryptor.decrypt(
                ciphertext = ciphertextBytes,
                nonce = nonceBytes,
                senderPublicKey = senderEncPublicKey,
                recipientPrivateKey = myEncPrivateKey
            )
        } catch (e: Exception) {
            Log.w(TAG, "Decryption failed for ${item.messageId}: ${e.message}")
            return InboundResult.Rejected("Decryption failed")
        }

        Log.d(TAG, "Decrypted message ${item.messageId}, plaintext size: ${plaintext.size}")

        // Step 6: Extract text (if text message)
        val text: String? = if (item.msgType == MessageType.TEXT) {
            String(plaintext, Charsets.UTF_8)
        } else {
            null
        }

        // Step 7: Persist to database
        val conversationId = item.from // For direct messages, conversation ID = peer ID
        val entity = MessageEntity(
            messageId = item.messageId,
            conversationId = conversationId,
            from = item.from,
            to = item.to,
            msgType = item.msgType,
            timestamp = item.timestamp,
            nonceB64 = item.nonce,
            ciphertextB64 = item.ciphertext,
            sigB64 = item.sig,
            text = text,
            status = MessageStatus.DELIVERED,
            isOutgoing = false
        )

        val insertResult = messageDao.insert(entity)
        if (insertResult == -1L) {
            // Already exists (race condition)
            Log.d(TAG, "Message already exists (race): ${item.messageId}")
            return InboundResult.Duplicate(item.messageId)
        }

        Log.d(TAG, "Persisted message ${item.messageId}")

        // Step 8: Update conversation
        conversationDao.upsertWithNewMessage(
            conversationId = conversationId,
            type = ConversationType.DIRECT,
            timestamp = item.timestamp,
            preview = text?.take(100),
            incrementUnread = true
        )

        // Step 9: Send delivery receipt
        val myWhisperId = myWhisperIdProvider()
        if (myWhisperId != null) {
            receiptSender.sendDeliveryReceipt(
                messageId = item.messageId,
                from = myWhisperId,  // I'm the recipient sending receipt
                to = item.from       // To the original sender
            )
            Log.d(TAG, "Sent delivery receipt for ${item.messageId}")
        }

        return InboundResult.Success(entity)
    }

    /**
     * Handle incoming delivery receipt (message_delivered from server)
     * Updates outgoing message status: sent → delivered → read
     *
     * Status ordering: pending < sent < delivered < read
     * Status can only move forward, never backward.
     */
    fun handleDeliveryReceipt(messageId: String, status: String, timestamp: Long): Boolean {
        Log.d(TAG, "Processing delivery receipt: $messageId status=$status")

        val message = messageDao.getById(messageId)
        if (message == null) {
            Log.w(TAG, "Unknown message for delivery receipt: $messageId")
            return false
        }

        // Only update if moving forward in status hierarchy
        if (!shouldUpdateStatus(message.status, status)) {
            Log.d(TAG, "Ignoring receipt: current=${message.status} new=$status (not moving forward)")
            return false
        }

        messageDao.updateStatus(messageId, status)
        Log.d(TAG, "Updated message $messageId status to $status")
        return true
    }

    /**
     * Check if status should be updated (only allow forward movement)
     * Status hierarchy: pending < sent < delivered < read
     * failed is terminal
     */
    private fun shouldUpdateStatus(currentStatus: String, newStatus: String): Boolean {
        val statusOrder = mapOf(
            MessageStatus.PENDING to 0,
            MessageStatus.SENT to 1,
            MessageStatus.DELIVERED to 2,
            MessageStatus.READ to 3,
            MessageStatus.FAILED to -1 // Terminal, cannot be changed
        )

        val currentOrder = statusOrder[currentStatus]
        val newOrder = statusOrder[newStatus]

        // If current is failed, don't update
        if (currentOrder == -1) return false

        // If new status is unknown, don't update
        if (newOrder == null) return false

        // Only update if moving forward
        return newOrder > (currentOrder ?: -1)
    }

    /**
     * Get message by ID
     */
    fun getMessage(messageId: String): MessageEntity? {
        return messageDao.getById(messageId)
    }

    /**
     * Get messages for conversation
     */
    fun getMessages(conversationId: String): List<MessageEntity> {
        return messageDao.getByConversation(conversationId)
    }

    /**
     * Get conversation
     */
    fun getConversation(id: String): ConversationEntity? {
        return conversationDao.getById(id)
    }
}
