package com.whisper2.app

import android.app.Application
import android.os.SystemClock
import com.whisper2.app.core.Logger
import com.whisper2.app.network.ws.WsClient
import com.whisper2.app.services.messaging.OutboxQueue
import com.whisper2.app.ui.state.AppStateManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Whisper2 Application class
 * Entry point for Hilt dependency injection
 */
@HiltAndroidApp
class App : Application() {

    @Inject
    lateinit var appStateManager: AppStateManager

    @Inject
    lateinit var wsClient: WsClient

    @Inject
    lateinit var outboxQueue: OutboxQueue

    override fun onCreate() {
        val startTime = SystemClock.elapsedRealtime()
        super.onCreate()

        // Wire AppStateManager to real-time services for UI state tracking
        appStateManager.setWsClient(wsClient)
        appStateManager.setOutboxQueue(outboxQueue)

        val coldStartTime = SystemClock.elapsedRealtime() - startTime
        Logger.info("App onCreate completed in ${coldStartTime}ms", Logger.Category.APP)
        Logger.info("=== Whisper2 App Started ===", Logger.Category.APP)
    }

    companion object {
        // For tracking app-wide cold start
        val processStartTime: Long = SystemClock.elapsedRealtime()
    }
}
