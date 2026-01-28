package com.whisper2.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whisper2.app.data.local.db.dao.ContactDao
import com.whisper2.app.data.local.db.entities.ContactEntity
import com.whisper2.app.services.contacts.ContactsService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ContactProfileViewModel @Inject constructor(
    private val contactDao: ContactDao,
    private val contactsService: ContactsService
) : ViewModel() {

    private val _peerId = MutableStateFlow("")

    val contact: StateFlow<ContactEntity?> = _peerId
        .filter { it.isNotEmpty() }
        .flatMapLatest { contactDao.getContactByWhisperId(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

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
            contactsService.deleteContact(peerId)
        }
    }
}
