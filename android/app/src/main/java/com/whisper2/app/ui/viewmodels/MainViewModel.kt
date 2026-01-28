package com.whisper2.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whisper2.app.core.Logger
import com.whisper2.app.data.local.db.entities.MessageEntity
import com.whisper2.app.data.network.ws.WsClientImpl
import com.whisper2.app.data.network.ws.WsConnectionState
import com.whisper2.app.services.auth.AuthService
import com.whisper2.app.services.auth.AuthState
import com.whisper2.app.services.messaging.MessageHandler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val authService: AuthService,
    private val wsClient: WsClientImpl,
    private val messageHandler: MessageHandler
) : ViewModel() {
    val authState: StateFlow<AuthState> = authService.authState
    val connectionState: StateFlow<WsConnectionState> = wsClient.connectionState

    // Expose new messages for UI updates
    val newMessages: SharedFlow<MessageEntity> = messageHandler.newMessages

    private var hasFetchedPending = false

    init {
        // Auto-reconnect when app starts with existing session
        viewModelScope.launch {
            val currentAuth = authState.value
            val currentConnection = connectionState.value

            Logger.d("[MainViewModel] Init: authState=$currentAuth, connectionState=$currentConnection")

            if (currentAuth is AuthState.Authenticated && currentConnection == WsConnectionState.DISCONNECTED) {
                Logger.d("[MainViewModel] Auto-reconnecting WebSocket...")
                val result = authService.reconnect()
                if (result.isSuccess && !hasFetchedPending) {
                    hasFetchedPending = true
                    Logger.d("[MainViewModel] Reconnect success - fetching pending messages")
                    fetchPendingMessages()
                }
            }
        }

        // Watch for auth state changes (e.g., after new login)
        viewModelScope.launch {
            authState.collect { state ->
                if (state is AuthState.Authenticated && connectionState.value == WsConnectionState.CONNECTED && !hasFetchedPending) {
                    hasFetchedPending = true
                    Logger.d("[MainViewModel] Auth completed while connected - fetching pending messages")
                    fetchPendingMessages()
                }
            }
        }
    }

    private fun reconnect() {
        viewModelScope.launch {
            try {
                authService.reconnect()
            } catch (e: Exception) {
                Logger.e("[MainViewModel] Reconnect failed", e)
            }
        }
    }

    // Request pending messages from server (call after reconnect)
    fun fetchPendingMessages() {
        messageHandler.fetchPendingMessages()
    }
}
