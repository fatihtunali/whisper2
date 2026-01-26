package com.whisper2.app.ui.screens.chat

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.whisper2.app.network.ws.WsState
import com.whisper2.app.ui.state.AttachmentDownloadState
import com.whisper2.app.ui.state.AttachmentUiItem
import com.whisper2.app.ui.state.MessageUiItem
import com.whisper2.app.ui.state.MessageUiStatus
import com.whisper2.app.ui.theme.*
import com.whisper2.app.ui.viewmodels.ChatViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Chat Screen
 * Edge-to-edge, responsive design matching original React Native app
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: String,
    onBackClick: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val listState = rememberLazyListState()

    // Responsive values
    val spacingMd = WhisperSpacing.md()
    val spacingSm = WhisperSpacing.sm()

    // Check if tablet for bubble max width
    val configuration = LocalConfiguration.current
    val isTabletDevice = configuration.screenWidthDp >= 600
    val maxBubbleWidth = if (isTabletDevice) 400.dp else (configuration.screenWidthDp * 0.8f).dp

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WhisperColors.Background)
            // Handle status bar inset for edge-to-edge
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        // Header
        ChatHeader(
            peerDisplayName = uiState.peerDisplayName,
            wsState = connectionState.wsState,
            onBackClick = onBackClick,
            onVoiceCall = { viewModel.initiateVoiceCall() },
            onVideoCall = { viewModel.initiateVideoCall() }
        )

        // Messages content
        if (uiState.messages.isEmpty()) {
            EmptyChatContent(
                modifier = Modifier.weight(1f)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = spacingMd),
                state = listState,
                reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(WhisperSpacing.xs()),
                contentPadding = PaddingValues(vertical = spacingSm)
            ) {
                items(
                    items = uiState.messages,
                    key = { it.id }
                ) { message ->
                    val downloadState by viewModel.getAttachmentDownloadState(message.id).collectAsState()

                    MessageBubble(
                        message = message,
                        maxWidth = maxBubbleWidth,
                        attachmentDownloadState = downloadState,
                        onRetry = {
                            if (message.status == MessageUiStatus.FAILED) {
                                viewModel.retryFailedMessage(message.id)
                            }
                        },
                        onDownloadAttachment = {
                            viewModel.downloadAttachment(message.id)
                        }
                    )
                }
            }
        }

        // Input area with navigation bar padding
        MessageInput(
            text = uiState.inputText,
            onTextChange = { viewModel.updateInputText(it) },
            onSend = { viewModel.sendMessage() },
            onAttachmentClick = { viewModel.onAttachmentPickerClick() },
            isSending = uiState.isSending,
            isConnected = connectionState.wsState == WsState.CONNECTED
        )
    }

    // Error handling
    if (uiState.error != null) {
        LaunchedEffect(uiState.error) {
            kotlinx.coroutines.delay(3000)
            viewModel.clearError()
        }
    }
}

@Composable
private fun ChatHeader(
    peerDisplayName: String,
    wsState: WsState,
    onBackClick: () -> Unit,
    onVoiceCall: () -> Unit,
    onVideoCall: () -> Unit
) {
    val spacingMd = WhisperSpacing.md()
    val spacingSm = WhisperSpacing.sm()
    val avatarSize = moderateScale(40f)
    val buttonSize = moderateScale(40f)
    val iconSize = moderateScale(24f)

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacingMd, vertical = spacingSm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Back button
            Surface(
                modifier = Modifier.size(buttonSize),
                shape = CircleShape,
                color = Color.Transparent
            ) {
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier.size(buttonSize)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        modifier = Modifier.size(iconSize),
                        tint = WhisperColors.Primary
                    )
                }
            }

            Spacer(modifier = Modifier.width(spacingSm))

            // Avatar
            Surface(
                modifier = Modifier.size(avatarSize),
                shape = CircleShape,
                color = WhisperColors.Primary
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = peerDisplayName.take(2).uppercase(),
                        fontSize = WhisperFontSize.md(),
                        fontWeight = FontWeight.SemiBold,
                        color = WhisperColors.TextPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.width(spacingSm))

            // Name and status
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = peerDisplayName,
                    fontSize = WhisperFontSize.md(),
                    fontWeight = FontWeight.SemiBold,
                    color = WhisperColors.TextPrimary
                )
                when (wsState) {
                    WsState.CONNECTED -> Text(
                        text = "Online",
                        fontSize = WhisperFontSize.xs(),
                        color = WhisperColors.Success
                    )
                    WsState.CONNECTING -> Text(
                        text = "Connecting...",
                        fontSize = WhisperFontSize.xs(),
                        color = WhisperColors.Warning
                    )
                    WsState.DISCONNECTED -> Text(
                        text = "Offline",
                        fontSize = WhisperFontSize.xs(),
                        color = WhisperColors.TextMuted
                    )
                }
            }

            // Call buttons
            IconButton(
                onClick = onVoiceCall,
                modifier = Modifier.size(buttonSize)
            ) {
                Icon(
                    Icons.Default.Call,
                    contentDescription = "Voice call",
                    modifier = Modifier.size(iconSize),
                    tint = WhisperColors.TextPrimary
                )
            }

            IconButton(
                onClick = onVideoCall,
                modifier = Modifier.size(buttonSize)
            ) {
                Icon(
                    Icons.Default.Videocam,
                    contentDescription = "Video call",
                    modifier = Modifier.size(iconSize),
                    tint = WhisperColors.TextPrimary
                )
            }
        }

        HorizontalDivider(color = WhisperColors.Border, thickness = 1.dp)
    }
}

