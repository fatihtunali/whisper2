package com.whisper2.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whisper2.app.data.local.db.entities.DisappearingMessageTimer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisappearingMessageSettingsScreen(
    peerId: String,
    currentTimer: DisappearingMessageTimer,
    onTimerSelected: (DisappearingMessageTimer) -> Unit,
    onBack: () -> Unit
) {
    var selectedTimer by remember { mutableStateOf(currentTimer) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Disappearing Messages", color = Color.White) },
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
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            // Header icon with gradient
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(Color(0xFF3B82F6), Color(0xFF8B5CF6))
                        ),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Timer,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "Disappearing Messages",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                "Messages will be automatically deleted after the selected time. This applies to new messages only.",
                fontSize = 14.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 20.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Timer options
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DisappearingMessageTimer.entries.forEach { timer ->
                    TimerOptionRow(
                        timer = timer,
                        isSelected = selectedTimer == timer,
                        onClick = {
                            selectedTimer = timer
                            onTimerSelected(timer)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Status info
            if (selectedTimer != DisappearingMessageTimer.OFF) {
                Surface(
                    color = Color(0xFF3B82F6).copy(alpha = 0.1f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = Color(0xFF3B82F6),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            "New messages in this chat will disappear after ${selectedTimer.displayName.lowercase()}",
                            fontSize = 13.sp,
                            color = Color.Gray
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun TimerOptionRow(
    timer: DisappearingMessageTimer,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val icon: ImageVector = when (timer) {
        DisappearingMessageTimer.OFF -> Icons.Default.AllInclusive
        DisappearingMessageTimer.ONE_DAY -> Icons.Default.Schedule
        DisappearingMessageTimer.SEVEN_DAYS -> Icons.Default.DateRange
        DisappearingMessageTimer.THIRTY_DAYS -> Icons.Default.CalendarMonth
    }

    val description = when (timer) {
        DisappearingMessageTimer.OFF -> "Messages won't be automatically deleted"
        else -> "Messages auto-delete after ${timer.displayName.lowercase()}"
    }

    Surface(
        color = if (isSelected) Color(0xFF3B82F6).copy(alpha = 0.1f) else Color.Gray.copy(alpha = 0.1f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        if (isSelected) Color(0xFF3B82F6).copy(alpha = 0.2f) else Color.Gray.copy(alpha = 0.2f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (isSelected) Color(0xFF3B82F6) else Color.Gray,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Text
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    timer.displayName,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Text(
                    description,
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }

            // Checkmark
            if (isSelected) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF3B82F6),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
