package com.whisper2.app.ui.screens.calls

import android.Manifest
import android.content.pm.PackageManager
import android.view.ViewGroup
import android.widget.FrameLayout
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.whisper2.app.core.Logger
import com.whisper2.app.services.calls.CallEndReason
import com.whisper2.app.services.calls.CallState
import com.whisper2.app.ui.theme.*
import kotlinx.coroutines.delay
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * Unified Call Screen for both audio and video calls.
 * Uses the same layout structure - for video calls, the avatar is replaced with remote video
 * and a small local video PiP is added.
 *
 * Video rendering uses SurfaceViewRenderer with proper z-ordering:
 * - Remote video: setZOrderMediaOverlay(false) - renders behind
 * - Local video: setZOrderMediaOverlay(true) - renders on top as PiP
 *
 * This matches the react-native-webrtc implementation pattern.
 */
@Composable
fun CallScreen(
    peerId: String? = null,
    isVideo: Boolean = false,
    isOutgoing: Boolean = true,
    onCallEnded: () -> Unit,
    shouldAnswerIncoming: Boolean = false,
    viewModel: CallViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val callState by viewModel.callState.collectAsState()
    val activeCall by viewModel.activeCall.collectAsState()
    val callDuration by viewModel.callDuration.collectAsState()

    var permissionsGranted by remember { mutableStateOf(false) }
    var permissionsDenied by remember { mutableStateOf(false) }

    // Determine if this is a video call
    val isVideoCall = isVideo || (activeCall?.isVideo == true)

    // Get EGL context for video rendering
    val eglContext = viewModel.eglBaseContext

    // Video renderer state - using SurfaceViewRenderer with z-ordering
    var remoteRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var localRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var renderersInitialized by remember { mutableStateOf(false) }

    // Track if we've already answered the incoming call
    var incomingCallAnswered by remember { mutableStateOf(false) }

    // Permissions
    val requiredPermissions = remember(isVideo) {
        if (isVideo) {
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        } else {
            arrayOf(Manifest.permission.RECORD_AUDIO)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissionsGranted = permissions.values.all { it }
        permissionsDenied = !permissionsGranted
    }

    // Check permissions on launch
    LaunchedEffect(Unit) {
        Logger.i("[CallScreen] === INIT === isVideo=$isVideo, isOutgoing=$isOutgoing, peerId=$peerId")
        val allGranted = requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            permissionsGranted = true
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    // Log state changes
    LaunchedEffect(callState, activeCall, eglContext) {
        Logger.i("[CallScreen] State: callState=$callState, isVideoCall=$isVideoCall, eglContext=${eglContext != null}")
        activeCall?.let {
            Logger.i("[CallScreen] ActiveCall: callId=${it.callId}, isVideo=${it.isVideo}, peer=${it.peerName}")
        }
    }

    // Initiate outgoing call
    var callInitiated by remember { mutableStateOf(false) }
    LaunchedEffect(permissionsGranted, isOutgoing) {
        if (permissionsGranted && isOutgoing && peerId != null && !callInitiated) {
            callInitiated = true
            Logger.i("[CallScreen] Initiating outgoing call to $peerId, isVideo=$isVideo")
            viewModel.initiateCall(peerId, isVideo)
        }
    }

    // Answer incoming call
    LaunchedEffect(shouldAnswerIncoming, permissionsGranted, isVideoCall) {
        if (shouldAnswerIncoming && permissionsGranted && !incomingCallAnswered) {
            incomingCallAnswered = true
            Logger.i("[CallScreen] Answering incoming call (isVideo=$isVideoCall)")
            viewModel.answerCall()
        }
    }

    // Handle call ended
    LaunchedEffect(callState) {
        if (callState is CallState.Ended) {
            Logger.i("[CallScreen] Call ended, cleaning up...")
            delay(1500)
            viewModel.resetCallState()
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

    // Lifecycle observer for proper cleanup
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                Logger.i("[CallScreen] ON_DESTROY - releasing renderers")
                cleanupRenderers(viewModel, localRenderer, remoteRenderer)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            Logger.i("[CallScreen] onDispose - releasing renderers")
            lifecycleOwner.lifecycle.removeObserver(observer)
            cleanupRenderers(viewModel, localRenderer, remoteRenderer)
        }
    }

    // Permission denied UI
    if (permissionsDenied) {
        PermissionDeniedContent(isVideo)
        return
    }

    // Unified call UI
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(MetalBlack, MetalDark, MetalNavy)
                )
            )
    ) {
        CallContent(
            callState = callState,
            activeCall = activeCall,
            callDuration = callDuration,
            isVideoCall = isVideoCall,
            eglContext = eglContext,
            viewModel = viewModel,
            onRemoteRendererCreated = { remoteRenderer = it },
            onLocalRendererCreated = { localRenderer = it }
        )
    }
}

