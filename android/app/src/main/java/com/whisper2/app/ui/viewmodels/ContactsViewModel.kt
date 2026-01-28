package com.whisper2.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whisper2.app.data.local.db.dao.ContactDao
import com.whisper2.app.data.local.db.entities.ContactEntity
import com.whisper2.app.services.contacts.ContactsService
import com.whisper2.app.services.contacts.QrCodeData
import com.whisper2.app.services.contacts.QrParseResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val contactDao: ContactDao,
    private val contactsService: ContactsService
) : ViewModel() {

    val contacts: StateFlow<List<ContactEntity>> = contactDao
        .getAllContacts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _scannedQrData = MutableStateFlow<QrCodeData?>(null)
    val scannedQrData: StateFlow<QrCodeData?> = _scannedQrData.asStateFlow()

    private val _addContactState = MutableStateFlow<AddContactState>(AddContactState.Idle)
    val addContactState: StateFlow<AddContactState> = _addContactState.asStateFlow()

    fun parseQrCode(qrContent: String): QrParseResult {
        return contactsService.parseQrCode(qrContent)
    }

    fun setScannedQrData(data: QrCodeData) {
        _scannedQrData.value = data
    }

    fun clearScannedQrData() {
        _scannedQrData.value = null
        _addContactState.value = AddContactState.Idle
    }

    fun addContactFromQr(qrData: QrCodeData, nickname: String) {
        viewModelScope.launch {
            _addContactState.value = AddContactState.Loading
            val result = contactsService.addContactFromQr(qrData, nickname)
            _addContactState.value = if (result.isSuccess) {
                AddContactState.Success
            } else {
                AddContactState.Error(result.exceptionOrNull()?.message ?: "Failed to add contact")
            }
        }
    }

    fun addContact(whisperId: String, nickname: String) {
        viewModelScope.launch {
            contactsService.addContact(whisperId, nickname)
        }
    }

    fun deleteContact(whisperId: String) {
        viewModelScope.launch {
            contactDao.deleteByWhisperId(whisperId)
        }
    }

    fun updateContactNickname(whisperId: String, nickname: String) {
        viewModelScope.launch {
            contactDao.updateNickname(whisperId, nickname)
        }
    }
}

sealed class AddContactState {
    object Idle : AddContactState()
    object Loading : AddContactState()
    object Success : AddContactState()
    data class Error(val message: String) : AddContactState()
}
