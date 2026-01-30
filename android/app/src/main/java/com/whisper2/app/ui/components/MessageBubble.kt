package com.whisper2.app.ui.components

import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.whisper2.app.data.local.db.entities.MessageEntity
import com.whisper2.app.ui.theme.*
import kotlinx.coroutines.delay
import java.io.File

/**
 * Premium Metal Message Bubble
 * iOS-inspired with glass effect, refined gradients, and elegant shadows.
 */
@Composable
fun MessageBubble(
    message: MessageEntity,
    onDelete: (Boolean) -> Unit,
    onDownload: (String) -> Unit = {},
    isDownloading: Boolean = false,
    onImageClick: ((String) -> Unit)? = null,
    onVideoClick: ((String) -> Unit)? = null
) {
    val isOutgoing = message.direction == "outgoing"
    var showMenu by remember { mutableStateOf(false) }

    // Premium bubble shape with asymmetric corners
    val bubbleShape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = if (isOutgoing) 18.dp else 4.dp,
        bottomEnd = if (isOutgoing) 4.dp else 18.dp
    )

    // Premium gradient brushes
    val bubbleGradient = if (isOutgoing) {
        Brush.linearGradient(
            colors = listOf(
                BubbleOutgoingStart,
                BubbleOutgoingEnd
            )
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                BubbleIncomingStart,
                BubbleIncomingEnd
            )
        )
    }

    // Subtle border for glass effect
    val borderGradient = if (isOutgoing) {
        Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.15f),
                Color.White.copy(alpha = 0.05f)
            )
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.08f),
                Color.White.copy(alpha = 0.02f)
            )
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start
    ) {
        if (isOutgoing) Spacer(modifier = Modifier.weight(0.15f))

        Box {
            // Main bubble
            Box(
                modifier = Modifier
                    .clip(bubbleShape)
                    .background(bubbleGradient)
                    .border(
                        width = 1.dp,
                        brush = borderGradient,
                        shape = bubbleShape
                    )
            ) {
                // Glass overlay effect
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.08f),
                                    Color.Transparent
                                ),
                                startY = 0f,
                                endY = 100f
                            )
                        )
                )

                Column(
                    modifier = Modifier.padding(
                        horizontal = 14.dp,
                        vertical = 10.dp
                    )
                ) {
                    // Content based on type
                    when (message.contentType) {
                        "voice", "audio" -> VoiceMessageContent(message, isOutgoing, onDownload, isDownloading)
                        "location" -> LocationMessageContent(message, isOutgoing)
                        "image" -> ImageMessageContent(message, isOutgoing, onDownload, isDownloading, onImageClick)
                        "video" -> VideoMessageContent(message, isOutgoing, onDownload, isDownloading, onVideoClick)
                        "file" -> FileMessageContent(message, isOutgoing, onDownload, isDownloading)
                        "call" -> CallMessageContent(message, isOutgoing)
                        else -> TextMessageContent(message.content)
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Time and status row
                    Row(
                        modifier = Modifier.align(if (isOutgoing) Alignment.End else Alignment.Start),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = message.formattedTime,
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                        if (isOutgoing) {
                            MessageStatusIcon(message.status)
                        }
                    }
                }
            }

            // Context menu
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                modifier = Modifier.background(MetalSlate)
            ) {
                if (message.contentType == "text") {
                    DropdownMenuItem(
                        text = { Text("Copy", color = TextPrimary) },
                        onClick = {
                            // Copy to clipboard
                            showMenu = false
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.ContentCopy,
                                null,
                                tint = TextSecondary
                            )
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text("Delete for Me", color = TextPrimary) },
                    onClick = {
                        onDelete(false)
                        showMenu = false
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Delete,
                            null,
                            tint = TextSecondary
                        )
                    }
                )
                if (isOutgoing) {
                    DropdownMenuItem(
                        text = { Text("Delete for Everyone", color = StatusError) },
                        onClick = {
                            onDelete(true)
                            showMenu = false
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.DeleteForever,
                                null,
                                tint = StatusError
                            )
                        }
                    )
                }
            }
        }

        if (!isOutgoing) Spacer(modifier = Modifier.weight(0.15f))
    }
}

