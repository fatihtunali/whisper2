package com.whisper2.app.services.calls

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import com.whisper2.app.App
import com.whisper2.app.R
import com.whisper2.app.core.Logger
import com.whisper2.app.data.local.prefs.SecureStorage
import com.whisper2.app.ui.IncomingCallActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * CallForegroundService is the SINGLE AUTHORITY for:
 * - Starting/stopping ringtone
 * - Starting/stopping vibration
 * - Showing call notifications
 * - Coordinating answer/decline actions
 *
 * All Answer/Decline actions from notifications go through this service,
 * which then calls CallService for signaling and launches the UI.
 */
@AndroidEntryPoint
class CallForegroundService : Service() {

    companion object {
        const val ACTION_INCOMING_CALL = "ACTION_INCOMING_CALL"
        const val ACTION_CALL_ACTIVE = "ACTION_CALL_ACTIVE"
        const val ACTION_CALL_ANSWERED = "ACTION_CALL_ANSWERED"
        const val ACTION_CALL_DECLINED = "ACTION_CALL_DECLINED"
        const val ACTION_CALL_ENDED = "ACTION_CALL_ENDED"

        const val EXTRA_CALL_ID = "EXTRA_CALL_ID"
        const val EXTRA_CALLER_ID = "EXTRA_CALLER_ID"
        const val EXTRA_CALLER_NAME = "EXTRA_CALLER_NAME"
        const val EXTRA_IS_VIDEO = "EXTRA_IS_VIDEO"

        private const val NOTIFICATION_ID = 2001
        // Channel ID is dynamic - may be "calls" or "calls_v2" if migrated
        // Use getCallsChannelId() which handles cold-start race conditions

        fun startIncomingCall(
            context: Context,
            callId: String,
            callerId: String,
            callerName: String?,
            isVideo: Boolean
        ) {
            val intent = Intent(context, CallForegroundService::class.java).apply {
                action = ACTION_INCOMING_CALL
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_CALLER_ID, callerId)
                putExtra(EXTRA_CALLER_NAME, callerName ?: callerId)
                putExtra(EXTRA_IS_VIDEO, isVideo)
            }
            context.startForegroundService(intent)
        }

        /**
         * Transition to active call notification (keeps foreground service alive)
         */
        fun setCallActive(
            context: Context,
            callerName: String?,
            isVideo: Boolean
        ) {
            val intent = Intent(context, CallForegroundService::class.java).apply {
                action = ACTION_CALL_ACTIVE
                putExtra(EXTRA_CALLER_NAME, callerName ?: "Unknown")
                putExtra(EXTRA_IS_VIDEO, isVideo)
            }
            context.startForegroundService(intent)
        }

