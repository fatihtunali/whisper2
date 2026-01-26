package com.whisper2.app.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.whisper2.app.network.ws.WsState
import com.whisper2.app.ui.state.ConnectionState

/**
 * Connection status indicator bar
 * Shows when disconnected or connecting
 */
@Composable
fun ConnectionIndicator(
    connectionState: ConnectionState,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = connectionState.wsState != WsState.CONNECTED,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        Surface(
            color = when (connectionState.wsState) {
                WsState.CONNECTING -> MaterialTheme.colorScheme.secondaryContainer
                WsState.DISCONNECTED -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surface
            },
            modifier = modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (connectionState.wsState == WsState.CONNECTING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Text(
                    text = when (connectionState.wsState) {
                        WsState.CONNECTING -> "Connecting..."
                        WsState.DISCONNECTED -> "No connection - messages will queue"
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when (connectionState.wsState) {
                        WsState.CONNECTING -> MaterialTheme.colorScheme.onSecondaryContainer
                        WsState.DISCONNECTED -> MaterialTheme.colorScheme.onErrorContainer
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
            }
        }
    }
}