@Composable
private fun TextMessageContent(content: String) {
    Text(
        text = content,
        color = Color.White,
        fontSize = 15.sp,
        lineHeight = 20.sp
    )
}

/**
 * Voice message content with audio player.
 * Plays audio from local file path or can be downloaded from server.
 */
@Composable
private fun VoiceMessageContent(
    message: MessageEntity,
    isOutgoing: Boolean,
    onDownload: (String) -> Unit,
    isDownloading: Boolean
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableStateOf(0f) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    // Duration from message (content is duration in seconds for voice messages)
    val durationSeconds = message.attachmentDuration ?: message.content.toIntOrNull() ?: 0

    // Check if file is available locally
    val localPath = message.attachmentLocalPath
    val hasLocalFile = remember(localPath) {
        localPath != null && (File(localPath).exists() || localPath.startsWith("content://"))
    }

    // Cleanup media player when composable is disposed
    DisposableEffect(message.id) {
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    // Update progress while playing
    LaunchedEffect(isPlaying) {
        while (isPlaying && mediaPlayer != null) {
            try {
                mediaPlayer?.let { player ->
                    if (player.isPlaying) {
                        val duration = player.duration.toFloat()
                        if (duration > 0) {
                            currentPosition = player.currentPosition / duration
                        }
                    }
                }
            } catch (e: Exception) {
                // Player may be released
            }
            delay(100)
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        // Play/Pause/Download button with glow effect
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.2f),
                            Color.Transparent
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isDownloading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            } else {
                IconButton(
                    onClick = {
                        if (!hasLocalFile) {
                            // Need to download first
                            onDownload(message.id)
                        } else if (isPlaying) {
                            // Pause
                            mediaPlayer?.pause()
                            isPlaying = false
                        } else {
                            // Play
                            if (localPath != null) {
                                try {
                                    if (mediaPlayer == null) {
                                        mediaPlayer = MediaPlayer().apply {
                                            if (localPath.startsWith("content://")) {
                                                setDataSource(context, Uri.parse(localPath))
                                            } else {
                                                setDataSource(localPath)
                                            }
                                            prepare()
                                            setOnCompletionListener {
                                                isPlaying = false
                                                currentPosition = 0f
                                            }
                                        }
                                    }
                                    mediaPlayer?.start()
                                    isPlaying = true
                                } catch (e: Exception) {
                                    android.util.Log.e("VoiceMessage", "Error playing audio: ${e.message}")
                                }
                            }
                        }
                    }
                ) {
                    Icon(
                        when {
                            !hasLocalFile -> Icons.Default.Download
                            isPlaying -> Icons.Default.Pause
                            else -> Icons.Default.PlayArrow
                        },
                        contentDescription = when {
                            !hasLocalFile -> "Download"
                            isPlaying -> "Pause"
                            else -> "Play"
                        },
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        Column {
            // Waveform visualization with progress
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.width(100.dp)
            ) {
                val totalBars = 20
                val playedBars = (currentPosition * totalBars).toInt()
                repeat(totalBars) { index ->
                    val height = (8 + (index * 7 % 16)).dp
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(height)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                if (index <= playedBars) {
                                    Color.White.copy(alpha = 0.9f)
                                } else {
                                    Color.White.copy(alpha = 0.4f)
                                }
                            )
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (!hasLocalFile && !isDownloading) "Tap to download" else "${durationSeconds}s",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun LocationMessageContent(message: MessageEntity, isOutgoing: Boolean) {
    val context = LocalContext.current

    // Get coordinates from message
    val latitude = message.locationLatitude ?: run {
        // Try parsing from JSON content
        try {
            val json = org.json.JSONObject(message.content)
            json.optDouble("latitude", 0.0)
        } catch (e: Exception) { 0.0 }
    }
    val longitude = message.locationLongitude ?: run {
        try {
            val json = org.json.JSONObject(message.content)
            json.optDouble("longitude", 0.0)
        } catch (e: Exception) { 0.0 }
    }

    Column(
        modifier = Modifier.clickable {
            // Open in Google Maps
            val uri = android.net.Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude")
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
            intent.setPackage("com.google.android.apps.maps")
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                // If Google Maps not installed, open in browser
                val browserUri = android.net.Uri.parse("https://www.google.com/maps?q=$latitude,$longitude")
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, browserUri))
            }
        }
    ) {
        // Map placeholder with metal styling
        Box(
            modifier = Modifier
                .size(200.dp, 130.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MetalSurface2,
                            MetalSurface1
                        )
                    )
                )
                .border(
                    1.dp,
                    BorderDefault.copy(alpha = 0.3f),
                    RoundedCornerShape(12.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.LocationOn,
                null,
                tint = StatusError,
                modifier = Modifier.size(36.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Map,
                null,
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Open in Maps",
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.9f),
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun ImageMessageContent(
    message: MessageEntity,
    isOutgoing: Boolean,
    onDownload: (String) -> Unit,
    isDownloading: Boolean,
    onImageClick: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val localPath = message.attachmentLocalPath

    // Check if we have a local file to display
    val imageUri = remember(localPath) {
        when {
            localPath == null -> null
            localPath.startsWith("content://") -> Uri.parse(localPath)
            File(localPath).exists() -> Uri.fromFile(File(localPath))
            else -> null
        }
    }

    Box(
        modifier = Modifier
            .widthIn(max = 240.dp)
            .heightIn(max = 300.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MetalSurface2,
                        MetalSurface1
                    )
                )
            )
            .border(
                1.dp,
                BorderDefault.copy(alpha = 0.3f),
                RoundedCornerShape(12.dp)
            )
            .clickable(enabled = !isDownloading) {
                if (imageUri != null && localPath != null) {
                    // Image is downloaded, open viewer
                    onImageClick?.invoke(localPath)
                } else {
                    // Need to download first
                    onDownload(message.id)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (imageUri != null) {
            // Display actual image using Coil
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(imageUri)
                    .crossfade(true)
                    .build(),
                contentDescription = "Image",
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Fit
            )
        } else {
            // Placeholder when image not available locally
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                if (isDownloading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(40.dp),
                        color = TextSecondary,
                        strokeWidth = 3.dp
                    )
                } else {
                    Icon(
                        Icons.Default.Download,
                        null,
                        tint = TextTertiary,
                        modifier = Modifier.size(40.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (isDownloading) "Downloading..." else "Tap to download",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun VideoMessageContent(
    message: MessageEntity,
    isOutgoing: Boolean,
    onDownload: (String) -> Unit,
    isDownloading: Boolean,
    onVideoClick: ((String) -> Unit)? = null
) {
    val localPath = message.attachmentLocalPath
    val hasLocalFile = remember(localPath) {
        localPath != null && File(localPath).exists()
    }

    Box(
        modifier = Modifier
            .size(220.dp, 160.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MetalSurface2,
                        MetalSurface1
                    )
                )
            )
            .border(
                1.dp,
                BorderDefault.copy(alpha = 0.3f),
                RoundedCornerShape(12.dp)
            )
            .clickable(enabled = !isDownloading) {
                if (hasLocalFile && localPath != null) {
                    // Video is downloaded, open player
                    onVideoClick?.invoke(localPath)
                } else {
                    // Need to download first
                    onDownload(message.id)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Play/Download button overlay
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.6f),
                            Color.Black.copy(alpha = 0.3f)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isDownloading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = Color.White,
                    strokeWidth = 3.dp
                )
            } else {
                Icon(
                    if (hasLocalFile) Icons.Default.PlayArrow else Icons.Default.Download,
                    null,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

@Composable
private fun FileMessageContent(
    message: MessageEntity,
    isOutgoing: Boolean,
    onDownload: (String) -> Unit,
    isDownloading: Boolean
) {
    val localPath = message.attachmentLocalPath
    val hasLocalFile = remember(localPath) {
        localPath != null && File(localPath).exists()
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .padding(vertical = 4.dp)
            .clickable(enabled = !hasLocalFile && !isDownloading) {
                onDownload(message.id)
            }
    ) {
        // File icon with background
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            MetalSurface2,
                            MetalSurface3
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isDownloading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = PrimaryBlueLight,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    if (hasLocalFile) Icons.AutoMirrored.Filled.InsertDriveFile else Icons.Default.Download,
                    null,
                    tint = PrimaryBlueLight,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Column {
            Text(
                text = message.attachmentFileName ?: "File",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = when {
                    isDownloading -> "Downloading..."
                    hasLocalFile -> "Tap to open"
                    else -> "Tap to download"
                },
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun MessageStatusIcon(status: String) {
    val icon = when (status) {
        "pending" -> Icons.Default.Schedule
        "sent" -> Icons.Default.Check
        "delivered" -> Icons.Default.DoneAll
        "read" -> Icons.Default.DoneAll
        "failed" -> Icons.Default.Error
        else -> Icons.Default.Schedule
    }

    val tint = when (status) {
        "read" -> PrimaryBlueLight
        "failed" -> StatusError
        else -> Color.White.copy(alpha = 0.6f)
    }

    Icon(
        icon,
        contentDescription = status,
        modifier = Modifier.size(14.dp),
        tint = tint
    )
}

/**
 * Call message content showing call history as a message bubble.
 * Displays call type (voice/video), outcome, and duration.
 */
@Composable
private fun CallMessageContent(message: MessageEntity, isOutgoing: Boolean) {
    // Parse call metadata from content JSON
    val callMetadata = remember(message.content) {
        try {
            val json = org.json.JSONObject(message.content)
            CallMetadata(
                type = json.optString("type", "voice"),
                outcome = json.optString("outcome", "ended"),
                duration = json.optInt("duration", 0)
            )
        } catch (e: Exception) {
            CallMetadata("voice", "ended", 0)
        }
    }

    val isVideo = callMetadata.type == "video"
    val isMissedOrDeclined = callMetadata.outcome in listOf("missed", "declined", "no_answer", "cancelled")
    val isSuccessful = callMetadata.outcome in listOf("completed", "ended", "answered")

    // Color based on outcome
    val iconColor = when {
        isMissedOrDeclined -> StatusError
        isSuccessful -> StatusSuccess
        else -> TextSecondary
    }

    // Icon based on call type and direction
    val callIcon = when {
        isVideo && isOutgoing -> Icons.Default.VideoCall
        isVideo && !isOutgoing -> Icons.Default.VideoCall
        isOutgoing -> Icons.Default.Call
        else -> Icons.Default.Call
    }

    // Direction indicator
    val directionIcon = when {
        isMissedOrDeclined && !isOutgoing -> Icons.Default.CallMissed
        isOutgoing -> Icons.Default.CallMade
        else -> Icons.Default.CallReceived
    }

    // Outcome text
    val outcomeText = when (callMetadata.outcome) {
        "completed", "ended", "answered" -> if (isVideo) "Video Call" else "Voice Call"
        "missed" -> "Missed ${if (isVideo) "Video" else ""} Call"
        "declined" -> "Declined"
        "no_answer" -> "No Answer"
        "cancelled" -> "Cancelled"
        "busy" -> "Busy"
        "failed" -> "Failed"
        else -> if (isVideo) "Video Call" else "Voice Call"
    }

    // Duration text
    val durationText = if (callMetadata.duration > 0) {
        formatDuration(callMetadata.duration)
    } else {
        null
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        // Call type icon with background
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.linearGradient(
                        colors = if (isMissedOrDeclined) {
                            listOf(StatusError.copy(alpha = 0.2f), StatusError.copy(alpha = 0.1f))
                        } else {
                            listOf(StatusSuccess.copy(alpha = 0.2f), StatusSuccess.copy(alpha = 0.1f))
                        }
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                callIcon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(24.dp)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Direction indicator
                Icon(
                    directionIcon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = outcomeText,
                    color = if (isMissedOrDeclined) StatusError else Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // Duration if available
            if (durationText != null) {
                Text(
                    text = durationText,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
        }
    }
}

/**
 * Data class for call metadata stored in message content.
 */
private data class CallMetadata(
    val type: String,    // "voice" or "video"
    val outcome: String, // "completed", "missed", "declined", "no_answer", "cancelled", "busy", "failed"
    val duration: Int    // Duration in seconds
)

/**
 * Format duration in seconds to human-readable string.
 */
private fun formatDuration(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60

    return when {
        hours > 0 -> String.format("%d:%02d:%02d", hours, minutes, secs)
        minutes > 0 -> String.format("%d:%02d", minutes, secs)
        else -> String.format("0:%02d", secs)
    }
}

/**
 * Standalone Call Message Bubble for use in conversation list or dedicated displays.
 * This provides a full-width call record display with all details.
 */
@Composable
fun CallMessageBubble(
    callType: String,        // "voice" or "video"
    outcome: String,         // "completed", "missed", "declined", etc.
    duration: Int?,          // Duration in seconds (null if not answered)
    isOutgoing: Boolean,     // true if user initiated the call
    timestamp: Long,         // When the call occurred
    modifier: Modifier = Modifier
) {
    val isVideo = callType == "video"
    val isMissedOrDeclined = outcome in listOf("missed", "declined", "no_answer", "cancelled")
    val isSuccessful = outcome in listOf("completed", "ended", "answered")

    // Color scheme based on outcome
    val accentColor = when {
        isMissedOrDeclined -> StatusError
        isSuccessful -> StatusSuccess
        else -> TextSecondary
    }

    val backgroundColor = when {
        isMissedOrDeclined -> StatusError.copy(alpha = 0.1f)
        isSuccessful -> StatusSuccess.copy(alpha = 0.1f)
        else -> MetalSurface1
    }

    // Icons
    val callIcon = if (isVideo) Icons.Default.VideoCall else Icons.Default.Call

    val directionIcon = when {
        isMissedOrDeclined && !isOutgoing -> Icons.Default.CallMissed
        isOutgoing -> Icons.Default.CallMade
        else -> Icons.Default.CallReceived
    }

    // Text descriptions
    val typeText = if (isVideo) "Video Call" else "Voice Call"
    val outcomeText = when (outcome) {
        "completed", "ended", "answered" -> if (isOutgoing) "Outgoing" else "Incoming"
        "missed" -> "Missed"
        "declined" -> "Declined"
        "no_answer" -> "No Answer"
        "cancelled" -> "Cancelled"
        "busy" -> "Busy"
        "failed" -> "Call Failed"
        else -> ""
    }

    val formattedTime = remember(timestamp) {
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
    }

    val durationText = duration?.let { formatDuration(it) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = backgroundColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Call type icon
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accentColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    callIcon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(28.dp)
                )
            }

            // Call details
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        directionIcon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = typeText,
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = outcomeText,
                        color = if (isMissedOrDeclined) accentColor else TextSecondary,
                        fontSize = 13.sp
                    )

                    if (durationText != null) {
                        Text(
                            text = "~",
                            color = TextTertiary,
                            fontSize = 13.sp
                        )
                        Text(
                            text = durationText,
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            // Timestamp
            Text(
                text = formattedTime,
                color = TextTertiary,
                fontSize = 12.sp
            )
        }
    }
}
