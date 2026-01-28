package com.whisper2.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.whisper2.app.ui.navigation.WhisperNavigation
import com.whisper2.app.ui.theme.Whisper2Theme
import com.whisper2.app.ui.viewmodels.MainViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Allow content to extend behind system bars for proper IME handling
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            Whisper2Theme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val viewModel: MainViewModel = hiltViewModel()
                    val authState by viewModel.authState.collectAsState()
                    val connectionState by viewModel.connectionState.collectAsState()
                    WhisperNavigation(authState = authState, connectionState = connectionState)
                }
            }
        }
    }
}
