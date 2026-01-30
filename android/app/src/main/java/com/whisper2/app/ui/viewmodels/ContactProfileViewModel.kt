package com.whisper2.app.ui.viewmodels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whisper2.app.core.AvatarHelper
import com.whisper2.app.core.Logger
import com.whisper2.app.data.local.db.dao.ContactDao
import com.whisper2.app.data.local.db.dao.ConversationDao
import com.whisper2.app.data.local.db.entities.ContactEntity
import com.whisper2.app.services.contacts.ContactsService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class ContactProfileViewModel @Inject constructor(
    private val contactDao: ContactDao,
    private val conversationDao: ConversationDao,
    private val contactsService: ContactsService,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _peerId = MutableStateFlow("")

    val contact: StateFlow<ContactEntity?> = _peerId
        .filter { it.isNotEmpty() }
        .flatMapLatest { contactDao.getContactByWhisperId(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _avatarUpdateTrigger = MutableStateFlow(0L)
    val avatarUpdateTrigger: StateFlow<Long> = _avatarUpdateTrigger.asStateFlow()

    fun loadContact(peerId: String) {
        _peerId.value = peerId
    }

    fun updateNickname(peerId: String, nickname: String) {
        viewModelScope.launch {
            contactDao.updateNickname(peerId, nickname.ifEmpty { peerId })
        }
    }

    fun blockContact(peerId: String) {
        viewModelScope.launch {
            contactsService.blockContact(peerId)
        }
    }

    fun unblockContact(peerId: String) {
        viewModelScope.launch {
            contactsService.unblockContact(peerId)
        }
    }

    fun deleteContact(peerId: String) {
        viewModelScope.launch {
            // Delete avatar file when contact is deleted
            AvatarHelper.deleteAvatar(context, peerId)
            contactsService.deleteContact(peerId)
        }
    }

    /**
     * Update avatar from a URI (gallery selection).
     */
    fun updateAvatarFromUri(peerId: String, uri: Uri) {
        viewModelScope.launch {
            val avatarPath = AvatarHelper.saveAvatarFromUri(context, peerId, uri)
            if (avatarPath != null) {
                contactDao.updateAvatar(peerId, avatarPath)
                // Also update conversation avatar for chat list display
                conversationDao.updatePeerAvatar(peerId, avatarPath)
                _avatarUpdateTrigger.value = System.currentTimeMillis()
                Logger.d("[ContactProfileViewModel] Avatar updated from URI for $peerId")
            }
        }
    }

    /**
     * Update avatar from a file path (camera capture).
     */
    fun updateAvatarFromFile(peerId: String, filePath: String) {
        viewModelScope.launch {
            val avatarPath = AvatarHelper.saveAvatarFromFile(context, peerId, filePath)
            if (avatarPath != null) {
                contactDao.updateAvatar(peerId, avatarPath)
                // Also update conversation avatar for chat list display
                conversationDao.updatePeerAvatar(peerId, avatarPath)
                _avatarUpdateTrigger.value = System.currentTimeMillis()
                Logger.d("[ContactProfileViewModel] Avatar updated from file for $peerId")
            }
        }
    }

    /**
     * Remove the contact's avatar.
     */
    fun removeAvatar(peerId: String) {
        viewModelScope.launch {
            AvatarHelper.deleteAvatar(context, peerId)
            contactDao.updateAvatar(peerId, null)
            // Also update conversation avatar
            conversationDao.updatePeerAvatar(peerId, null)
            _avatarUpdateTrigger.value = System.currentTimeMillis()
            Logger.d("[ContactProfileViewModel] Avatar removed for $peerId")
        }
    }

    /**
     * Create a temp file for camera capture.
     */
    fun createTempImageFile(): File {
        return AvatarHelper.createTempImageFile(context)
    }

    /**
     * Check if avatar path is valid.
     */
    fun isAvatarValid(avatarPath: String?): Boolean {
        return AvatarHelper.isAvatarPathValid(avatarPath)
    }
}
