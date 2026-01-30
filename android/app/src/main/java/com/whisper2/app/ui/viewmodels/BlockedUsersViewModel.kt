package com.whisper2.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whisper2.app.data.local.db.dao.ContactDao
import com.whisper2.app.data.local.db.entities.ContactEntity
import com.whisper2.app.services.contacts.ContactsService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BlockedUser(
    val whisperId: String,
    val displayName: String?
)

@HiltViewModel
class BlockedUsersViewModel @Inject constructor(
    private val contactDao: ContactDao,
    private val contactsService: ContactsService
) : ViewModel() {

    val blockedUsers: StateFlow<List<BlockedUser>> = contactDao
        .getBlockedContacts()
        .map { contacts ->
            contacts.map { contact ->
                BlockedUser(
                    whisperId = contact.whisperId,
                    displayName = contact.displayName
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun unblockUser(whisperId: String) {
        viewModelScope.launch {
            contactsService.unblockContact(whisperId)
        }
    }
}
