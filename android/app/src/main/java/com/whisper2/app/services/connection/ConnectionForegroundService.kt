package com.whisper2.app.services.connection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.whisper2.app.R
import com.whisper2.app.core.Logger
import com.whisper2.app.data.network.ws.WsClientImpl
import com.whisper2.app.data.network.ws.WsConnectionState
import com.whisper2.app.services.auth.AuthService
import com.whisper2.app.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import javax.inject.Inject

/**
 * Foreground service to keep WebSocket connection alive when app is in background.
 *
 * This service:
 * - Maintains a persistent notification (required for foreground service)
 * - Monitors WebSocket connection state
 * - Triggers reconnection when connection is lost
 * - Acquires wake lock during reconnection to prevent system from killing the process
 *
 * The service is started when user logs in and stopped when user logs out.
 */
@AndroidEntryPoint
class ConnectionForegroundService : Service() {

    @Inject
    lateinit var wsClient: WsClientImpl

    @Inject
    lateinit var authService: AuthService

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var wakeLock: PowerManager.WakeLock? = null
    private var connectionMonitorJob: Job? = null
    private var isRunning = false

    companion object {
        private const val NOTIFICATION_ID = 9001
        private const val CHANNEL_ID = "connection"
        private const val WAKE_LOCK_TAG = "Whisper2:ConnectionService"

        // Reconnect intervals
        private const val RECONNECT_CHECK_INTERVAL_MS = 30_000L  // Check every 30 seconds
        private const val WAKE_LOCK_TIMEOUT_MS = 60_000L  // 1 minute max wake lock

        @Volatile
        private var isServiceRunning = false

        fun isRunning(): Boolean = isServiceRunning

        fun startService(context: Context) {
            if (isServiceRunning) {
                Logger.d("[ConnectionService] Service already running, skipping start")
                return
            }

            val intent = Intent(context, ConnectionForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Logger.i("[ConnectionService] Service start requested")
            } catch (e: Exception) {
                Logger.e("[ConnectionService] Failed to start service", e)
            }
        }

        fun stopService(context: Context) {
            if (!isServiceRunning) {
                Logger.d("[ConnectionService] Service not running, skipping stop")
                return
            }

            val intent = Intent(context, ConnectionForegroundService::class.java)
            try {
                context.stopService(intent)
                Logger.i("[ConnectionService] Service stop requested")
            } catch (e: Exception) {
                Logger.e("[ConnectionService] Failed to stop service", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Logger.i("[ConnectionService] onCreate")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logger.i("[ConnectionService] onStartCommand - flags=$flags, startId=$startId")

        if (!isRunning) {
            isRunning = true
            isServiceRunning = true

            // Start as foreground service immediately
            startForeground()

            // Start monitoring connection
            startConnectionMonitor()
        }

        // Restart service if killed by system
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Logger.i("[ConnectionService] onDestroy")
        isRunning = false
        isServiceRunning = false

        connectionMonitorJob?.cancel()
        connectionMonitorJob = null

        releaseWakeLock()
        serviceScope.cancel()

        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Connection Service",
                NotificationManager.IMPORTANCE_LOW  // Low importance = no sound, minimized
            ).apply {
                description = "Keeps Whisper2 connected for instant messages"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForeground() {
        val notification = buildNotification("Connected")

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ requires foreground service type
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Logger.i("[ConnectionService] Started as foreground service")
        } catch (e: Exception) {
            Logger.e("[ConnectionService] Failed to start foreground", e)
            stopSelf()
        }
    }

    private fun buildNotification(status: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Whisper2")
            .setContentText(status)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(status: String) {
        val notification = buildNotification(status)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun startConnectionMonitor() {
        connectionMonitorJob?.cancel()
        connectionMonitorJob = serviceScope.launch {
            // Monitor connection state changes
            launch {
                wsClient.connectionState.collectLatest { state ->
                    Logger.d("[ConnectionService] Connection state: $state")

                    when (state) {
                        WsConnectionState.CONNECTED -> {
                            updateNotification("Connected")
                            releaseWakeLock()
                        }
                        WsConnectionState.CONNECTING,
                        WsConnectionState.RECONNECTING -> {
                            updateNotification("Connecting...")
                            acquireWakeLock()
                        }
                        WsConnectionState.DISCONNECTED -> {
                            updateNotification("Reconnecting...")
                            acquireWakeLock()
                            triggerReconnect()
                        }
                        WsConnectionState.AUTH_EXPIRED -> {
                            updateNotification("Session expired")
                            releaseWakeLock()
                        }
                    }
                }
            }

            // Periodic connection check (backup for missed state changes)
            launch {
                while (isActive) {
                    delay(RECONNECT_CHECK_INTERVAL_MS)
                    checkAndReconnect()
                }
            }
        }
    }

    private suspend fun checkAndReconnect() {
        val state = wsClient.connectionState.value
        if (state == WsConnectionState.DISCONNECTED) {
            Logger.i("[ConnectionService] Periodic check: disconnected, triggering reconnect")
            triggerReconnect()
        }
    }

    private suspend fun triggerReconnect() {
        try {
            acquireWakeLock()
            Logger.i("[ConnectionService] Triggering reconnect...")
            authService.reconnect()
        } catch (e: Exception) {
            Logger.e("[ConnectionService] Reconnect failed", e)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) {
            return
        }

        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKE_LOCK_TAG
            ).apply {
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
            Logger.d("[ConnectionService] Wake lock acquired")
        } catch (e: Exception) {
            Logger.e("[ConnectionService] Failed to acquire wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Logger.d("[ConnectionService] Wake lock released")
            }
        } catch (e: Exception) {
            Logger.e("[ConnectionService] Failed to release wake lock", e)
        }
        wakeLock = null
    }
}
