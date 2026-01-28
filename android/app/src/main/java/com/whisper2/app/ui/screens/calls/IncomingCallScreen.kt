package com.whisper2.app.ui.screens.calls

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun IncomingCallScreen(
    callerName: String,
    callerId: String,
    isVideo: Boolean,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    viewModel: CallViewModel = hiltViewModel()
) {
    // Pulsing animation
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val animationPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAnim"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF22C55E).copy(alpha = 0.3f),
                        Color.Black
                    ),
                    radius = 400f + animationPhase * 100f
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.weight(1f))

            // Call type indicator
            Surface(
                color = Color(0xFF22C55E).copy(alpha = 0.2f),
                shape = RoundedCornerShape(20.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (isVideo) Icons.Default.Videocam else Icons.Default.Phone,
                        contentDescription = null,
                        tint = Color(0xFF22C55E),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (isVideo) "Incoming Video Call" else "Incoming Call",
                        fontSize = 14.sp,
                        color = Color(0xFF22C55E)
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            // Caller avatar with pulsing rings
            Box(contentAlignment = Alignment.Center) {
                // Pulsing rings
                repeat(3) { index ->
                    Box(
                        modifier = Modifier
                            .size((140 + index * 30).dp)
                            .scale(1f + animationPhase * 0.1f)
                            .clip(CircleShape)
                            .background(Color.Transparent)
                            .then(
                                Modifier.background(
                                    Color.Transparent,
                                    CircleShape
                                )
                            )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .background(Color.Transparent)
                        ) {
                            // Ring outline
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                shape = CircleShape,
                                color = Color.Transparent,
                                border = ButtonDefaults.outlinedButtonBorder.copy(
                                    brush = Brush.linearGradient(
                                        listOf(
                                            Color(0xFF22C55E).copy(alpha = 0.3f - index * 0.1f),
                                            Color(0xFF22C55E).copy(alpha = 0.3f - index * 0.1f)
                                        )
                                    )
                                )
                            ) {}
                        }
                    }
                }

                // Avatar
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(CircleShape)
                        .background(Color.Gray.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = callerName.take(1).uppercase(),
                        fontSize = 50.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Caller name
            Text(
                text = callerName,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Call type
            Text(
                text = "Whisper2 ${if (isVideo) "Video" else "Audio"}",
                fontSize = 14.sp,
                color = Color.Gray
            )

            Spacer(modifier = Modifier.weight(1f))

            // Action buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(80.dp)
            ) {
                // Decline
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = {
                            viewModel.declineCall()
                            onDecline()
                        },
                        modifier = Modifier
                            .size(70.dp)
                            .clip(CircleShape)
                            .background(Color.Red)
                    ) {
                        Icon(
                            Icons.Default.CallEnd,
                            contentDescription = "Decline",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Text(
                        text = "Decline",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }

                // Answer
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = {
                            viewModel.answerCall()
                            onAnswer()
                        },
                        modifier = Modifier
                            .size(70.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF22C55E))
                    ) {
                        Icon(
                            imageVector = if (isVideo) Icons.Default.Videocam else Icons.Default.Phone,
                            contentDescription = "Answer",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Text(
                        text = "Answer",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
            }

            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}
