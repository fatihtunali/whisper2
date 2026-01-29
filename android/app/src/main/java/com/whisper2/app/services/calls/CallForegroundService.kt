package com.whisper2.app.services.calls

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import com.whisper2.app.R
import com.whisper2.app.core.Logger
import com.whisper2.app.ui.IncomingCallActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class CallForegroundService : Service() {

    companion object {
        const val ACTION_INCOMING_CALL = "ACTION_INCOMING_CALL"
        const val ACTION_CALL_ANSWERED = "ACTION_CALL_ANSWERED"
        const val ACTION_CALL_DECLINED = "ACTION_CALL_DECLINED"
        const val ACTION_CALL_ENDED = "ACTION_CALL_ENDED"

        const val EXTRA_CALL_ID = "EXTRA_CALL_ID"
        const val EXTRA_CALLER_ID = "EXTRA_CALLER_ID"
        const val EXTRA_CALLER_NAME = "EXTRA_CALLER_NAME"
        const val EXTRA_IS_VIDEO = "EXTRA_IS_VIDEO"

        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "calls"

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

        fun stopService(context: Context) {
            context.stopService(Intent(context, CallForegroundService::class.java))
        }
    }

    private var vibrator: Vibrator? = null

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
                Logger.i("[CallForegroundService] callId=$callId")
                Logger.i("[CallForegroundService] callerId=$callerId")
                Logger.i("[CallForegroundService] callerName=$callerName")
                Logger.i("[CallForegroundService] isVideo=$isVideo")

                showIncomingCallNotification(callId, callerId, callerName, isVideo)
                startVibration()
            }
            ACTION_CALL_ANSWERED, ACTION_CALL_DECLINED, ACTION_CALL_ENDED -> {
                stopVibration()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVibration()
        Logger.d("[CallForegroundService] onDestroy")
    }

    private fun showIncomingCallNotification(
        callId: String,
        callerId: String,
        callerName: String,
        isVideo: Boolean
    ) {
        // Use unified IncomingCallActivity for both audio and video calls
        val activityClass = IncomingCallActivity::class.java

        Logger.i("[CallForegroundService] Routing to IncomingCallActivity (isVideo=$isVideo)")

        // Full screen intent - opens the appropriate activity
        // Use SINGLE_TOP to reuse existing activity instead of creating new one
        val fullScreenIntent = Intent(this, activityClass).apply {
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

        // Answer action - launch the appropriate activity with answer flag
        // Use SINGLE_TOP to reuse existing activity
        val answerIntent = Intent(this, activityClass).apply {
            putExtra(EXTRA_CALL_ID, callId)
            putExtra(EXTRA_CALLER_ID, callerId)
            putExtra(EXTRA_CALLER_NAME, callerName)
            putExtra(EXTRA_IS_VIDEO, isVideo)
            putExtra("auto_answer", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val answerPendingIntent = PendingIntent.getActivity(
            this, 1, answerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Decline action
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
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Create Person for the caller
            val caller = Person.Builder()
                .setName(callerName)
                .setImportant(true)
                .build()

            Notification.Builder(this, CHANNEL_ID)
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
            NotificationCompat.Builder(this, CHANNEL_ID)
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

        // Android 10+ (API 29+) requires foregroundServiceType to match manifest declaration
        // Android 14+ (API 34+) strictly enforces this - missing type causes SecurityException crash
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
    }

    private fun stopVibration() {
        vibrator?.cancel()
        vibrator = null
    }
}
