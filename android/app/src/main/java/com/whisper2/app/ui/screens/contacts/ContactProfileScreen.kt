package com.whisper2.app.ui.screens.contacts

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Message
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
import androidx.hilt.navigation.compose.hiltViewModel
import com.whisper2.app.data.local.db.entities.ContactEntity
import com.whisper2.app.ui.viewmodels.ContactProfileViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactProfileScreen(
    peerId: String,
    onBack: () -> Unit,
    onNavigateToChat: () -> Unit,
    viewModel: ContactProfileViewModel = hiltViewModel()
) {
    val contact by viewModel.contact.collectAsState()
    var showEditNickname by remember { mutableStateOf(false) }
    var showBlockConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var newNickname by remember { mutableStateOf("") }

    LaunchedEffect(peerId) {
        viewModel.loadContact(peerId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profile", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
            )
        },
        containerColor = Color.Black
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(20.dp))

            // Avatar
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFF3B82F6), Color(0xFF8B5CF6))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = (contact?.displayName?.firstOrNull() ?: '?').uppercase().toString(),
                    fontSize = 50.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Name
            Text(
                text = contact?.displayName ?: peerId,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            // Online status
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color.Gray)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Offline",
                    fontSize = 14.sp,
                    color = Color.Gray
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Message button
            Button(
                onClick = onNavigateToChat,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                shape = RoundedCornerShape(25.dp),
                modifier = Modifier.padding(horizontal = 48.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Message, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Message")
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Info cards
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ProfileInfoCard(
                    icon = Icons.Default.Badge,
                    title = "Whisper ID",
                    value = peerId,
                    copyable = true
                )

                contact?.displayName?.let { name ->
                    if (name != peerId) {
                        ProfileInfoCard(
                            icon = Icons.Default.Edit,
                            title = "Nickname",
                            value = name
                        )
                    }
                }

                contact?.addedAt?.let { addedAt ->
                    ProfileInfoCard(
                        icon = Icons.Default.CalendarMonth,
                        title = "Added",
                        value = formatDate(addedAt)
                    )
                }

                ProfileInfoCard(
                    icon = if (contact?.encPublicKey != null) Icons.Default.Lock else Icons.Default.LockOpen,
                    title = "Encryption",
                    value = if (contact?.encPublicKey != null) "End-to-end encrypted" else "Key not available",
                    valueColor = if (contact?.encPublicKey != null) Color(0xFF22C55E) else Color(0xFFF59E0B)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Actions
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Edit nickname
                ActionButton(
                    icon = Icons.Default.Edit,
                    text = "Edit Nickname",
                    onClick = {
                        newNickname = contact?.displayName ?: ""
                        showEditNickname = true
                    },
                    showChevron = true
                )

                // Block/Unblock
                ActionButton(
                    icon = if (contact?.isBlocked == true) Icons.Default.PersonOff else Icons.Default.Block,
                    text = if (contact?.isBlocked == true) "Unblock Contact" else "Block Contact",
                    onClick = { showBlockConfirm = true },
                    textColor = if (contact?.isBlocked == true) Color(0xFF3B82F6) else Color(0xFFF59E0B)
                )

                // Delete
                ActionButton(
                    icon = Icons.Default.Delete,
                    text = "Delete Contact",
                    onClick = { showDeleteConfirm = true },
                    textColor = Color(0xFFEF4444)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // Edit nickname dialog
    if (showEditNickname) {
        AlertDialog(
            onDismissRequest = { showEditNickname = false },
            title = { Text("Edit Nickname") },
            text = {
                OutlinedTextField(
                    value = newNickname,
                    onValueChange = { newNickname = it },
                    label = { Text("Nickname") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateNickname(peerId, newNickname)
                    showEditNickname = false
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditNickname = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Block confirm dialog
    if (showBlockConfirm) {
        val isBlocked = contact?.isBlocked == true
        AlertDialog(
            onDismissRequest = { showBlockConfirm = false },
            title = { Text(if (isBlocked) "Unblock Contact?" else "Block Contact?") },
            text = {
                Text(
                    if (isBlocked)
                        "You will be able to receive messages from this contact again."
                    else
                        "You will no longer receive messages from this contact."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (isBlocked) {
                        viewModel.unblockContact(peerId)
                    } else {
                        viewModel.blockContact(peerId)
                    }
                    showBlockConfirm = false
                }) {
                    Text(if (isBlocked) "Unblock" else "Block")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBlockConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete confirm dialog
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Contact?") },
            text = { Text("This will remove the contact and all conversation history.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteContact(peerId)
                    showDeleteConfirm = false
                    onBack()
                }) {
                    Text("Delete", color = Color(0xFFEF4444))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ProfileInfoCard(
    icon: ImageVector,
    title: String,
    value: String,
    copyable: Boolean = false,
    valueColor: Color = Color.White
) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.Gray.copy(alpha = 0.15f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = Color(0xFF3B82F6),
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                Text(
                    text = value,
                    fontSize = 14.sp,
                    color = valueColor
                )
            }

            if (copyable) {
                IconButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("WhisperID", value))
                    copied = true
                }) {
                    Icon(
                        if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        tint = if (copied) Color(0xFF22C55E) else Color.Gray
                    )
                }

                LaunchedEffect(copied) {
                    if (copied) {
                        kotlinx.coroutines.delay(2000)
                        copied = false
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
    textColor: Color = Color.White,
    showChevron: Boolean = false
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.Gray.copy(alpha = 0.15f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = textColor
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = text,
                color = textColor,
                modifier = Modifier.weight(1f)
            )
            if (showChevron) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = Color.Gray
                )
            }
        }
    }
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
