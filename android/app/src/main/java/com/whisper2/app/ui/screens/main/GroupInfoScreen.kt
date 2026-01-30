package com.whisper2.app.ui.screens.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.whisper2.app.ui.viewmodels.GroupInfoViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoScreen(
    groupId: String,
    onBack: () -> Unit,
    onAddMembers: () -> Unit,
    onGroupLeft: () -> Unit = onBack,  // Navigate back to groups list after leaving
    viewModel: GroupInfoViewModel = hiltViewModel()
) {
    val group by viewModel.group.collectAsState()
    val members by viewModel.members.collectAsState()
    val isOwner by viewModel.isOwner.collectAsState()
    var showLeaveDialog by remember { mutableStateOf(false) }
    var memberToRemove by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(groupId) {
        viewModel.loadGroup(groupId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Group Info", color = Color.White) },
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Group header
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Group avatar
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .background(Color(0xFF8B5CF6).copy(alpha = 0.3f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Groups,
                            contentDescription = null,
                            tint = Color(0xFF8B5CF6),
                            modifier = Modifier.size(50.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        group?.name ?: "Loading...",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        "${members.size} members",
                        fontSize = 14.sp,
                        color = Color.Gray
                    )

                    if (isOwner) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            color = Color(0xFF3B82F6).copy(alpha = 0.2f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                "You are the admin",
                                fontSize = 12.sp,
                                color = Color(0xFF3B82F6),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }

            // Add members button (admin only)
            if (isOwner) {
                item {
                    Surface(
                        color = Color.Gray.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clickable(onClick = onAddMembers)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(Color(0xFF22C55E).copy(alpha = 0.2f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.PersonAdd,
                                    contentDescription = null,
                                    tint = Color(0xFF22C55E)
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                "Add Members",
                                color = Color.White,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // Members section header
            item {
                Text(
                    "Members",
                    color = Color.Gray,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // Members list
            items(members) { member ->
                MemberRow(
                    memberId = member.memberId,
                    displayName = member.displayName,
                    role = member.role,
                    isCurrentUser = member.isCurrentUser,
                    canRemove = isOwner && !member.isCurrentUser && member.role != "owner",
                    onRemove = { memberToRemove = member.memberId }
                )
                HorizontalDivider(
                    color = Color.Gray.copy(alpha = 0.2f),
                    modifier = Modifier.padding(start = 72.dp)
                )
            }

            // Leave group button
            item {
                Spacer(modifier = Modifier.height(24.dp))
                Surface(
                    color = Color(0xFFEF4444).copy(alpha = 0.1f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clickable { showLeaveDialog = true }
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.ExitToApp,
                            contentDescription = null,
                            tint = Color(0xFFEF4444)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Leave Group",
                            color = Color(0xFFEF4444),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // Leave group dialog
    if (showLeaveDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveDialog = false },
            title = { Text("Leave Group?", color = Color.White) },
            text = {
                Text(
                    "Are you sure you want to leave this group? You won't receive any new messages.",
                    color = Color.Gray
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.leaveGroup()
                        showLeaveDialog = false
                        onGroupLeft()
                    }
                ) {
                    Text("Leave", color = Color(0xFFEF4444))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF1A1A1A),
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Remove member dialog
    memberToRemove?.let { memberId ->
        val member = members.find { it.memberId == memberId }
        AlertDialog(
            onDismissRequest = { memberToRemove = null },
            title = { Text("Remove Member?", color = Color.White) },
            text = {
                Text(
                    "Are you sure you want to remove ${member?.displayName ?: memberId} from this group?",
                    color = Color.Gray
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeMember(memberId)
                        memberToRemove = null
                    }
                ) {
                    Text("Remove", color = Color(0xFFEF4444))
                }
            },
            dismissButton = {
                TextButton(onClick = { memberToRemove = null }) {
                    Text("Cancel", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF1A1A1A),
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
private fun MemberRow(
    memberId: String,
    displayName: String,
    role: String,
    isCurrentUser: Boolean,
    canRemove: Boolean,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(Color.Gray.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                displayName.take(1).uppercase(),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Name and role
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    displayName,
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )
                if (isCurrentUser) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = Color(0xFF3B82F6).copy(alpha = 0.2f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            "You",
                            fontSize = 10.sp,
                            color = Color(0xFF3B82F6),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            if (role == "owner") {
                Text(
                    "Admin",
                    fontSize = 12.sp,
                    color = Color(0xFF8B5CF6)
                )
            }
        }

        // Remove button
        if (canRemove) {
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove",
                    tint = Color(0xFFEF4444)
                )
            }
        }
    }
}
