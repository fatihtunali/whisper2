package com.whisper2.app.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.whisper2.app.BuildConfig
import com.whisper2.app.ui.components.DevPanel
import com.whisper2.app.ui.viewmodels.SettingsViewModel

/**
 * Settings Screen
 *
 * Shows user settings and debug info.
 * Uses real SessionManager - no mock data.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    onLogout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var showDevPanel by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // My WhisperID
            ListItem(
                headlineContent = { Text("My WhisperID") },
                supportingContent = {
                    Text(
                        text = uiState.whisperId ?: "Not registered",
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                leadingContent = {
                    Icon(Icons.Default.Person, contentDescription = null)
                },
                trailingContent = {
                    if (uiState.whisperId != null) {
                        IconButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(uiState.whisperId!!))
                            }
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                        }
                    }
                }
            )

            HorizontalDivider()

            // Device ID
            ListItem(
                headlineContent = { Text("Device ID") },
                supportingContent = {
                    Text(
                        text = uiState.deviceId ?: "Unknown",
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                leadingContent = {
                    Icon(Icons.Default.PhoneAndroid, contentDescription = null)
                }
            )

            HorizontalDivider()

            // Version
            ListItem(
                headlineContent = { Text("Version") },
                supportingContent = {
                    Text(
                        text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                leadingContent = {
                    Icon(Icons.Default.Info, contentDescription = null)
                }
            )

            HorizontalDivider()

            // Dev Panel (debug builds only)
            if (BuildConfig.DEBUG || BuildConfig.IS_CONFORMANCE_BUILD) {
                ListItem(
                    modifier = Modifier.clickable { showDevPanel = !showDevPanel },
                    headlineContent = { Text("Developer Panel") },
                    supportingContent = { Text("Debug info and connection status") },
                    leadingContent = {
                        Icon(Icons.Default.DeveloperMode, contentDescription = null)
                    },
                    trailingContent = {
                        Icon(
                            if (showDevPanel) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null
                        )
                    }
                )

                if (showDevPanel) {
                    DevPanel(
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }

                HorizontalDivider()
            }

            Spacer(modifier = Modifier.weight(1f))

            // Logout button
            ListItem(
                modifier = Modifier.clickable { showLogoutConfirm = true },
                headlineContent = {
                    Text(
                        text = "Logout",
                        color = MaterialTheme.colorScheme.error
                    )
                },
                leadingContent = {
                    Icon(
                        Icons.AutoMirrored.Filled.Logout,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            )
        }
    }

    // Logout confirmation dialog
    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("Logout") },
            text = {
                Text("Are you sure you want to logout? You'll need your recovery phrase to login again.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutConfirm = false
                        viewModel.logout()
                        onLogout()
                    }
                ) {
                    Text("Logout", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
