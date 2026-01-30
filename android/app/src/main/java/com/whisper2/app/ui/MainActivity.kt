package com.whisper2.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.whisper2.app.core.Logger
import com.whisper2.app.ui.navigation.WhisperNavigation
import com.whisper2.app.ui.theme.Whisper2Theme
import com.whisper2.app.ui.viewmodels.MainViewModel
import dagger.hilt.android.AndroidEntryPoint

data class NotificationData(
    val isIncomingCall: Boolean = false,
    val callId: String? = null,
    val conversationId: String? = null
)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Notification data from intent
    private val notificationData = mutableStateOf<NotificationData?>(null)

    // In-App Update
    private lateinit var appUpdateManager: AppUpdateManager
    private lateinit var updateLauncher: ActivityResultLauncher<IntentSenderRequest>

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Logger.i("[MainActivity] POST_NOTIFICATIONS permission granted")
        } else {
            Logger.w("[MainActivity] POST_NOTIFICATIONS permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Allow content to extend behind system bars for proper IME handling
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Initialize In-App Update
        appUpdateManager = AppUpdateManagerFactory.create(this)
        updateLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode != RESULT_OK) {
                Logger.w("[MainActivity] Update flow failed or cancelled: ${result.resultCode}")
                // Re-check for update if user cancelled
                checkForAppUpdate()
            }
        }

        // Check for updates
        checkForAppUpdate()

        // Request notification permission for Android 13+
        requestNotificationPermission()

        // Handle notification intent
        handleIntent(intent)

        setContent {
            Whisper2Theme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val viewModel: MainViewModel = hiltViewModel()
                    val authState by viewModel.authState.collectAsState()
                    val connectionState by viewModel.connectionState.collectAsState()
                    val notification = notificationData.value

                    WhisperNavigation(
                        authState = authState,
                        connectionState = connectionState,
                        notificationData = notification,
                        onNotificationHandled = { notificationData.value = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.let {
            val isIncomingCall = it.getBooleanExtra("isIncomingCall", false)
            val callId = it.getStringExtra("callId")
            val conversationId = it.getStringExtra("conversationId")

            Logger.d("[MainActivity] Intent extras: isIncomingCall=$isIncomingCall, callId=$callId, conversationId=$conversationId")

            if (isIncomingCall || callId != null || conversationId != null) {
                notificationData.value = NotificationData(
                    isIncomingCall = isIncomingCall,
                    callId = callId,
                    conversationId = conversationId
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Check if an update was in progress and ensure it completes
        appUpdateManager.appUpdateInfo.addOnSuccessListener { appUpdateInfo ->
            if (appUpdateInfo.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                // Resume the update if it was interrupted
                appUpdateManager.startUpdateFlowForResult(
                    appUpdateInfo,
                    updateLauncher,
                    AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
                )
            }
        }
    }

    private fun checkForAppUpdate() {
        appUpdateManager.appUpdateInfo.addOnSuccessListener { appUpdateInfo ->
            if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                Logger.i("[MainActivity] Update available, starting immediate update flow")
                appUpdateManager.startUpdateFlowForResult(
                    appUpdateInfo,
                    updateLauncher,
                    AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
                )
            } else {
                Logger.d("[MainActivity] No update available or not allowed")
            }
        }.addOnFailureListener { e ->
            Logger.e("[MainActivity] Failed to check for updates", e)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    Logger.d("[MainActivity] POST_NOTIFICATIONS already granted")
                }
                else -> {
                    Logger.d("[MainActivity] Requesting POST_NOTIFICATIONS permission")
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }
}
