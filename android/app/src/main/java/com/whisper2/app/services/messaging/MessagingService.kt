package com.whisper2.app.services.messaging

import android.util.Base64
import com.whisper2.app.core.Constants
import com.whisper2.app.core.Logger
import com.whisper2.app.crypto.CryptoService
import com.whisper2.app.data.local.db.dao.ContactDao
import com.whisper2.app.data.local.db.dao.ConversationDao
import com.whisper2.app.data.local.db.dao.MessageDao
import com.whisper2.app.data.local.db.dao.OutboxDao
import com.whisper2.app.data.local.db.entities.ConversationEntity
import com.whisper2.app.data.local.db.entities.MessageEntity
import com.whisper2.app.data.local.db.entities.OutboxEntity
import com.whisper2.app.data.local.prefs.SecureStorage
import com.whisper2.app.data.network.ws.*
import com.whisper2.app.services.attachments.AttachmentService
import kotlinx.coroutines.*
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessagingService @Inject constructor(
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val contactDao: ContactDao,
    private val outboxDao: OutboxDao,
    private val wsClient: WsClientImpl,
    private val cryptoService: CryptoService,
    private val secureStorage: SecureStorage,
    private val attachmentService: AttachmentService
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Send a text message to a peer.
     * Encrypts using NaCl box and sends via WebSocket.
     */
    suspend fun sendTextMessage(peerId: String, content: String) {
        val messageId = UUID.randomUUID().toString()
        val myId = secureStorage.whisperId ?: throw IllegalStateException("Not logged in")

        // Save to local DB immediately (optimistic)
        val message = MessageEntity(
            id = messageId,
            conversationId = peerId,
            from = myId,
            to = peerId,
            contentType = Constants.ContentType.TEXT,
            content = content,
            timestamp = System.currentTimeMillis(),
            status = Constants.MessageStatus.PENDING,
            direction = Constants.Direction.OUTGOING
        )
        messageDao.insert(message)
        updateConversation(peerId, content)

        // Encrypt and send
        sendEncryptedMessage(messageId, peerId, Constants.ContentType.TEXT, content)
    }

    /**
     * Encrypt message and send via WebSocket.
     * Uses NaCl box: box(plaintext, nonce, recipientPubKey, senderPrivKey)
     */
    private suspend fun sendEncryptedMessage(
        messageId: String,
        peerId: String,
        msgType: String,
        content: String,
        replyTo: String? = null,
        attachment: AttachmentPointer? = null
    ) {
        try {
            val myId = secureStorage.whisperId ?: return
            val myPrivKey = secureStorage.encPrivateKey ?: return
            val mySignPrivKey = secureStorage.signPrivateKey ?: return
            val sessionToken = secureStorage.sessionToken ?: return

            // Get recipient's public key
            val contact = contactDao.getContactById(peerId)
            val recipientPubKeyBase64 = contact?.encPublicKey

            if (recipientPubKeyBase64 == null) {
                Logger.e("[MessagingService] No public key for recipient $peerId")
                messageDao.updateStatus(messageId, Constants.MessageStatus.FAILED)
                return
            }

            val recipientPubKey = Base64.decode(recipientPubKeyBase64, Base64.NO_WRAP)
            val timestamp = System.currentTimeMillis()

            // Generate nonce (24 bytes)
            val nonce = cryptoService.generateNonce()

            // Encrypt: box(plaintext, nonce, recipientPubKey, senderPrivKey)
            val plaintext = content.toByteArray(Charsets.UTF_8)
            val ciphertext = cryptoService.boxSeal(plaintext, nonce, recipientPubKey, myPrivKey)

            // Sign the message for authenticity
            // Note: signature uses frame type "send_message", not content type
            val signature = cryptoService.signMessage(
                messageType = Constants.MsgType.SEND_MESSAGE,
                messageId = messageId,
                from = myId,
                toOrGroupId = peerId,
                timestamp = timestamp,
                nonce = nonce,
                ciphertext = ciphertext,
                privateKey = mySignPrivKey
            )

            // Create payload
            val payload = SendMessagePayload(
                sessionToken = sessionToken,
                messageId = messageId,
                from = myId,
                to = peerId,
                msgType = msgType,
                timestamp = timestamp,
                nonce = Base64.encodeToString(nonce, Base64.NO_WRAP),
                ciphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
                sig = Base64.encodeToString(signature, Base64.NO_WRAP),
                replyTo = replyTo,
                attachment = attachment
            )

            // Send via WebSocket
            if (wsClient.connectionState.value == WsConnectionState.CONNECTED) {
                wsClient.send(WsFrame(Constants.MsgType.SEND_MESSAGE, payload = payload))
                Logger.d("[MessagingService] Sent message: $messageId")
            } else {
                // Queue for later
                queueMessage(messageId, peerId, msgType, content)
                Logger.d("[MessagingService] Queued message: $messageId (not connected)")
            }

        } catch (e: Exception) {
            Logger.e("[MessagingService] Failed to send message", e)
            messageDao.updateStatus(messageId, Constants.MessageStatus.FAILED)
        }
    }

    suspend fun sendAudioMessage(peerId: String, audioPath: String, duration: Long) {
        val messageId = UUID.randomUUID().toString()
        val myId = secureStorage.whisperId ?: throw IllegalStateException("Not logged in")

        val message = MessageEntity(
            id = messageId,
            conversationId = peerId,
            from = myId,
            to = peerId,
            contentType = Constants.ContentType.AUDIO,
            content = "Voice message",
            timestamp = System.currentTimeMillis(),
            status = Constants.MessageStatus.PENDING,
            direction = Constants.Direction.OUTGOING,
            attachmentLocalPath = audioPath,
            attachmentDuration = duration.toInt()
        )

        messageDao.insert(message)
        updateConversation(peerId, "Voice message")

        try {
            // Get recipient's public key for file encryption
            val contact = contactDao.getContactById(peerId)
            val recipientPubKeyBase64 = contact?.encPublicKey
            if (recipientPubKeyBase64 == null) {
                Logger.e("[MessagingService] No public key for recipient $peerId")
                messageDao.updateStatus(messageId, Constants.MessageStatus.FAILED)
                return
            }
            val recipientPubKey = Base64.decode(recipientPubKeyBase64, Base64.NO_WRAP)

            // Upload encrypted audio file
            val attachmentPointer = attachmentService.uploadAttachment(audioPath, recipientPubKey)
            Logger.d("[MessagingService] Audio uploaded: ${attachmentPointer.objectKey}")

            // Send message with attachment pointer
            sendEncryptedMessage(
                messageId = messageId,
                peerId = peerId,
                msgType = Constants.ContentType.AUDIO,
                content = "Voice message ($duration ms)",
                attachment = attachmentPointer
            )
        } catch (e: Exception) {
            Logger.e("[MessagingService] Failed to send audio message", e)
            messageDao.updateStatus(messageId, Constants.MessageStatus.FAILED)
        }
    }

    suspend fun sendLocationMessage(peerId: String, latitude: Double, longitude: Double) {
        val messageId = UUID.randomUUID().toString()
        val myId = secureStorage.whisperId ?: throw IllegalStateException("Not logged in")

        val content = "$latitude,$longitude"
        val message = MessageEntity(
            id = messageId,
            conversationId = peerId,
            from = myId,
            to = peerId,
            contentType = Constants.ContentType.LOCATION,
            content = content,
            timestamp = System.currentTimeMillis(),
            status = Constants.MessageStatus.PENDING,
            direction = Constants.Direction.OUTGOING,
            locationLatitude = latitude,
            locationLongitude = longitude
        )

        messageDao.insert(message)
        updateConversation(peerId, "Location")
        sendEncryptedMessage(messageId, peerId, Constants.ContentType.LOCATION, content)
    }

    suspend fun sendAttachment(peerId: String, uri: String) {
        val messageId = UUID.randomUUID().toString()
        val myId = secureStorage.whisperId ?: throw IllegalStateException("Not logged in")

        val message = MessageEntity(
            id = messageId,
            conversationId = peerId,
            from = myId,
            to = peerId,
            contentType = Constants.ContentType.FILE,
            content = "File",
            timestamp = System.currentTimeMillis(),
            status = Constants.MessageStatus.PENDING,
            direction = Constants.Direction.OUTGOING,
            attachmentLocalPath = uri
        )

        messageDao.insert(message)
        updateConversation(peerId, "File")

        try {
            // Get recipient's public key for file encryption
            val contact = contactDao.getContactById(peerId)
            val recipientPubKeyBase64 = contact?.encPublicKey
            if (recipientPubKeyBase64 == null) {
                Logger.e("[MessagingService] No public key for recipient $peerId")
                messageDao.updateStatus(messageId, Constants.MessageStatus.FAILED)
                return
            }
            val recipientPubKey = Base64.decode(recipientPubKeyBase64, Base64.NO_WRAP)

            // Upload encrypted file
            val contentUri = android.net.Uri.parse(uri)
            val attachmentPointer = attachmentService.uploadAttachment(contentUri, recipientPubKey)
            Logger.d("[MessagingService] File uploaded: ${attachmentPointer.objectKey}")

            // Send message with attachment pointer
            sendEncryptedMessage(
                messageId = messageId,
                peerId = peerId,
                msgType = Constants.ContentType.FILE,
                content = "File attachment",
                attachment = attachmentPointer
            )
        } catch (e: Exception) {
            Logger.e("[MessagingService] Failed to send attachment", e)
            messageDao.updateStatus(messageId, Constants.MessageStatus.FAILED)
        }
    }

    /**
     * Process queued outbox messages (called after reconnect).
     */
    suspend fun processOutbox() {
        val pending = outboxDao.getPending()
        Logger.d("[MessagingService] Processing ${pending.size} queued messages")

        pending.forEach { outbox ->
            sendEncryptedMessage(
                messageId = outbox.messageId,
                peerId = outbox.to,
                msgType = outbox.msgType,
                content = outbox.encryptedPayload  // This is actually plaintext content
            )
            outboxDao.delete(outbox.messageId)
        }
    }

    private suspend fun updateConversation(peerId: String, preview: String) {
        val existing = conversationDao.getConversationById(peerId)
        if (existing != null) {
            conversationDao.insert(existing.copy(
                lastMessagePreview = preview,
                lastMessageTimestamp = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            ))
        } else {
            val contact = contactDao.getContactById(peerId)
            conversationDao.insert(ConversationEntity(
                peerId = peerId,
                peerNickname = contact?.displayName,
                lastMessagePreview = preview,
                lastMessageTimestamp = System.currentTimeMillis()
            ))
        }
    }

    private suspend fun queueMessage(messageId: String, peerId: String, type: String, content: String) {
        outboxDao.insert(OutboxEntity(
            messageId = messageId,
            to = peerId,
            groupId = null,
            msgType = type,
            encryptedPayload = content,
            createdAt = System.currentTimeMillis(),
            status = Constants.MessageStatus.PENDING
        ))
    }

    /**
     * Send typing indicator to peer.
     */
    suspend fun sendTypingIndicator(peerId: String, isTyping: Boolean = true) {
        try {
            val token = secureStorage.sessionToken ?: return

            if (wsClient.connectionState.value == WsConnectionState.CONNECTED) {
                val payload = TypingPayload(
                    sessionToken = token,
                    to = peerId,
                    isTyping = isTyping
                )
                wsClient.send(WsFrame(
                    type = Constants.MsgType.TYPING,
                    payload = payload
                ))
            }
        } catch (e: Exception) {
            Logger.e("[MessagingService] Failed to send typing indicator", e)
        }
    }

    /**
     * Delete message for everyone.
     * Signs the delete request for authenticity.
     */
    suspend fun deleteMessageForEveryone(peerId: String, messageId: String, forEveryone: Boolean = true) {
        try {
            val myId = secureStorage.whisperId ?: return
            val token = secureStorage.sessionToken ?: return
            val signPrivKey = secureStorage.signPrivateKey ?: return

            val timestamp = System.currentTimeMillis()

            // Sign the delete request
            // Format: v1\ndelete_message\nmessageId\nfrom\nconversationId\ntimestamp\ndeleteForEveryone\n
            val canonical = "v1\n${Constants.MsgType.DELETE_MESSAGE}\n$messageId\n$myId\n$peerId\n$timestamp\n$forEveryone\n"
            val hash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
            val signature = cryptoService.signChallenge(hash, signPrivKey)

            if (wsClient.connectionState.value == WsConnectionState.CONNECTED) {
                val payload = DeleteMessagePayload(
                    sessionToken = token,
                    messageId = messageId,
                    conversationId = peerId,
                    deleteForEveryone = forEveryone,
                    timestamp = timestamp,
                    sig = Base64.encodeToString(signature, Base64.NO_WRAP)
                )
                wsClient.send(WsFrame(
                    type = Constants.MsgType.DELETE_MESSAGE,
                    payload = payload
                ))
                Logger.d("[MessagingService] Sent delete request for $messageId")

                // Delete locally
                if (!forEveryone) {
                    messageDao.deleteById(messageId)
                }
            }
        } catch (e: Exception) {
            Logger.e("[MessagingService] Failed to delete message", e)
        }
    }
}
