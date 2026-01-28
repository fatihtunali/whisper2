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
                        Text(contactName, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        if (isTyping) {
                            Text("typing...", color = Color(0xFF3B82F6), fontSize = 12.sp)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = onVoiceCall, enabled = canSendMessages) {
                        Icon(Icons.Default.Phone, "Voice Call", tint = if (canSendMessages) Color.White else Color.Gray)
                    }
                    IconButton(onClick = onVideoCall, enabled = canSendMessages) {
                        Icon(Icons.Default.Videocam, "Video Call", tint = if (canSendMessages) Color.White else Color.Gray)
                    }
                    // More options menu like iOS
                    var showMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, "More", tint = Color.White)
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("View Profile") },
                                onClick = {
                                    showMenu = false
                                    onNavigateToProfile()
                                },
                                leadingIcon = { Icon(Icons.Default.Person, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Search Messages") },
                                onClick = { showMenu = false },
                                leadingIcon = { Icon(Icons.Default.Search, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Mute Notifications") },
                                onClick = { showMenu = false },
                                leadingIcon = { Icon(Icons.Default.NotificationsOff, null) }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Clear Chat", color = Color(0xFFEF4444)) },
                                onClick = { showMenu = false },
                                leadingIcon = { Icon(Icons.Default.Delete, null, tint = Color(0xFFEF4444)) }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A1A1A))
            )
        },
        containerColor = Color.Black
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
                    color = Color(0xFFF59E0B).copy(alpha = 0.1f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, null, tint = Color(0xFFF59E0B), modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Scan contact's QR code to enable messaging",
                            color = Color(0xFFF59E0B),
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { /* Scan QR */ }) {
                            Text("Scan", color = Color(0xFF3B82F6))
                        }
                    }
                }
            }

            // Error display
            error?.let { err ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFFEF4444).copy(alpha = 0.1f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Error, null, tint = Color(0xFFEF4444), modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(err, color = Color(0xFFEF4444), fontSize = 12.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { viewModel.clearError() }) {
                            Text("Dismiss", color = Color.Gray)
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
                    Text("$contactName is typing...", color = Color.Gray, fontSize = 12.sp)
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
            val alpha by remember { mutableFloatStateOf(0.3f + (index * 0.2f)) }
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(Color.Gray.copy(alpha = alpha), CircleShape)
            )
        }
    }
}