        fun stopService(context: Context) {
            context.stopService(Intent(context, CallForegroundService::class.java))
        }
    }

    @Inject
    lateinit var callService: dagger.Lazy<CallService>

    @Inject
    lateinit var secureStorage: SecureStorage

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var vibrator: Vibrator? = null
    private var ringtone: Ringtone? = null

    // Track per-callId to prevent FCM+WS double-ring (RAM copy for fast access)
    private var ringingCallId: String? = null

    /**
     * Get the correct calls channel ID, handling cold-start race conditions.
     * FCM can wake the app and start service before App.onCreate() completes.
     * Query NotificationManager directly as fallback.
     * Also ensures the channel exists before returning.
     */
    private fun getCallsChannelId(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return App.CALLS_CHANNEL_ID
        }

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Fast path: App already set to v2
        val cached = App.currentCallsChannelId
        if (cached == App.CALLS_CHANNEL_ID_V2) {
            ensureChannelExists(nm, App.CALLS_CHANNEL_ID_V2)
            return App.CALLS_CHANNEL_ID_V2
        }

        // Check if old channel is noisy (cold-start safety)
        val oldChannel = nm.getNotificationChannel(App.CALLS_CHANNEL_ID)
        if (oldChannel != null) {
            val hasSound = oldChannel.sound != null
            val hasVibration = oldChannel.vibrationPattern != null || oldChannel.shouldVibrate()
            if (hasSound || hasVibration) {
                Logger.d("[CallForegroundService] Cold-start detected noisy channel, using calls_v2")
                ensureChannelExists(nm, App.CALLS_CHANNEL_ID_V2)
                return App.CALLS_CHANNEL_ID_V2
            }
        }

        // Use default channel, ensure it exists
        ensureChannelExists(nm, App.CALLS_CHANNEL_ID)
        return App.CALLS_CHANNEL_ID
    }

    /**
     * Ensure the notification channel exists (cold-start safety).
     * FCM can wake the service before App.onCreate() creates channels.
     */
    private fun ensureChannelExists(nm: NotificationManager, channelId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        if (nm.getNotificationChannel(channelId) == null) {
            Logger.i("[CallForegroundService] Creating channel $channelId (cold-start)")
            val channel = NotificationChannel(
                channelId,
                "Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Whisper2 incoming call notifications"
                enableVibration(false)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(false)
                setSound(null, null)
            }
            nm.createNotificationChannel(channel)
        }
    }

    /**
     * Check if user has disabled the calls notification channel.
     * If disabled, we should respect that and not ring manually either.
     */
    private fun isCallChannelEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = getCallsChannelId()
        val channel = nm.getNotificationChannel(channelId) ?: return true

        // IMPORTANCE_NONE means user disabled the channel
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    // Store call info for answer/decline actions
    private var pendingCallerId: String? = null
    private var pendingCallerName: String? = null
    private var pendingIsVideo: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Logger.d("[CallForegroundService] onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logger.i("[CallForegroundService] onStartCommand: action=${intent?.action}")

        when (intent?.action) {
            ACTION_INCOMING_CALL -> {
                val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: ""
                val callerId = intent.getStringExtra(EXTRA_CALLER_ID) ?: ""
                val callerName = intent.getStringExtra(EXTRA_CALLER_NAME) ?: callerId
                val isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)

                Logger.i("[CallForegroundService] *** INCOMING CALL ***")
                Logger.i("[CallForegroundService] callId=$callId, ringingCallId=$ringingCallId")
                Logger.i("[CallForegroundService] callerId=$callerId, callerName=$callerName, isVideo=$isVideo")

                // Prevent double-ringing from FCM + WebSocket race
                // Check both RAM (fast) and persistent storage (survives process death)
                if (callId == ringingCallId) {
                    Logger.i("[CallForegroundService] Same call already ringing (RAM), ignoring duplicate")
                    return START_NOT_STICKY
                }

                // Check persistent storage for dedupe across process restarts (OEM battery killer)
                if (secureStorage.isCallAlreadyRinging(callId)) {
                    Logger.i("[CallForegroundService] Same call already ringing (persistent), ignoring duplicate")
                    return START_NOT_STICKY
                }

                // New call - stop old ringing if different callId
                if (ringingCallId != null && ringingCallId != callId) {
                    Logger.i("[CallForegroundService] Different call arriving, stopping old ring")
                    stopRingtone()
                    stopVibration()
                }

                // Store call info for answer/decline (RAM + persistent)
                ringingCallId = callId
                secureStorage.setCallRinging(callId)
                pendingCallerId = callerId
                pendingCallerName = callerName
                pendingIsVideo = isVideo

                showIncomingCallNotification(callId, callerId, callerName, isVideo)

                // Respect user's channel settings - if channel disabled, don't ring manually either
                if (isCallChannelEnabled()) {
                    startRingtone()
                    startVibration()
                } else {
                    Logger.i("[CallForegroundService] Call channel disabled by user, skipping manual ring/vibration")
                }
            }

            ACTION_CALL_ANSWERED -> {
                val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: ringingCallId ?: ""
                val callerId = intent.getStringExtra(EXTRA_CALLER_ID) ?: pendingCallerId ?: ""
                val callerName = intent.getStringExtra(EXTRA_CALLER_NAME) ?: pendingCallerName ?: "Unknown"
                val isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, pendingIsVideo)

                Logger.i("[CallForegroundService] *** ANSWER from notification *** callId=$callId")

                // 1. Stop ringtone/vibration FIRST (service is single authority)
                stopRingtone()
                stopVibration()

                // 2. Call signaling layer to answer
                serviceScope.launch {
                    try {
                        callService.get().answerCall()
                    } catch (e: Exception) {
                        Logger.e("[CallForegroundService] Failed to answer call", e)
                    }
                }

                // 3. Transition to active call notification
                showOngoingCallNotification(callerName, isVideo)
                ringingCallId = null
                secureStorage.clearRingingCall()

                // 4. Launch the call UI activity
                val activityIntent = Intent(this, IncomingCallActivity::class.java).apply {
                    putExtra(EXTRA_CALL_ID, callId)
                    putExtra(EXTRA_CALLER_ID, callerId)
                    putExtra(EXTRA_CALLER_NAME, callerName)
                    putExtra(EXTRA_IS_VIDEO, isVideo)
                    putExtra("already_answered", true)  // Skip answer UI, go straight to call
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(activityIntent)
            }

            ACTION_CALL_DECLINED -> {
                val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: ringingCallId ?: ""
                Logger.i("[CallForegroundService] *** DECLINE from notification *** callId=$callId")

                // 1. Stop ringtone/vibration FIRST
                stopRingtone()
                stopVibration()

                // 2. Call signaling layer to decline
                serviceScope.launch {
                    try {
                        callService.get().declineCall()
                    } catch (e: Exception) {
                        Logger.e("[CallForegroundService] Failed to decline call", e)
                    }
                }

                // 3. Cleanup and stop service
                ringingCallId = null
                secureStorage.clearRingingCall()
                pendingCallerId = null
                pendingCallerName = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            ACTION_CALL_ACTIVE -> {
                // Transition from ringing to active call - keep service alive!
                val callerName = intent.getStringExtra(EXTRA_CALLER_NAME) ?: pendingCallerName ?: "Unknown"
                val isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, pendingIsVideo)

                Logger.i("[CallForegroundService] *** CALL ACTIVE *** callerName=$callerName, isVideo=$isVideo")
                stopRingtone()
                stopVibration()
                ringingCallId = null
                secureStorage.clearRingingCall()
                showOngoingCallNotification(callerName, isVideo)
            }

            ACTION_CALL_ENDED -> {
                Logger.i("[CallForegroundService] *** CALL ENDED ***")

                // Stop ringtone/vibration
                stopRingtone()
                stopVibration()

                // Call signaling layer to end call
                serviceScope.launch {
                    try {
                        callService.get().endCall()
                    } catch (e: Exception) {
                        Logger.e("[CallForegroundService] Failed to end call", e)
                    }
                }

                // Cleanup (RAM + persistent)
                ringingCallId = null
                secureStorage.clearRingingCall()
                pendingCallerId = null
                pendingCallerName = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRingtone()
        stopVibration()
        ringingCallId = null
        // Note: Don't clear persistent storage here - service may be killed by system
        // while call is still ringing. Persistent state has its own timeout (60s).
        pendingCallerId = null
        pendingCallerName = null
        Logger.d("[CallForegroundService] onDestroy")
    }

    private fun showIncomingCallNotification(
        callId: String,
        callerId: String,
        callerName: String,
        isVideo: Boolean
    ) {
        // Full screen intent - opens IncomingCallActivity when notification is tapped
        val fullScreenIntent = Intent(this, IncomingCallActivity::class.java).apply {
            putExtra(EXTRA_CALL_ID, callId)
            putExtra(EXTRA_CALLER_ID, callerId)
            putExtra(EXTRA_CALLER_NAME, callerName)
            putExtra(EXTRA_IS_VIDEO, isVideo)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, 0, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Answer action - goes to SERVICE (single authority for ringtone control)
        val answerIntent = Intent(this, CallForegroundService::class.java).apply {
            action = ACTION_CALL_ANSWERED
            putExtra(EXTRA_CALL_ID, callId)
            putExtra(EXTRA_CALLER_ID, callerId)
            putExtra(EXTRA_CALLER_NAME, callerName)
            putExtra(EXTRA_IS_VIDEO, isVideo)
        }
        val answerPendingIntent = PendingIntent.getService(
            this, 1, answerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Decline action - goes to SERVICE (single authority)
        val declineIntent = Intent(this, CallForegroundService::class.java).apply {
            action = ACTION_CALL_DECLINED
            putExtra(EXTRA_CALL_ID, callId)
        }
        val declinePendingIntent = PendingIntent.getService(
            this, 2, declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val callType = if (isVideo) "Video Call" else "Voice Call"

        // Use modern CallStyle notification for Android 12+ (API 31+)
        // Channel is SILENT - ringtone/vibration handled manually
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val caller = Person.Builder()
                .setName(callerName)
                .setImportant(true)
                .build()

            Notification.Builder(this, getCallsChannelId())
                .setSmallIcon(Icon.createWithResource(this, R.drawable.ic_notification))
                .setContentIntent(fullScreenPendingIntent)
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .setStyle(Notification.CallStyle.forIncomingCall(
                    caller,
                    declinePendingIntent,
                    answerPendingIntent
                ))
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_CALL)
                .build()
        } else {
            // Fallback for older Android versions
            NotificationCompat.Builder(this, getCallsChannelId())
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(callerName)
                .setContentText("Incoming $callType")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(false)
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .addAction(R.drawable.ic_notification, "Decline", declinePendingIntent)
                .addAction(R.drawable.ic_notification, "Answer", answerPendingIntent)
                .build()
        }

        // Use MICROPHONE foreground service type for VoIP (not PHONE_CALL which is for telephony)
        // PHONE_CALL type has stricter Play Store policy requirements
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ - use microphone type
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10-13 - phoneCall type is more permissive here
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        Logger.i("[CallForegroundService] Showing incoming call notification for $callerName (CallStyle=${Build.VERSION.SDK_INT >= Build.VERSION_CODES.S})")
    }

    private fun showOngoingCallNotification(callerName: String, isVideo: Boolean) {
        val callType = if (isVideo) "Video call" else "Voice call"

        // Use modern CallStyle for ongoing call on Android 12+
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val caller = Person.Builder()
                .setName(callerName)
                .setImportant(true)
                .build()

            // End call action - goes to service
            val endCallIntent = Intent(this, CallForegroundService::class.java).apply {
                action = ACTION_CALL_ENDED
            }
            val endCallPendingIntent = PendingIntent.getService(
                this, 3, endCallIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            Notification.Builder(this, getCallsChannelId())
                .setSmallIcon(Icon.createWithResource(this, R.drawable.ic_notification))
                .setStyle(Notification.CallStyle.forOngoingCall(caller, endCallPendingIntent))
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_CALL)
                .build()
        } else {
            NotificationCompat.Builder(this, getCallsChannelId())
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(callerName)
                .setContentText("$callType in progress")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .build()
        }

        // Update the notification (keeps foreground service running)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)

        Logger.i("[CallForegroundService] Showing ongoing call notification for $callerName")
    }

    private fun startVibration() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        val pattern = longArrayOf(0, 1000, 500, 1000, 500)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, 0)
        }
        Logger.i("[CallForegroundService] Vibration started")
    }

    private fun stopVibration() {
        vibrator?.cancel()
        vibrator = null
        Logger.d("[CallForegroundService] Vibration stopped")
    }

    private fun startRingtone() {
        // Prevent duplicate ringtones - check ringingCallId
        if (ringtone?.isPlaying == true) {
            Logger.i("[CallForegroundService] Ringtone already playing, skipping")
            return
        }

        try {
            // Stop any existing ringtone first (safety net)
            ringtone?.stop()
            ringtone = null

            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    isLooping = true
                }
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                play()
            }
            Logger.i("[CallForegroundService] Ringtone started")
        } catch (e: Exception) {
            Logger.e("[CallForegroundService] Failed to start ringtone: ${e.message}")
        }
    }

    private fun stopRingtone() {
        try {
            ringtone?.let {
                if (it.isPlaying) {
                    it.stop()
                }
            }
            ringtone = null
            Logger.d("[CallForegroundService] Ringtone stopped")
        } catch (e: Exception) {
            Logger.e("[CallForegroundService] Failed to stop ringtone: ${e.message}")
        }
    }
}
