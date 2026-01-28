package com.whisper2.app.ui

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.whisper2.app.core.Logger
import com.whisper2.app.services.calls.CallForegroundService
import com.whisper2.app.services.calls.CallState
import com.whisper2.app.ui.screens.calls.CallScreen
import com.whisper2.app.ui.screens.calls.CallViewModel
import com.whisper2.app.ui.screens.calls.IncomingCallScreen
import com.whisper2.app.ui.theme.MetalBlack
import com.whisper2.app.ui.theme.Whisper2Theme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class IncomingCallActivity : ComponentActivity() {

    private var callId: String? = null
    private var callerId: String? = null
    private var callerName: String? = null
    private var isVideo: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Allow activity to show over lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        // Keep screen on during call
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Get call details from intent
        callId = intent.getStringExtra(CallForegroundService.EXTRA_CALL_ID)
        callerId = intent.getStringExtra(CallForegroundService.EXTRA_CALLER_ID) ?: ""
        callerName = intent.getStringExtra(CallForegroundService.EXTRA_CALLER_NAME) ?: callerId
        isVideo = intent.getBooleanExtra(CallForegroundService.EXTRA_IS_VIDEO, false)

        Logger.d("[IncomingCallActivity] onCreate: callId=$callId, caller=$callerName, isVideo=$isVideo")

        // Check if this was launched from notification answer button
        val autoAnswer = intent.getBooleanExtra("auto_answer", false)

        setContent {
            Whisper2Theme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MetalBlack
                ) {
                    val viewModel: CallViewModel = hiltViewModel()
                    val callState by viewModel.callState.collectAsState()
                    var answered by remember { mutableStateOf(autoAnswer) }

                    // Auto-answer if launched from notification Answer button
                    LaunchedEffect(autoAnswer) {
                        if (autoAnswer) {
                            Logger.d("[IncomingCallActivity] Auto-answering call")
                            viewModel.setConnectionActive()
                            viewModel.answerCall()
                            CallForegroundService.stopService(this@IncomingCallActivity)
                            answered = true
                        }
                    }

                    // Handle call end - close activity
                    LaunchedEffect(callState) {
                        if (callState is CallState.Ended || callState is CallState.Idle) {
                            if (answered) {
                                Logger.d("[IncomingCallActivity] Call ended, finishing activity")
                                finish()
                            }
                        }
                    }

                    if (!answered) {
                        // Show incoming call UI
                        IncomingCallScreen(
                            callerName = callerName ?: "Unknown",
                            callerId = callerId ?: "",
                            isVideo = isVideo,
                            onAnswer = {
                                Logger.d("[IncomingCallActivity] Answer clicked")
                                viewModel.setConnectionActive()
                                viewModel.answerCall()
                                CallForegroundService.stopService(this@IncomingCallActivity)
                                answered = true
                            },
                            onDecline = {
                                Logger.d("[IncomingCallActivity] Decline clicked")
                                viewModel.declineCall()
                                CallForegroundService.stopService(this@IncomingCallActivity)
                                finish()
                            }
                        )
                    } else {
                        // Show active call UI - no navigation needed
                        CallScreen(
                            peerId = callerId ?: "",
                            isVideo = isVideo,
                            isOutgoing = false,
                            onCallEnded = {
                                Logger.d("[IncomingCallActivity] Call ended callback")
                                finish()
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onBackPressed() {
        // Don't allow back press during incoming call
        // User must answer or decline
    }
}
