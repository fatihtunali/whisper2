package com.whisper2.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import com.whisper2.app.ui.theme.*

/**
 * Chat theme definition with colors for background and message bubbles.
 */
data class ChatTheme(
    val id: String,
    val name: String,
    val backgroundColor: Color,
    val backgroundGradient: List<Color>? = null,  // Optional gradient
    val outgoingBubbleStart: Color,
    val outgoingBubbleEnd: Color,
    val incomingBubbleStart: Color,
    val incomingBubbleEnd: Color,
    val textColor: Color = Color.White
)

/**
 * Available chat themes.
 */
object ChatThemes {
    val Default = ChatTheme(
        id = "default",
        name = "Default",
        backgroundColor = MetalDark,
        outgoingBubbleStart = BubbleOutgoingStart,
        outgoingBubbleEnd = BubbleOutgoingEnd,
        incomingBubbleStart = BubbleIncomingStart,
        incomingBubbleEnd = BubbleIncomingEnd
    )

    val Ocean = ChatTheme(
        id = "ocean",
        name = "Ocean",
        backgroundColor = Color(0xFF0C1929),
        backgroundGradient = listOf(Color(0xFF0C1929), Color(0xFF1A3A52)),
        outgoingBubbleStart = Color(0xFF0077B6),
        outgoingBubbleEnd = Color(0xFF023E8A),
        incomingBubbleStart = Color(0xFF1B3D5C),
        incomingBubbleEnd = Color(0xFF1B4D6E)
    )

    val Forest = ChatTheme(
        id = "forest",
        name = "Forest",
        backgroundColor = Color(0xFF0D1F0D),
        backgroundGradient = listOf(Color(0xFF0D1F0D), Color(0xFF1A3A1A)),
        outgoingBubbleStart = Color(0xFF2D6A4F),
        outgoingBubbleEnd = Color(0xFF1B4332),
        incomingBubbleStart = Color(0xFF1E3A2B),
        incomingBubbleEnd = Color(0xFF264D3B)
    )

    val Sunset = ChatTheme(
        id = "sunset",
        name = "Sunset",
        backgroundColor = Color(0xFF1A0F1E),
        backgroundGradient = listOf(Color(0xFF1A0F1E), Color(0xFF2D1B35)),
        outgoingBubbleStart = Color(0xFFE85D04),
        outgoingBubbleEnd = Color(0xFFD00000),
        incomingBubbleStart = Color(0xFF3D2348),
        incomingBubbleEnd = Color(0xFF4A2C56)
    )

    val Midnight = ChatTheme(
        id = "midnight",
        name = "Midnight",
        backgroundColor = Color(0xFF0D0D1A),
        backgroundGradient = listOf(Color(0xFF0D0D1A), Color(0xFF1A1A33)),
        outgoingBubbleStart = Color(0xFF5E35B1),
        outgoingBubbleEnd = Color(0xFF4527A0),
        incomingBubbleStart = Color(0xFF1E1E3F),
        incomingBubbleEnd = Color(0xFF28284A)
    )

    val Rose = ChatTheme(
        id = "rose",
        name = "Rose",
        backgroundColor = Color(0xFF1A0F14),
        backgroundGradient = listOf(Color(0xFF1A0F14), Color(0xFF2D1B24)),
        outgoingBubbleStart = Color(0xFFBE185D),
        outgoingBubbleEnd = Color(0xFF9D174D),
        incomingBubbleStart = Color(0xFF3D1F2E),
        incomingBubbleEnd = Color(0xFF4A2638)
    )

    val allThemes = listOf(Default, Ocean, Forest, Sunset, Midnight, Rose)

    fun getThemeById(id: String): ChatTheme {
        return allThemes.find { it.id == id } ?: Default
    }
}

