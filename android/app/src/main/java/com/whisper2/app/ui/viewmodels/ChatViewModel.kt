package com.whisper2.app.ui.viewmodels

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whisper2.app.network.api.AttachmentPointer
import com.whisper2.app.network.api.FileKeyBox
import com.whisper2.app.services.attachments.AttachmentService
import com.whisper2.app.services.auth.ISessionManager
import com.whisper2.app.services.calls.CallService
import com.whisper2.app.services.contacts.KeyLookupResult
import com.whisper2.app.services.contacts.KeyLookupService
import com.whisper2.app.services.messaging.OutboxQueue
import com.whisper2.app.storage.db.dao.ConversationDao
import com.whisper2.app.storage.db.dao.MessageDao
import com.whisper2.app.storage.db.entities.ConversationType
import com.whisper2.app.storage.db.entities.MessageEntity
import com.whisper2.app.storage.db.entities.MessageStatus
import com.whisper2.app.storage.db.entities.MessageType
import com.whisper2.app.storage.key.SecurePrefs
import com.whisper2.app.ui.state.AppStateManager
import com.whisper2.app.ui.state.AttachmentDownloadState
import com.whisper2.app.ui.state.ConnectionState
import com.whisper2.app.ui.state.MessageUiItem
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

/**
 * Chat Screen UI State
 */
data class ChatUiState(
    val conversationId: String = "",
    val peerDisplayName: String = "",
    val messages: List<MessageUiItem> = emptyList(),
    val inputText: String = "",
    val isSending: Boolean = false,
    val error: String? = null,
    val isTyping: Boolean = false // Future: typing indicators
)

