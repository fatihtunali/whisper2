package com.whisper2.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.whisper2.app.BuildConfig
import com.whisper2.app.network.ws.WsState
import com.whisper2.app.ui.viewmodels.DevPanelViewModel
import java.text.SimpleDateFormat
import java.util.*

/**
 * Developer Panel
 *
 * Shows real-time debug info from actual services:
 * - WS state
 * - Outbox state
 * - Recent log events
 * - Golden Path Demo Mode (conformance builds only)
 *
 * Only visible in debug/conformance builds.
 */
@Composable
fun DevPanel(
    modifier: Modifier = Modifier,
    viewModel: DevPanelViewModel = hiltViewModel()
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val outboxState by viewModel.outboxState.collectAsState()
    val recentLogs by viewModel.recentLogs.collectAsState()
    val testStatus by viewModel.testStatus.collectAsState()
    var showDemoMode by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // Header with expand toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Debug Info",
                    style = MaterialTheme.typography.titleSmall
                )
                if (BuildConfig.IS_CONFORMANCE_BUILD) {
                    IconButton(
                        onClick = { showDemoMode = !showDemoMode },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = if (showDemoMode) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = "Toggle Demo Mode"
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Connection State
            DevPanelRow(
                label = "WS State",
                value = connectionState.wsState.name,
                valueColor = when (connectionState.wsState) {
                    WsState.CONNECTED -> MaterialTheme.colorScheme.primary
                    WsState.CONNECTING -> MaterialTheme.colorScheme.secondary
                    WsState.DISCONNECTED -> MaterialTheme.colorScheme.error
                }
            )

            DevPanelRow(
                label = "Online",
                value = if (connectionState.isOnline) "Yes" else "No"
            )

            connectionState.lastConnectedAt?.let { timestamp ->
                DevPanelRow(
                    label = "Last Connected",
                    value = formatTimestamp(timestamp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Outbox State
            Text(
                text = "Outbox",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            DevPanelRow(
                label = "Queued",
                value = outboxState.queuedCount.toString()
            )

            DevPanelRow(
                label = "Sending",
                value = outboxState.sendingCount.toString()
            )

            DevPanelRow(
                label = "Failed",
                value = outboxState.failedCount.toString(),
                valueColor = if (outboxState.failedCount > 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )

            // Recent Logs
            if (recentLogs.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Recent Events",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                recentLogs.takeLast(5).forEach { log ->
                    Text(
                        text = log,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }

            // Golden Path Demo Mode (Conformance builds only)
            if (showDemoMode && BuildConfig.IS_CONFORMANCE_BUILD) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Golden Path Demo",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Test status
                if (testStatus.isNotEmpty()) {
                    Text(
                        text = testStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (testStatus.contains("PASS")) {
                            MaterialTheme.colorScheme.primary
                        } else if (testStatus.contains("FAIL")) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Demo buttons
                DemoButton(
                    text = "Run Critical Gates (S1+S3+S6)",
                    icon = Icons.Default.PlayArrow,
                    onClick = { viewModel.runCriticalConformance() }
                )

                DemoButton(
                    text = "Seed Test Conversation",
                    icon = Icons.Default.Message,
                    onClick = { viewModel.seedTestConversation() }
                )

                DemoButton(
                    text = "Simulate Offline",
                    icon = Icons.Default.WifiOff,
                    onClick = { viewModel.simulateOffline() }
                )

                DemoButton(
                    text = "Simulate Reconnect",
                    icon = Icons.Default.Wifi,
                    onClick = { viewModel.simulateReconnect() }
                )

                DemoButton(
                    text = "Simulate Incoming Call",
                    icon = Icons.Default.Call,
                    onClick = { viewModel.simulateIncomingCall() }
                )

                DemoButton(
                    text = "Simulate Video Call",
                    icon = Icons.Default.Videocam,
                    onClick = { viewModel.simulateIncomingVideoCall() }
                )

                DemoButton(
                    text = "Force Logout",
                    icon = Icons.Default.Logout,
                    onClick = { viewModel.forceLogout() }
                )
            }
        }
    }
}

@Composable
private fun DemoButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun DevPanelRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor
        )
    }
}

private fun formatTimestamp(timestamp: Long): String {
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}
