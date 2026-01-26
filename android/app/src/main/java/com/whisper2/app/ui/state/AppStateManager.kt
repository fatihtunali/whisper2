package com.whisper2.app.ui.state

import com.whisper2.app.network.ws.WsClient
import com.whisper2.app.network.ws.WsState
import com.whisper2.app.services.auth.ISessionManager
import com.whisper2.app.services.messaging.OutboxQueue
import com.whisper2.app.storage.db.dao.ConversationDao
import com.whisper2.app.storage.db.dao.MessageDao
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Application State Manager
 *
 * Provides reactive StateFlows for UI consumption.
 * All data is sourced from real services - no mock data.
 *
 * This is the single source of truth for UI state.
 */
@Singleton
class AppStateManager @Inject constructor(
    private val sessionManager: ISessionManager,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // =========================================================================
    // Auth State
    // =========================================================================

    private val _authState = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    // =========================================================================
    // Connection State
    // =========================================================================

    private val _connectionState = MutableStateFlow(ConnectionState())
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Reference to WsClient (set via setWsClient)
    private var wsClient: WsClient? = null

    // =========================================================================
    // Outbox State
    // =========================================================================

    private val _outboxState = MutableStateFlow(OutboxState())
    val outboxState: StateFlow<OutboxState> = _outboxState.asStateFlow()

    // Reference to OutboxQueue (set via setOutboxQueue)
    private var outboxQueue: OutboxQueue? = null

    // =========================================================================
    // UI-G6: Call State
    // =========================================================================

    private val _callState = MutableStateFlow<CallUiState>(CallUiState.Idle)
    val callState: StateFlow<CallUiState> = _callState.asStateFlow()

    // =========================================================================
    // Conversations (reactive from Room)
    // =========================================================================

    val conversations: Flow<List<ConversationUiItem>> = conversationDao.observeAll()
        .map { entities ->
            entities.map { entity ->
                ConversationUiItem.from(entity)
            }
        }

    val totalUnreadCount: Flow<Int> = conversationDao.observeTotalUnreadCount()
        .map { it ?: 0 }

    // =========================================================================
    // Messages for a conversation (reactive from Room)
    // =========================================================================

    fun messagesForConversation(conversationId: String): Flow<List<MessageUiItem>> {
        return messageDao.observeByConversation(conversationId)
            .map { entities ->
                entities.map { entity ->
                    MessageUiItem.from(entity)
                }
            }
    }

    // =========================================================================
    // Initialization
    // =========================================================================

    init {
        // Check initial auth state
        refreshAuthState()
    }

    /**
     * Refresh auth state from SessionManager
     */
    fun refreshAuthState() {
        val whisperId = sessionManager.whisperId
        val deviceId = sessionManager.deviceId

        _authState.value = when {
            whisperId != null && deviceId != null -> {
                AuthState.Authenticated(whisperId, deviceId)
            }
            else -> AuthState.Unauthenticated
        }
    }

    /**
     * Set authenticating state
     */
    fun setAuthenticating() {
        _authState.value = AuthState.Authenticating
    }

    /**
     * Set auth error state
     */
    fun setAuthError(message: String) {
        _authState.value = AuthState.Error(message)
    }

    // =========================================================================
    // WsClient Integration
    // =========================================================================

    /**
     * Set the WsClient reference for connection state tracking
     * Called during app initialization
     */
    fun setWsClient(client: WsClient) {
        wsClient = client
        startWsStatePolling()
    }

    private var wsPollingJob: Job? = null

    private fun startWsStatePolling() {
        wsPollingJob?.cancel()
        wsPollingJob = scope.launch {
            // Poll WsClient state every 500ms
            // (Ideally WsClient would emit StateFlow, but this works for now)
            while (isActive) {
                val client = wsClient
                if (client != null) {
                    val currentState = _connectionState.value
                    val newWsState = client.state

                    if (currentState.wsState != newWsState) {
                        _connectionState.value = currentState.copy(
                            wsState = newWsState,
                            isOnline = newWsState == WsState.CONNECTED,
                            lastConnectedAt = if (newWsState == WsState.CONNECTED) {
                                System.currentTimeMillis()
                            } else {
                                currentState.lastConnectedAt
                            }
                        )
                    }
                }
                delay(500)
            }
        }
    }

    // =========================================================================
    // OutboxQueue Integration
    // =========================================================================

    /**
     * Set the OutboxQueue reference for outbox state tracking
     */
    fun setOutboxQueue(queue: OutboxQueue) {
        outboxQueue = queue
        startOutboxStatePolling()
    }

    private var outboxPollingJob: Job? = null

    private fun startOutboxStatePolling() {
        outboxPollingJob?.cancel()
        outboxPollingJob = scope.launch {
            while (isActive) {
                val queue = outboxQueue
                if (queue != null) {
                    _outboxState.value = OutboxState(
                        queuedCount = queue.queuedCount(),
                        sendingCount = queue.sendingCount(),
                        failedCount = queue.failedItems().size
                    )
                }
                delay(500)
            }
        }
    }

    // =========================================================================
    // UI-G6: Call State Management
    // =========================================================================

    /**
     * Handle incoming call - show full-screen incoming call UI
     */
    fun onIncomingCall(callId: String, from: String, fromDisplayName: String? = null, isVideo: Boolean = false) {
        _callState.value = CallUiState.Incoming(
            callId = callId,
            from = from,
            fromDisplayName = fromDisplayName,
            isVideo = isVideo
        )
    }

    /**
     * Start outgoing call
     */
    fun onOutgoingCall(callId: String, to: String, toDisplayName: String? = null, isVideo: Boolean = false) {
        _callState.value = CallUiState.Outgoing(
            callId = callId,
            to = to,
            toDisplayName = toDisplayName,
            isVideo = isVideo
        )
    }

    /**
     * Call is connecting (after accept)
     */
    fun onCallConnecting(callId: String, peerId: String, isVideo: Boolean = false) {
        _callState.value = CallUiState.Connecting(
            callId = callId,
            peerId = peerId,
            isVideo = isVideo
        )
    }

    /**
     * Call connected - media flowing
     */
    fun onCallConnected(callId: String, peerId: String, peerDisplayName: String? = null, isVideo: Boolean = false) {
        _callState.value = CallUiState.InCall(
            callId = callId,
            peerId = peerId,
            peerDisplayName = peerDisplayName,
            isVideo = isVideo
        )
    }

    /**
     * Update in-call state (mute, speaker, duration)
     */
    fun updateInCallState(
        durationSeconds: Int? = null,
        isMuted: Boolean? = null,
        isSpeakerOn: Boolean? = null,
        isVideoEnabled: Boolean? = null
    ) {
        val current = _callState.value
        if (current is CallUiState.InCall) {
            _callState.value = current.copy(
                durationSeconds = durationSeconds ?: current.durationSeconds,
                isMuted = isMuted ?: current.isMuted,
                isSpeakerOn = isSpeakerOn ?: current.isSpeakerOn,
                isVideoEnabled = isVideoEnabled ?: current.isVideoEnabled
            )
        }
    }

    /**
     * Call ended
     */
    fun onCallEnded(callId: String, reason: CallEndReason, durationSeconds: Int = 0) {
        _callState.value = CallUiState.Ended(
            callId = callId,
            reason = reason,
            durationSeconds = durationSeconds
        )
    }

    /**
     * Dismiss call UI (return to idle)
     */
    fun dismissCallUi() {
        _callState.value = CallUiState.Idle
    }

    // =========================================================================
    // Cleanup
    // =========================================================================

    fun cleanup() {
        wsPollingJob?.cancel()
        outboxPollingJob?.cancel()
        scope.cancel()
    }
}