@Composable
private fun MessageBubble(
    message: MessageUiItem,
    maxWidth: androidx.compose.ui.unit.Dp,
    attachmentDownloadState: AttachmentDownloadState?,
    onRetry: () -> Unit,
    onDownloadAttachment: () -> Unit
) {
    val spacingMd = WhisperSpacing.md()
    val spacingSm = WhisperSpacing.sm()
    val spacingXs = WhisperSpacing.xs()
    val borderRadius = WhisperBorderRadius.lg()

    val bubbleColor = if (message.isOutgoing) WhisperColors.MessageSent else WhisperColors.MessageReceived
    val textColor = if (message.isOutgoing) WhisperColors.TextPrimary else WhisperColors.TextSecondary
    val timeColor = if (message.isOutgoing) Color.White.copy(alpha = 0.7f) else WhisperColors.TextMuted

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacingXs),
        horizontalArrangement = if (message.isOutgoing) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = bubbleColor,
            shape = RoundedCornerShape(
                topStart = borderRadius,
                topEnd = borderRadius,
                bottomStart = if (message.isOutgoing) borderRadius else spacingXs,
                bottomEnd = if (message.isOutgoing) spacingXs else borderRadius
            ),
            modifier = Modifier.widthIn(max = maxWidth)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = spacingMd, vertical = spacingSm)
            ) {
                // Attachment content
                if (message.attachment != null) {
                    AttachmentContent(
                        attachment = message.attachment,
                        downloadState = attachmentDownloadState,
                        onDownload = onDownloadAttachment
                    )
                    if (message.text != null) {
                        Spacer(modifier = Modifier.height(spacingSm))
                    }
                }

                // Text content
                if (message.text != null) {
                    Text(
                        text = message.text,
                        fontSize = WhisperFontSize.md(),
                        color = textColor,
                        lineHeight = scaleFontSize(22f)
                    )
                } else if (message.attachment == null) {
                    Text(
                        text = "[Encrypted content]",
                        fontSize = WhisperFontSize.md(),
                        color = textColor.copy(alpha = 0.7f)
                    )
                }

                Spacer(modifier = Modifier.height(spacingXs))

                // Footer
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatMessageTime(message.timestamp),
                        fontSize = WhisperFontSize.xs(),
                        color = timeColor
                    )

                    if (message.isOutgoing) {
                        Spacer(modifier = Modifier.width(spacingXs))
                        MessageStatusIcon(
                            status = message.status,
                            onRetry = onRetry,
                            tintColor = timeColor
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentContent(
    attachment: AttachmentUiItem,
    downloadState: AttachmentDownloadState?,
    onDownload: () -> Unit
) {
    val spacingMd = WhisperSpacing.md()
    val spacingSm = WhisperSpacing.sm()

    val effectiveState = when {
        attachment.localPath != null && File(attachment.localPath).exists() -> AttachmentDownloadState.Ready
        downloadState != null -> downloadState
        else -> attachment.state
    }

    when (effectiveState) {
        is AttachmentDownloadState.NotDownloaded -> {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(WhisperBorderRadius.sm()))
                    .clickable(onClick = onDownload),
                color = WhisperColors.SurfaceLight
            ) {
                Row(
                    modifier = Modifier.padding(spacingMd),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = when {
                            attachment.isImage -> Icons.Default.Image
                            attachment.isVideo -> Icons.Default.VideoFile
                            attachment.isAudio || attachment.isVoice -> Icons.Default.AudioFile
                            else -> Icons.Default.AttachFile
                        },
                        contentDescription = null,
                        modifier = Modifier.size(moderateScale(32f)),
                        tint = WhisperColors.Primary
                    )
                    Spacer(modifier = Modifier.width(spacingMd))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when {
                                attachment.isImage -> "Image"
                                attachment.isVideo -> "Video"
                                attachment.isVoice -> "Voice message"
                                attachment.isAudio -> "Audio"
                                else -> "File"
                            },
                            fontSize = WhisperFontSize.md(),
                            color = WhisperColors.TextPrimary
                        )
                        Text(
                            text = attachment.displaySize,
                            fontSize = WhisperFontSize.sm(),
                            color = WhisperColors.TextSecondary
                        )
                    }
                    Icon(
                        Icons.Default.Download,
                        contentDescription = "Download",
                        tint = WhisperColors.Primary
                    )
                }
            }
        }

        is AttachmentDownloadState.Downloading -> {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = WhisperColors.SurfaceLight,
                shape = RoundedCornerShape(WhisperBorderRadius.sm())
            ) {
                Column(
                    modifier = Modifier.padding(spacingMd),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        progress = { effectiveState.progress },
                        modifier = Modifier.size(moderateScale(32f)),
                        color = WhisperColors.Primary
                    )
                    Spacer(modifier = Modifier.height(spacingSm))
                    Text(
                        text = "Downloading... ${(effectiveState.progress * 100).toInt()}%",
                        fontSize = WhisperFontSize.sm(),
                        color = WhisperColors.TextSecondary
                    )
                }
            }
        }

        is AttachmentDownloadState.Ready -> {
            if (attachment.isImage && attachment.localPath != null) {
                val bitmap = remember(attachment.localPath) {
                    try {
                        BitmapFactory.decodeFile(attachment.localPath)
                    } catch (e: Exception) {
                        null
                    }
                }

                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Image attachment",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .clip(RoundedCornerShape(WhisperBorderRadius.sm())),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    AttachmentReadyPlaceholder(attachment)
                }
            } else if (attachment.isVoice || attachment.isAudio) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = WhisperColors.SurfaceLight,
                    shape = RoundedCornerShape(WhisperBorderRadius.sm())
                ) {
                    Row(
                        modifier = Modifier.padding(spacingMd),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.PlayCircle,
                            contentDescription = "Play",
                            modifier = Modifier.size(moderateScale(32f)),
                            tint = WhisperColors.Primary
                        )
                        Spacer(modifier = Modifier.width(spacingMd))
                        Text(
                            text = if (attachment.isVoice) "Voice message" else "Audio",
                            fontSize = WhisperFontSize.md(),
                            color = WhisperColors.TextPrimary
                        )
                    }
                }
            } else {
                AttachmentReadyPlaceholder(attachment)
            }
        }

        is AttachmentDownloadState.Failed -> {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onDownload),
                color = WhisperColors.Error.copy(alpha = 0.2f),
                shape = RoundedCornerShape(WhisperBorderRadius.sm())
            ) {
                Row(
                    modifier = Modifier.padding(spacingMd),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Error,
                        contentDescription = null,
                        tint = WhisperColors.Error
                    )
                    Spacer(modifier = Modifier.width(spacingSm))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Download failed",
                            fontSize = WhisperFontSize.md(),
                            color = WhisperColors.TextPrimary
                        )
                        Text(
                            text = effectiveState.error,
                            fontSize = WhisperFontSize.sm(),
                            color = WhisperColors.TextSecondary
                        )
                    }
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Retry",
                        tint = WhisperColors.Error
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentReadyPlaceholder(attachment: AttachmentUiItem) {
    val spacingMd = WhisperSpacing.md()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = WhisperColors.SurfaceLight,
        shape = RoundedCornerShape(WhisperBorderRadius.sm())
    ) {
        Row(
            modifier = Modifier.padding(spacingMd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when {
                    attachment.isVideo -> Icons.Default.VideoFile
                    else -> Icons.Default.CheckCircle
                },
                contentDescription = null,
                modifier = Modifier.size(moderateScale(32f)),
                tint = WhisperColors.Primary
            )
            Spacer(modifier = Modifier.width(spacingMd))
            Column {
                Text(
                    text = when {
                        attachment.isVideo -> "Video ready"
                        else -> "File ready"
                    },
                    fontSize = WhisperFontSize.md(),
                    color = WhisperColors.TextPrimary
                )
                Text(
                    text = attachment.displaySize,
                    fontSize = WhisperFontSize.sm(),
                    color = WhisperColors.TextSecondary
                )
            }
        }
    }
}

