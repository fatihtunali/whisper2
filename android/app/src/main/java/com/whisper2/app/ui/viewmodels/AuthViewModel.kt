package com.whisper2.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whisper2.app.crypto.BIP39
import com.whisper2.app.crypto.KeyDerivation
import com.whisper2.app.services.auth.AuthCoordinator
import com.whisper2.app.ui.state.AppStateManager
import com.whisper2.app.ui.state.AuthState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Auth Screen UI State
 */
data class AuthUiState(
    val mode: AuthMode = AuthMode.CHOOSE,
    val mnemonic: String = "",
    val mnemonicWords: List<String> = emptyList(),
    val isValidMnemonic: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val registrationComplete: Boolean = false
)

enum class AuthMode {
    CHOOSE,      // Initial: choose register or recover
    REGISTER,    // New registration: show generated mnemonic
    RECOVER      // Recovery: enter existing mnemonic
}

/**
 * Auth ViewModel
 *
 * Handles registration and recovery flows.
 * Uses real AuthCoordinator - no mock data.
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authCoordinator: AuthCoordinator,
    private val appStateManager: AppStateManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    val authState: StateFlow<AuthState> = appStateManager.authState

    // Observe auth flow state for progress updates
    init {
        viewModelScope.launch {
            authCoordinator.flowState.collect { flowState ->
                when (flowState) {
                    is AuthCoordinator.AuthFlowState.Success -> {
                        appStateManager.refreshAuthState()
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                registrationComplete = true
                            )
                        }
                    }
                    is AuthCoordinator.AuthFlowState.Error -> {
                        appStateManager.setAuthError(flowState.message)
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = flowState.message
                            )
                        }
                    }
                    else -> {
                        // Update loading state based on flow progress
                        val isLoading = flowState != AuthCoordinator.AuthFlowState.Idle
                        if (_uiState.value.isLoading != isLoading) {
                            _uiState.update { it.copy(isLoading = isLoading) }
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // Mode Selection
    // =========================================================================

    fun selectRegisterMode() {
        // Generate new mnemonic
        val mnemonic = KeyDerivation.generateMnemonic()
        val words = mnemonic.split(" ")

        _uiState.update {
            it.copy(
                mode = AuthMode.REGISTER,
                mnemonic = mnemonic,
                mnemonicWords = words,
                isValidMnemonic = true,
                error = null
            )
        }
    }

    fun selectRecoverMode() {
        _uiState.update {
            it.copy(
                mode = AuthMode.RECOVER,
                mnemonic = "",
                mnemonicWords = emptyList(),
                isValidMnemonic = false,
                error = null
            )
        }
    }

    fun backToChoose() {
        authCoordinator.reset()
        _uiState.update {
            AuthUiState() // Reset to initial state
        }
    }

    // =========================================================================
    // Mnemonic Input (Recovery Mode)
    // =========================================================================

    fun updateMnemonic(input: String) {
        val normalized = input.trim().lowercase()
        val words = normalized.split("\\s+".toRegex()).filter { it.isNotEmpty() }
        // Accept both 12-word and 24-word mnemonics (app generates 24-word)
        val isValid = (words.size == 12 || words.size == 24) && BIP39.isValidMnemonic(normalized)

        _uiState.update {
            it.copy(
                mnemonic = normalized,
                mnemonicWords = words,
                isValidMnemonic = isValid,
                error = null
            )
        }
    }

    // =========================================================================
    // Registration / Recovery
    // =========================================================================

    fun confirmMnemonicAndRegister() {
        val state = _uiState.value

        if (!state.isValidMnemonic) {
            _uiState.update { it.copy(error = "Invalid mnemonic") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            appStateManager.setAuthenticating()

            try {
                // Call AuthCoordinator for full registration flow
                val result = authCoordinator.registerWithMnemonic(state.mnemonic)

                if (!result.success) {
                    // Error already handled by flowState observer
                }
            } catch (e: Exception) {
                val errorMsg = e.message ?: "Registration error"
                appStateManager.setAuthError(errorMsg)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = errorMsg
                    )
                }
            }
        }
    }

    fun recover() {
        val state = _uiState.value

        if (!state.isValidMnemonic) {
            _uiState.update { it.copy(error = "Please enter a valid 12 or 24-word mnemonic") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            appStateManager.setAuthenticating()

            try {
                // Call AuthCoordinator for recovery (same flow, server recognizes existing keys)
                val result = authCoordinator.registerWithMnemonic(state.mnemonic)

                if (!result.success) {
                    // Error already handled by flowState observer
                }
            } catch (e: Exception) {
                val errorMsg = e.message ?: "Recovery error"
                appStateManager.setAuthError(errorMsg)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = errorMsg
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
