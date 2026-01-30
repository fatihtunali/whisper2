package com.whisper2.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whisper2.app.data.network.ws.WsConnectionState
import com.whisper2.app.ui.theme.*

/**
 * Connection status bar that displays at the top of the screen.
 * Shows the current WebSocket connection state with animated indicators.
 */
@Composable
fun ConnectionStatusBar(
    connectionState: WsConnectionState,
    modifier: Modifier = Modifier
) {
    // Don't show if connected
    if (connectionState == WsConnectionState.CONNECTED) {
        return
    }

    val (backgroundColor, textColor, statusText, icon) = when (connectionState) {
        WsConnectionState.CONNECTED -> {
            StatusBarConfig(
                StatusSuccess.copy(alpha = 0.15f),
                StatusSuccess,
                "Connected",
                Icons.Default.CloudDone
            )
        }
        WsConnectionState.CONNECTING -> {
            StatusBarConfig(
                StatusWarning.copy(alpha = 0.15f),
                StatusWarning,
                "Connecting...",
                Icons.Default.CloudSync
            )
        }
        WsConnectionState.RECONNECTING -> {
            StatusBarConfig(
                Color(0xFFF97316).copy(alpha = 0.15f),  // Orange
                Color(0xFFF97316),
                "Reconnecting...",
                Icons.Default.Sync
            )
        }
        WsConnectionState.DISCONNECTED -> {
            StatusBarConfig(
                StatusError.copy(alpha = 0.15f),
                StatusError,
                "Disconnected",
                Icons.Default.CloudOff
            )
        }
        WsConnectionState.AUTH_EXPIRED -> {
            StatusBarConfig(
                StatusError.copy(alpha = 0.15f),
                StatusError,
                "Session Expired",
                Icons.Default.LockClock
            )
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = backgroundColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // Animated dot indicator
            AnimatedStatusDot(
                color = textColor,
                isAnimating = connectionState == WsConnectionState.CONNECTING ||
                        connectionState == WsConnectionState.RECONNECTING
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Status icon
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(16.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Status text
            Text(
                text = statusText,
                color = textColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * Animated dot that pulses when connecting/reconnecting.
 */
@Composable
private fun AnimatedStatusDot(
    color: Color,
    isAnimating: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "statusDot")

    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isAnimating) 0.3f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotAlpha"
    )

    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isAnimating) 1.4f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotScale"
    )

    Box(
        modifier = modifier
            .size(8.dp)
            .scale(scale)
            .alpha(alpha)
            .background(color, CircleShape)
    )
}

/**
 * Configuration data class for status bar appearance.
 */
private data class StatusBarConfig(
    val backgroundColor: Color,
    val textColor: Color,
    val statusText: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

/**
 * Compact connection status indicator for use in app bars.
 * Shows just a colored dot with tooltip-style behavior.
 */
@Composable
fun ConnectionStatusIndicator(
    connectionState: WsConnectionState,
    modifier: Modifier = Modifier
) {
    val color = when (connectionState) {
        WsConnectionState.CONNECTED -> StatusSuccess
        WsConnectionState.CONNECTING -> StatusWarning
        WsConnectionState.RECONNECTING -> Color(0xFFF97316)  // Orange
        WsConnectionState.DISCONNECTED -> StatusError
        WsConnectionState.AUTH_EXPIRED -> StatusError
    }

    val isAnimating = connectionState == WsConnectionState.CONNECTING ||
            connectionState == WsConnectionState.RECONNECTING

    AnimatedStatusDot(
        color = color,
        isAnimating = isAnimating,
        modifier = modifier
    )
}
