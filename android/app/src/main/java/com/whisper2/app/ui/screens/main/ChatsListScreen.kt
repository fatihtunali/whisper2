package com.whisper2.app.ui.screens.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.whisper2.app.data.network.ws.WsConnectionState
import com.whisper2.app.ui.components.ConnectionStatusBar
import com.whisper2.app.ui.components.ContactAvatar
import com.whisper2.app.ui.theme.*
import com.whisper2.app.ui.viewmodels.ChatsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsListScreen(
    onChatClick: (String) -> Unit,
    onMessageRequestsClick: () -> Unit = {},
    connectionState: WsConnectionState = WsConnectionState.CONNECTED,
    viewModel: ChatsViewModel = hiltViewModel()
) {
    val conversations by viewModel.conversations.collectAsState()
    val pendingRequests by viewModel.pendingRequestCount.collectAsState()
    var showNewChat by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chats", color = TextPrimary, fontWeight = FontWeight.Bold) },
                actions = {
                    if (pendingRequests > 0) {
                        IconButton(onClick = onMessageRequestsClick) {
                            BadgedBox(badge = { Badge(containerColor = PrimaryBlue) { Text("$pendingRequests") } }) {
                                Icon(Icons.Default.Inbox, "Requests", tint = TextPrimary)
                            }
                        }
                    }
                    IconButton(onClick = { showNewChat = true }) {
                        Icon(Icons.Default.Edit, "New Chat", tint = PrimaryBlue)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MetalDark)
            )
        },
        containerColor = MetalDark
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            // Connection status bar (only shows when not connected)
            ConnectionStatusBar(connectionState = connectionState)

            // Message requests banner
            if (pendingRequests > 0) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onMessageRequestsClick),
                    color = Color(0xFFF59E0B).copy(alpha = 0.1f)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(Color(0xFFF59E0B).copy(alpha = 0.2f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Email, null, tint = Color(0xFFF59E0B))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Message Requests", fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 14.sp)
                            Text("$pendingRequests pending request${if (pendingRequests == 1) "" else "s"}", color = Color.Gray, fontSize = 12.sp)
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = Color.Gray)
                    }
                }
            }

            // Search bar
            if (conversations.isNotEmpty()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search chats", color = Color.Gray) },
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = Color.Gray) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF3B82F6),
                        unfocusedBorderColor = Color.Gray.copy(alpha = 0.3f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
            }

            val filteredConversations = if (searchQuery.isEmpty()) conversations else {
                conversations.filter {
                    (it.peerNickname ?: it.peerId).contains(searchQuery, ignoreCase = true)
                }
            }

            if (conversations.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.AutoMirrored.Filled.Chat,
                            contentDescription = null,
                            modifier = Modifier.size(60.dp),
                            tint = Color.Gray
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("No Conversations", fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 18.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Start a new conversation with your contacts", color = Color.Gray, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = { showNewChat = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                            shape = RoundedCornerShape(25.dp)
                        ) {
                            Text("New Chat", modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            } else {
                LazyColumn {
                    items(filteredConversations, key = { it.peerId }) { conversation ->
                        ChatRow(
                            displayName = conversation.peerNickname ?: conversation.peerId,
                            avatarPath = conversation.peerAvatarPath,
                            lastMessage = conversation.lastMessagePreview ?: "",
                            timestamp = conversation.formattedTime,
                            unreadCount = conversation.unreadCount,
                            isTyping = conversation.isTyping,
                            onClick = { onChatClick(conversation.peerId) }
                        )
                        HorizontalDivider(color = Color.Gray.copy(alpha = 0.2f), modifier = Modifier.padding(start = 76.dp))
                    }
                }
            }
        }
    }

    if (showNewChat) {
        NewChatSheet(
            onDismiss = { showNewChat = false },
            onContactSelected = { peerId ->
                showNewChat = false
                onChatClick(peerId)
            }
        )
    }
}

@Composable
fun ChatRow(
    displayName: String,
    avatarPath: String? = null,
    lastMessage: String,
    timestamp: String,
    unreadCount: Int,
    isTyping: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar - uses custom avatar if available, otherwise initials
        ContactAvatar(
            displayName = displayName,
            avatarPath = avatarPath,
            size = 52.dp,
            fontSize = 20.sp
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    displayName,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(timestamp, color = Color.Gray, fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (isTyping) "typing..." else lastMessage,
                    color = if (isTyping) Color(0xFF3B82F6) else Color.Gray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 14.sp,
                    fontWeight = if (isTyping) FontWeight.Medium else FontWeight.Normal,
                    modifier = Modifier.weight(1f)
                )
                if (unreadCount > 0) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .background(Color(0xFF3B82F6), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("$unreadCount", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatSheet(
    onDismiss: () -> Unit,
    onContactSelected: (String) -> Unit,
    viewModel: ChatsViewModel = hiltViewModel()
) {
    val contacts by viewModel.contacts.collectAsState()
    var searchQuery by remember { mutableStateOf("") }

    val filteredContacts = if (searchQuery.isEmpty()) contacts else {
        contacts.filter {
            it.displayName.contains(searchQuery, ignoreCase = true) ||
            it.whisperId.contains(searchQuery, ignoreCase = true)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1A1A)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text("New Chat", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 20.sp)
            Spacer(modifier = Modifier.height(16.dp))

            // Search field
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search contacts", color = Color.Gray) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = Color.Gray) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF3B82F6),
                    unfocusedBorderColor = Color.Gray.copy(alpha = 0.3f),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (filteredContacts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.PersonSearch, null, tint = Color.Gray, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            if (contacts.isEmpty()) "No contacts yet" else "No contacts found",
                            color = Color.Gray
                        )
                        if (contacts.isEmpty()) {
                            Text("Add contacts from the Contacts tab", color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                ) {
                    items(filteredContacts, key = { it.whisperId }) { contact ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onContactSelected(contact.whisperId) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ContactAvatar(
                                displayName = contact.displayName,
                                avatarPath = contact.avatarPath,
                                size = 44.dp,
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    contact.displayName,
                                    fontWeight = FontWeight.Medium,
                                    color = Color.White
                                )
                                Text(
                                    contact.whisperId,
                                    color = Color.Gray,
                                    fontSize = 12.sp
                                )
                            }
                            Icon(Icons.Default.ChevronRight, null, tint = Color.Gray)
                        }
                        HorizontalDivider(color = Color.Gray.copy(alpha = 0.2f))
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
