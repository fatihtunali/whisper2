package com.whisper2.app.services.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Notification Channels for Whisper2
 *
 * Android 8.0+ requires notification channels.
 * - messages: Default priority for message notifications
 * - calls: High priority for incoming calls (full-screen intent)
 */
object NotificationChannels {
    private const val TAG = "NotificationChannels"

    // Channel IDs
    const val CHANNEL_MESSAGES = "whisper2_messages"
    const val CHANNEL_CALLS = "whisper2_calls"

    // Channel names (user-visible)
    private const val NAME_MESSAGES = "Messages"
    private const val NAME_CALLS = "Calls"

    // Channel descriptions
    private const val DESC_MESSAGES = "New message notifications"
    private const val DESC_CALLS = "Incoming call notifications"

    private var channelsCreated = false

    /**
     * Create notification channels
     * Should be called at app startup
     * Idempotent - safe to call multiple times
     */
    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.d(TAG, "Notification channels not needed on API < 26")
            return
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Messages channel - default importance
        val messagesChannel = NotificationChannel(
            CHANNEL_MESSAGES,
            NAME_MESSAGES,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = DESC_MESSAGES
            enableLights(true)
            enableVibration(true)
        }

        // Calls channel - high importance (for full-screen intent)
        val callsChannel = NotificationChannel(
            CHANNEL_CALLS,
            NAME_CALLS,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = DESC_CALLS
            enableLights(true)
            enableVibration(true)
            setBypassDnd(true) // Calls can bypass Do Not Disturb
        }

        notificationManager.createNotificationChannel(messagesChannel)
        notificationManager.createNotificationChannel(callsChannel)

        channelsCreated = true
        Log.d(TAG, "Notification channels created")
    }

    /**
     * Check if channels have been created
     */
    fun areChannelsCreated(): Boolean = channelsCreated

    /**
     * Get channel info for testing
     */
    fun getChannelInfo(context: Context, channelId: String): ChannelInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return null
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = notificationManager.getNotificationChannel(channelId) ?: return null

        return ChannelInfo(
            id = channel.id,
            name = channel.name.toString(),
            importance = channel.importance
        )
    }

    /**
     * Channel info for testing/verification
     */
    data class ChannelInfo(
        val id: String,
        val name: String,
        val importance: Int
    )
}
