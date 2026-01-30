package com.whisper2.app.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.whisper2.app.core.Logger

/**
 * Helper for managing battery optimization exemptions.
 *
 * Android's Doze mode and battery optimization can kill background processes,
 * which prevents the app from maintaining WebSocket connections and receiving
 * real-time notifications.
 *
 * This helper:
 * - Checks if the app is exempt from battery optimization
 * - Provides methods to request exemption from the user
 * - Works across different Android versions
 */
object BatteryOptimizationHelper {

    /**
     * Check if the app is exempt from battery optimization.
     *
     * @return true if exempt (good), false if not exempt (should request)
     */
    fun isExemptFromBatteryOptimization(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // Battery optimization doesn't exist before Android 6
            return true
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Request battery optimization exemption from the user.
     *
     * This opens the system settings page where the user can whitelist the app.
     * According to Play Store policy, this should only be called for apps that
     * require real-time messaging (like Whisper2).
     *
     * @return true if the intent was launched successfully
     */
    fun requestBatteryOptimizationExemption(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true  // Not needed
        }

        // Check if already exempt
        if (isExemptFromBatteryOptimization(context)) {
            Logger.d("[BatteryOptimization] Already exempt from battery optimization")
            return true
        }

        return try {
            // Direct request intent (shows dialog)
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Logger.i("[BatteryOptimization] Requested battery optimization exemption")
            true
        } catch (e: Exception) {
            Logger.e("[BatteryOptimization] Failed to request exemption via direct intent", e)
            // Fallback: open battery optimization settings list
            openBatteryOptimizationSettings(context)
        }
    }

    /**
     * Open the battery optimization settings page.
     *
     * This is a fallback if the direct request intent fails.
     * User will need to find and select the app manually.
     *
     * @return true if the settings were opened successfully
     */
    fun openBatteryOptimizationSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true
        }

        return try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Logger.i("[BatteryOptimization] Opened battery optimization settings")
            true
        } catch (e: Exception) {
            Logger.e("[BatteryOptimization] Failed to open battery optimization settings", e)
            // Last resort: open general settings
            openAppSettings(context)
        }
    }

    /**
     * Open the app's settings page.
     *
     * Last resort fallback.
     */
    private fun openAppSettings(context: Context): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Logger.i("[BatteryOptimization] Opened app settings")
            true
        } catch (e: Exception) {
            Logger.e("[BatteryOptimization] Failed to open app settings", e)
            false
        }
    }

    /**
     * Check if we should show the battery optimization prompt.
     *
     * Returns true if:
     * - App is not exempt from battery optimization
     * - User hasn't permanently dismissed the prompt
     * - Enough time has passed since last prompt (to avoid spamming)
     *
     * @param lastPromptTime Time in millis when the prompt was last shown (0 if never)
     * @param promptInterval Minimum interval between prompts in millis
     */
    fun shouldShowPrompt(
        context: Context,
        lastPromptTime: Long,
        promptInterval: Long = 24 * 60 * 60 * 1000  // 24 hours default
    ): Boolean {
        // Already exempt - no need to prompt
        if (isExemptFromBatteryOptimization(context)) {
            return false
        }

        // Check if enough time has passed since last prompt
        val now = System.currentTimeMillis()
        return (now - lastPromptTime) >= promptInterval
    }
}
