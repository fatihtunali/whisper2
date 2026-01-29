package com.whisper2.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.whisper2.app.ui.theme.*

/**
 * Premium Metal Message Input Bar
 * iOS-inspired with glass effect and refined interactions.
 */
enum class AttachmentType {
    PHOTO_VIDEO,
    FILE
}

@Composable
fun MessageInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttachment: (AttachmentType) -> Unit,
    onVoiceMessage: () -> Unit,
    onLocation: () -> Unit,
    isEnabled: Boolean
) {
    var showAttachmentMenu by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }

    // Animated background for focus state
    val containerColor by animateColorAsState(
        targetValue = if (isFocused) MetalSurface1 else MetalSlate,
        animationSpec = tween(200),
        label = "inputBg"
    )

    // Main container with glass effect
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MetalNavy,
                        MetalDark
                    )
                )
            )
    ) {
        // Top border line for separation
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(BorderDefault.copy(alpha = 0.5f))
                .align(Alignment.TopCenter)
        )

        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Attachment button with glass style
            Box {
                IconButton(
                    onClick = { showAttachmentMenu = true },
                    enabled = isEnabled,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (showAttachmentMenu) MetalSurface2 else Color.Transparent
                        )
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Attachment",
                        tint = if (isEnabled) PrimaryBlue else TextDisabled,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Premium dropdown menu
                DropdownMenu(
                    expanded = showAttachmentMenu,
                    onDismissRequest = { showAttachmentMenu = false },
                    modifier = Modifier
                        .background(MetalSlate)
                        .border(1.dp, BorderDefault.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                ) {
                    DropdownMenuItem(
                        text = { Text("Photo/Video", color = TextPrimary) },
                        onClick = {
                            showAttachmentMenu = false
                            onAttachment(AttachmentType.PHOTO_VIDEO)
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Image,
                                null,
                                tint = PrimaryBlue
                            )
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("File", color = TextPrimary) },
                        onClick = {
                            showAttachmentMenu = false
                            onAttachment(AttachmentType.FILE)
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.AttachFile,
                                null,
                                tint = SecondaryPurple
                            )
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Location", color = TextPrimary) },
                        onClick = {
                            showAttachmentMenu = false
                            onLocation()
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.LocationOn,
                                null,
                                tint = StatusError
                            )
                        }
                    )
                }
            }

            // Text input field with glass effect
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                placeholder = {
                    Text(
                        "Message",
                        color = TextTertiary
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp, max = 120.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryBlue.copy(alpha = 0.5f),
                    unfocusedBorderColor = BorderDefault.copy(alpha = 0.3f),
                    focusedContainerColor = containerColor,
                    unfocusedContainerColor = MetalSlate.copy(alpha = 0.5f),
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = PrimaryBlue,
                    disabledContainerColor = MetalSlate.copy(alpha = 0.3f),
                    disabledBorderColor = BorderDefault.copy(alpha = 0.2f),
                    disabledTextColor = TextDisabled
                ),
                shape = RoundedCornerShape(24.dp),
                enabled = isEnabled,
                textStyle = LocalTextStyle.current.copy(
                    color = TextPrimary
                )
            )

            // Send or voice button with gradient
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (isEnabled) {
                            Brush.linearGradient(
                                colors = listOf(PrimaryBlue, SecondaryPurple)
                            )
                        } else {
                            Brush.linearGradient(
                                colors = listOf(MetalSteel, MetalTitanium)
                            )
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                // Glass overlay
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.15f),
                                    Color.Transparent
                                ),
                                startY = 0f,
                                endY = 50f
                            )
                        )
                )

                IconButton(
                    onClick = if (text.isNotBlank()) onSend else onVoiceMessage,
                    enabled = isEnabled
                ) {
                    Icon(
                        imageVector = if (text.isNotBlank()) {
                            Icons.AutoMirrored.Filled.Send
                        } else {
                            Icons.Default.Mic
                        },
                        contentDescription = if (text.isNotBlank()) "Send" else "Voice Message",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}
