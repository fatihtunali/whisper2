package com.whisper2.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.whisper2.app.core.AvatarHelper
import java.io.File

/**
 * Reusable composable for displaying a contact's avatar.
 * Shows the custom avatar if available, otherwise shows initials.
 *
 * @param displayName The contact's display name (used for initials if no avatar)
 * @param avatarPath The path to the custom avatar image (nullable)
 * @param size The size of the avatar
 * @param fontSize The font size for initials
 * @param gradientColors The gradient colors for the background when showing initials
 */
@Composable
fun ContactAvatar(
    displayName: String,
    avatarPath: String?,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    fontSize: TextUnit = 20.sp,
    gradientColors: List<Color> = listOf(Color(0xFF3B82F6), Color(0xFF8B5CF6))
) {
    val context = LocalContext.current
    val hasValidAvatar = AvatarHelper.isAvatarPathValid(avatarPath)

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                brush = Brush.linearGradient(colors = gradientColors),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (hasValidAvatar) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(File(avatarPath!!))
                    .crossfade(true)
                    .build(),
                contentDescription = "$displayName avatar",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = displayName.take(1).uppercase(),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = fontSize
            )
        }
    }
}

/**
 * Simple avatar showing just initials with a solid background.
 */
@Composable
fun InitialsAvatar(
    displayName: String,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    fontSize: TextUnit = 20.sp,
    backgroundColor: Color = Color.Gray.copy(alpha = 0.3f)
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(backgroundColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = displayName.take(1).uppercase(),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = fontSize
        )
    }
}
