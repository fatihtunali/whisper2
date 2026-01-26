package com.whisper2.app.ui.screens.conversations

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.whisper2.app.network.ws.WsState
import com.whisper2.app.ui.components.NewChatDialog
import com.whisper2.app.ui.state.ConversationUiItem
import com.whisper2.app.ui.theme.*
import com.whisper2.app.ui.viewmodels.ConversationsViewModel
import java.text.SimpleDateFormat
import java.util.*

/**
 * Conversations List Screen
 * Edge-to-edge, responsive design matching original React Native app
 */
@Composable
fun ConversationsScreen(
    onConversationClick: (String) -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: ConversationsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val outboxState by viewModel.outboxState.collectAsState()

    var showNewChatDialog by remember { mutableStateOf(false) }

    // Responsive values
    val spacingMd = WhisperSpacing.md()
    val spacingLg = WhisperSpacing.lg()
    val spacingSm = WhisperSpacing.sm()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WhisperColors.Background)
            // Handle status bar inset for edge-to-edge
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        // Header matching original design
        ChatsHeader(
            onSettingsClick = onSettingsClick,
            onNewChatClick = { showNewChatDialog = true }
        )

        // Connection status banner
        ConnectionStatusBanner(wsState = connectionState.wsState)

        // Pending messages indicator
        if (outboxState.hasPending) {
            PendingMessagesBanner(count = outboxState.queuedCount + outboxState.sendingCount)
        }

        if (uiState.isLoading && uiState.conversations.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = WhisperColors.Primary)
            }
        } else if (uiState.conversations.isEmpty()) {
            EmptyConversationsContent(onNewChatClick = { showNewChatDialog = true })
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                // Handle navigation bar inset for edge-to-edge
                contentPadding = WindowInsets.navigationBars.asPaddingValues()
            ) {
                items(
                    items = uiState.conversations,
                    key = { it.id }
                ) { conversation ->
                    ConversationItem(
                        conversation = conversation,
                        onClick = {
                            viewModel.markConversationAsRead(conversation.id)
                            onConversationClick(conversation.id)
                        }
                    )
                }
            }
        }
    }

    if (showNewChatDialog) {
        NewChatDialog(
            onDismiss = { showNewChatDialog = false },
            onStartChat = { whisperId ->
                showNewChatDialog = false
                val conversationId = viewModel.startConversation(whisperId)
                onConversationClick(conversationId)
            }
        )
    }
}

@Composable
private fun ChatsHeader(
    onSettingsClick: () -> Unit,
    onNewChatClick: () -> Unit
) {
    val spacingLg = WhisperSpacing.lg()
    val spacingMd = WhisperSpacing.md()
    val spacingSm = WhisperSpacing.sm()
    val buttonSize = moderateScale(36f)
    val iconSize = moderateScale(20f)

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacingLg, vertical = spacingMd),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Chats",
                fontSize = WhisperFontSize.xxl(),
                fontWeight = FontWeight.Bold,
                color = WhisperColors.TextPrimary
            )

            Row(horizontalArrangement = Arrangement.spacedBy(spacingSm)) {
                // Settings button - surface color
                Surface(
                    modifier = Modifier.size(buttonSize),
                    shape = CircleShape,
                    color = WhisperColors.Surface
                ) {
                    IconButton(
                        onClick = onSettingsClick,
                        modifier = Modifier.size(buttonSize)
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            modifier = Modifier.size(iconSize),
                            tint = WhisperColors.TextPrimary
                        )
                    }
                }

                // New chat button - primary color
                Surface(
                    modifier = Modifier.size(buttonSize),
                    shape = CircleShape,
                    color = WhisperColors.Primary
                ) {
                    IconButton(
                        onClick = onNewChatClick,
                        modifier = Modifier.size(buttonSize)
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "New Chat",
                            modifier = Modifier.size(iconSize),
                            tint = WhisperColors.TextPrimary
                        )
                    }
                }
            }
        }

        // Border line
        HorizontalDivider(color = WhisperColors.Border, thickness = 1.dp)
    }
}

@Composable
private fun ConnectionStatusBanner(wsState: WsState) {
    if (wsState == WsState.CONNECTED) return

    val spacingLg = WhisperSpacing.lg()
    val spacingSm = WhisperSpacing.sm()

    val (backgroundColor, text) = when (wsState) {
        WsState.CONNECTING -> Pair(
            WhisperColors.Warning.copy(alpha = 0.2f),
            "Connecting..."
        )
        WsState.DISCONNECTED -> Pair(
            WhisperColors.SurfaceLight,
            "No connection - messages will queue"
        )
        else -> return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(horizontal = spacingLg, vertical = spacingSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacingSm)
    ) {
        if (wsState == WsState.CONNECTING) {
            CircularProgressIndicator(
                modifier = Modifier.size(moderateScale(16f)),
                strokeWidth = 2.dp,
                color = WhisperColors.Warning
            )
        } else {
            Icon(
                Icons.Default.WifiOff,
                contentDescription = null,
                modifier = Modifier.size(moderateScale(16f)),
                tint = WhisperColors.TextSecondary
            )
        }
        Text(
            text = text,
            fontSize = WhisperFontSize.sm(),
            color = WhisperColors.TextSecondary
        )
    }
}

