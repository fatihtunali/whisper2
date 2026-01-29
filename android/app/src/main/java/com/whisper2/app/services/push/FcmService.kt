package com.whisper2.app.services.push

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import com.whisper2.app.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class FcmService : FirebaseMessagingService() {

    @Inject lateinit var secureStorage: SecureStorage
    @Inject lateinit var wsClient: WsClientImpl

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val CHANNEL_ID_MESSAGES = "messages"
        private const val CHANNEL_ID_CALLS = "calls"
        private const val NOTIFICATION_ID_MESSAGE = 1001
        private const val NOTIFICATION_ID_CALL = 2001
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
                "call" -> handleWakeCall()
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
                    Logger.d("[FcmService] Unknown push type, checking for notification payload")
                    // Generic notification from RemoteMessage.notification
                    message.notification?.let { notification ->
                        Logger.d("[FcmService] Showing generic notification")
                        showNotification(
                            title = notification.title ?: "Whisper2",
                            body = notification.body ?: "New message"
                        )
                    } ?: run {
                        // If no notification payload but has data, show data as notification
                        if (data.isNotEmpty()) {
                            Logger.d("[FcmService] Showing notification from data payload")
                            showNotification(
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

        // Show notification
        showNotification(
            title = "Whisper2",
            body = "You have new messages"
        )

        // Wake up WebSocket to fetch pending messages
        wakeUpConnection()
    }

    /**
     * Handle wake push for incoming call.
     * NOTE: We do NOT show a notification here. The WebSocket will receive
     * the call_incoming message and the Telecom/CallForegroundService will
     * show the proper incoming call UI with answer/decline buttons.
     */
    private fun handleWakeCall() {
        Logger.i("[FcmService] Wake push for call - waking connection (no notification, Telecom handles UI)")

        // Only wake up WebSocket for call signaling
        // The call_incoming handler will show proper UI via CallForegroundService
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

        // Show notification
        showNotification(
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
        val isVideo = data["isVideo"]?.toBoolean() ?: false

        Logger.i("[FcmService] Call push (legacy) - callId: $callId, from: $fromId, isVideo: $isVideo")
        Logger.i("[FcmService] NOT showing notification - CallForegroundService will handle UI")

        // DO NOT show notification here!
        // CallForegroundService will show the proper incoming call UI
        // with answer/decline buttons and route to IncomingCallActivity
        // (unified activity handles both audio and video calls)

        // Only wake up WebSocket for call signaling
        wakeUpConnection()
    }

    private fun handleGroupPush(data: Map<String, String>) {
        val groupId = data["groupId"] ?: return
        val groupName = data["groupName"] ?: "Group"
        val preview = data["preview"] ?: "New group message"

        Logger.d("[FcmService] Group push - groupId: $groupId")

        showNotification(
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
            try {
                if (wsClient.connectionState.value != WsConnectionState.CONNECTED) {
                    Logger.d("[FcmService] Reconnecting WebSocket...")
                    wsClient.connect()
                }
                // MessageHandler will automatically fetch pending messages on connect
            } catch (e: Exception) {
                Logger.e("[FcmService] Failed to wake up connection", e)
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

    private fun showNotification(
        title: String,
        body: String,
        conversationId: String? = null,
        isCall: Boolean = false,
        callId: String? = null
    ) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            conversationId?.let { putExtra("conversationId", it) }
            callId?.let { putExtra("callId", it) }
            if (isCall) putExtra("isIncomingCall", true)
        }

        // Use unique request code for each notification to avoid PendingIntent reuse
        val requestCode = System.currentTimeMillis().toInt()

        val pendingIntent = PendingIntent.getActivity(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = if (isCall) CHANNEL_ID_CALLS else CHANNEL_ID_MESSAGES
        // Use unique notification ID for messages so they don't replace each other
        val notificationId = if (isCall) NOTIFICATION_ID_CALL else (NOTIFICATION_ID_MESSAGE + requestCode % 1000)

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(if (isCall) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (isCall) NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // Show on lock screen
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setDefaults(NotificationCompat.DEFAULT_ALL) // Sound, vibration, lights

        // Add full screen intent for lock screen / heads-up display
        val fullScreenIntent = PendingIntent.getActivity(
            this,
            requestCode + 1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.setFullScreenIntent(fullScreenIntent, true)

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, builder.build())
        Logger.i("[FcmService] Notification posted - id: $notificationId, title: $title, channel: $channelId")
    }
}
