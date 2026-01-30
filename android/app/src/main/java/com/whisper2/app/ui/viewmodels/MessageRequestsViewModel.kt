package com.whisper2.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whisper2.app.data.local.db.dao.ContactDao
import com.whisper2.app.data.local.db.dao.MessageDao
import com.whisper2.app.data.local.db.entities.ContactEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MessageRequestItem(
    val contact: ContactEntity,
    val messageCount: Int,
    val lastMessageTimestamp: Long?
)

@HiltViewModel
class MessageRequestsViewModel @Inject constructor(
    private val contactDao: ContactDao,
    private val messageDao: MessageDao
) : ViewModel() {

    private val _messageRequests = MutableStateFlow<List<MessageRequestItem>>(emptyList())
    val messageRequests: StateFlow<List<MessageRequestItem>> = _messageRequests.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadMessageRequests()
    }

    private fun loadMessageRequests() {
        viewModelScope.launch {
            contactDao.getMessageRequestsOrdered()
                .collect { contacts ->
                    val items = contacts.map { contact ->
                        val messageCount = try {
                            messageDao.getMessageCount(contact.whisperId)
                        } catch (e: Exception) {
                            0
                        }
                        MessageRequestItem(
                            contact = contact,
                            messageCount = messageCount,
                            lastMessageTimestamp = contact.updatedAt
                        )
                    }
                    _messageRequests.value = items
                    _isLoading.value = false
                }
        }
    }

    fun acceptRequest(whisperId: String) {
        viewModelScope.launch {
            contactDao.acceptMessageRequest(whisperId)
        }
    }

    fun blockUser(whisperId: String) {
        viewModelScope.launch {
            contactDao.setBlocked(whisperId, true)
            contactDao.deleteByWhisperId(whisperId)
        }
    }

    fun deleteRequest(whisperId: String) {
        viewModelScope.launch {
            contactDao.deleteByWhisperId(whisperId)
        }
    }
}
