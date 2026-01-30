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

    companion object {
        const val CALLS_CHANNEL_ID = "calls"
        const val CALLS_CHANNEL_ID_V2 = "calls_v2"  // Silent version for existing noisy channels

        // Cached channel ID for use by CallForegroundService
        @Volatile
        var currentCallsChannelId: String = CALLS_CHANNEL_ID
            private set
    }

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
        // App going to background
        // NOTE: We do NOT end calls when app goes to background!
        // Calls should continue with the foreground service notification.
        // Only end calls on actual app destruction (onDestroy).
        Logger.i("[App] App going to background - calls continue via foreground service")
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

    /**
     * Get the calls channel ID, migrating from "calls" to "calls_v2" if the old channel is noisy.
     *
     * IMPORTANT: We do NOT delete the old channel - Android punishes that.
     * Instead, we just create calls_v2 and switch to it, leaving the old channel alone.
     * User can manage or delete it themselves if they want.
     */
    private fun getCallsChannelId(nm: NotificationManager): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return CALLS_CHANNEL_ID
        }

        val oldChannel = nm.getNotificationChannel(CALLS_CHANNEL_ID)
        if (oldChannel == null) {
            // No old channel exists, use default
            currentCallsChannelId = CALLS_CHANNEL_ID
            return CALLS_CHANNEL_ID
        }

        // Check if old channel has sound or vibration enabled (noisy)
        val hasSound = oldChannel.sound != null
        val hasVibration = oldChannel.vibrationPattern != null || oldChannel.shouldVibrate()

        if (hasSound || hasVibration) {
            // Old channel is noisy - switch to v2 but DO NOT delete old channel
            // User explicitly enabled those settings, respect that by leaving channel alone
            Logger.i("[App] Old 'calls' channel is noisy (sound=$hasSound, vibration=$hasVibration), switching to 'calls_v2'")
            currentCallsChannelId = CALLS_CHANNEL_ID_V2
            return CALLS_CHANNEL_ID_V2
        }

        // Old channel is already silent, keep using it
        currentCallsChannelId = CALLS_CHANNEL_ID
        return CALLS_CHANNEL_ID
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            // NOTE: Do NOT delete/recreate channels - it can break user settings
            // Channel IDs should be stable. If behavior must change, use a new ID (e.g. "calls_v2")

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

            // Calls channel - SILENT! Ringtone/vibration handled manually by CallForegroundService
            // Without Telecom, bypassing DND is a policy/UX landmine - respect user settings
            //
            // MIGRATION: If user had old "calls" channel with sound/vibration enabled,
            // Android won't let us change it programmatically. So we check and migrate to "calls_v2"
            // if the old channel is noisy.
            val callsChannelId = getCallsChannelId(nm)

            val callsChannel = NotificationChannel(
                callsChannelId,
                "Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Whisper2 incoming call notifications"
                enableVibration(false) // Vibration handled by CallForegroundService
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(false) // Respect user's DND settings (no Telecom = no clean bypass)
                setSound(null, null) // SILENT - ringtone played manually so we can stop it
            }
            nm.createNotificationChannel(callsChannel)
        }
    }
}
