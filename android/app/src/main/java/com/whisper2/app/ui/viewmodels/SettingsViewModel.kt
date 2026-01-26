package com.whisper2.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import com.whisper2.app.services.auth.ISessionManager
import com.whisper2.app.services.cleanup.DataCleanup
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * Settings Screen UI State
 */
data class SettingsUiState(
    val whisperId: String? = null,
    val deviceId: String? = null
)

/**
 * Settings ViewModel
 *
 * Uses real SessionManager - no mock data.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val sessionManager: ISessionManager,
    private val dataCleanup: DataCleanup
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        _uiState.value = SettingsUiState(
            whisperId = sessionManager.whisperId,
            deviceId = sessionManager.deviceId
        )
    }

    fun logout() {
        // Force logout through session manager
        sessionManager.forceLogout("User requested logout")

        // Clean up local data
        dataCleanup.cleanupOnLogout()
    }
}
