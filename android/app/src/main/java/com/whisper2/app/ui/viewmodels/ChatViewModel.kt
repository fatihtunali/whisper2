package com.whisper2.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whisper2.app.data.local.db.dao.ContactDao
import com.whisper2.app.data.local.db.dao.ConversationDao
import com.whisper2.app.data.local.db.dao.MessageDao
import com.whisper2.app.data.local.db.entities.MessageEntity
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
    private val messageHandler: MessageHandler
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
}
