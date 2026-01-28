package com.whisper2.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whisper2.app.data.local.db.dao.GroupDao
import com.whisper2.app.data.local.db.dao.MessageDao
import com.whisper2.app.data.local.db.entities.GroupEntity
import com.whisper2.app.data.local.db.entities.MessageEntity
import com.whisper2.app.services.groups.GroupService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GroupChatViewModel @Inject constructor(
    private val groupDao: GroupDao,
    private val messageDao: MessageDao,
    private val groupService: GroupService
) : ViewModel() {

    private val _groupId = MutableStateFlow<String?>(null)

    private val _group = MutableStateFlow<GroupEntity?>(null)
    val group: StateFlow<GroupEntity?> = _group.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val messages: StateFlow<List<MessageEntity>> = _groupId
        .filterNotNull()
        .flatMapLatest { groupId ->
            messageDao.getMessagesForConversation(groupId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun loadGroup(groupId: String) {
        _groupId.value = groupId
        viewModelScope.launch {
            _group.value = groupDao.getGroupById(groupId)
            groupService.markAsRead(groupId)
        }
    }

    fun sendMessage(content: String) {
        val groupId = _groupId.value ?: return
        if (content.isBlank()) return

        viewModelScope.launch {
            val result = groupService.sendMessage(groupId, content)
            result.onFailure { e ->
                _error.value = e.message ?: "Failed to send message"
            }
        }
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            messageDao.deleteById(messageId)
        }
    }

    fun clearError() {
        _error.value = null
    }
}
