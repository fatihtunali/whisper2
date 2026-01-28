package com.whisper2.app.services.messaging

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.whisper2.app.core.Constants
import com.whisper2.app.core.Logger
import com.whisper2.app.crypto.CryptoService
import com.whisper2.app.data.local.db.dao.ContactDao
import com.whisper2.app.data.local.db.dao.ConversationDao
import com.whisper2.app.data.local.db.dao.MessageDao
import com.whisper2.app.data.local.db.entities.ConversationEntity
import com.whisper2.app.data.local.db.entities.MessageEntity
import com.whisper2.app.data.local.prefs.SecureStorage
import com.whisper2.app.data.network.ws.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles incoming messages from WebSocket.
 * Decrypts and stores messages, sends delivery receipts.
 */
@Singleton
class MessageHandler @Inject constructor(
    private val wsClient: WsClientImpl,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val contactDao: ContactDao,
    private val cryptoService: CryptoService,
    private val secureStorage: SecureStorage,
    private val gson: Gson
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Emits newly received messages for UI updates
    private val _newMessages = MutableSharedFlow<MessageEntity>(extraBufferCapacity = 100)
    val newMessages: SharedFlow<MessageEntity> = _newMessages.asSharedFlow()

    // Emits typing notifications
    private val _typingNotifications = MutableSharedFlow<TypingNotificationPayload>(extraBufferCapacity = 100)
    val typingNotifications: SharedFlow<TypingNotificationPayload> = _typingNotifications.asSharedFlow()

    init {
        startListening()
    }

    private fun startListening() {
        scope.launch {
            wsClient.messages.collect { frame ->
                handleFrame(frame)
            }
        }
    }

    private suspend fun handleFrame(frame: WsFrame<JsonElement>) {
        when (frame.type) {
            Constants.MsgType.MESSAGE_RECEIVED -> {
                handleMessageReceived(frame.payload)
            }
            Constants.MsgType.MESSAGE_ACCEPTED -> {
                handleMessageAccepted(frame.payload)
            }
            Constants.MsgType.DELIVERY_RECEIPT -> {
                handleDeliveryReceipt(frame.payload)
            }
            Constants.MsgType.PENDING_MESSAGES -> {
                handlePendingMessages(frame.payload)
            }
            Constants.MsgType.TYPING_NOTIFICATION -> {
                handleTypingNotification(frame.payload)
            }
        }
    }

    /**
     * Handle incoming message from another user.
     * Decrypt and store in database.
     */
    private suspend fun handleMessageReceived(payload: JsonElement) {
        try {
            val msg = gson.fromJson(payload, MessageReceivedPayload::class.java)
            Logger.d("[MessageHandler] Received message from ${msg.from}: ${msg.messageId}")

            val myId = secureStorage.whisperId ?: return
            val myPrivateKey = secureStorage.encPrivateKey ?: return

            // Check if sender is blocked
            val contact = contactDao.getContactById(msg.from)
            if (contact?.isBlocked == true) {
                Logger.d("[MessageHandler] Ignoring message from blocked user: ${msg.from}")
                return
            }

            // Decrypt message
            val decryptedContent = decryptMessage(msg, myPrivateKey)

            // Create message entity
            val message = MessageEntity(
                id = msg.messageId,
                conversationId = msg.from,  // For 1:1, conversation ID is the sender
                groupId = msg.groupId,
                from = msg.from,
                to = msg.to,
                contentType = msg.msgType,
                content = decryptedContent,
                timestamp = msg.timestamp,
                status = "delivered",
                direction = Constants.Direction.INCOMING,
                replyTo = msg.replyTo
            )

            // Store message
            messageDao.insert(message)

            // Update conversation
            updateConversation(msg.from, decryptedContent, contact?.displayName)

            // Emit for UI
            _newMessages.emit(message)

            // Send delivery receipt
            sendDeliveryReceipt(msg.messageId, msg.from, "delivered")

            Logger.d("[MessageHandler] Message processed: ${msg.messageId}")

        } catch (e: Exception) {
            Logger.e("[MessageHandler] Failed to process message", e)
        }
    }

    /**
     * Decrypt message using NaCl box open.
     * ciphertext was encrypted with: box(plaintext, nonce, recipientPubKey, senderPrivKey)
     * We decrypt with: box.open(ciphertext, nonce, senderPubKey, recipientPrivKey)
     */
    private suspend fun decryptMessage(msg: MessageReceivedPayload, myPrivateKey: ByteArray): String {
        return try {
            // Get sender's public key - first try from contacts
            var contact = contactDao.getContactById(msg.from)
            var senderPubKeyBase64 = contact?.encPublicKey

            // If no contact, check if sender's keys are in the message payload
            if (senderPubKeyBase64 == null && msg.senderEncPublicKey != null) {
                Logger.d("[MessageHandler] Using sender's public key from message payload")
                senderPubKeyBase64 = msg.senderEncPublicKey

                // Auto-add sender as a contact with provided keys (message request accepted)
                addContactFromMessageRequest(msg)
            }

            if (senderPubKeyBase64 == null) {
                Logger.w("[MessageHandler] No public key for sender ${msg.from}")
                return "[Message Request - Accept to decrypt]"
            }

            val senderPubKey = Base64.decode(senderPubKeyBase64, Base64.NO_WRAP)
            val nonce = Base64.decode(msg.nonce, Base64.NO_WRAP)
            val ciphertext = Base64.decode(msg.ciphertext, Base64.NO_WRAP)

            // Decrypt using NaCl box open
            val plaintext = cryptoService.boxOpen(ciphertext, nonce, senderPubKey, myPrivateKey)

            String(plaintext, Charsets.UTF_8)
        } catch (e: Exception) {
            Logger.e("[MessageHandler] Decryption failed", e)
            "[Decryption failed]"
        }
    }

    /**
     * Add sender as contact from message request payload.
     * This allows accepting message requests without QR code scanning.
     */
    private suspend fun addContactFromMessageRequest(msg: MessageReceivedPayload) {
        try {
            val existingContact = contactDao.getContactById(msg.from)
            if (existingContact != null) {
                // Contact exists, just update public key if needed
                if (existingContact.encPublicKey != msg.senderEncPublicKey) {
                    contactDao.updatePublicKey(msg.from, msg.senderEncPublicKey!!)
                    msg.senderSignPublicKey?.let {
                        contactDao.updateSignPublicKey(msg.from, it)
                    }
                    Logger.d("[MessageHandler] Updated contact keys for ${msg.from}")
                }
                return
            }

            // Create new contact from message request
            val newContact = com.whisper2.app.data.local.db.entities.ContactEntity(
                whisperId = msg.from,
                displayName = msg.from.takeLast(4),  // Use last 4 chars as default name
                encPublicKey = msg.senderEncPublicKey,
                signPublicKey = msg.senderSignPublicKey
            )
            contactDao.insert(newContact)
            Logger.d("[MessageHandler] Added contact from message request: ${msg.from}")
        } catch (e: Exception) {
            Logger.e("[MessageHandler] Failed to add contact from message request", e)
        }
    }

    private suspend fun updateConversation(peerId: String, lastMessage: String, peerNickname: String?) {
        val existing = conversationDao.getConversationById(peerId)
        val preview = if (lastMessage.startsWith("[")) "New message" else lastMessage

        if (existing != null) {
            conversationDao.insert(existing.copy(
                lastMessagePreview = preview,
                lastMessageTimestamp = System.currentTimeMillis(),
                unreadCount = existing.unreadCount + 1,
                updatedAt = System.currentTimeMillis()
            ))
        } else {
            conversationDao.insert(ConversationEntity(
                peerId = peerId,
                peerNickname = peerNickname,
                lastMessagePreview = preview,
                lastMessageTimestamp = System.currentTimeMillis(),
                unreadCount = 1
            ))
        }
    }

    private fun sendDeliveryReceipt(messageId: String, from: String, status: String) {
        scope.launch {
            try {
                val myId = secureStorage.whisperId ?: return@launch
                val token = secureStorage.sessionToken ?: return@launch

                val payload = DeliveryReceiptPayload(
                    sessionToken = token,
                    messageId = messageId,
                    from = myId,
                    to = from,
                    status = status,
                    timestamp = System.currentTimeMillis()
                )
                wsClient.send(WsFrame(Constants.MsgType.DELIVERY_RECEIPT, payload = payload))
                Logger.d("[MessageHandler] Sent delivery receipt for $messageId")
            } catch (e: Exception) {
                Logger.e("[MessageHandler] Failed to send delivery receipt", e)
            }
        }
    }

    /**
     * Handle acknowledgment that our sent message was accepted by server.
     */
    private suspend fun handleMessageAccepted(payload: JsonElement) {
        try {
            val data = gson.fromJson(payload, MessageAcceptedPayload::class.java)
            Logger.d("[MessageHandler] Message accepted: ${data.messageId}")

            // Update message status to "sent"
            messageDao.updateStatus(data.messageId, "sent")
        } catch (e: Exception) {
            Logger.e("[MessageHandler] Failed to handle message accepted", e)
        }
    }

    /**
     * Handle delivery/read receipts from recipients.
     */
    private suspend fun handleDeliveryReceipt(payload: JsonElement) {
        try {
            val receipt = gson.fromJson(payload, DeliveryReceiptPayload::class.java)
            Logger.d("[MessageHandler] Receipt for ${receipt.messageId}: ${receipt.status}")

            // Update message status
            messageDao.updateStatus(receipt.messageId, receipt.status)
        } catch (e: Exception) {
            Logger.e("[MessageHandler] Failed to handle delivery receipt", e)
        }
    }

    /**
     * Handle pending messages fetched from server (offline messages).
     */
    private suspend fun handlePendingMessages(payload: JsonElement) {
        try {
            val data = gson.fromJson(payload, PendingMessagesPayload::class.java)
            Logger.d("[MessageHandler] Received ${data.messages.size} pending messages")

            data.messages.forEach { msg ->
                handleMessageReceived(gson.toJsonTree(msg))
            }
        } catch (e: Exception) {
            Logger.e("[MessageHandler] Failed to handle pending messages", e)
        }
    }

    /**
     * Handle typing notification from another user.
     */
    private suspend fun handleTypingNotification(payload: JsonElement) {
        try {
            val notification = gson.fromJson(payload, TypingNotificationPayload::class.java)
            Logger.d("[MessageHandler] Typing notification from ${notification.from}: ${notification.isTyping}")
            _typingNotifications.emit(notification)
        } catch (e: Exception) {
            Logger.e("[MessageHandler] Failed to handle typing notification", e)
        }
    }

    /**
     * Request pending messages from server (called after reconnect).
     */
    fun fetchPendingMessages() {
        scope.launch {
            try {
                val token = secureStorage.sessionToken ?: return@launch
                val payload = FetchPendingPayload(
                    sessionToken = token,
                    cursor = null,
                    limit = null
                )
                wsClient.send(WsFrame(
                    type = Constants.MsgType.FETCH_PENDING,
                    payload = payload
                ))
                Logger.d("[MessageHandler] Requested pending messages")
            } catch (e: Exception) {
                Logger.e("[MessageHandler] Failed to fetch pending messages", e)
            }
        }
    }
}
