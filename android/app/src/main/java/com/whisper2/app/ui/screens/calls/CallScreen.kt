package com.whisper2.app.ui.screens.calls

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.whisper2.app.services.calls.CallEndReason
import com.whisper2.app.services.calls.CallState
import com.whisper2.app.ui.theme.*
import kotlinx.coroutines.delay
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

@Composable
fun CallScreen(
    peerId: String? = null,
    isVideo: Boolean = false,
    isOutgoing: Boolean = true,
    onCallEnded: () -> Unit,
    viewModel: CallViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val callState by viewModel.callState.collectAsState()
    val activeCall by viewModel.activeCall.collectAsState()
    val callDuration by viewModel.callDuration.collectAsState()

    var permissionsGranted by remember { mutableStateOf(false) }
    var permissionsDenied by remember { mutableStateOf(false) }

    // Permissions needed for calls
    val requiredPermissions = remember(isVideo) {
        if (isVideo) {
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        } else {
            arrayOf(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            permissionsGranted = true
        } else {
            permissionsDenied = true
        }
    }

    // Check and request permissions on launch
    LaunchedEffect(Unit) {
        val allGranted = requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            permissionsGranted = true
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    // Initiate outgoing call when permissions granted (only once)
    var callInitiated by remember { mutableStateOf(false) }
    LaunchedEffect(permissionsGranted, isOutgoing) {
        if (permissionsGranted && isOutgoing && peerId != null && !callInitiated) {
            callInitiated = true
            viewModel.initiateCall(peerId, isVideo)
        }
    }

    // Handle call ended
    LaunchedEffect(callState) {
        if (callState is CallState.Ended) {
            delay(1500) // Show end reason briefly
            viewModel.resetCallState()  // Reset state before navigating
            onCallEnded()
        }
    }

    // Handle permissions denied
    LaunchedEffect(permissionsDenied) {
        if (permissionsDenied) {
            delay(2000)
            onCallEnded()
        }
    }

    // Show permission denied message
    if (permissionsDenied) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(MetalBlack, MetalDark, MetalNavy)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    Icons.Default.MicOff,
                    contentDescription = null,
                    tint = StatusError,
                    modifier = Modifier.size(64.dp)
                )
                Text(
                    text = if (isVideo) "Camera and microphone permission required"
                           else "Microphone permission required",
                    color = TextPrimary,
                    fontSize = 16.sp
                )
                Text(
                    text = "Please enable in Settings",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            }
        }
        return
    }

    // Get EGL context for video rendering
    val eglContext = viewModel.eglBaseContext

    // Create and remember SurfaceViewRenderers
    var localRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var remoteRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    // Log video state for debugging
    LaunchedEffect(activeCall, eglContext) {
        com.whisper2.app.core.Logger.d("[CallScreen] activeCall?.isVideo=${activeCall?.isVideo}, eglContext=${if (eglContext != null) "exists" else "NULL"}")
    }

    // Cleanup renderers on dispose
    DisposableEffect(Unit) {
        onDispose {
            try {
                localRenderer?.release()
                remoteRenderer?.release()
                viewModel.setLocalVideoSink(null)
                viewModel.setRemoteVideoSink(null)
            } catch (e: Exception) {
                com.whisper2.app.core.Logger.e("[CallScreen] Error releasing renderers: ${e.message}")
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(MetalBlack, MetalDark, MetalNavy)
                )
            )
    ) {
        // Video call UI
        if (activeCall?.isVideo == true && eglContext != null) {
            // Remote video (full screen background)
            AndroidView(
                factory = { ctx ->
                    SurfaceViewRenderer(ctx).apply {
                        try {
                            setMirror(false)
                            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                            if (eglContext != null) {
                                init(eglContext, null)
                                remoteRenderer = this
                                viewModel.setRemoteVideoSink(this)
                            }
                        } catch (e: Exception) {
                            com.whisper2.app.core.Logger.e("[CallScreen] Error initializing remote renderer: ${e.message}")
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Local video (small picture-in-picture)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(120.dp, 160.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(2.dp, MetalGlow, RoundedCornerShape(12.dp))
            ) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceViewRenderer(ctx).apply {
                            try {
                                setMirror(true)  // Mirror front camera
                                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                                if (eglContext != null) {
                                    init(eglContext, null)
                                    localRenderer = this
                                    viewModel.setLocalVideoSink(this)
                                }
                            } catch (e: Exception) {
                                com.whisper2.app.core.Logger.e("[CallScreen] Error initializing local renderer: ${e.message}")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Video call overlay controls
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top: Name and status
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 40.dp)
                ) {
                    Text(
                        text = activeCall?.peerName ?: activeCall?.peerId ?: "Unknown",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val statusColor = when (callState) {
                        CallState.Connected -> CallActive
                        is CallState.Ended -> StatusError
                        else -> Color.White.copy(alpha = 0.7f)
                    }
                    Text(
                        text = getStatusText(callState, callDuration),
                        fontSize = 14.sp,
                        color = statusColor
                    )
                }

                // Bottom: Controls
                CallControlsView(
                    isMuted = activeCall?.isMuted ?: false,
                    isSpeakerOn = activeCall?.isSpeakerOn ?: false,
                    isVideoEnabled = activeCall?.isLocalVideoEnabled ?: true,
                    isVideo = true,
                    onMuteToggle = { viewModel.toggleMute() },
                    onSpeakerToggle = { viewModel.toggleSpeaker() },
                    onVideoToggle = { viewModel.toggleLocalVideo() },
                    onCameraSwitch = { viewModel.switchCamera() },
                    onEndCall = { viewModel.endCall() }
                )
            }
        } else {
            // Audio call UI
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(60.dp))

                // Avatar with pulsing animation
                val pulseScale by rememberInfiniteTransition(label = "pulse").animateFloat(
                    initialValue = 1f,
                    targetValue = 1.08f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1000, easing = EaseInOutSine),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "pulseScale"
                )

                Box(
                    modifier = Modifier
                        .size((100 * pulseScale).dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(MetalSurface2, MetalSurface1, MetalSlate)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = (activeCall?.peerName ?: activeCall?.peerId ?: "?").take(1).uppercase(),
                        fontSize = 40.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Name
                Text(
                    text = activeCall?.peerName ?: activeCall?.peerId ?: "Unknown",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Status/Duration with color based on state
                val statusColor = when (callState) {
                    CallState.Connected -> CallActive
                    is CallState.Ended -> StatusError
                    else -> TextSecondary
                }
                Text(
                    text = getStatusText(callState, callDuration),
                    fontSize = 14.sp,
                    color = statusColor
                )

                Spacer(modifier = Modifier.weight(1f))

                // Call controls
                CallControlsView(
                    isMuted = activeCall?.isMuted ?: false,
                    isSpeakerOn = activeCall?.isSpeakerOn ?: false,
                    isVideoEnabled = activeCall?.isLocalVideoEnabled ?: true,
                    isVideo = activeCall?.isVideo ?: false,
                    onMuteToggle = { viewModel.toggleMute() },
                    onSpeakerToggle = { viewModel.toggleSpeaker() },
                    onVideoToggle = { viewModel.toggleLocalVideo() },
                    onCameraSwitch = { viewModel.switchCamera() },
                    onEndCall = { viewModel.endCall() }
                )

                Spacer(modifier = Modifier.height(50.dp))
            }
        }
    }
}

@Composable
private fun CallControlsView(
    isMuted: Boolean,
    isSpeakerOn: Boolean,
    isVideoEnabled: Boolean,
    isVideo: Boolean,
    onMuteToggle: () -> Unit,
    onSpeakerToggle: () -> Unit,
    onVideoToggle: () -> Unit,
    onCameraSwitch: () -> Unit,
    onEndCall: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Top row of controls
        Row(
            horizontalArrangement = Arrangement.spacedBy(40.dp)
        ) {
            // Mute
            CallControlButton(
                icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                label = if (isMuted) "Unmute" else "Mute",
                isActive = isMuted,
                onClick = onMuteToggle
            )

            // Video toggle (video calls only)
            if (isVideo) {
                CallControlButton(
                    icon = if (isVideoEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                    label = if (isVideoEnabled) "Stop Video" else "Start Video",
                    isActive = !isVideoEnabled,
                    onClick = onVideoToggle
                )
            }

            // Speaker
            CallControlButton(
                icon = if (isSpeakerOn) Icons.Default.VolumeUp else Icons.Default.VolumeDown,
                label = if (isSpeakerOn) "Speaker On" else "Speaker",
                isActive = isSpeakerOn,
                onClick = onSpeakerToggle
            )

            // Switch camera (video calls only)
            if (isVideo) {
                CallControlButton(
                    icon = Icons.Default.Cameraswitch,
                    label = "Flip",
                    isActive = false,
                    onClick = onCameraSwitch
                )
            }
        }

        // End call button - vibrant red with glow effect
        IconButton(
            onClick = onEndCall,
            modifier = Modifier
                .size(70.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(CallDecline, StatusErrorMuted)
                    )
                )
        ) {
            Icon(
                Icons.Default.CallEnd,
                contentDescription = "End Call",
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
private fun CallControlButton(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(
                    if (isActive)
                        Brush.radialGradient(listOf(TextPrimary, MetalGlow))
                    else
                        Brush.radialGradient(listOf(MetalSurface2, MetalSurface1))
                )
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (isActive) MetalDark else TextPrimary,
                modifier = Modifier.size(24.dp)
            )
        }

        Text(
            text = label,
            fontSize = 10.sp,
            color = TextTertiary
        )
    }
}

private fun getStatusText(callState: CallState, durationSeconds: Long): String {
    return when (callState) {
        CallState.Idle -> ""
        CallState.Initiating -> "Calling..."
        CallState.Ringing -> "Ringing..."
        CallState.Connecting -> "Connecting..."
        CallState.Connected -> formatDuration(durationSeconds)
        CallState.Reconnecting -> "Reconnecting..."
        is CallState.Ended -> getEndReasonText(callState.reason)
    }
}

private fun formatDuration(seconds: Long): String {
    val minutes = seconds / 60
    val secs = seconds % 60
    return String.format("%02d:%02d", minutes, secs)
}

private fun getEndReasonText(reason: CallEndReason): String {
    return when (reason) {
        CallEndReason.ENDED -> "Call ended"
        CallEndReason.DECLINED -> "Call declined"
        CallEndReason.BUSY -> "User busy"
        CallEndReason.TIMEOUT -> "No answer"
        CallEndReason.FAILED -> "Call failed"
    }
}
