package com.whisper2.app.ui.screens.main

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.whisper2.app.ui.components.ContactAvatar
import com.whisper2.app.ui.viewmodels.ContactsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsListScreen(
    onContactClick: (String) -> Unit,
    onScanQrClick: () -> Unit,
    onContactProfileClick: (String) -> Unit,
    viewModel: ContactsViewModel = hiltViewModel()
) {
    val contacts by viewModel.contacts.collectAsState()
    var showAddContact by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val filteredContacts = if (searchQuery.isEmpty()) contacts else {
        contacts.filter {
            it.displayName.contains(searchQuery, ignoreCase = true) ||
            it.whisperId.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Contacts", color = Color.White, fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onScanQrClick) {
                        Icon(Icons.Default.QrCodeScanner, "Scan QR", tint = Color.White)
                    }
                    IconButton(onClick = { showAddContact = true }) {
                        Icon(Icons.Default.PersonAdd, "Add Contact", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onScanQrClick,
                containerColor = Color(0xFF3B82F6)
            ) {
                Icon(Icons.Default.QrCodeScanner, "Scan QR Code", tint = Color.White)
            }
        },
        containerColor = Color.Black
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search contacts", color = Color.Gray) },
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

            if (contacts.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Contacts,
                            contentDescription = null,
                            modifier = Modifier.size(60.dp),
                            tint = Color.Gray
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("No Contacts", fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 18.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Scan a QR code to add contacts", color = Color.Gray, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = onScanQrClick,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                            shape = RoundedCornerShape(25.dp)
                        ) {
                            Icon(Icons.Default.QrCodeScanner, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Scan QR Code", modifier = Modifier.padding(horizontal = 8.dp))
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { showAddContact = true },
                            shape = RoundedCornerShape(25.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) {
                            Text("Enter ID Manually", modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            } else {
                LazyColumn {
                    items(filteredContacts, key = { it.whisperId }) { contact ->
                        ContactRow(
                            displayName = contact.displayName,
                            avatarPath = contact.avatarPath,
                            whisperId = contact.whisperId,
                            hasPublicKey = contact.encPublicKey != null,
                            onClick = { onContactClick(contact.whisperId) },
                            onInfoClick = { onContactProfileClick(contact.whisperId) }
                        )
                        HorizontalDivider(color = Color.Gray.copy(alpha = 0.2f), modifier = Modifier.padding(start = 76.dp))
                    }
                }
            }
        }
    }

    if (showAddContact) {
        AddContactDialog(
            onDismiss = { showAddContact = false },
            onAdd = { whisperId, nickname ->
                viewModel.addContact(whisperId, nickname)
                showAddContact = false
            }
        )
    }
}

@Composable
fun ContactRow(
    displayName: String,
    avatarPath: String? = null,
    whisperId: String,
    hasPublicKey: Boolean,
    onClick: () -> Unit,
    onInfoClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar - uses custom avatar if available
        ContactAvatar(
            displayName = displayName,
            avatarPath = avatarPath,
            size = 52.dp,
            fontSize = 20.sp
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                displayName,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(whisperId, color = Color.Gray, fontSize = 12.sp)
            if (!hasPublicKey) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, null, tint = Color(0xFFF59E0B), modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Scan QR to enable messaging", color = Color(0xFFF59E0B), fontSize = 11.sp)
                }
            }
        }

        IconButton(onClick = onInfoClick) {
            Icon(Icons.Default.Info, "View profile", tint = Color.Gray)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String) -> Unit
) {
    var whisperId by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Contact") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = whisperId,
                    onValueChange = { whisperId = it },
                    label = { Text("Whisper ID") },
                    placeholder = { Text("WSP-XXXX-XXXX-XXXX") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    label = { Text("Nickname (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(whisperId, nickname.ifEmpty { whisperId }) },
                enabled = whisperId.isNotBlank()
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
