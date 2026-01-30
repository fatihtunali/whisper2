package com.whisper2.app.services.connection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.whisper2.app.core.Logger
import com.whisper2.app.data.local.prefs.SecureStorage
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Broadcast receiver that handles device boot completion.
 * Restarts the connection foreground service if user was logged in.
 *
 * This ensures that the app maintains WebSocket connection even after
 * device reboot, allowing push notifications to work properly.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var secureStorage: SecureStorage

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            Logger.i("[BootReceiver] Device booted, checking login state...")

            try {
                // Only start services if user is logged in
                if (secureStorage.isLoggedIn()) {
                    Logger.i("[BootReceiver] User is logged in, starting connection service")
                    ConnectionForegroundService.startService(context)
                } else {
                    Logger.d("[BootReceiver] User not logged in, skipping service start")
                }
            } catch (e: Exception) {
                Logger.e("[BootReceiver] Failed to start service on boot", e)
            }
        }
    }
}
