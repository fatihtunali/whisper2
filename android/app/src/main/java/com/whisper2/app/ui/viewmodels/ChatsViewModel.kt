package com.whisper2.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whisper2.app.data.local.db.dao.ConversationDao
import com.whisper2.app.data.local.db.dao.ContactDao
import com.whisper2.app.data.local.db.entities.ContactEntity
import com.whisper2.app.data.local.db.entities.ConversationEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatsViewModel @Inject constructor(
    private val conversationDao: ConversationDao,
    private val contactDao: ContactDao
) : ViewModel() {

    val conversations: StateFlow<List<ConversationEntity>> = conversationDao
        .getAllConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingRequestCount: StateFlow<Int> = contactDao
        .getPendingRequestCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val contacts: StateFlow<List<ContactEntity>> = contactDao
        .getAllContacts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deleteConversation(peerId: String) {
        viewModelScope.launch {
            conversationDao.deleteByPeerId(peerId)
        }
    }

    fun refreshConversations() {
        // Trigger refresh from server if needed
    }
}
