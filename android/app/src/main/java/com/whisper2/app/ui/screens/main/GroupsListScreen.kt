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
import com.whisper2.app.ui.viewmodels.ContactsViewModel
import com.whisper2.app.ui.viewmodels.GroupsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupsListScreen(
    onGroupClick: (String) -> Unit,
    viewModel: GroupsViewModel = hiltViewModel(),
    contactsViewModel: ContactsViewModel = hiltViewModel()
) {
    val groups by viewModel.groups.collectAsState()
    var showCreateGroup by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Groups", color = Color.White, fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { showCreateGroup = true }) {
                        Icon(Icons.Default.GroupAdd, "Create Group", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateGroup = true },
                containerColor = Color(0xFF3B82F6)
            ) {
                Icon(Icons.Default.Add, "Create Group", tint = Color.White)
            }
        },
        containerColor = Color.Black
    ) { padding ->
        if (groups.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Groups,
                        contentDescription = null,
                        modifier = Modifier.size(60.dp),
                        tint = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("No Groups", fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 18.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Create a group to chat with multiple people at once",
                        color = Color.Gray,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = { showCreateGroup = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                        shape = RoundedCornerShape(25.dp)
                    ) {
                        Text("Create Group", modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        } else {
            LazyColumn(modifier = Modifier.padding(padding)) {
                items(groups, key = { it.groupId }) { group ->
                    GroupRow(
                        name = group.name,
                        memberCount = group.memberCount,
                        lastMessage = group.lastMessagePreview,
                        timestamp = group.formattedTime,
                        unreadCount = group.unreadCount,
                        onClick = { onGroupClick(group.groupId) }
                    )
                    HorizontalDivider(color = Color.Gray.copy(alpha = 0.2f), modifier = Modifier.padding(start = 76.dp))
                }
            }
        }
    }

    // Create Group Dialog
    if (showCreateGroup) {
        CreateGroupDialog(
            contactsViewModel = contactsViewModel,
            onDismiss = { showCreateGroup = false },
            onCreate = { name, memberIds ->
                viewModel.createGroup(name, memberIds)
                showCreateGroup = false
            }
        )
    }
}

@Composable
fun GroupRow(
    name: String,
    memberCount: Int,
    lastMessage: String?,
    timestamp: String,
    unreadCount: Int = 0,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Group avatar with purple tint like iOS
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(Color(0xFF8B5CF6).copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Groups, null, tint = Color(0xFF8B5CF6), modifier = Modifier.size(28.dp))
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
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
                    lastMessage ?: "$memberCount members",
                    color = Color.Gray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                if (unreadCount > 0) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF3B82F6)
                    ) {
                        Text(
                            "$unreadCount",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupDialog(
    contactsViewModel: ContactsViewModel,
    onDismiss: () -> Unit,
    onCreate: (String, List<String>) -> Unit
) {
    val contacts by contactsViewModel.contacts.collectAsState()
    var groupName by remember { mutableStateOf("") }
    var selectedMembers by remember { mutableStateOf(setOf<String>()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1A1A)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = Color.Gray)
                }
                Text("New Group", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 18.sp)
                TextButton(
                    onClick = { onCreate(groupName, selectedMembers.toList()) },
                    enabled = groupName.isNotBlank() && selectedMembers.isNotEmpty()
                ) {
                    Text(
                        "Create",
                        color = if (groupName.isNotBlank() && selectedMembers.isNotEmpty())
                            Color(0xFF3B82F6) else Color.Gray
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Group name input
            Text("Group Name", color = Color.White, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = groupName,
                onValueChange = { groupName = it },
                placeholder = { Text("Enter group name", color = Color.Gray) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF3B82F6),
                    unfocusedBorderColor = Color.Gray.copy(alpha = 0.3f),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                shape = RoundedCornerShape(10.dp),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Member selection
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Select Members", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("${selectedMembers.size} selected", color = Color.Gray, fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (contacts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.PersonOff, null, tint = Color.Gray, modifier = Modifier.size(40.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("No contacts available", color = Color.Gray, fontSize = 14.sp)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.height(300.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(contacts.filter { it.encPublicKey != null }) { contact ->
                        val isSelected = selectedMembers.contains(contact.whisperId)
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) Color(0xFF3B82F6).copy(alpha = 0.1f) else Color.Transparent,
                            modifier = Modifier.clickable {
                                selectedMembers = if (isSelected) {
                                    selectedMembers - contact.whisperId
                                } else {
                                    selectedMembers + contact.whisperId
                                }
                            }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(Color.Gray.copy(alpha = 0.3f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        contact.displayName.take(1).uppercase(),
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    contact.displayName,
                                    color = Color.White,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                    null,
                                    tint = if (isSelected) Color(0xFF3B82F6) else Color.Gray
                                )
                            }
                        }
                    }
                    // Show contacts without encryption key
                    val contactsWithoutKey = contacts.filter { it.encPublicKey == null }
                    if (contactsWithoutKey.isNotEmpty()) {
                        item {
                            Text(
                                "Contacts without encryption key cannot be added",
                                color = Color.Gray,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                        items(contactsWithoutKey) { contact ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(Color.Gray.copy(alpha = 0.2f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        contact.displayName.take(1).uppercase(),
                                        color = Color.Gray,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(contact.displayName, color = Color.Gray)
                                    Text("No encryption key", color = Color(0xFFF59E0B), fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
