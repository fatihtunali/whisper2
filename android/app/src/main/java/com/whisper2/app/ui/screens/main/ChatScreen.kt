package com.whisper2.app.ui.screens.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.whisper2.app.ui.components.MessageBubble
import com.whisper2.app.ui.components.MessageInputBar
import com.whisper2.app.ui.theme.*
import com.whisper2.app.ui.viewmodels.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    peerId: String,
    onBack: () -> Unit,
    onVoiceCall: () -> Unit,
    onVideoCall: () -> Unit,
    onNavigateToProfile: () -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsState()
    val contactName by viewModel.contactName.collectAsState()
    val canSendMessages by viewModel.canSendMessages.collectAsState()
    val isTyping by viewModel.peerIsTyping.collectAsState()
    val error by viewModel.error.collectAsState()
    var messageText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(peerId) {
        viewModel.loadConversation(peerId)
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(contactName, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        if (isTyping) {
                            Text("typing...", color = PrimaryBlue, fontSize = 12.sp)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = PrimaryBlue)
                    }
                },
                actions = {
                    IconButton(onClick = onVoiceCall, enabled = canSendMessages) {
                        Icon(Icons.Default.Phone, "Voice Call", tint = if (canSendMessages) CallAccept else TextDisabled)
                    }
                    IconButton(onClick = onVideoCall, enabled = canSendMessages) {
                        Icon(Icons.Default.Videocam, "Video Call", tint = if (canSendMessages) PrimaryBlue else TextDisabled)
                    }
                    // More options menu like iOS
                    var showMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, "More", tint = TextSecondary)
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            containerColor = MetalSlate
                        ) {
                            DropdownMenuItem(
                                text = { Text("View Profile", color = TextPrimary) },
                                onClick = {
                                    showMenu = false
                                    onNavigateToProfile()
                                },
                                leadingIcon = { Icon(Icons.Default.Person, null, tint = TextSecondary) }
                            )
                            DropdownMenuItem(
                                text = { Text("Search Messages", color = TextPrimary) },
                                onClick = { showMenu = false },
                                leadingIcon = { Icon(Icons.Default.Search, null, tint = TextSecondary) }
                            )
                            DropdownMenuItem(
                                text = { Text("Mute Notifications", color = TextPrimary) },
                                onClick = { showMenu = false },
                                leadingIcon = { Icon(Icons.Default.NotificationsOff, null, tint = TextSecondary) }
                            )
                            HorizontalDivider(color = BorderDefault)
                            DropdownMenuItem(
                                text = { Text("Clear Chat", color = StatusError) },
                                onClick = { showMenu = false },
                                leadingIcon = { Icon(Icons.Default.Delete, null, tint = StatusError) }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MetalNavy)
            )
        },
        containerColor = MetalDark
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()  // This makes the content adjust when keyboard appears
        ) {
            // Key missing warning
            if (!canSendMessages) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = StatusWarningMuted.copy(alpha = 0.3f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, null, tint = StatusWarning, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Scan contact's QR code to enable messaging",
                            color = StatusWarning,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { /* Scan QR */ }) {
                            Text("Scan", color = PrimaryBlue)
                        }
                    }
                }
            }

            // Error display
            error?.let { err ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = StatusErrorMuted.copy(alpha = 0.3f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Error, null, tint = StatusError, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(err, color = StatusError, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { viewModel.clearError() }) {
                            Text("Dismiss", color = TextTertiary)
                        }
                    }
                }
            }

            // Messages
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        onDelete = { forEveryone -> viewModel.deleteMessage(message.id, forEveryone) }
                    )
                }
            }

            // Typing indicator
            if (isTyping) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TypingIndicator()
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("$contactName is typing...", color = PrimaryBlue, fontSize = 12.sp)
                }
            }

            // Input bar
            MessageInputBar(
                text = messageText,
                onTextChange = {
                    messageText = it
                    viewModel.onTyping()
                },
                onSend = {
                    viewModel.sendMessage(messageText)
                    messageText = ""
                },
                onAttachment = { /* Pick attachment */ },
                onVoiceMessage = { /* Record voice */ },
                onLocation = { /* Send location */ },
                isEnabled = canSendMessages
            )
        }
    }
}

@Composable
fun TypingIndicator() {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { index ->
            val alpha by remember { mutableFloatStateOf(0.4f + (index * 0.2f)) }
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(PrimaryBlue.copy(alpha = alpha), CircleShape)
            )
        }
    }
}
