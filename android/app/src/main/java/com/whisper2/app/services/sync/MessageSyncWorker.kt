package com.whisper2.app.services.sync

import android.content.Context
import android.os.PowerManager
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.whisper2.app.core.Logger
import com.whisper2.app.data.local.prefs.SecureStorage
import com.whisper2.app.data.network.ws.WsClientImpl
import com.whisper2.app.data.network.ws.WsConnectionState
import com.whisper2.app.services.auth.AuthService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker for periodic background message sync.
 *
 * This worker:
 * - Runs periodically (minimum 15 minutes due to WorkManager constraints)
 * - Acquires wake lock to ensure work completes
 * - Reconnects WebSocket if disconnected
 * - Triggers pending message fetch
 *
 * This is a fallback mechanism when the foreground service is killed.
 */
@HiltWorker
class MessageSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val wsClient: WsClientImpl,
    private val authService: AuthService,
    private val secureStorage: SecureStorage
) : CoroutineWorker(context, params) {

    companion object {
        private const val WORK_NAME = "message_sync"
        private const val WAKE_LOCK_TAG = "Whisper2:MessageSyncWorker"
        private const val WAKE_LOCK_TIMEOUT_MS = 30_000L
        private const val CONNECTION_WAIT_MS = 10_000L

        /**
         * Schedule periodic message sync.
         * Called when user logs in.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<MessageSyncWorker>(
                15, TimeUnit.MINUTES,  // Minimum interval for periodic work
                5, TimeUnit.MINUTES    // Flex interval
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,  // Don't restart if already scheduled
                    request
                )

            Logger.i("[MessageSyncWorker] Periodic sync scheduled")
        }

        /**
         * Cancel periodic message sync.
         * Called when user logs out.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Logger.i("[MessageSyncWorker] Periodic sync cancelled")
        }

        /**
         * Trigger immediate one-time sync.
         * Called when needed outside of periodic schedule.
         */
        fun syncNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<MessageSyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context)
                .enqueue(request)

            Logger.i("[MessageSyncWorker] Immediate sync requested")
        }
    }

    override suspend fun doWork(): Result {
        Logger.i("[MessageSyncWorker] Starting sync work")

        // Check if user is logged in
        if (secureStorage.sessionToken == null) {
            Logger.d("[MessageSyncWorker] Not logged in, skipping sync")
            return Result.success()
        }

        var wakeLock: PowerManager.WakeLock? = null

        try {
            // Acquire wake lock to ensure work completes
            val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKE_LOCK_TAG
            ).apply {
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
            Logger.d("[MessageSyncWorker] Wake lock acquired")

            // Check connection state
            val currentState = wsClient.connectionState.value

            when (currentState) {
                WsConnectionState.CONNECTED -> {
                    Logger.d("[MessageSyncWorker] Already connected, sync will happen naturally")
                    return Result.success()
                }

                WsConnectionState.CONNECTING,
                WsConnectionState.RECONNECTING -> {
                    Logger.d("[MessageSyncWorker] Connection in progress, waiting...")
                    delay(CONNECTION_WAIT_MS)
                    return if (wsClient.connectionState.value == WsConnectionState.CONNECTED) {
                        Result.success()
                    } else {
                        Result.retry()
                    }
                }

                WsConnectionState.AUTH_EXPIRED -> {
                    Logger.d("[MessageSyncWorker] Auth expired, cannot sync")
                    return Result.success()  // Don't retry - user needs to re-login
                }

                WsConnectionState.DISCONNECTED -> {
                    Logger.i("[MessageSyncWorker] Disconnected, triggering reconnect")
                    try {
                        authService.reconnect()

                        // Wait for connection
                        var waitTime = 0L
                        while (waitTime < CONNECTION_WAIT_MS) {
                            if (wsClient.connectionState.value == WsConnectionState.CONNECTED) {
                                Logger.i("[MessageSyncWorker] Reconnected successfully")
                                return Result.success()
                            }
                            delay(500)
                            waitTime += 500
                        }

                        Logger.w("[MessageSyncWorker] Reconnect timeout, will retry")
                        return Result.retry()
                    } catch (e: Exception) {
                        Logger.e("[MessageSyncWorker] Reconnect failed", e)
                        return Result.retry()
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e("[MessageSyncWorker] Sync work failed", e)
            return Result.retry()
        } finally {
            // Release wake lock
            try {
                if (wakeLock?.isHeld == true) {
                    wakeLock.release()
                    Logger.d("[MessageSyncWorker] Wake lock released")
                }
            } catch (e: Exception) {
                Logger.e("[MessageSyncWorker] Failed to release wake lock", e)
            }
        }
    }
}
