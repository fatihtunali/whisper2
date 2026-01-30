package com.whisper2.app

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.whisper2.app.core.Logger
import com.whisper2.app.data.local.prefs.SecureStorage
import com.whisper2.app.data.network.ws.WsClientImpl
import com.whisper2.app.services.auth.AuthService
import com.whisper2.app.services.calls.CallService
import com.whisper2.app.services.calls.CallState
import com.whisper2.app.services.connection.ConnectionForegroundService
import com.whisper2.app.services.sync.MessageSyncWorker
import com.whisper2.app.utils.BatteryOptimizationHelper
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    @Inject
    lateinit var wsClient: dagger.Lazy<WsClientImpl>

    @Inject
    lateinit var authService: dagger.Lazy<AuthService>

    @Inject
    lateinit var secureStorage: dagger.Lazy<SecureStorage>

    // Lock screen state
    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Network monitoring
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super<Application>.onCreate()
        createNotificationChannels()

        // Register app lifecycle observer
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        // Setup network monitoring
        setupNetworkMonitoring()

        // If user is already logged in, start background services
        // This handles app restart and upgrade scenarios
        try {
            if (secureStorage.get().isLoggedIn()) {
                Logger.i("[App] User already logged in on startup - starting background services")
                onUserLoggedIn()
            }
        } catch (e: Exception) {
            Logger.w("[App] Error checking login state on startup: ${e.message}")
        }
    }

    /**
     * Monitor network connectivity changes to trigger WebSocket reconnect.
     * When network becomes available AND validated, reconnect immediately.
     *
     * IMPORTANT:
     * - onAvailable() means "a network exists", NOT "internet works"
     * - Only treat as available when NET_CAPABILITY_INTERNET + NET_CAPABILITY_VALIDATED
     * - This prevents reconnect on captive portals / broken Wi-Fi
     * - WsClient.setNetworkAvailable() is idempotent (ignores duplicate calls)
     */
    private fun setupNetworkMonitoring() {
        // Guard against duplicate registration
        if (networkCallback != null) {
            Logger.w("[App] Network monitoring already setup, skipping")
            return
        }

        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Don't set available=true here - wait for onCapabilitiesChanged with VALIDATED
                // onAvailable just means "a network exists", not "internet works"
                Logger.d("[App] Network available (waiting for validation)")
            }

            override fun onLost(network: Network) {
                Logger.i("[App] Network lost")
                try {
                    wsClient.get().setNetworkAvailable(false)
                } catch (e: Exception) {
                    Logger.w("[App] Error setting network unavailable: ${e.message}")
                }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                // Only consider available when both INTERNET and VALIDATED are present
                // VALIDATED means system verified actual internet connectivity (not captive portal)
                val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                                  caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

                // WsClient.setNetworkAvailable() is idempotent - ignores if value unchanged
                // This prevents reconnect storms from frequent capability changes
                try {
                    wsClient.get().setNetworkAvailable(hasInternet)
                } catch (e: Exception) {
                    Logger.w("[App] Error updating network status: ${e.message}")
                }
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        try {
            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
            Logger.i("[App] Network monitoring started")
        } catch (e: Exception) {
            Logger.e("[App] Failed to register network callback", e)
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        // App coming to foreground
        Logger.i("[App] App entering foreground")

        // Check if lock screen should be shown
        try {
            val storage = secureStorage.get()
            if (storage.shouldShowLockScreen()) {
                Logger.i("[App] Lock timeout elapsed, showing lock screen")
                _isLocked.value = true
            }
        } catch (e: Exception) {
            Logger.w("[App] Error checking lock screen: ${e.message}")
        }

        try {
            // Trigger auth reconnect - this handles both WS connection and authentication
            // NOTE: Don't call wsClient.handleAppForeground() separately - it's redundant
            // since authService.reconnect() already calls wsClient.connect()
            appScope.launch {
                try {
                    authService.get().reconnect()
                } catch (e: Exception) {
                    Logger.w("[App] Auth reconnect failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Logger.w("[App] Error handling foreground: ${e.message}")
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        // App going to background
        // NOTE: We do NOT end calls when app goes to background!
        // Calls should continue with the foreground service notification.
        // Only end calls on actual app destruction (onDestroy).
        Logger.i("[App] App going to background - calls continue via foreground service")

        // Record background time for lock timeout
        try {
            val storage = secureStorage.get()
            if (storage.biometricLockEnabled) {
                storage.lastBackgroundTime = System.currentTimeMillis()
                Logger.d("[App] Recorded background time for biometric lock")
            }
        } catch (e: Exception) {
            Logger.w("[App] Error recording background time: ${e.message}")
        }

        try {
            wsClient.get().handleAppBackground()
        } catch (e: Exception) {
            Logger.w("[App] Error handling background: ${e.message}")
        }
    }

    /**
     * Unlock the app after successful biometric authentication.
     */
    fun unlock() {
        _isLocked.value = false
        Logger.i("[App] App unlocked")
    }

    /**
     * Check if the app should be locked.
     */
    fun shouldShowLockScreen(): Boolean {
        return try {
            secureStorage.get().shouldShowLockScreen()
        } catch (e: Exception) {
            Logger.w("[App] Error checking lock screen: ${e.message}")
            false
        }
    }

    /**
     * Called when user logs in successfully.
     * Starts background connection services.
     */
    fun onUserLoggedIn() {
        Logger.i("[App] User logged in - starting background services")

        // Start foreground service to maintain WebSocket connection
        ConnectionForegroundService.startService(this)

        // Schedule periodic message sync as fallback
        MessageSyncWorker.schedule(this)

        // Check battery optimization (show prompt later in UI)
        if (!BatteryOptimizationHelper.isExemptFromBatteryOptimization(this)) {
            Logger.i("[App] App is not exempt from battery optimization - recommend user enables it")
        }
    }

    /**
     * Called when user logs out.
     * Stops background connection services.
     */
    fun onUserLoggedOut() {
        Logger.i("[App] User logged out - stopping background services")

        // Stop foreground service
        ConnectionForegroundService.stopService(this)

        // Cancel periodic message sync
        MessageSyncWorker.cancel(this)
    }

    /**
     * Request battery optimization exemption.
     * Should be called from UI with appropriate context.
     */
    fun requestBatteryOptimizationExemption(): Boolean {
        return BatteryOptimizationHelper.requestBatteryOptimizationExemption(this)
    }

    /**
     * Check if battery optimization prompt should be shown.
     */
    fun shouldShowBatteryOptimizationPrompt(lastPromptTime: Long): Boolean {
        return BatteryOptimizationHelper.shouldShowPrompt(this, lastPromptTime)
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

            // Connection service channel - Low importance, minimized in notification shade
            val connectionChannel = NotificationChannel(
                "connection",
                "Connection Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Whisper2 connected for instant messages"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(connectionChannel)
        }
    }
}
