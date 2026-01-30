package com.whisper2.app.services.messaging

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.whisper2.app.R
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
import com.whisper2.app.services.attachments.AttachmentService
import com.whisper2.app.ui.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
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
    @ApplicationContext private val context: Context,
    private val wsClient: WsClientImpl,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val contactDao: ContactDao,
    private val cryptoService: CryptoService,
    private val secureStorage: SecureStorage,
    private val attachmentService: AttachmentService,
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
        // Clear any stale typing indicators from previous sessions
        scope.launch {
            conversationDao.clearAllTyping()
            Logger.d("[MessageHandler] Cleared all stale typing indicators")
        }
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
            Constants.MsgType.MESSAGE_DELETED -> {
                handleMessageDeleted(frame.payload)
            }
            Constants.MsgType.MESSAGE_DELIVERED -> {
                handleMessageDelivered(frame.payload)
            }
            Constants.MsgType.PRESENCE_UPDATE -> {
                handlePresenceUpdate(frame.payload)
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

            // Create message entity with attachment metadata
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
                replyTo = msg.replyTo,
                // Store attachment metadata for later download
                attachmentBlobId = msg.attachment?.objectKey,
                attachmentKey = msg.attachment?.fileKeyBox?.ciphertext,  // encrypted file key
                attachmentNonce = msg.attachment?.fileKeyBox?.nonce,     // nonce for decrypting file key
                attachmentMimeType = msg.attachment?.contentType,
                attachmentSize = msg.attachment?.ciphertextSize?.toLong()
            )

            // Store message
            messageDao.insert(message)

            // Update conversation
            updateConversation(msg.from, decryptedContent, contact?.displayName)

            // Emit for UI
            _newMessages.emit(message)

            // Clear typing indicator when message is received (they finished typing)
            conversationDao.setTyping(msg.from, false)
            typingTimeoutJobs[msg.from]?.cancel()
            _typingNotifications.emit(TypingNotificationPayload(msg.from, false))

            // Show notification if app is in background
            showMessageNotification(msg.from, decryptedContent, contact?.displayName)

            // Send delivery receipt
            sendDeliveryReceipt(msg.messageId, msg.from, "delivered")

            // Auto-download attachment from known contacts
            if (msg.attachment != null && contact?.encPublicKey != null) {
                autoDownloadAttachment(msg.messageId, msg.attachment, contact.encPublicKey)
            }

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

            // Sanitize base64: spaces can appear when + is URL-decoded incorrectly
            val sanitizedPubKey = senderPubKeyBase64.replace(" ", "+").trim()
            val senderPubKey = Base64.decode(sanitizedPubKey, Base64.NO_WRAP)
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

    /**
     * Send delivery/read receipt to sender.
     * Respects the sendReadReceipts privacy setting for "read" status.
     * Always sends "delivered" status regardless of setting.
     */
    private fun sendDeliveryReceipt(messageId: String, from: String, status: String) {
        scope.launch {
            try {
                // For "read" status, check privacy setting
                if (status == Constants.DeliveryStatus.READ && !secureStorage.sendReadReceipts) {
                    Logger.d("[MessageHandler] Read receipts disabled in privacy settings")
                    return@launch
                }

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
                Logger.d("[MessageHandler] Sent $status receipt for $messageId")
            } catch (e: Exception) {
                Logger.e("[MessageHandler] Failed to send delivery receipt", e)
            }
        }
    }

    /**
     * Send read receipt for a message.
     * Public method for when user reads a message (e.g., opens chat).
     * Respects the sendReadReceipts privacy setting.
     */
    fun sendReadReceipt(messageId: String, senderId: String) {
        if (!secureStorage.sendReadReceipts) {
            Logger.d("[MessageHandler] Read receipts disabled in privacy settings")
            return
        }
        sendDeliveryReceipt(messageId, senderId, Constants.DeliveryStatus.READ)
    }

    /**
     * Auto-download attachment from known contact.
     */
    private fun autoDownloadAttachment(messageId: String, attachment: AttachmentPointer, senderPubKeyBase64: String) {
        scope.launch {
            try {
                val senderPubKey = Base64.decode(senderPubKeyBase64.replace(" ", "+").trim(), Base64.NO_WRAP)

                // Download and decrypt
                val decryptedContent = attachmentService.downloadAttachment(attachment, senderPubKey)

                // Save to file
                val extension = when {
                    attachment.contentType.startsWith("audio/") -> "m4a"
                    attachment.contentType.startsWith("image/jpeg") -> "jpg"
                    attachment.contentType.startsWith("image/png") -> "png"
                    attachment.contentType.startsWith("image/gif") -> "gif"
                    attachment.contentType.startsWith("image/webp") -> "webp"
                    attachment.contentType.startsWith("video/") -> "mp4"
                    else -> "bin"
                }
                val savedFile = attachmentService.saveToFile(decryptedContent, "${messageId}.$extension")

                // Update message with local path
                messageDao.updateAttachmentLocalPath(messageId, savedFile.absolutePath)
                Logger.i("[MessageHandler] Auto-downloaded attachment: ${savedFile.absolutePath}")
            } catch (e: Exception) {
                Logger.e("[MessageHandler] Auto-download failed for $messageId", e)
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

    // Track typing timeout jobs per user
    private val typingTimeoutJobs = mutableMapOf<String, Job>()

    /**
     * Handle typing notification from another user.
     */
    private suspend fun handleTypingNotification(payload: JsonElement) {
        try {
            val notification = gson.fromJson(payload, TypingNotificationPayload::class.java)
            Logger.d("[MessageHandler] Typing notification from ${notification.from}: ${notification.isTyping}")

            // Update database so ChatsListScreen shows typing indicator
            conversationDao.setTyping(notification.from, notification.isTyping)

            // Cancel any existing timeout for this user
            typingTimeoutJobs[notification.from]?.cancel()

            // If user is typing, set a timeout to auto-clear after 5 seconds
            if (notification.isTyping) {
                typingTimeoutJobs[notification.from] = scope.launch {
                    delay(5000)
                    conversationDao.setTyping(notification.from, false)
                    // Also emit to clear in ChatViewModel
                    _typingNotifications.emit(TypingNotificationPayload(notification.from, false))
                    Logger.d("[MessageHandler] Auto-cleared typing for ${notification.from}")
                }
            }

            // Emit for ChatScreen UI
            _typingNotifications.emit(notification)
        } catch (e: Exception) {
            Logger.e("[MessageHandler] Failed to handle typing notification", e)
        }
    }

    /**
     * Handle message deleted notification from server.
     * Server sends this when someone deletes a message for everyone.
     */
    private suspend fun handleMessageDeleted(payload: JsonElement) {
        try {
            val data = gson.fromJson(payload, MessageDeletedPayload::class.java)
            Logger.d("[MessageHandler] Message deleted: ${data.messageId} by ${data.deletedBy}")

            if (data.deleteForEveryone) {
                // Delete message from local database
                messageDao.deleteById(data.messageId)
                Logger.d("[MessageHandler] Deleted message ${data.messageId} from database")
            }
        } catch (e: Exception) {
            Logger.e("[MessageHandler] Failed to handle message deleted", e)
        }
    }

    /**
     * Handle message delivered notification from server.
     * Server sends this to sender when recipient confirms delivery.
     */
    private suspend fun handleMessageDelivered(payload: JsonElement) {
        try {
            val data = gson.fromJson(payload, MessageDeliveredPayload::class.java)
            Logger.d("[MessageHandler] Message delivered: ${data.messageId} status=${data.status}")

            // Update message status in database
            messageDao.updateStatus(data.messageId, data.status)
        } catch (e: Exception) {
            Logger.e("[MessageHandler] Failed to handle message delivered", e)
        }
    }

    /**
     * Handle presence update notification from server.
     * Updates contact's online status and last seen timestamp.
     */
    private suspend fun handlePresenceUpdate(payload: JsonElement) {
        try {
            val data = gson.fromJson(payload, PresenceUpdatePayload::class.java)
            Logger.d("[MessageHandler] Presence update: ${data.whisperId} is ${data.status}")

            // Update contact's presence status
            val isOnline = data.status == Constants.PresenceStatus.ONLINE
            contactDao.updatePresence(data.whisperId, isOnline, data.lastSeen)
        } catch (e: Exception) {
            Logger.e("[MessageHandler] Failed to handle presence update", e)
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

    /**
     * Show notification for incoming message.
     * Only shows if app is in background or screen is locked.
     */
    private fun showMessageNotification(senderId: String, content: String, senderName: String?) {
        try {
            // Check if app is in foreground - skip notification if user is actively using the app
            val isAppInForeground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(
                androidx.lifecycle.Lifecycle.State.RESUMED
            )

            if (isAppInForeground) {
                Logger.d("[MessageHandler] App in foreground, skipping notification")
                return
            }

            val displayName = senderName ?: senderId.takeLast(8)
            val preview = if (content.startsWith("[")) "New message" else content.take(100)

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("conversationId", senderId)
            }

            val requestCode = System.currentTimeMillis().toInt()
            val pendingIntent = PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Full screen intent for lock screen
            val fullScreenIntent = PendingIntent.getActivity(
                context,
                requestCode + 1,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, "messages")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(displayName)
                .setContentText(preview)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setFullScreenIntent(fullScreenIntent, true)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .build()

            val notificationId = 1001 + (senderId.hashCode() % 1000)
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(notificationId, notification)

            Logger.i("[MessageHandler] Notification posted for message from $displayName")
        } catch (e: Exception) {
            Logger.e("[MessageHandler] Failed to show notification", e)
        }
    }
}