/**
 * Bottom sheet for selecting a chat theme.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatThemePickerSheet(
    currentThemeId: String,
    onThemeSelected: (ChatTheme) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedThemeId by remember { mutableStateOf(currentThemeId) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MetalSlate,
        contentColor = TextPrimary,
        dragHandle = {
            Surface(
                modifier = Modifier.padding(vertical = 12.dp),
                color = TextTertiary.copy(alpha = 0.4f),
                shape = RoundedCornerShape(2.dp)
            ) {
                Box(modifier = Modifier.size(32.dp, 4.dp))
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            // Title
            Text(
                text = "Chat Theme",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Text(
                text = "Choose a theme for this conversation",
                fontSize = 14.sp,
                color = TextSecondary,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            // Theme grid
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.heightIn(max = 400.dp)
            ) {
                items(ChatThemes.allThemes) { theme ->
                    ThemePreviewCard(
                        theme = theme,
                        isSelected = theme.id == selectedThemeId,
                        onClick = {
                            selectedThemeId = theme.id
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Apply button
            Button(
                onClick = {
                    onThemeSelected(ChatThemes.getThemeById(selectedThemeId))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryBlue
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    "Apply Theme",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
            }
        }
    }
}

/**
 * Preview card showing a mini representation of the chat theme.
 */
@Composable
private fun ThemePreviewCard(
    theme: ChatTheme,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) PrimaryBlue else BorderDefault

    Box(
        modifier = Modifier
            .aspectRatio(0.85f)
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
    ) {
        // Background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = if (theme.backgroundGradient != null) {
                        Brush.verticalGradient(theme.backgroundGradient)
                    } else {
                        Brush.verticalGradient(listOf(theme.backgroundColor, theme.backgroundColor))
                    }
                )
        ) {
            // Sample chat bubbles
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                verticalArrangement = Arrangement.Center
            ) {
                // Incoming bubble
                Box(
                    modifier = Modifier
                        .align(Alignment.Start)
                        .fillMaxWidth(0.7f)
                        .height(24.dp)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomEnd = 12.dp, bottomStart = 4.dp))
                        .background(
                            brush = Brush.horizontalGradient(
                                listOf(theme.incomingBubbleStart, theme.incomingBubbleEnd)
                            )
                        )
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Outgoing bubble
                Box(
                    modifier = Modifier
                        .align(Alignment.End)
                        .fillMaxWidth(0.7f)
                        .height(24.dp)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomEnd = 4.dp, bottomStart = 12.dp))
                        .background(
                            brush = Brush.horizontalGradient(
                                listOf(theme.outgoingBubbleStart, theme.outgoingBubbleEnd)
                            )
                        )
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Another incoming bubble
                Box(
                    modifier = Modifier
                        .align(Alignment.Start)
                        .fillMaxWidth(0.5f)
                        .height(20.dp)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomEnd = 12.dp, bottomStart = 4.dp))
                        .background(
                            brush = Brush.horizontalGradient(
                                listOf(theme.incomingBubbleStart, theme.incomingBubbleEnd)
                            )
                        )
                )
            }

            // Selected checkmark
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(24.dp)
                        .background(PrimaryBlue, CircleShape),
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

            // Theme name at bottom
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(vertical = 6.dp)
            ) {
                Text(
                    text = theme.name,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Dialog version of theme picker (alternative to bottom sheet).
 */
@Composable
fun ChatThemePickerDialog(
    currentThemeId: String,
    onThemeSelected: (ChatTheme) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedThemeId by remember { mutableStateOf(currentThemeId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MetalSlate,
        title = {
            Text(
                "Chat Theme",
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.heightIn(max = 350.dp)
            ) {
                items(ChatThemes.allThemes) { theme ->
                    ThemePreviewCard(
                        theme = theme,
                        isSelected = theme.id == selectedThemeId,
                        onClick = {
                            selectedThemeId = theme.id
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onThemeSelected(ChatThemes.getThemeById(selectedThemeId)) }
            ) {
                Text("Apply", color = PrimaryBlue, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}
