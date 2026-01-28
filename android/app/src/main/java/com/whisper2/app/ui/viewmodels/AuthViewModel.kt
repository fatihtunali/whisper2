package com.whisper2.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whisper2.app.crypto.CryptoService
import com.whisper2.app.services.auth.AuthService
import com.whisper2.app.services.auth.AuthState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authService: AuthService,
    private val cryptoService: CryptoService
) : ViewModel() {

    val authState: StateFlow<AuthState> = authService.authState

    fun generateMnemonic(): String {
        return cryptoService.generateMnemonic()
    }

    fun createAccount(mnemonic: String, onComplete: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            val result = authService.registerNewAccount(mnemonic)
            onComplete(result)
        }
    }

    fun recoverAccount(mnemonic: String, onComplete: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            val result = authService.recoverAccount(mnemonic)
            onComplete(result)
        }
    }

    fun logout() {
        authService.logout()
    }
}
