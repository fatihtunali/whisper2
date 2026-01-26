package com.whisper2.app.services.push

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Notification Dispatcher
 *
 * Creates and shows notifications for messages and calls.
 * Uses the appropriate notification channel based on type.
 */
class NotificationDispatcher(private val context: Context) {
    companion object {
        private const val TAG = "NotificationDispatcher"

        // Notification IDs
        private const val NOTIFICATION_ID_MESSAGE_BASE = 1000
        private const val NOTIFICATION_ID_CALL = 2000
    }

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private var messageNotificationCounter = 0

    /**
     * Show a message notification
     *
     * @param title Notification title (e.g., sender name)
     * @param body Notification body (e.g., message preview)
     * @param whisperId Sender's WhisperID
     */
    fun showMessageNotification(title: String, body: String, whisperId: String) {
        Log.d(TAG, "Showing message notification: $title")

        val notificationId = NOTIFICATION_ID_MESSAGE_BASE + (messageNotificationCounter++ % 100)

        val builder = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.ic_dialog_email) // Using system icon
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(createMessageIntent(whisperId))

        notificationManager.notify(notificationId, builder.build())
    }

    /**
     * Show an incoming call notification
     *
     * @param callerName Caller's display name
     * @param whisperId Caller's WhisperID
     */
    fun showIncomingCallNotification(callerName: String, whisperId: String) {
        Log.d(TAG, "Showing incoming call notification: $callerName")

        val builder = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_CALLS)
            .setSmallIcon(android.R.drawable.ic_menu_call) // Using system icon
            .setContentTitle("Incoming Call")
            .setContentText("$callerName is calling...")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true) // Can't be swiped away
            .setFullScreenIntent(createCallIntent(whisperId), true)
            .addAction(createDeclineAction(whisperId))
            .addAction(createAnswerAction(whisperId))

        notificationManager.notify(NOTIFICATION_ID_CALL, builder.build())
    }

    /**
     * Cancel call notification
     */
    fun cancelCallNotification() {
        notificationManager.cancel(NOTIFICATION_ID_CALL)
    }

    /**
     * Cancel all notifications
     */
    fun cancelAll() {
        notificationManager.cancelAll()
    }

    // ==========================================================================
    // Intent Creators
    // ==========================================================================

    private fun createMessageIntent(whisperId: String): PendingIntent {
        // TODO: Create intent to open conversation
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        intent?.putExtra("whisperId", whisperId)
        intent?.putExtra("action", "open_conversation")

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        return PendingIntent.getActivity(context, whisperId.hashCode(), intent, flags)
    }

    private fun createCallIntent(whisperId: String): PendingIntent {
        // TODO: Create intent to open call screen
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        intent?.putExtra("whisperId", whisperId)
        intent?.putExtra("action", "incoming_call")

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        return PendingIntent.getActivity(context, whisperId.hashCode(), intent, flags)
    }

    private fun createDeclineAction(whisperId: String): NotificationCompat.Action {
        // TODO: Create broadcast intent for decline
        val intent = Intent("com.whisper2.DECLINE_CALL").apply {
            putExtra("whisperId", whisperId)
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flags)

        return NotificationCompat.Action.Builder(
            android.R.drawable.ic_delete, // Using system icon for decline
            "Decline",
            pendingIntent
        ).build()
    }

    private fun createAnswerAction(whisperId: String): NotificationCompat.Action {
        // TODO: Create intent for answer
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            putExtra("whisperId", whisperId)
            putExtra("action", "answer_call")
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getActivity(context, 1, intent, flags)

        return NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_call, // Using system icon for answer
            "Answer",
            pendingIntent
        ).build()
    }
}
