package com.whisper2.app.ui.viewmodels

import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whisper2.app.core.Logger
import com.whisper2.app.data.local.db.dao.ContactDao
import com.whisper2.app.data.local.db.dao.ConversationDao
import com.whisper2.app.data.local.db.dao.MessageDao
import com.whisper2.app.data.local.db.entities.MessageEntity
import com.whisper2.app.data.network.ws.AttachmentPointer
import com.whisper2.app.data.network.ws.FileKeyBox
import com.whisper2.app.services.attachments.AttachmentService
import com.whisper2.app.services.messaging.MessageHandler
import com.whisper2.app.services.messaging.MessagingService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val contactDao: ContactDao,
    private val messagingService: MessagingService,
    private val messageHandler: MessageHandler,
    private val attachmentService: AttachmentService
) : ViewModel() {

    private val _peerId = MutableStateFlow("")
    private val _peerIsTyping = MutableStateFlow(false)
    private var typingTimeoutJob: Job? = null

    val messages: StateFlow<List<MessageEntity>> = _peerId
        .filter { it.isNotEmpty() }
        .flatMapLatest { messageDao.getMessagesForConversation(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val contactName: StateFlow<String> = _peerId
        .filter { it.isNotEmpty() }
        .flatMapLatest { peerId ->
            contactDao.getContactByWhisperId(peerId).map { it?.displayName ?: peerId }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val canSendMessages: StateFlow<Boolean> = _peerId
        .filter { it.isNotEmpty() }
        .flatMapLatest { peerId ->
            contactDao.getContactByWhisperId(peerId).map { it?.encPublicKey != null }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val peerIsTyping: StateFlow<Boolean> = _peerIsTyping.asStateFlow()

    init {
        // Listen for typing notifications
        viewModelScope.launch {
            messageHandler.typingNotifications.collect { notification ->
                if (notification.from == _peerId.value) {
                    _peerIsTyping.value = notification.isTyping
                    // Auto-clear typing indicator after 5 seconds
                    if (notification.isTyping) {
                        typingTimeoutJob?.cancel()
                        typingTimeoutJob = viewModelScope.launch {
                            delay(5000)
                            _peerIsTyping.value = false
                        }
                    }
                }
            }
        }
    }

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun loadConversation(peerId: String) {
        // Reset typing state when entering a new conversation
        typingTimeoutJob?.cancel()
        _peerIsTyping.value = false

        _peerId.value = peerId
        viewModelScope.launch {
            conversationDao.markAsRead(peerId)
        }
    }

    fun sendMessage(content: String) {
        if (content.isBlank() || _peerId.value.isEmpty()) return

        viewModelScope.launch {
            try {
                messagingService.sendTextMessage(_peerId.value, content)
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to send message"
            }
        }
    }

    fun sendVoiceMessage(audioPath: String, duration: Long) {
        viewModelScope.launch {
            try {
                messagingService.sendAudioMessage(_peerId.value, audioPath, duration)
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to send voice message"
            }
        }
    }

    fun sendLocationMessage(latitude: Double, longitude: Double) {
        viewModelScope.launch {
            try {
                messagingService.sendLocationMessage(_peerId.value, latitude, longitude)
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to send location"
            }
        }
    }

    fun sendAttachment(uri: String) {
        viewModelScope.launch {
            try {
                messagingService.sendAttachment(_peerId.value, uri)
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to send attachment"
            }
        }
    }

    fun deleteMessage(messageId: String, forEveryone: Boolean) {
        viewModelScope.launch {
            try {
                if (forEveryone) {
                    messagingService.deleteMessageForEveryone(_peerId.value, messageId)
                }
                messageDao.deleteById(messageId)
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to delete message"
            }
        }
    }

    fun onTyping() {
        viewModelScope.launch {
            messagingService.sendTypingIndicator(_peerId.value)
        }
    }

    fun clearError() {
        _error.value = null
    }

    // Track which messages are downloading
    private val _downloadingMessages = MutableStateFlow<Set<String>>(emptySet())
    val downloadingMessages: StateFlow<Set<String>> = _downloadingMessages.asStateFlow()

    /**
     * Download attachment for a message.
     * Decrypts and saves the file, then updates the message with local path.
     */
    fun downloadAttachment(messageId: String) {
        viewModelScope.launch {
            try {
                val message = messageDao.getMessageById(messageId) ?: run {
                    Logger.e("[ChatViewModel] Message not found: $messageId")
                    _error.value = "Message not found"
                    return@launch
                }

                // Check if already downloading
                if (messageId in _downloadingMessages.value) {
                    return@launch
                }

                // Check if already downloaded
                if (!message.attachmentLocalPath.isNullOrEmpty()) {
                    val file = java.io.File(message.attachmentLocalPath)
                    if (file.exists()) {
                        return@launch
                    }
                }

                // Need attachment metadata
                if (message.attachmentBlobId.isNullOrEmpty()) {
                    Logger.e("[ChatViewModel] No attachment blob ID for message: $messageId (contentType=${message.contentType})")
                    _error.value = "Attachment metadata missing - message was received before update"
                    return@launch
                }

                if (message.attachmentKey.isNullOrEmpty() || message.attachmentNonce.isNullOrEmpty()) {
                    Logger.e("[ChatViewModel] Missing encryption keys for message: $messageId")
                    _error.value = "Attachment encryption keys missing"
                    return@launch
                }

                // Mark as downloading
                _downloadingMessages.value = _downloadingMessages.value + messageId
                Logger.i("[ChatViewModel] Starting download for: $messageId")

                // Get sender's public key
                val senderId = message.from
                val contact = contactDao.getContactById(senderId)
                val senderPubKeyBase64 = contact?.encPublicKey
                if (senderPubKeyBase64 == null) {
                    Logger.e("[ChatViewModel] No public key for sender: $senderId")
                    _error.value = "Cannot decrypt - sender key not found"
                    _downloadingMessages.value = _downloadingMessages.value - messageId
                    return@launch
                }

                val senderPubKey = Base64.decode(
                    senderPubKeyBase64.replace(" ", "+").trim(),
                    Base64.NO_WRAP
                )

                // Build AttachmentPointer from stored metadata
                val attachmentPointer = AttachmentPointer(
                    objectKey = message.attachmentBlobId,
                    contentType = message.attachmentMimeType ?: "application/octet-stream",
                    ciphertextSize = message.attachmentSize?.toInt() ?: 0,
                    fileNonce = "",  // Not used - nonce is in the encrypted data
                    fileKeyBox = FileKeyBox(
                        nonce = message.attachmentNonce ?: "",
                        ciphertext = message.attachmentKey ?: ""
                    )
                )

                // Download and decrypt
                val decryptedContent = attachmentService.downloadAttachment(
                    pointer = attachmentPointer,
                    senderPublicKey = senderPubKey
                )

                // Save to local file
                val extension = when {
                    message.contentType == "voice" || message.contentType == "audio" -> "m4a"
                    message.attachmentMimeType?.startsWith("image/jpeg") == true -> "jpg"
                    message.attachmentMimeType?.startsWith("image/png") == true -> "png"
                    message.attachmentMimeType?.startsWith("image/gif") == true -> "gif"
                    message.attachmentMimeType?.startsWith("image/webp") == true -> "webp"
                    message.attachmentMimeType?.startsWith("video/") == true -> "mp4"
                    else -> "bin"
                }
                val fileName = "${messageId}.$extension"
                val savedFile = attachmentService.saveToFile(decryptedContent, fileName)

                // Update message with local path
                messageDao.updateAttachmentLocalPath(messageId, savedFile.absolutePath)
                Logger.d("[ChatViewModel] Downloaded and saved: ${savedFile.absolutePath}")

            } catch (e: Exception) {
                Logger.e("[ChatViewModel] Download failed for $messageId", e)
                _error.value = "Download failed: ${e.message}"
            } finally {
                _downloadingMessages.value = _downloadingMessages.value - messageId
            }
        }
    }
}
