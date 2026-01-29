package com.whisper2.app.ui.components

import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    onDelete: (Boolean) -> Unit
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
                        "voice", "audio" -> VoiceMessageContent(message, isOutgoing)
                        "location" -> LocationMessageContent(message, isOutgoing)
                        "image" -> ImageMessageContent(message, isOutgoing)
                        "video" -> VideoMessageContent(message, isOutgoing)
                        "file" -> FileMessageContent(message, isOutgoing)
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
private fun VoiceMessageContent(message: MessageEntity, isOutgoing: Boolean) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableStateOf(0f) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    // Duration from message (content is duration in seconds for voice messages)
    val durationSeconds = message.attachmentDuration ?: message.content.toIntOrNull() ?: 0

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
        // Play/Pause button with glow effect
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
            IconButton(
                onClick = {
                    if (isPlaying) {
                        // Pause
                        mediaPlayer?.pause()
                        isPlaying = false
                    } else {
                        // Play
                        val localPath = message.attachmentLocalPath
                        if (localPath != null && (File(localPath).exists() || localPath.startsWith("content://"))) {
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
                        } else {
                            // TODO: Download from server if not available locally
                            android.util.Log.d("VoiceMessage", "Audio file not available locally")
                        }
                    }
                }
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
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
                text = "${durationSeconds}s",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun LocationMessageContent(message: MessageEntity, isOutgoing: Boolean) {
    Column {
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
private fun ImageMessageContent(message: MessageEntity, isOutgoing: Boolean) {
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
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.Image,
            null,
            tint = TextTertiary,
            modifier = Modifier.size(40.dp)
        )
    }
}

@Composable
private fun VideoMessageContent(message: MessageEntity, isOutgoing: Boolean) {
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
            ),
        contentAlignment = Alignment.Center
    ) {
        // Play button overlay
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
            Icon(
                Icons.Default.PlayArrow,
                null,
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
private fun FileMessageContent(message: MessageEntity, isOutgoing: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(vertical = 4.dp)
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
            Icon(
                Icons.AutoMirrored.Filled.InsertDriveFile,
                null,
                tint = PrimaryBlueLight,
                modifier = Modifier.size(24.dp)
            )
        }
        Column {
            Text(
                text = "File",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "Tap to download",
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
