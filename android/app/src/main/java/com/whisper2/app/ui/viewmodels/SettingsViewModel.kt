package com.whisper2.app.ui.viewmodels

import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whisper2.app.data.local.db.WhisperDatabase
import com.whisper2.app.data.local.prefs.SecureStorage
import com.whisper2.app.services.auth.AuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val secureStorage: SecureStorage,
    private val authService: AuthService,
    private val database: WhisperDatabase
) : ViewModel() {

    private val _whisperId = MutableStateFlow<String?>(null)
    val whisperId: StateFlow<String?> = _whisperId.asStateFlow()

    private val _seedPhrase = MutableStateFlow<String?>(null)
    val seedPhrase: StateFlow<String?> = _seedPhrase.asStateFlow()

    private val _encPublicKeyBase64 = MutableStateFlow<String?>(null)
    val encPublicKeyBase64: StateFlow<String?> = _encPublicKeyBase64.asStateFlow()

    private val _deviceId = MutableStateFlow<String?>(null)
    val deviceId: StateFlow<String?> = _deviceId.asStateFlow()

    init {
        loadUserData()
    }

    private fun loadUserData() {
        _whisperId.value = secureStorage.whisperId
        _deviceId.value = secureStorage.deviceId
        secureStorage.encPublicKey?.let {
            _encPublicKeyBase64.value = Base64.encodeToString(it, Base64.NO_WRAP)
        }
    }

    fun loadSeedPhrase() {
        _seedPhrase.value = secureStorage.mnemonic
    }

    fun hideSeedPhrase() {
        _seedPhrase.value = null
    }

    fun logout() {
        viewModelScope.launch {
            authService.logout()
        }
    }

    fun wipeAllData() {
        viewModelScope.launch {
            // Clear database
            database.clearAllTables()
            // Clear secure storage
            secureStorage.clearAll()
        }
    }

    /**
     * Generate QR code data for sharing contact info.
     * Format: whisper2://add?id=WSP-XXXX-XXXX-XXXX&key=base64pubkey
     */
    fun getQrCodeData(): String? {
        val id = whisperId.value ?: return null
        val key = encPublicKeyBase64.value ?: return null
        return "whisper2://add?id=$id&key=$key"
    }
}