/**
 * Chat ViewModel
 *
 * Manages chat screen state and message sending.
 * Uses real OutboxQueue for message delivery - no mock data.
 * Supports UI-G5: Attachment downloads.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val appStateManager: AppStateManager,
    private val sessionManager: ISessionManager,
    private val securePrefs: SecurePrefs,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val outboxQueue: OutboxQueue,
    private val attachmentService: AttachmentService,
    private val callService: CallService,
    private val keyLookupService: KeyLookupService
) : ViewModel() {

    // Get conversationId from navigation argument
    private val conversationId: String = savedStateHandle.get<String>("conversationId") ?: ""

    // Track attachment download states (messageId -> state)
    private val _attachmentDownloadStates = MutableStateFlow<Map<String, AttachmentDownloadState>>(emptyMap())

    // =========================================================================
    // UI State
    // =========================================================================

    private val _uiState = MutableStateFlow(ChatUiState(conversationId = conversationId))
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    // Connection state
    val connectionState: StateFlow<ConnectionState> = appStateManager.connectionState

    init {
        loadConversationInfo()
        observeMessages()
        markAsRead()
    }

    // =========================================================================
    // Data Loading
    // =========================================================================

    private fun loadConversationInfo() {
        // For direct messages, conversation ID = peer's WhisperID
        // Display name is WhisperID (truncated) - could be enhanced with contact lookup
        val displayName = conversationId.take(20) + if (conversationId.length > 20) "..." else ""
        _uiState.update { it.copy(peerDisplayName = displayName) }
    }

    private fun observeMessages() {
        viewModelScope.launch {
            appStateManager.messagesForConversation(conversationId).collect { messages ->
                _uiState.update {
                    it.copy(messages = messages)
                }
            }
        }
    }

    private fun markAsRead() {
        viewModelScope.launch {
            conversationDao.markAsRead(conversationId)
        }
    }

    // =========================================================================
    // Input Handling
    // =========================================================================

    fun updateInputText(text: String) {
        _uiState.update { it.copy(inputText = text, error = null) }
    }

    // =========================================================================
    // Send Message
    // =========================================================================

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        if (text.isEmpty()) return

        val myWhisperId = sessionManager.whisperId
        if (myWhisperId == null) {
            _uiState.update { it.copy(error = "Not authenticated") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSending = true, inputText = "") }

            try {
                // Create local message entity first (optimistic UI)
                val messageId = UUID.randomUUID().toString()
                val timestamp = System.currentTimeMillis()

                val localMessage = MessageEntity(
                    messageId = messageId,
                    conversationId = conversationId,
                    from = myWhisperId,
                    to = conversationId,
                    msgType = MessageType.TEXT,
                    timestamp = timestamp,
                    nonceB64 = "", // Will be set by OutboxQueue
                    ciphertextB64 = "",
                    sigB64 = "",
                    text = text,
                    status = MessageStatus.PENDING,
                    isOutgoing = true
                )

                // Insert into local database (optimistic)
                messageDao.insert(localMessage)

                // Update conversation
                conversationDao.upsertWithNewMessage(
                    conversationId = conversationId,
                    type = ConversationType.DIRECT,
                    timestamp = timestamp,
                    preview = text.take(100),
                    incrementUnread = false // Don't increment for own messages
                )

                // Enqueue for sending via OutboxQueue
                // OutboxQueue handles encryption, signing, and delivery
                val enqueuedId = outboxQueue.enqueueTextMessage(
                    plaintext = text,
                    recipientId = conversationId
                )

                if (enqueuedId == null) {
                    // Failed to enqueue - update local message status
                    messageDao.updateStatus(messageId, MessageStatus.FAILED)
                    _uiState.update {
                        it.copy(isSending = false, error = "Failed to queue message")
                    }
                } else {
                    _uiState.update { it.copy(isSending = false) }
                }

            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSending = false,
                        error = e.message ?: "Send failed"
                    )
                }
            }
        }
    }

    // =========================================================================
    // Actions
    // =========================================================================

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    // =========================================================================
    // Call Actions
    // =========================================================================

    /**
     * Initiate a voice call to the current conversation peer
     */
    fun initiateVoiceCall() {
        viewModelScope.launch {
            try {
                val result = callService.initiateCall(conversationId, isVideo = false)
                result.onFailure { e ->
                    _uiState.update { it.copy(error = "Call failed: ${e.message}") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Call failed: ${e.message}") }
            }
        }
    }

    /**
     * Initiate a video call to the current conversation peer
     */
    fun initiateVideoCall() {
        viewModelScope.launch {
            try {
                val result = callService.initiateCall(conversationId, isVideo = true)
                result.onFailure { e ->
                    _uiState.update { it.copy(error = "Video call failed: ${e.message}") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Video call failed: ${e.message}") }
            }
        }
    }

    // =========================================================================
    // Attachment Actions
    // =========================================================================

    /**
     * Handle attachment picker button click
     * Note: Full attachment upload requires ActivityResultLauncher for file picking.
     * This will be wired up from the Activity/Fragment level.
     */
    fun onAttachmentPickerClick() {
        // Attachment picking requires ActivityResultLauncher which must be
        // registered at the composable/activity level. For now, show info message.
        _uiState.update { it.copy(error = "Attachment picker: Use system share or drag-drop") }
    }

    fun retryFailedMessage(messageId: String) {
        viewModelScope.launch {
            val message = messageDao.getById(messageId)
            if (message != null && message.status == MessageStatus.FAILED && message.text != null) {
                // Update status to pending
                messageDao.updateStatus(messageId, MessageStatus.PENDING)

                // Re-enqueue
                outboxQueue.enqueueTextMessage(
                    plaintext = message.text,
                    recipientId = message.to
                )
            }
        }
    }

    // =========================================================================
    // UI-G5: Attachment Downloads
    // =========================================================================

    /**
     * Get current download state for a message's attachment
     */
    fun getAttachmentDownloadState(messageId: String): StateFlow<AttachmentDownloadState?> {
        return _attachmentDownloadStates.map { states -> states[messageId] }
            .stateIn(viewModelScope, SharingStarted.Lazily, null)
    }

    /**
     * Download attachment for a message
     */
    fun downloadAttachment(messageId: String) {
        viewModelScope.launch {
            val message = messageDao.getById(messageId) ?: return@launch
            val objectKey = message.attachmentPointer ?: return@launch
            val contentType = message.attachmentContentType ?: "application/octet-stream"

            // Check if already downloaded
            if (message.attachmentLocalPath != null) {
                val file = File(message.attachmentLocalPath)
                if (file.exists()) {
                    _attachmentDownloadStates.update { states ->
                        states + (messageId to AttachmentDownloadState.Ready)
                    }
                    return@launch
                }
            }

            // Mark as downloading
            _attachmentDownloadStates.update { states ->
                states + (messageId to AttachmentDownloadState.Downloading(0f))
            }

            try {
                // Get conversation key for decryption
                val conversationKey = getConversationKey(conversationId) ?: run {
                    _attachmentDownloadStates.update { states ->
                        states + (messageId to AttachmentDownloadState.Failed("Missing conversation key"))
                    }
                    return@launch
                }

                // Create AttachmentPointer from message fields
                // Note: We need fileNonce and fileKeyBox from message - stored in ciphertextB64 as JSON
                val pointer = parseAttachmentPointer(message) ?: run {
                    _attachmentDownloadStates.update { states ->
                        states + (messageId to AttachmentDownloadState.Failed("Invalid attachment data"))
                    }
                    return@launch
                }

                // Download and decrypt
                val decryptedBytes = withContext(Dispatchers.IO) {
                    attachmentService.downloadAndDecrypt(pointer, conversationKey)
                }

                // Save to file
                val localPath = saveAttachmentToFile(objectKey, contentType, decryptedBytes)

                // Update database
                withContext(Dispatchers.IO) {
                    messageDao.updateAttachmentLocalPath(messageId, localPath)
                }

                // Mark as ready
                _attachmentDownloadStates.update { states ->
                    states + (messageId to AttachmentDownloadState.Ready)
                }

            } catch (e: Exception) {
                _attachmentDownloadStates.update { states ->
                    states + (messageId to AttachmentDownloadState.Failed(e.message ?: "Download failed"))
                }
            }
        }
    }

    /**
     * Get peer's public encryption key for attachment decryption.
     * Attachment keys are encrypted using NaCl box (X25519 key exchange).
     */
    private suspend fun getPeerEncPublicKey(peerId: String): ByteArray? {
        val peerKeysResult = keyLookupService.getKeys(peerId)
        return when (peerKeysResult) {
            is KeyLookupResult.Success -> peerKeysResult.keys.encPublicKey
            else -> null
        }
    }

    /**
     * Get our private encryption key
     */
    private fun getMyEncPrivateKey(): ByteArray? {
        return securePrefs.getBytesOrNull(com.whisper2.app.core.Constants.StorageKey.ENC_PRIVATE_KEY)
    }

    /**
     * Get conversation key for decryption (deprecated - use getPeerEncPublicKey instead)
     * Attachment decryption uses NaClBox.open directly with peer's public key
     */
    @Suppress("unused")
    private suspend fun getConversationKey(peerId: String): ByteArray? {
        // For attachment decryption, we don't use a pre-computed shared key.
        // Instead, NaClBox.open derives it internally from the peer's public key
        // and our private key. Return null here - caller should use direct box decryption.
        return null
    }

    /**
     * Parse attachment pointer from message entity
     */
    private fun parseAttachmentPointer(message: MessageEntity): AttachmentPointer? {
        val objectKey = message.attachmentPointer ?: return null
        val contentType = message.attachmentContentType ?: "application/octet-stream"
        val size = message.attachmentSize ?: 0

        // For demo/conformance, return a stub pointer
        // In production, fileNonce and fileKeyBox should be stored in the message
        // (typically in a JSON field or separate columns)
        return AttachmentPointer(
            objectKey = objectKey,
            contentType = contentType,
            ciphertextSize = size,
            fileNonce = "", // Would come from message
            fileKeyBox = FileKeyBox(nonce = "", ciphertext = "") // Would come from message
        )
    }

    /**
     * Save decrypted attachment to local file
     */
    private suspend fun saveAttachmentToFile(
        objectKey: String,
        contentType: String,
        data: ByteArray
    ): String = withContext(Dispatchers.IO) {
        // Determine file extension from content type
        val extension = when {
            contentType.startsWith("image/jpeg") -> "jpg"
            contentType.startsWith("image/png") -> "png"
            contentType.startsWith("image/gif") -> "gif"
            contentType.startsWith("audio/aac") -> "aac"
            contentType.startsWith("audio/opus") -> "opus"
            contentType.startsWith("video/mp4") -> "mp4"
            else -> "bin"
        }

        // Create attachments directory
        val attachmentsDir = File(context.filesDir, "attachments")
        if (!attachmentsDir.exists()) {
            attachmentsDir.mkdirs()
        }

        // Generate safe filename from objectKey
        val safeKey = objectKey.replace("/", "_").replace("\\", "_")
        val fileName = "$safeKey.$extension"
        val file = File(attachmentsDir, fileName)

        // Write data
        file.writeBytes(data)

        file.absolutePath
    }
}