@Composable
private fun CallContent(
    callState: CallState,
    activeCall: com.whisper2.app.services.calls.ActiveCall?,
    callDuration: Long,
    isVideoCall: Boolean,
    eglContext: EglBase.Context?,
    viewModel: CallViewModel,
    onRemoteRendererCreated: (SurfaceViewRenderer) -> Unit,
    onLocalRendererCreated: (SurfaceViewRenderer) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // For video calls: REMOTE VIDEO is FULL SCREEN background
        // For audio calls: Show avatar in center
        if (isVideoCall && eglContext != null) {
            // FULL SCREEN remote video - this is the big screen showing the other person
            AndroidView(
                factory = { ctx ->
                    Logger.i("[CallScreen] Creating REMOTE SurfaceViewRenderer (FULL SCREEN)")
                    SurfaceViewRenderer(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        setEnableHardwareScaler(true)
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                        setMirror(false)
                        // Remote video - render behind (zOrder=0)
                        setZOrderMediaOverlay(false)

                        try {
                            init(eglContext, object : RendererCommon.RendererEvents {
                                override fun onFirstFrameRendered() {
                                    Logger.i("[CallScreen] REMOTE first frame rendered!")
                                }
                                override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
                                    Logger.i("[CallScreen] REMOTE resolution: ${width}x${height}, rotation: $rotation")
                                }
                            })
                            Logger.i("[CallScreen] REMOTE renderer initialized successfully")
                            onRemoteRendererCreated(this)
                            viewModel.setRemoteVideoSink(this)
                        } catch (e: Exception) {
                            Logger.e("[CallScreen] Failed to init REMOTE renderer: ${e.message}", e)
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Gradient overlay at bottom for controls visibility
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, MetalBlack.copy(alpha = 0.8f))
                        )
                    )
            )

            // Call info overlay (shown when not connected or briefly)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 100.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = activeCall?.peerName ?: activeCall?.peerId ?: "Unknown",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = getStatusText(callState, callDuration),
                        fontSize = 14.sp,
                        color = when (callState) {
                            CallState.Connected -> CallActive
                            is CallState.Ended -> StatusError
                            else -> TextSecondary
                        }
                    )
                }
            }

            // Call controls at bottom
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 50.dp)
            ) {
                CallControlsView(
                    isMuted = activeCall?.isMuted ?: false,
                    isSpeakerOn = activeCall?.isSpeakerOn ?: false,
                    isVideoEnabled = activeCall?.isLocalVideoEnabled ?: true,
                    isVideo = isVideoCall,
                    onMuteToggle = { viewModel.toggleMute() },
                    onSpeakerToggle = { viewModel.toggleSpeaker() },
                    onVideoToggle = { viewModel.toggleLocalVideo() },
                    onCameraSwitch = { viewModel.switchCamera() },
                    onEndCall = { viewModel.endCall() }
                )
            }

            // LOCAL video PiP - small overlay in top-right corner showing yourself
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 50.dp, end = 16.dp)
                    .size(100.dp, 140.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(2.dp, MetalGlow, RoundedCornerShape(12.dp))
                    .background(MetalSurface1)
            ) {
                AndroidView(
                    factory = { ctx ->
                        Logger.i("[CallScreen] Creating LOCAL SurfaceViewRenderer (PiP)")
                        SurfaceViewRenderer(ctx).apply {
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            setEnableHardwareScaler(true)
                            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                            setMirror(true) // Mirror for selfie view
                            // Local video - render on top (zOrder=1)
                            setZOrderMediaOverlay(true)

                            try {
                                init(eglContext, object : RendererCommon.RendererEvents {
                                    override fun onFirstFrameRendered() {
                                        Logger.i("[CallScreen] LOCAL first frame rendered!")
                                    }
                                    override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
                                        Logger.i("[CallScreen] LOCAL resolution: ${width}x${height}, rotation: $rotation")
                                    }
                                })
                                Logger.i("[CallScreen] LOCAL renderer initialized successfully")
                                onLocalRendererCreated(this)
                                viewModel.setLocalVideoSink(this)
                            } catch (e: Exception) {
                                Logger.e("[CallScreen] Failed to init LOCAL renderer: ${e.message}", e)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(12.dp))
                )
            }
        } else {
            // AUDIO CALL layout - avatar in center
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(100.dp))

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

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = activeCall?.peerName ?: activeCall?.peerId ?: "Unknown",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = getStatusText(callState, callDuration),
                    fontSize = 14.sp,
                    color = when (callState) {
                        CallState.Connected -> CallActive
                        is CallState.Ended -> StatusError
                        else -> TextSecondary
                    }
                )

                Spacer(modifier = Modifier.weight(1f))

                CallControlsView(
                    isMuted = activeCall?.isMuted ?: false,
                    isSpeakerOn = activeCall?.isSpeakerOn ?: false,
                    isVideoEnabled = activeCall?.isLocalVideoEnabled ?: true,
                    isVideo = isVideoCall,
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
private fun PermissionDeniedContent(isVideo: Boolean) {
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
}

private fun cleanupRenderers(
    viewModel: CallViewModel,
    localRenderer: SurfaceViewRenderer?,
    remoteRenderer: SurfaceViewRenderer?
) {
    try {
        viewModel.setLocalVideoSink(null)
        viewModel.setRemoteVideoSink(null)
    } catch (e: Exception) {
        Logger.e("[CallScreen] Error clearing sinks: ${e.message}")
    }

    try {
        localRenderer?.release()
        Logger.i("[CallScreen] Local renderer released")
    } catch (e: Exception) {
        Logger.e("[CallScreen] Error releasing local renderer: ${e.message}")
    }

    try {
        remoteRenderer?.release()
        Logger.i("[CallScreen] Remote renderer released")
    } catch (e: Exception) {
        Logger.e("[CallScreen] Error releasing remote renderer: ${e.message}")
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
        Row(
            horizontalArrangement = Arrangement.spacedBy(40.dp)
        ) {
            CallControlButton(
                icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                label = if (isMuted) "Unmute" else "Mute",
                isActive = isMuted,
                onClick = onMuteToggle
            )

            if (isVideo) {
                CallControlButton(
                    icon = if (isVideoEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                    label = if (isVideoEnabled) "Stop Video" else "Start Video",
                    isActive = !isVideoEnabled,
                    onClick = onVideoToggle
                )
            }

            CallControlButton(
                icon = if (isSpeakerOn) Icons.Default.VolumeUp else Icons.Default.VolumeDown,
                label = if (isSpeakerOn) "Speaker On" else "Speaker",
                isActive = isSpeakerOn,
                onClick = onSpeakerToggle
            )

            if (isVideo) {
                CallControlButton(
                    icon = Icons.Default.Cameraswitch,
                    label = "Flip",
                    isActive = false,
                    onClick = onCameraSwitch
                )
            }
        }

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
        CallEndReason.CANCELLED -> "Call cancelled"
        CallEndReason.DECLINED -> "Call declined"
        CallEndReason.BUSY -> "User busy"
        CallEndReason.TIMEOUT -> "No answer"
        CallEndReason.FAILED -> "Call failed"
    }
}