@Composable
private fun MessageStatusIcon(
    status: MessageUiStatus,
    onRetry: () -> Unit,
    tintColor: Color
) {
    val iconSize = moderateScale(14f)

    when (status) {
        MessageUiStatus.PENDING -> Icon(
            Icons.Default.Schedule,
            contentDescription = "Pending",
            modifier = Modifier.size(iconSize),
            tint = tintColor
        )
        MessageUiStatus.SENDING -> CircularProgressIndicator(
            modifier = Modifier.size(iconSize),
            strokeWidth = 2.dp,
            color = tintColor
        )
        MessageUiStatus.SENT -> Icon(
            Icons.Default.Check,
            contentDescription = "Sent",
            modifier = Modifier.size(iconSize),
            tint = tintColor
        )
        MessageUiStatus.DELIVERED -> Icon(
            Icons.Default.DoneAll,
            contentDescription = "Delivered",
            modifier = Modifier.size(iconSize),
            tint = tintColor
        )
        MessageUiStatus.READ -> Icon(
            Icons.Default.DoneAll,
            contentDescription = "Read",
            modifier = Modifier.size(iconSize),
            tint = Color(0xFF60A5FA) // Blue for read
        )
        MessageUiStatus.FAILED -> IconButton(
            onClick = onRetry,
            modifier = Modifier.size(moderateScale(24f))
        ) {
            Icon(
                Icons.Default.Error,
                contentDescription = "Failed - tap to retry",
                tint = WhisperColors.Error
            )
        }
    }
}

