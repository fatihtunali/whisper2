package com.whisper2.app.services.push

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.whisper2.app.R
import com.whisper2.app.core.Constants
import com.whisper2.app.core.Logger
import com.whisper2.app.data.local.prefs.SecureStorage
import com.whisper2.app.data.network.ws.UpdateTokensPayload
import com.whisper2.app.data.network.ws.WsClientImpl
import com.whisper2.app.data.network.ws.WsConnectionState
import com.whisper2.app.data.network.ws.WsFrame
import com.whisper2.app.services.auth.AuthService
import com.whisper2.app.services.calls.CallForegroundService
import com.whisper2.app.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class FcmService : FirebaseMessagingService() {

    @Inject lateinit var secureStorage: SecureStorage
    @Inject lateinit var wsClient: WsClientImpl
    @Inject lateinit var authService: AuthService

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val CHANNEL_ID_MESSAGES = "messages"
        private const val NOTIFICATION_ID_MESSAGE = 1001
        private const val WAKE_LOCK_TAG = "Whisper2:FcmService"
        private const val WAKE_LOCK_TIMEOUT_MS = 30_000L  // 30 seconds max
        // NOTE: Call notifications are handled by CallForegroundService, not FcmService
    }

    override fun onCreate() {
        super.onCreate()
        // Notification channels are created in App.kt
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Logger.i("[FcmService] FCM token refreshed: ${token.take(20)}...")

        // Save token locally
        secureStorage.fcmToken = token

        // Sync with server if connected
        syncTokenWithServer(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        Logger.i("[FcmService] FCM message received from: ${message.from}")
        Logger.d("[FcmService] Data payload: ${message.data}")
        Logger.d("[FcmService] Notification payload: ${message.notification?.let { "title=${it.title}, body=${it.body}" } ?: "null"}")

        val data = message.data
        val pushType = data["type"]
        val pushReason = data["reason"]
        Logger.d("[FcmService] Push type: $pushType, reason: $pushReason")

        // Server sends type="wake" with reason="message"|"call"|"system"
        if (pushType == "wake") {
            when (pushReason) {
                "message" -> handleWakeMessage()
                "call" -> handleWakeCall(data)  // Pass data for call details
                "system" -> handleWakeSystem()
                else -> {
                    Logger.d("[FcmService] Unknown wake reason: $pushReason")
                    handleWakeSystem() // Default to system wake
                }
            }
        } else {
            // Legacy format support
            when (pushType) {
                "message" -> handleMessagePush(data)
                "call" -> handleCallPush(data)
                "group" -> handleGroupPush(data)
                "wakeup" -> handleWakeupPush()
                else -> {
                    // GUARD: Never show notification for call-related pushes
                    // CallForegroundService handles all call UI
                    if (pushType == "call" || pushReason == "call" || data["callId"] != null) {
                        Logger.i("[FcmService] Call-related push in else branch - only waking connection, no notification")
                        wakeUpConnection()
                        return
                    }

                    Logger.d("[FcmService] Unknown push type, checking for notification payload")
                    // Generic notification from RemoteMessage.notification
                    message.notification?.let { notification ->
                        Logger.d("[FcmService] Showing generic notification")
                        showMessageNotification(
                            title = notification.title ?: "Whisper2",
                            body = notification.body ?: "New message"
                        )
                    } ?: run {
                        // If no notification payload but has data, show data as notification
                        if (data.isNotEmpty()) {
                            Logger.d("[FcmService] Showing notification from data payload")
                            showMessageNotification(
                                title = data["title"] ?: "Whisper2",
                                body = data["body"] ?: data["message"] ?: "New notification"
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Handle wake push for new messages.
     * Server sends type=wake, reason=message when there are pending messages.
     */
    private fun handleWakeMessage() {
        Logger.i("[FcmService] Wake push for message - showing notification and connecting")

        // Show message notification with stable ID (updates instead of stacking)
        showMessageNotification(
            title = "Whisper2",
            body = "You have new messages",
            useStableId = true  // Generic wake - don't spam multiple notifications
        )

        // Wake up WebSocket to fetch pending messages
        wakeUpConnection()
    }

    /**
     * Handle wake push for incoming call.
     *
     * WhatsApp-like behavior: If FCM payload includes call details (callId, from, isVideo),
     * we start CallForegroundService IMMEDIATELY without waiting for WebSocket.
     * This ensures instant call UI even when app is killed.
     *
     * WebSocket is still woken for signaling (SDP offer/answer, ICE candidates).
     */
    private fun handleWakeCall(data: Map<String, String>) {
        val callId = data["callId"]
        val from = data["from"]
        val callerName = data["callerName"] ?: from
        val isVideo = data["isVideo"]?.toBoolean() ?: false

        Logger.i("[FcmService] Wake push for call - callId=$callId, from=$from, isVideo=$isVideo")

        // INSTANT UI: If FCM includes call details, show call UI immediately
        // Don't wait for WebSocket - it may take 1-3 seconds to reconnect
        if (callId != null && from != null) {
            Logger.i("[FcmService] Starting CallForegroundService immediately (WhatsApp-like)")
            try {
                CallForegroundService.startIncomingCall(
                    context = this,
                    callId = callId,
                    callerId = from,
                    callerName = callerName,
                    isVideo = isVideo
                )
            } catch (e: Exception) {
                // Android 12+ may throw ForegroundServiceStartNotAllowedException
                // Fallback: just wake WebSocket and rely on WS + app UI
                Logger.e("[FcmService] Failed to start CallForegroundService from FCM (Android 12+ restriction?)", e)
            }
        } else {
            Logger.i("[FcmService] No call details in FCM, waiting for WebSocket")
        }

        // Also wake WebSocket for signaling (SDP offer/answer, ICE)
        wakeUpConnection()
    }

    /**
     * Handle wake push for system notification.
     */
    private fun handleWakeSystem() {
        Logger.i("[FcmService] Wake push for system - connecting to check updates")
        wakeUpConnection()
    }

    private fun handleMessagePush(data: Map<String, String>) {
        val fromId = data["from"] ?: return
        val preview = data["preview"] ?: "New message"

        Logger.d("[FcmService] Message push from: $fromId")

        // Show message notification (no sound/vibration - channel handles it)
        showMessageNotification(
            title = "New message",
            body = preview,
            conversationId = fromId
        )

        // Wake up WebSocket to fetch pending messages
        wakeUpConnection()
    }

    private fun handleCallPush(data: Map<String, String>) {
        val callId = data["callId"] ?: return
        val fromId = data["from"] ?: return
        val callerName = data["callerName"] ?: fromId
        val isVideo = data["isVideo"]?.toBoolean() ?: false

        Logger.i("[FcmService] Call push (legacy) - callId: $callId, from: $fromId, isVideo: $isVideo")

        // INSTANT UI: Start CallForegroundService immediately (WhatsApp-like)
        Logger.i("[FcmService] Starting CallForegroundService immediately")
        try {
            CallForegroundService.startIncomingCall(
                context = this,
                callId = callId,
                callerId = fromId,
                callerName = callerName,
                isVideo = isVideo
            )
        } catch (e: Exception) {
            // Android 12+ may throw ForegroundServiceStartNotAllowedException
            Logger.e("[FcmService] Failed to start CallForegroundService (legacy path)", e)
        }

        // Also wake WebSocket for signaling (SDP offer/answer, ICE)
        wakeUpConnection()
    }

    private fun handleGroupPush(data: Map<String, String>) {
        val groupId = data["groupId"] ?: return
        val groupName = data["groupName"] ?: "Group"
        val preview = data["preview"] ?: "New group message"

        Logger.d("[FcmService] Group push - groupId: $groupId")

        // Show message notification (no sound/vibration - channel handles it)
        showMessageNotification(
            title = groupName,
            body = preview,
            conversationId = groupId
        )

        wakeUpConnection()
    }

    private fun handleWakeupPush() {
        Logger.d("[FcmService] Wakeup push - connecting to fetch pending messages")
        wakeUpConnection()
    }

    private fun wakeUpConnection() {
        scope.launch {
            var wakeLock: PowerManager.WakeLock? = null
            try {
                // Acquire wake lock to ensure reconnection completes
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    WAKE_LOCK_TAG
                ).apply {
                    acquire(WAKE_LOCK_TIMEOUT_MS)
                }
                Logger.d("[FcmService] Wake lock acquired for reconnection")

                if (wsClient.connectionState.value != WsConnectionState.CONNECTED) {
                    Logger.d("[FcmService] Reconnecting and authenticating WebSocket...")
                    // Use authService.reconnect() instead of wsClient.connect()
                    // This ensures proper authentication after connection
                    authService.reconnect()

                    // Wait a bit for connection to establish
                    var waitTime = 0L
                    while (waitTime < 10_000 && wsClient.connectionState.value != WsConnectionState.CONNECTED) {
                        delay(500)
                        waitTime += 500
                    }

                    if (wsClient.connectionState.value == WsConnectionState.CONNECTED) {
                        Logger.i("[FcmService] Successfully reconnected WebSocket")
                    } else {
                        Logger.w("[FcmService] WebSocket reconnection timeout, state: ${wsClient.connectionState.value}")
                    }
                }
                // MessageHandler will automatically fetch pending messages on connect
            } catch (e: Exception) {
                Logger.e("[FcmService] Failed to wake up connection", e)
            } finally {
                // Release wake lock
                try {
                    if (wakeLock?.isHeld == true) {
                        wakeLock.release()
                        Logger.d("[FcmService] Wake lock released")
                    }
                } catch (e: Exception) {
                    Logger.e("[FcmService] Failed to release wake lock", e)
                }
            }
        }
    }

    private fun syncTokenWithServer(token: String) {
        scope.launch {
            try {
                val sessionToken = secureStorage.sessionToken
                if (sessionToken == null) {
                    Logger.d("[FcmService] Not logged in, will sync token on next auth")
                    return@launch
                }

                if (wsClient.connectionState.value == WsConnectionState.CONNECTED) {
                    val payload = UpdateTokensPayload(
                        sessionToken = sessionToken,
                        pushToken = token
                    )
                    wsClient.send(WsFrame(Constants.MsgType.UPDATE_TOKENS, payload = payload))
                    Logger.i("[FcmService] FCM token synced with server")
                } else {
                    Logger.d("[FcmService] WebSocket not connected, token will sync on reconnect")
                }
            } catch (e: Exception) {
                Logger.e("[FcmService] Failed to sync token with server", e)
            }
        }
    }

    /**
     * Show a MESSAGE notification only.
     * - NO setDefaults(DEFAULT_ALL) - let channel handle sound/vibration
     * - NO fullScreenIntent - messages don't need to wake screen like calls
     * - NEVER use this for calls - CallForegroundService handles call UI
     *
     * @param useStableId If true, uses NOTIFICATION_ID_MESSAGE (updates instead of stacking).
     *                    Use for generic "wake" notifications. False for per-conversation messages.
     */
    private fun showMessageNotification(
        title: String,
        body: String,
        conversationId: String? = null,
        useStableId: Boolean = false
    ) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            conversationId?.let { putExtra("conversationId", it) }
        }

        // Use elapsedRealtime to avoid negative values and reduce collisions
        val requestCode = (SystemClock.elapsedRealtime() % Int.MAX_VALUE).toInt()

        val pendingIntent = PendingIntent.getActivity(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Stable ID for generic wakes (updates instead of spamming)
        // Unique ID for per-conversation messages (they stack)
        val notificationId = if (useStableId) {
            NOTIFICATION_ID_MESSAGE
        } else {
            NOTIFICATION_ID_MESSAGE + (requestCode % 1000)
        }

        // Message notification - let channel configuration handle sound/vibration
        // DO NOT use setDefaults(DEFAULT_ALL) - causes duplicate sounds
        // DO NOT use setFullScreenIntent - messages don't need to interrupt like calls
        val builder = NotificationCompat.Builder(this, CHANNEL_ID_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, builder.build())
        Logger.i("[FcmService] Message notification posted - id: $notificationId, title: $title, stable=$useStableId")
    }
}