@Composable
private fun PendingMessagesBanner(count: Int) {
    val spacingLg = WhisperSpacing.lg()
    val spacingSm = WhisperSpacing.sm()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(WhisperColors.Primary.copy(alpha = 0.1f))
            .padding(horizontal = spacingLg, vertical = spacingSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacingSm)
    ) {
        Icon(
            Icons.Default.Schedule,
            contentDescription = null,
            modifier = Modifier.size(moderateScale(16f)),
            tint = WhisperColors.Primary
        )
        Text(
            text = "$count message${if (count > 1) "s" else ""} pending",
            fontSize = WhisperFontSize.sm(),
            color = WhisperColors.Primary
        )
    }
}

@Composable
private fun ConversationItem(
    conversation: ConversationUiItem,
    onClick: () -> Unit
) {
    val spacingMd = WhisperSpacing.md()
    val spacingSm = WhisperSpacing.sm()
    val spacingXs = WhisperSpacing.xs()
    val avatarSize = moderateScale(50f)

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = spacingMd, vertical = spacingMd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar - circular with initials
            Surface(
                modifier = Modifier.size(avatarSize),
                shape = CircleShape,
                color = WhisperColors.Primary
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = conversation.displayName.take(2).uppercase(),
                        fontSize = WhisperFontSize.lg(),
                        fontWeight = FontWeight.SemiBold,
                        color = WhisperColors.TextPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.width(spacingMd))

            // Content
            Column(modifier = Modifier.weight(1f)) {
                // Top row: Name + Time
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = conversation.displayName,
                        fontSize = WhisperFontSize.md(),
                        fontWeight = if (conversation.unreadCount > 0) FontWeight.SemiBold else FontWeight.Normal,
                        color = WhisperColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (conversation.lastMessageTime > 0) {
                        Text(
                            text = formatTime(conversation.lastMessageTime),
                            fontSize = WhisperFontSize.xs(),
                            color = WhisperColors.TextMuted
                        )
                    }
                }

                Spacer(modifier = Modifier.height(spacingXs))

                // Bottom row: Message preview + Unread badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = conversation.lastMessage ?: "No messages yet",
                        fontSize = WhisperFontSize.sm(),
                        color = WhisperColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    if (conversation.unreadCount > 0) {
                        Spacer(modifier = Modifier.width(spacingSm))
                        // Unread badge
                        Surface(
                            shape = CircleShape,
                            color = WhisperColors.Primary
                        ) {
                            Text(
                                text = conversation.unreadCount.toString(),
                                fontSize = WhisperFontSize.xs(),
                                fontWeight = FontWeight.SemiBold,
                                color = WhisperColors.TextPrimary,
                                modifier = Modifier.padding(
                                    horizontal = spacingSm,
                                    vertical = spacingXs
                                )
                            )
                        }
                    }
                }
            }
        }

        // Border line - indented to start after avatar
        HorizontalDivider(
            color = WhisperColors.Border,
            thickness = 1.dp,
            modifier = Modifier.padding(start = avatarSize + spacingMd + spacingMd)
        )
    }
}

@Composable
private fun EmptyConversationsContent(onNewChatClick: () -> Unit) {
    val spacingXl = WhisperSpacing.xl()
    val spacingLg = WhisperSpacing.lg()
    val spacingMd = WhisperSpacing.md()
    val spacingSm = WhisperSpacing.sm()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(spacingXl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "--",
            fontSize = scaleFontSize(64f),
            color = WhisperColors.TextMuted
        )
        Spacer(modifier = Modifier.height(spacingLg))
        Text(
            text = "No conversations yet",
            fontSize = WhisperFontSize.xl(),
            fontWeight = FontWeight.SemiBold,
            color = WhisperColors.TextPrimary
        )
        Spacer(modifier = Modifier.height(spacingSm))
        Text(
            text = "Add a contact to start messaging",
            fontSize = WhisperFontSize.md(),
            color = WhisperColors.TextSecondary
        )
        Spacer(modifier = Modifier.height(spacingLg))
        Button(
            onClick = onNewChatClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = WhisperColors.Primary
            ),
            shape = RoundedCornerShape(WhisperBorderRadius.md()),
            contentPadding = PaddingValues(
                horizontal = spacingLg,
                vertical = spacingSm
            )
        ) {
            Text(
                text = "Add Contact",
                fontSize = WhisperFontSize.md(),
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

private fun formatTime(timestamp: Long): String {
    if (timestamp == 0L) return ""

    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> "Now"
        diff < 3600_000 -> "${diff / 60_000}m"
        diff < 86400_000 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        diff < 604800_000 -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(timestamp))
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
    }
}
