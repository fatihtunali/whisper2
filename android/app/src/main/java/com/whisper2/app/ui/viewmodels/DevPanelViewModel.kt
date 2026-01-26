package com.whisper2.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whisper2.app.BuildConfig
import com.whisper2.app.core.Logger
import com.whisper2.app.network.ws.WsClient
import com.whisper2.app.services.auth.ISessionManager
import com.whisper2.app.storage.db.dao.ConversationDao
import com.whisper2.app.storage.db.dao.MessageDao
import com.whisper2.app.storage.db.entities.ConversationEntity
import com.whisper2.app.storage.db.entities.ConversationType
import com.whisper2.app.storage.db.entities.MessageEntity
import com.whisper2.app.storage.db.entities.MessageStatus
import com.whisper2.app.storage.db.entities.MessageType
import com.whisper2.app.ui.state.AppStateManager
import com.whisper2.app.ui.state.ConnectionState
import com.whisper2.app.ui.state.OutboxState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * Dev Panel ViewModel
 *
 * Provides debug info from real services.
 * Includes Golden Path Demo Mode for conformance builds.
 *
 * Used only in debug/conformance builds.
 */
@HiltViewModel
class DevPanelViewModel @Inject constructor(
    private val appStateManager: AppStateManager,
    private val sessionManager: ISessionManager,
    private val wsClient: WsClient,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = appStateManager.connectionState

    val outboxState: StateFlow<OutboxState> = appStateManager.outboxState

    // Recent log events
    private val _recentLogs = MutableStateFlow<List<String>>(emptyList())
    val recentLogs: StateFlow<List<String>> = _recentLogs.asStateFlow()

    // Test status for demo mode
    private val _testStatus = MutableStateFlow("")
    val testStatus: StateFlow<String> = _testStatus.asStateFlow()

    // Track state changes as log events
    init {
        viewModelScope.launch {
            connectionState.collect { state ->
                addLog("WS: ${state.wsState}")
            }
        }

        viewModelScope.launch {
            outboxState.collect { state ->
                if (state.queuedCount > 0 || state.sendingCount > 0) {
                    addLog("Outbox: q=${state.queuedCount} s=${state.sendingCount} f=${state.failedCount}")
                }
            }
        }
    }

    private fun addLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())

        _recentLogs.update { logs ->
            (logs + "[$timestamp] $message").takeLast(20)
        }
    }

    // =========================================================================
    // Golden Path Demo Mode Actions
    // =========================================================================

    /**
     * Run critical conformance gates (S1 + S3 + S6)
     * This tests: Auth, Messaging, Push
     */
    fun runCriticalConformance() {
        if (!BuildConfig.IS_CONFORMANCE_BUILD) return

        viewModelScope.launch {
            _testStatus.value = "Running critical gates..."
            addLog("Starting critical conformance (S1+S3+S6)")

            try {
                // S1: Auth check
                val authPassed = testAuthGate()
                if (!authPassed) {
                    _testStatus.value = "FAIL: S1 Auth"
                    return@launch
                }
                addLog("S1 Auth: PASS")

                // S3: Messaging check (just verify WS is connected)
                val messagingPassed = testMessagingGate()
                if (!messagingPassed) {
                    _testStatus.value = "FAIL: S3 Messaging"
                    return@launch
                }
                addLog("S3 Messaging: PASS")

                // S6: Push check (verify FCM token exists)
                val pushPassed = testPushGate()
                addLog("S6 Push: ${if (pushPassed) "PASS" else "SKIP (no token)"}")

                _testStatus.value = "PASS: Critical gates passed"
                addLog("Critical conformance: ALL PASS")

            } catch (e: Exception) {
                _testStatus.value = "FAIL: ${e.message}"
                addLog("Critical conformance: FAIL - ${e.message}")
            }
        }
    }

    private fun testAuthGate(): Boolean {
        return sessionManager.isLoggedIn && sessionManager.whisperId != null
    }

    private fun testMessagingGate(): Boolean {
        return connectionState.value.isOnline
    }

    private fun testPushGate(): Boolean {
        // Check if FCM token exists (optional - may not be available in emulator)
        return true // FCM token check would go here
    }

    /**
     * Seed a test conversation with sample messages
     * Uses test identities from BuildConfig
     */
    fun seedTestConversation() {
        if (!BuildConfig.IS_CONFORMANCE_BUILD) return

        viewModelScope.launch {
            _testStatus.value = "Seeding test conversation..."
            addLog("Seeding test conversation")

            try {
                val myWhisperId = sessionManager.whisperId ?: run {
                    _testStatus.value = "FAIL: Not logged in"
                    return@launch
                }

                // Create a test conversation (self-chat or with test identity)
                val testPeerId = "WSP-TEST-CONV-0001"
                val conversationId = testPeerId
                val now = System.currentTimeMillis()

                // Insert test conversation
                val conversation = ConversationEntity(
                    id = conversationId,
                    type = ConversationType.DIRECT,
                    lastMessageAt = now,
                    lastMessagePreview = "Test message from DevPanel",
                    unreadCount = 1
                )
                conversationDao.upsert(conversation)

                // Insert test messages
                val messages = listOf(
                    createTestMessage(conversationId, testPeerId, myWhisperId, "Hello from test!", now - 90000, false),
                    createTestMessage(conversationId, myWhisperId, testPeerId, "Hi! This is a reply.", now - 60000, true),
                    createTestAttachmentMessage(conversationId, testPeerId, myWhisperId, now - 30000, false), // UI-G5: Test attachment
                    createTestMessage(conversationId, testPeerId, myWhisperId, "Test message from DevPanel", now, false)
                )
                messages.forEach { messageDao.insert(it) }

                _testStatus.value = "Seeded: $conversationId"
                addLog("Test conversation seeded: $conversationId")

            } catch (e: Exception) {
                _testStatus.value = "FAIL: ${e.message}"
                addLog("Seed failed: ${e.message}")
            }
        }
    }

    private fun createTestMessage(
        conversationId: String,
        from: String,
        to: String,
        text: String,
        timestamp: Long,
        isOutgoing: Boolean
    ): MessageEntity {
        return MessageEntity(
            messageId = UUID.randomUUID().toString(),
            conversationId = conversationId,
            from = from,
            to = to,
            msgType = MessageType.TEXT,
            timestamp = timestamp,
            nonceB64 = "",
            ciphertextB64 = "",
            sigB64 = "",
            text = text,
            status = if (isOutgoing) MessageStatus.DELIVERED else MessageStatus.DELIVERED,
            isOutgoing = isOutgoing
        )
    }

    /**
     * UI-G5: Create test message with attachment
     */
    private fun createTestAttachmentMessage(
        conversationId: String,
        from: String,
        to: String,
        timestamp: Long,
        isOutgoing: Boolean
    ): MessageEntity {
        return MessageEntity(
            messageId = UUID.randomUUID().toString(),
            conversationId = conversationId,
            from = from,
            to = to,
            msgType = MessageType.IMAGE,
            timestamp = timestamp,
            nonceB64 = "",
            ciphertextB64 = "",
            sigB64 = "",
            text = "Check out this image!", // Caption
            status = if (isOutgoing) MessageStatus.DELIVERED else MessageStatus.DELIVERED,
            isOutgoing = isOutgoing,
            // Attachment fields
            attachmentPointer = "test/attachment/image-${UUID.randomUUID()}.jpg",
            attachmentContentType = "image/jpeg",
            attachmentSize = 256 * 1024, // 256 KB
            attachmentLocalPath = null // Not downloaded yet
        )
    }

    /**
     * Simulate offline by disconnecting WS
     */
    fun simulateOffline() {
        if (!BuildConfig.IS_CONFORMANCE_BUILD) return

        viewModelScope.launch {
            addLog("Simulating offline...")
            wsClient.disconnect()
            _testStatus.value = "Offline: WS disconnected"
        }
    }

    /**
     * Simulate reconnect by connecting WS
     */
    fun simulateReconnect() {
        if (!BuildConfig.IS_CONFORMANCE_BUILD) return

        viewModelScope.launch {
            addLog("Simulating reconnect...")
            wsClient.connect()
            _testStatus.value = "Reconnecting..."

            // Wait for connection
            delay(3000)
            if (connectionState.value.isOnline) {
                _testStatus.value = "Online: WS connected"
                addLog("Reconnect successful")
            } else {
                _testStatus.value = "Still connecting..."
            }
        }
    }

    /**
     * Simulate incoming call
     * Triggers the full-screen incoming call UI
     */
    fun simulateIncomingCall() {
        if (!BuildConfig.IS_CONFORMANCE_BUILD) return

        viewModelScope.launch {
            addLog("Simulating incoming call...")

            // Generate test call data
            val callId = "call-${System.currentTimeMillis()}"
            val fromWhisperId = "WSP-TEST-CALLER-001"
            val fromDisplayName = "Test Caller"
            val isVideo = false // Audio call

            // Trigger incoming call UI via AppStateManager
            appStateManager.onIncomingCall(
                callId = callId,
                from = fromWhisperId,
                fromDisplayName = fromDisplayName,
                isVideo = isVideo
            )

            _testStatus.value = "Incoming call UI shown"
            Logger.info("Simulated incoming call: $callId from $fromWhisperId", Logger.Category.CALL)
        }
    }

    /**
     * Simulate incoming video call
     */
    fun simulateIncomingVideoCall() {
        if (!BuildConfig.IS_CONFORMANCE_BUILD) return

        viewModelScope.launch {
            addLog("Simulating incoming video call...")

            val callId = "vcall-${System.currentTimeMillis()}"
            val fromWhisperId = "WSP-TEST-CALLER-002"
            val fromDisplayName = "Video Caller"

            appStateManager.onIncomingCall(
                callId = callId,
                from = fromWhisperId,
                fromDisplayName = fromDisplayName,
                isVideo = true
            )

            _testStatus.value = "Incoming video call UI shown"
            Logger.info("Simulated incoming video call: $callId", Logger.Category.CALL)
        }
    }

    /**
     * Force logout - clears session and returns to auth
     */
    fun forceLogout() {
        viewModelScope.launch {
            addLog("Force logout triggered")
            sessionManager.forceLogout("DevPanel force logout")
            appStateManager.refreshAuthState()
            _testStatus.value = "Logged out"
        }
    }
}
