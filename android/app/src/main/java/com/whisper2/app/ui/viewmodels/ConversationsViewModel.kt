package com.whisper2.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whisper2.app.services.auth.ISessionManager
import com.whisper2.app.storage.db.dao.ConversationDao
import com.whisper2.app.ui.state.AppStateManager
import com.whisper2.app.ui.state.ConnectionState
import com.whisper2.app.ui.state.ConversationUiItem
import com.whisper2.app.ui.state.OutboxState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Conversations Screen UI State
 */
data class ConversationsUiState(
    val conversations: List<ConversationUiItem> = emptyList(),
    val totalUnreadCount: Int = 0,
    val isLoading: Boolean = false,
    val myWhisperId: String? = null
)

/**
 * Conversations ViewModel
 *
 * Observes real data from Room database via AppStateManager.
 * No mock data - all conversations come from actual message exchanges.
 */
@HiltViewModel
class ConversationsViewModel @Inject constructor(
    private val appStateManager: AppStateManager,
    private val sessionManager: ISessionManager,
    private val conversationDao: ConversationDao
) : ViewModel() {

    // =========================================================================
    // UI State
    // =========================================================================

    private val _uiState = MutableStateFlow(ConversationsUiState())
    val uiState: StateFlow<ConversationsUiState> = _uiState.asStateFlow()

    // Connection state from AppStateManager
    val connectionState: StateFlow<ConnectionState> = appStateManager.connectionState

    // Outbox state for pending indicator
    val outboxState: StateFlow<OutboxState> = appStateManager.outboxState

    init {
        observeConversations()
        observeUnreadCount()
        loadMyWhisperId()
    }

    // =========================================================================
    // Data Observation
    // =========================================================================

    private fun observeConversations() {
        viewModelScope.launch {
            appStateManager.conversations.collect { conversations ->
                _uiState.update {
                    it.copy(
                        conversations = conversations,
                        isLoading = false
                    )
                }
            }
        }
    }

    private fun observeUnreadCount() {
        viewModelScope.launch {
            appStateManager.totalUnreadCount.collect { count ->
                _uiState.update {
                    it.copy(totalUnreadCount = count)
                }
            }
        }
    }

    private fun loadMyWhisperId() {
        _uiState.update {
            it.copy(myWhisperId = sessionManager.whisperId)
        }
    }

    // =========================================================================
    // Actions
    // =========================================================================

    /**
     * Mark conversation as read when opened
     */
    fun markConversationAsRead(conversationId: String) {
        viewModelScope.launch {
            conversationDao.markAsRead(conversationId)
        }
    }

    /**
     * Start new conversation with a WhisperID
     * Returns the conversation ID (same as WhisperID for direct messages)
     */
    fun startConversation(recipientWhisperId: String): String {
        // For direct messages, conversation ID = recipient's WhisperID
        // The conversation will be created when first message is sent/received
        return recipientWhisperId
    }

    /**
     * Delete conversation (local only)
     */
    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            conversationDao.delete(conversationId)
        }
    }

    /**
     * Refresh conversations (pull-to-refresh)
     */
    fun refresh() {
        _uiState.update { it.copy(isLoading = true) }
        // Room Flow will auto-update when data changes
        // Just reset loading state after a brief delay
        viewModelScope.launch {
            kotlinx.coroutines.delay(500)
            _uiState.update { it.copy(isLoading = false) }
        }
    }
}