@Composable
private fun MessageInput(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttachmentClick: () -> Unit,
    isSending: Boolean,
    isConnected: Boolean
) {
    val spacingSm = WhisperSpacing.sm()
    val spacingXs = WhisperSpacing.xs()
    val buttonSize = moderateScale(44f)
    val sendButtonSize = moderateScale(36f)
    val iconSize = moderateScale(20f)

    Column {
        HorizontalDivider(color = WhisperColors.Border, thickness = 1.dp)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(WhisperColors.Background)
                .padding(horizontal = spacingSm, vertical = spacingSm)
                // Handle navigation bar inset
                .windowInsetsPadding(WindowInsets.navigationBars),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(spacingXs)
        ) {
            // Attachment button
            Surface(
                modifier = Modifier.size(buttonSize),
                shape = CircleShape,
                color = Color.Transparent
            ) {
                IconButton(
                    onClick = onAttachmentClick,
                    modifier = Modifier.size(buttonSize)
                ) {
                    Text(
                        text = "+",
                        fontSize = WhisperFontSize.xl(),
                        color = WhisperColors.TextPrimary
                    )
                }
            }

            // Text input
            TextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(max = moderateScale(100f)),
                placeholder = {
                    Text(
                        text = if (isConnected) "Message" else "Message (will queue)",
                        fontSize = WhisperFontSize.md(),
                        color = WhisperColors.TextMuted
                    )
                },
                maxLines = 4,
                shape = RoundedCornerShape(WhisperBorderRadius.lg()),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedContainerColor = WhisperColors.Surface,
                    unfocusedContainerColor = WhisperColors.Surface,
                    focusedTextColor = WhisperColors.TextPrimary,
                    unfocusedTextColor = WhisperColors.TextPrimary
                ),
                textStyle = LocalTextStyle.current.copy(
                    fontSize = WhisperFontSize.md()
                )
            )

            // Send button
            Surface(
                modifier = Modifier.size(sendButtonSize),
                shape = CircleShape,
                color = if (text.isNotBlank() && !isSending)
                    WhisperColors.Primary
                else
                    WhisperColors.Surface
            ) {
                IconButton(
                    onClick = onSend,
                    enabled = text.isNotBlank() && !isSending,
                    modifier = Modifier.size(sendButtonSize)
                ) {
                    if (isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(iconSize),
                            strokeWidth = 2.dp,
                            color = WhisperColors.TextPrimary
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            modifier = Modifier.size(iconSize),
                            tint = if (text.isNotBlank())
                                WhisperColors.TextPrimary
                            else
                                WhisperColors.TextMuted
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyChatContent(modifier: Modifier = Modifier) {
    val spacingMd = WhisperSpacing.md()

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.ChatBubbleOutline,
                contentDescription = null,
                modifier = Modifier.size(moderateScale(48f)),
                tint = WhisperColors.TextMuted
            )
            Spacer(modifier = Modifier.height(spacingMd))
            Text(
                text = "No messages yet",
                fontSize = WhisperFontSize.lg(),
                color = WhisperColors.TextSecondary
            )
            Text(
                text = "Send a message to start the conversation",
                fontSize = WhisperFontSize.md(),
                color = WhisperColors.TextMuted
            )
        }
    }
}

private fun formatMessageTime(timestamp: Long): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
}
