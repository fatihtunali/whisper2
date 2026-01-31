package com.whisper2.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whisper2.app.data.local.db.dao.ConversationDao
import com.whisper2.app.data.local.db.dao.ContactDao
import com.whisper2.app.data.local.db.dao.GroupDao
import com.whisper2.app.data.local.db.entities.ContactEntity
import com.whisper2.app.data.local.db.entities.ConversationEntity
import com.whisper2.app.data.local.db.entities.GroupEntity
import com.whisper2.app.core.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Unified chat item that can represent either a 1:1 conversation or a group.
 */
data class ChatItem(
    val id: String,
    val name: String,
    val lastMessage: String?,
    val lastMessageTimestamp: Long,
    val unreadCount: Int,
    val isTyping: Boolean,
    val isGroup: Boolean,
    val memberCount: Int? = null,  // Only for groups
    val avatarPath: String? = null  // For contacts with custom avatar
)

@HiltViewModel
class ChatsViewModel @Inject constructor(
    private val conversationDao: ConversationDao,
    private val contactDao: ContactDao,
    private val groupDao: GroupDao
) : ViewModel() {

    // Raw conversations (1:1 chats)
    private val rawConversations: Flow<List<ConversationEntity>> = conversationDao.getAllConversations()

    // Raw groups
    private val rawGroups: Flow<List<GroupEntity>> = groupDao.getAllGroupsFlow()

    // Combined and sorted chat items (both 1:1 and groups)
    val chatItems: StateFlow<List<ChatItem>> = combine(rawConversations, rawGroups) { conversations, groups ->
        val items = mutableListOf<ChatItem>()

        // Add 1:1 conversations
        conversations.forEach { conv ->
            items.add(ChatItem(
                id = conv.peerId,
                name = conv.peerNickname ?: conv.peerId.takeLast(8),
                lastMessage = conv.lastMessagePreview,
                lastMessageTimestamp = conv.lastMessageTimestamp ?: 0L,
                unreadCount = conv.unreadCount,
                isTyping = conv.isTyping,
                isGroup = false,
                avatarPath = conv.peerAvatarPath
            ))
        }

        // Add groups
        groups.forEach { group ->
            items.add(ChatItem(
                id = group.groupId,
                name = group.name,
                lastMessage = group.lastMessagePreview,
                lastMessageTimestamp = group.lastMessageTimestamp ?: group.createdAt,
                unreadCount = group.unreadCount,
                isTyping = false,
                isGroup = true,
                memberCount = group.memberCount
            ))
        }

        // Sort by last message timestamp (most recent first)
        items.sortedByDescending { it.lastMessageTimestamp }
    }
    .catch { e ->
        Logger.e("[ChatsViewModel] Error combining chat items", e)
        emit(emptyList())
    }
    .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Keep legacy conversations for backward compatibility
    val conversations: StateFlow<List<ConversationEntity>> = rawConversations
        .catch { e ->
            Logger.e("[ChatsViewModel] Error loading conversations", e)
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val pendingRequestCount: StateFlow<Int> = contactDao
        .getPendingRequestCount()
        .catch { e ->
            Logger.e("[ChatsViewModel] Error loading pending request count", e)
            emit(0)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val contacts: StateFlow<List<ContactEntity>> = contactDao
        .getAllContacts()
        .catch { e ->
            Logger.e("[ChatsViewModel] Error loading contacts", e)
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun deleteConversation(peerId: String) {
        viewModelScope.launch {
            conversationDao.deleteByPeerId(peerId)
        }
    }

    fun refreshConversations() {
        // Trigger refresh from server if needed
    }
}
