package com.whisper2.app

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.whisper2.app.core.Logger
import com.whisper2.app.services.calls.CallService
import com.whisper2.app.services.calls.CallState
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class App : Application(), DefaultLifecycleObserver {

    @Inject
    lateinit var callService: dagger.Lazy<CallService>

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super<Application>.onCreate()
        createNotificationChannels()

        // Register app lifecycle observer
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        // App going to background - end any active calls
        Logger.i("[App] App going to background, checking for active calls")
        try {
            val service = callService.get()
            val currentState = service.callState.value
            val activeCall = service.activeCall.value

            if (currentState != CallState.Idle && activeCall != null) {
                Logger.i("[App] Active call detected, ending call on app background")
                appScope.launch {
                    service.endCall()
                }
            }
        } catch (e: Exception) {
            Logger.w("[App] Error checking call state on background: ${e.message}")
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        // App being destroyed - cleanup
        Logger.i("[App] App being destroyed, cleaning up calls")
        try {
            val service = callService.get()
            val currentState = service.callState.value
            val activeCall = service.activeCall.value

            if (currentState != CallState.Idle && activeCall != null) {
                Logger.i("[App] Active call detected on destroy, ending call")
                appScope.launch {
                    service.endCall()
                }
            }
        } catch (e: Exception) {
            Logger.w("[App] Error cleaning up call on destroy: ${e.message}")
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            // Messages channel
            val messagesChannel = NotificationChannel(
                "messages",
                "Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Whisper2 message notifications"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 250, 250)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(
                    Settings.System.DEFAULT_NOTIFICATION_URI,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
            nm.createNotificationChannel(messagesChannel)

            // Calls channel
            val callsChannel = NotificationChannel(
                "calls",
                "Calls",
                NotificationManager.IMPORTANCE_MAX
            ).apply {
                description = "Whisper2 incoming call notifications"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 500, 1000)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true) // Allow calls to bypass Do Not Disturb
                setSound(
                    Settings.System.DEFAULT_RINGTONE_URI,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
            nm.createNotificationChannel(callsChannel)
        }
    }
}
