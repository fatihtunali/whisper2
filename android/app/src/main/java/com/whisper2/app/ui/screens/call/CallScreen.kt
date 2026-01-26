package com.whisper2.app.ui.screens.call

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whisper2.app.ui.state.CallEndReason
import com.whisper2.app.ui.state.CallUiState
import kotlinx.coroutines.delay

/**
 * UI-G6: Full-screen Call UI
 *
 * Shows different layouts based on call state:
 * - Incoming: Accept/Decline buttons, caller info
 * - Outgoing: Ringing indicator, cancel button
 * - Connecting: Connection progress
 * - InCall: Duration, mute/speaker/end controls
 * - Ended: End reason, dismiss button
 */
@Composable
fun CallScreen(
    callState: CallUiState,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onEndCall: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        when (callState) {
            is CallUiState.Idle -> {
                // Should not show call screen in idle state
            }

            is CallUiState.Incoming -> {
                IncomingCallContent(
                    from = callState.from,
                    fromDisplayName = callState.fromDisplayName,
                    isVideo = callState.isVideo,
                    onAccept = onAccept,
                    onDecline = onDecline
                )
            }

            is CallUiState.Outgoing -> {
                OutgoingCallContent(
                    to = callState.to,
                    toDisplayName = callState.toDisplayName,
                    isVideo = callState.isVideo,
                    onCancel = onDecline
                )
            }

            is CallUiState.Connecting -> {
                ConnectingCallContent(
                    peerId = callState.peerId,
                    isVideo = callState.isVideo
                )
            }

            is CallUiState.InCall -> {
                InCallContent(
                    peerId = callState.peerId,
                    peerDisplayName = callState.peerDisplayName,
                    isVideo = callState.isVideo,
                    durationSeconds = callState.durationSeconds,
                    isMuted = callState.isMuted,
                    isSpeakerOn = callState.isSpeakerOn,
                    onToggleMute = onToggleMute,
                    onToggleSpeaker = onToggleSpeaker,
                    onEndCall = onEndCall
                )
            }

            is CallUiState.Ended -> {
                EndedCallContent(
                    reason = callState.reason,
                    durationSeconds = callState.durationSeconds,
                    onDismiss = onDismiss
                )
            }
        }
    }
}

@Composable
private fun IncomingCallContent(
    from: String,
    fromDisplayName: String?,
    isVideo: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    // Pulsing animation for incoming call indicator
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        // Call type indicator
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (isVideo) "Incoming Video Call" else "Incoming Call",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Avatar placeholder
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isVideo) Icons.Default.Videocam else Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(60.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Caller info
            Text(
                text = fromDisplayName ?: from.take(20),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            if (fromDisplayName != null) {
                Text(
                    text = from.take(24) + if (from.length > 24) "..." else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Accept/Decline buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // Decline button
            CallActionButton(
                icon = Icons.Default.CallEnd,
                label = "Decline",
                backgroundColor = MaterialTheme.colorScheme.error,
                onClick = onDecline
            )

            // Accept button
            CallActionButton(
                icon = if (isVideo) Icons.Default.Videocam else Icons.Default.Call,
                label = "Accept",
                backgroundColor = Color(0xFF4CAF50), // Green
                onClick = onAccept
            )
        }

        Spacer(modifier = Modifier.height(48.dp))
    }
}

@Composable
private fun OutgoingCallContent(
    to: String,
    toDisplayName: String?,
    isVideo: Boolean,
    onCancel: () -> Unit
) {
    // Dots animation for "calling..."
    var dotsCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            dotsCount = (dotsCount + 1) % 4
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Calling" + ".".repeat(dotsCount),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Avatar placeholder
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isVideo) Icons.Default.Videocam else Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(60.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = toDisplayName ?: to.take(20),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            if (toDisplayName != null) {
                Text(
                    text = to.take(24) + if (to.length > 24) "..." else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Cancel button
        CallActionButton(
            icon = Icons.Default.CallEnd,
            label = "Cancel",
            backgroundColor = MaterialTheme.colorScheme.error,
            onClick = onCancel
        )

        Spacer(modifier = Modifier.height(48.dp))
    }
}

@Composable
private fun ConnectingCallContent(
    peerId: String,
    isVideo: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Connecting...",
            style = MaterialTheme.typography.titleLarge
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = peerId.take(20),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun InCallContent(
    peerId: String,
    peerDisplayName: String?,
    isVideo: Boolean,
    durationSeconds: Int,
    isMuted: Boolean,
    isSpeakerOn: Boolean,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onEndCall: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Duration
            Text(
                text = formatDuration(durationSeconds),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Avatar placeholder
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isVideo) Icons.Default.Videocam else Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(50.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = peerDisplayName ?: peerId.take(20),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }

        // Call controls
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Mute and speaker row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Mute button
                CallControlButton(
                    icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    label = if (isMuted) "Unmute" else "Mute",
                    isActive = isMuted,
                    onClick = onToggleMute
                )

                // Speaker button
                CallControlButton(
                    icon = if (isSpeakerOn) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                    label = if (isSpeakerOn) "Speaker Off" else "Speaker",
                    isActive = isSpeakerOn,
                    onClick = onToggleSpeaker
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // End call button
            CallActionButton(
                icon = Icons.Default.CallEnd,
                label = "End Call",
                backgroundColor = MaterialTheme.colorScheme.error,
                onClick = onEndCall
            )
        }

        Spacer(modifier = Modifier.height(48.dp))
    }
}

@Composable
private fun EndedCallContent(
    reason: CallEndReason,
    durationSeconds: Int,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.CallEnd,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = when (reason) {
                CallEndReason.ENDED -> "Call Ended"
                CallEndReason.DECLINED -> "Call Declined"
                CallEndReason.BUSY -> "User Busy"
                CallEndReason.TIMEOUT -> "No Answer"
                CallEndReason.FAILED -> "Call Failed"
                CallEndReason.MISSED -> "Missed Call"
            },
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )

        if (durationSeconds > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Duration: ${formatDuration(durationSeconds)}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = onDismiss) {
            Text("OK")
        }
    }
}

@Composable
private fun CallActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    backgroundColor: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(72.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = backgroundColor,
                contentColor = Color.White
            )
        ) {
            Icon(
                icon,
                contentDescription = label,
                modifier = Modifier.size(32.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun CallControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FilledTonalIconButton(
            onClick = onClick,
            modifier = Modifier.size(56.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = if (isActive) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            )
        ) {
            Icon(
                icon,
                contentDescription = label,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontSize = 12.sp
        )
    }
}

private fun formatDuration(seconds: Int): String {
    val minutes = seconds / 60
    val secs = seconds % 60
    return "%02d:%02d".format(minutes, secs)
}
