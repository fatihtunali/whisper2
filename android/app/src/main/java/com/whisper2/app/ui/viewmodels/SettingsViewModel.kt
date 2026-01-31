package com.whisper2.app.ui.viewmodels

import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whisper2.app.core.StorageHelper
import com.whisper2.app.core.StorageUsage
import com.whisper2.app.data.local.db.WhisperDatabase
import com.whisper2.app.data.local.prefs.SecureStorage
import com.whisper2.app.services.auth.AuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val secureStorage: SecureStorage,
    private val authService: AuthService,
    private val database: WhisperDatabase,
    private val storageHelper: StorageHelper,
    private val wsClient: com.whisper2.app.data.network.ws.WsClientImpl
) : ViewModel() {

    private val _whisperId = MutableStateFlow<String?>(null)
    val whisperId: StateFlow<String?> = _whisperId.asStateFlow()

    private val _seedPhrase = MutableStateFlow<String?>(null)
    val seedPhrase: StateFlow<String?> = _seedPhrase.asStateFlow()

    private val _encPublicKeyBase64 = MutableStateFlow<String?>(null)
    val encPublicKeyBase64: StateFlow<String?> = _encPublicKeyBase64.asStateFlow()

    private val _deviceId = MutableStateFlow<String?>(null)
    val deviceId: StateFlow<String?> = _deviceId.asStateFlow()

    // ═══════════════════════════════════════════════════════════════════
    // NOTIFICATION SETTINGS
    // ═══════════════════════════════════════════════════════════════════

    private val _notificationsEnabled = MutableStateFlow(true)
    val notificationsEnabled: StateFlow<Boolean> = _notificationsEnabled.asStateFlow()

    private val _messagePreview = MutableStateFlow(true)
    val messagePreview: StateFlow<Boolean> = _messagePreview.asStateFlow()

    private val _notificationSound = MutableStateFlow(true)
    val notificationSound: StateFlow<Boolean> = _notificationSound.asStateFlow()

    private val _notificationVibration = MutableStateFlow(true)
    val notificationVibration: StateFlow<Boolean> = _notificationVibration.asStateFlow()

    // ═══════════════════════════════════════════════════════════════════
    // PRIVACY SETTINGS
    // ═══════════════════════════════════════════════════════════════════

    private val _sendReadReceipts = MutableStateFlow(true)
    val sendReadReceipts: StateFlow<Boolean> = _sendReadReceipts.asStateFlow()

    private val _showTypingIndicator = MutableStateFlow(true)
    val showTypingIndicator: StateFlow<Boolean> = _showTypingIndicator.asStateFlow()

    private val _showOnlineStatus = MutableStateFlow(true)
    val showOnlineStatus: StateFlow<Boolean> = _showOnlineStatus.asStateFlow()

    // ═══════════════════════════════════════════════════════════════════
    // AUTO-DOWNLOAD SETTINGS
    // ═══════════════════════════════════════════════════════════════════

    private val _autoDownloadPhotos = MutableStateFlow(true)
    val autoDownloadPhotos: StateFlow<Boolean> = _autoDownloadPhotos.asStateFlow()

    private val _autoDownloadVideos = MutableStateFlow(false)
    val autoDownloadVideos: StateFlow<Boolean> = _autoDownloadVideos.asStateFlow()

    private val _autoDownloadAudio = MutableStateFlow(true)
    val autoDownloadAudio: StateFlow<Boolean> = _autoDownloadAudio.asStateFlow()

    // ═══════════════════════════════════════════════════════════════════
    // BIOMETRIC LOCK SETTINGS
    // ═══════════════════════════════════════════════════════════════════

    private val _biometricLockEnabled = MutableStateFlow(false)
    val biometricLockEnabled: StateFlow<Boolean> = _biometricLockEnabled.asStateFlow()

    private val _lockTimeoutMinutes = MutableStateFlow(0) // 0 = Immediately
    val lockTimeoutMinutes: StateFlow<Int> = _lockTimeoutMinutes.asStateFlow()

    // ═══════════════════════════════════════════════════════════════════
    // STORAGE
    // ═══════════════════════════════════════════════════════════════════

    private val _storageUsage = MutableStateFlow(StorageUsage.EMPTY)
    val storageUsage: StateFlow<StorageUsage> = _storageUsage.asStateFlow()

    private val _isLoadingStorage = MutableStateFlow(false)
    val isLoadingStorage: StateFlow<Boolean> = _isLoadingStorage.asStateFlow()

    private val _isClearingCache = MutableStateFlow(false)
    val isClearingCache: StateFlow<Boolean> = _isClearingCache.asStateFlow()

    init {
        loadUserData()
        loadSettings()
        loadStorageUsage()
        loadBiometricSettings()
    }

    private fun loadUserData() {
        _whisperId.value = secureStorage.whisperId
        _deviceId.value = secureStorage.deviceId
        secureStorage.encPublicKey?.let {
            _encPublicKeyBase64.value = Base64.encodeToString(it, Base64.NO_WRAP)
        }
    }

    private fun loadSettings() {
        // Load notification settings
        _notificationsEnabled.value = secureStorage.notificationsEnabled
        _messagePreview.value = secureStorage.messagePreview
        _notificationSound.value = secureStorage.notificationSound
        _notificationVibration.value = secureStorage.notificationVibration

        // Load privacy settings
        _sendReadReceipts.value = secureStorage.sendReadReceipts
        _showTypingIndicator.value = secureStorage.showTypingIndicator
        _showOnlineStatus.value = secureStorage.showOnlineStatus

        // Load auto-download settings
        _autoDownloadPhotos.value = secureStorage.autoDownloadPhotos
        _autoDownloadVideos.value = secureStorage.autoDownloadVideos
        _autoDownloadAudio.value = secureStorage.autoDownloadAudio
    }

    private fun loadStorageUsage() {
        viewModelScope.launch {
            _isLoadingStorage.value = true
            try {
                _storageUsage.value = storageHelper.calculateStorageUsage()
            } finally {
                _isLoadingStorage.value = false
            }
        }
    }

    fun refreshStorageUsage() {
        loadStorageUsage()
    }

    // ═══════════════════════════════════════════════════════════════════
    // NOTIFICATION SETTERS
    // ═══════════════════════════════════════════════════════════════════

    fun setNotificationsEnabled(enabled: Boolean) {
        _notificationsEnabled.value = enabled
        secureStorage.notificationsEnabled = enabled
    }

    fun setMessagePreview(enabled: Boolean) {
        _messagePreview.value = enabled
        secureStorage.messagePreview = enabled
    }

    fun setNotificationSound(enabled: Boolean) {
        _notificationSound.value = enabled
        secureStorage.notificationSound = enabled
    }

    fun setNotificationVibration(enabled: Boolean) {
        _notificationVibration.value = enabled
        secureStorage.notificationVibration = enabled
    }

    // ═══════════════════════════════════════════════════════════════════
    // PRIVACY SETTERS
    // ═══════════════════════════════════════════════════════════════════

    fun setSendReadReceipts(enabled: Boolean) {
        _sendReadReceipts.value = enabled
        secureStorage.sendReadReceipts = enabled
    }

    fun setShowTypingIndicator(enabled: Boolean) {
        _showTypingIndicator.value = enabled
        secureStorage.showTypingIndicator = enabled
    }

    fun setShowOnlineStatus(enabled: Boolean) {
        _showOnlineStatus.value = enabled
        secureStorage.showOnlineStatus = enabled
    }

    // ═══════════════════════════════════════════════════════════════════
    // AUTO-DOWNLOAD SETTERS
    // ═══════════════════════════════════════════════════════════════════

    fun setAutoDownloadPhotos(enabled: Boolean) {
        _autoDownloadPhotos.value = enabled
        secureStorage.autoDownloadPhotos = enabled
    }

    fun setAutoDownloadVideos(enabled: Boolean) {
        _autoDownloadVideos.value = enabled
        secureStorage.autoDownloadVideos = enabled
    }

    fun setAutoDownloadAudio(enabled: Boolean) {
        _autoDownloadAudio.value = enabled
        secureStorage.autoDownloadAudio = enabled
    }

    // ═══════════════════════════════════════════════════════════════════
    // STORAGE MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════

    fun clearCache() {
        viewModelScope.launch {
            _isClearingCache.value = true
            try {
                storageHelper.clearCache()
                loadStorageUsage() // Refresh storage info
            } finally {
                _isClearingCache.value = false
            }
        }
    }

    fun clearMedia() {
        viewModelScope.launch {
            _isClearingCache.value = true
            try {
                storageHelper.clearMedia()
                loadStorageUsage() // Refresh storage info
            } finally {
                _isClearingCache.value = false
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // BIOMETRIC LOCK SETTERS
    // ═══════════════════════════════════════════════════════════════════

    private fun loadBiometricSettings() {
        _biometricLockEnabled.value = secureStorage.biometricLockEnabled
        _lockTimeoutMinutes.value = secureStorage.lockTimeoutMinutes
    }

    fun setBiometricLockEnabled(enabled: Boolean) {
        _biometricLockEnabled.value = enabled
        secureStorage.biometricLockEnabled = enabled
    }

    fun setLockTimeoutMinutes(minutes: Int) {
        _lockTimeoutMinutes.value = minutes
        secureStorage.lockTimeoutMinutes = minutes
    }

    /**
     * Get human-readable label for timeout value.
     */
    fun getLockTimeoutLabel(minutes: Int): String {
        return when (minutes) {
            -1 -> "Never"
            0 -> "Immediately"
            1 -> "1 minute"
            5 -> "5 minutes"
            15 -> "15 minutes"
            60 -> "1 hour"
            else -> "$minutes minutes"
        }
    }

    /**
     * Available timeout options.
     */
    val lockTimeoutOptions = listOf(
        0 to "Immediately",
        1 to "1 minute",
        5 to "5 minutes",
        15 to "15 minutes",
        60 to "1 hour",
        -1 to "Never"
    )

    // ═══════════════════════════════════════════════════════════════════
    // RESET SETTINGS
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Reset all settings to their default values.
     * This preserves account data (seed phrase, contacts, messages).
     */
    fun resetSettings() {
        viewModelScope.launch {
            secureStorage.resetSettings()
            // Reload settings to reflect changes in UI
            loadSettings()
            loadBiometricSettings()
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

    private val _isDeletingAccount = MutableStateFlow(false)
    val isDeletingAccount: StateFlow<Boolean> = _isDeletingAccount.asStateFlow()

    private val _deleteAccountError = MutableStateFlow<String?>(null)
    val deleteAccountError: StateFlow<String?> = _deleteAccountError.asStateFlow()

    /**
     * Wipe all data - sends delete request to server first, then clears local data.
     * User cannot recover their account after this operation.
     * @return true if successful (even if server deletion fails, local data is still cleared)
     */
    suspend fun wipeAllData(): Boolean {
        _isDeletingAccount.value = true
        _deleteAccountError.value = null

        try {
            // Step 1: Try to delete account on server
            val serverSuccess = try {
                deleteAccountOnServer()
            } catch (e: Exception) {
                Logger.e("Server account deletion failed", e)
                _deleteAccountError.value = e.message
                false
            }

            // Step 2: Clear local data (always do this, even if server fails)
            clearLocalData()

            return true
        } finally {
            _isDeletingAccount.value = false
        }
    }

    /**
     * Send delete_account request to server.
     */
    private suspend fun deleteAccountOnServer(): Boolean {
        val sessionToken = secureStorage.sessionToken
            ?: throw IllegalStateException("No session token")

        // Build delete_account payload
        val payload = mapOf(
            "protocolVersion" to com.whisper2.app.core.Constants.PROTOCOL_VERSION,
            "cryptoVersion" to com.whisper2.app.core.Constants.CRYPTO_VERSION,
            "sessionToken" to sessionToken,
            "confirmPhrase" to "DELETE MY ACCOUNT"
        )

        val frame = com.whisper2.app.data.network.ws.WsFrame(
            type = "delete_account",
            payload = payload
        )

        // Send and wait for response with timeout
        return withTimeout(10_000) {
            kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                var hasResumed = false
                var messageCollectorJob: kotlinx.coroutines.Job? = null

                // Listen for account_deleted response
                messageCollectorJob = viewModelScope.launch {
                    wsClient.messages.collect { response ->
                        if (!hasResumed && response.type == "account_deleted") {
                            hasResumed = true
                            messageCollectorJob?.cancel()
                            val success = (response.payload as? Map<*, *>)?.get("success") as? Boolean ?: false
                            continuation.resume(success) { }
                        }
                    }
                }

                // Send the request
                viewModelScope.launch {
                    try {
                        wsClient.send(frame)
                    } catch (e: Exception) {
                        if (!hasResumed) {
                            hasResumed = true
                            messageCollectorJob?.cancel()
                            continuation.resumeWith(Result.failure(e))
                        }
                    }
                }

                continuation.invokeOnCancellation {
                    messageCollectorJob?.cancel()
                }
            }
        }
    }

    /**
     * Clear all local data.
     */
    private suspend fun clearLocalData() {
        // Clear cache (important: prevents stale state after wipe)
        storageHelper.clearCache()
        // Clear media/attachments
        storageHelper.clearMedia()
        // Clear database
        database.clearAllTables()
        // Clear secure storage
        secureStorage.clearAll()
        // Logout (stops services, disconnects WebSocket)
        authService.logout()
    }

    @Deprecated("Use suspend fun wipeAllData() instead", ReplaceWith("wipeAllData()"))
    fun wipeAllDataLegacy() {
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
