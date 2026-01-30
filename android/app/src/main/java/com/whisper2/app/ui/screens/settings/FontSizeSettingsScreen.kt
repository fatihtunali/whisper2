package com.whisper2.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.whisper2.app.ui.theme.*
import com.whisper2.app.ui.viewmodels.FontSizeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FontSizeSettingsScreen(
    onBack: () -> Unit,
    viewModel: FontSizeViewModel = hiltViewModel()
) {
    val selectedOption by viewModel.currentFontSize.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Font Size",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MetalDark
                )
            )
        },
        containerColor = MetalDark
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header with icon
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(PrimaryBlue, SecondaryPurple)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.TextFields,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Choose the font size that's most comfortable for you",
                    color = TextSecondary,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(24.dp))
            }

            // Font size options
            items(FontSizeOption.entries) { option ->
                FontSizeOptionItem(
                    option = option,
                    isSelected = selectedOption == option,
                    onClick = { viewModel.setFontSize(option) }
                )
            }

            // Preview section
            item {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Preview",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                FontSizePreview(option = selectedOption)
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun FontSizeOptionItem(
    option: FontSizeOption,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) PrimaryBlue else BorderDefault
    val backgroundColor = if (isSelected) PrimaryBlue.copy(alpha = 0.1f) else MetalSlate

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = backgroundColor,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = option.displayName,
                    color = TextPrimary,
                    fontSize = (16 * option.multiplier).sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "Aa Bb Cc",
                    color = TextSecondary,
                    fontSize = (14 * option.multiplier).sp
                )
            }

            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(PrimaryBlue),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun FontSizePreview(option: FontSizeOption) {
    val multiplier = option.multiplier

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MetalSlate,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Simulated chat preview
            Text(
                text = "John Doe",
                color = TextPrimary,
                fontSize = (16 * multiplier).sp,
                fontWeight = FontWeight.SemiBold
            )

            // Incoming message bubble
            Box(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(BubbleIncomingStart, BubbleIncomingEnd)
                        )
                    )
                    .padding(12.dp)
            ) {
                Column {
                    Text(
                        text = "Hey! How are you doing today?",
                        color = TextPrimary,
                        fontSize = (15 * multiplier).sp,
                        lineHeight = (20 * multiplier).sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "10:30 AM",
                        color = TextTertiary,
                        fontSize = (11 * multiplier).sp
                    )
                }
            }

            // Outgoing message bubble
            Box(
                modifier = Modifier
                    .align(Alignment.End)
                    .widthIn(max = 280.dp)
                    .clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(BubbleOutgoingStart, BubbleOutgoingEnd)
                        )
                    )
                    .padding(12.dp)
            ) {
                Column {
                    Text(
                        text = "I'm doing great, thanks for asking!",
                        color = Color.White,
                        fontSize = (15 * multiplier).sp,
                        lineHeight = (20 * multiplier).sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "10:31 AM",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = (11 * multiplier).sp
                    )
                }
            }
        }
    }
}
