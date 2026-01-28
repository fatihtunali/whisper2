package com.whisper2.app.services.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
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
        private const val CHANNEL_ID = "whisper2_messages"
        private const val CHANNEL_NAME = "Messages"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
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

        val data = message.data
        val pushType = data["type"]

        when (pushType) {
            "message" -> handleMessagePush(data)
            "call" -> handleCallPush(data)
            "group" -> handleGroupPush(data)
            "wakeup" -> handleWakeupPush()
            else -> {
                // Generic notification from RemoteMessage.notification
                message.notification?.let { notification ->
                    showNotification(
                        title = notification.title ?: "Whisper2",
                        body = notification.body ?: "New message"
                    )
                }
            }
        }
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

        Logger.d("[FcmService] Call push - callId: $callId, from: $fromId, isVideo: $isVideo")

        // For calls, the CallService with CallKit-equivalent handling should be triggered
        // This is handled by VoIP push on iOS, on Android we use high-priority FCM
        showNotification(
            title = if (isVideo) "Incoming Video Call" else "Incoming Call",
            body = "From $fromId",
            isCall = true,
            callId = callId
        )

        // Wake up WebSocket for call signaling
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

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Whisper2 message notifications"
                enableVibration(true)
                setShowBadge(true)
            }

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
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

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(if (isCall) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (isCall) NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }
}
