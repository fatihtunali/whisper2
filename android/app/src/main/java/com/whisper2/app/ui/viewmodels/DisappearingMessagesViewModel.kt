package com.whisper2.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whisper2.app.data.local.db.dao.ConversationDao
import com.whisper2.app.data.local.db.entities.DisappearingMessageTimer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DisappearingMessagesViewModel @Inject constructor(
    private val conversationDao: ConversationDao
) : ViewModel() {

    private var currentConversationId: String? = null

    private val _currentTimer = MutableStateFlow(DisappearingMessageTimer.OFF)
    val currentTimer: StateFlow<DisappearingMessageTimer> = _currentTimer.asStateFlow()

    fun loadConversation(conversationId: String) {
        if (currentConversationId == conversationId) return
        currentConversationId = conversationId

        viewModelScope.launch {
            conversationDao.getConversation(conversationId)
                .filterNotNull()
                .collect { conversation ->
                    _currentTimer.value = conversation.disappearingMessageTimer
                }
        }
    }

    fun setTimer(timer: DisappearingMessageTimer) {
        val conversationId = currentConversationId ?: return
        viewModelScope.launch {
            conversationDao.setDisappearingTimer(conversationId, timer.value)
            _currentTimer.value = timer
        }
    }
}
