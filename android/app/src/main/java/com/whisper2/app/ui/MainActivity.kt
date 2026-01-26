package com.whisper2.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.whisper2.app.services.auth.ISessionManager
import com.whisper2.app.ui.navigation.Routes
import com.whisper2.app.ui.navigation.WhisperNavHost
import com.whisper2.app.ui.screens.call.CallScreen
import com.whisper2.app.ui.state.AppStateManager
import com.whisper2.app.ui.state.CallEndReason
import com.whisper2.app.ui.state.CallUiState
import com.whisper2.app.ui.theme.WhisperTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Main Activity
 *
 * Entry point for the Whisper app.
 * Determines start destination based on auth state.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var sessionManager: ISessionManager

    @Inject
    lateinit var appStateManager: AppStateManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        setContent {
            WhisperTheme {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    WhisperApp(
                        sessionManager = sessionManager,
                        appStateManager = appStateManager
                    )
                }
            }
        }
    }
}

@Composable
fun WhisperApp(
    sessionManager: ISessionManager,
    appStateManager: AppStateManager
) {
    val navController = rememberNavController()

    // Determine start destination based on auth state
    val startDestination = remember {
        if (sessionManager.isLoggedIn && sessionManager.whisperId != null) {
            Routes.CONVERSATIONS
        } else {
            Routes.AUTH
        }
    }

    // UI-G6: Observe call state for overlay
    val callState by appStateManager.callState.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        // Main navigation
        WhisperNavHost(
            navController = navController,
            startDestination = startDestination
        )

        // UI-G6: Call screen overlay (shows on top when there's an active call)
        if (callState !is CallUiState.Idle) {
            CallScreen(
                callState = callState,
                onAccept = {
                    // Simulate accepting call -> connecting -> in-call
                    val state = callState
                    if (state is CallUiState.Incoming) {
                        appStateManager.onCallConnecting(state.callId, state.from, state.isVideo)
                        // Simulate connection established after 2 seconds
                        // In real app, this would be triggered by WebRTC connection
                        kotlinx.coroutines.MainScope().launch {
                            kotlinx.coroutines.delay(2000)
                            appStateManager.onCallConnected(
                                callId = state.callId,
                                peerId = state.from,
                                peerDisplayName = state.fromDisplayName,
                                isVideo = state.isVideo
                            )
                            // Start duration counter
                            startDurationCounter(appStateManager)
                        }
                    }
                },
                onDecline = {
                    val state = callState
                    when (state) {
                        is CallUiState.Incoming -> {
                            appStateManager.onCallEnded(state.callId, CallEndReason.DECLINED)
                        }
                        is CallUiState.Outgoing -> {
                            appStateManager.onCallEnded(state.callId, CallEndReason.ENDED)
                        }
                        else -> appStateManager.dismissCallUi()
                    }
                },
                onEndCall = {
                    val state = callState
                    val duration = if (state is CallUiState.InCall) state.durationSeconds else 0
                    val callId = when (state) {
                        is CallUiState.InCall -> state.callId
                        is CallUiState.Connecting -> state.callId
                        else -> ""
                    }
                    appStateManager.onCallEnded(callId, CallEndReason.ENDED, duration)
                },
                onToggleMute = {
                    val state = callState
                    if (state is CallUiState.InCall) {
                        appStateManager.updateInCallState(isMuted = !state.isMuted)
                    }
                },
                onToggleSpeaker = {
                    val state = callState
                    if (state is CallUiState.InCall) {
                        appStateManager.updateInCallState(isSpeakerOn = !state.isSpeakerOn)
                    }
                },
                onDismiss = {
                    appStateManager.dismissCallUi()
                }
            )
        }
    }
}

/**
 * Start call duration counter
 */
private fun startDurationCounter(appStateManager: AppStateManager) {
    kotlinx.coroutines.MainScope().launch {
        var seconds = 0
        while (appStateManager.callState.value is CallUiState.InCall) {
            kotlinx.coroutines.delay(1000)
            seconds++
            appStateManager.updateInCallState(durationSeconds = seconds)
        }
    }
}
