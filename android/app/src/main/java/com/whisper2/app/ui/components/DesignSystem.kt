package com.whisper2.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whisper2.app.ui.theme.*

/**
 * Whisper2 Premium Design System
 * iOS Metal-inspired components with depth, gradients, and refined aesthetics.
 */

// ═══════════════════════════════════════════════════════════════════
// PREMIUM BUTTONS
// ═══════════════════════════════════════════════════════════════════

/**
 * Primary gradient button with metallic sheen effect.
 */
@Composable
fun MetalPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val gradientColors = if (enabled) {
        listOf(PrimaryBlue, SecondaryPurple)
    } else {
        listOf(MetalSteel, MetalTitanium)
    }

    val animatedAlpha by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1f,
        animationSpec = tween(100),
        label = "buttonAlpha"
    )

    Box(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.linearGradient(gradientColors),
                alpha = animatedAlpha
            )
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = Color.White.copy(alpha = 0.2f)),
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = text,
                color = if (enabled) Color.White else TextDisabled,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
        }
    }
}

/**
 * Secondary outlined button with metal border.
 */
@Composable
fun MetalSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val borderColor by animateColorAsState(
        targetValue = if (isPressed) PrimaryBlue else BorderHover,
        animationSpec = tween(100),
        label = "borderColor"
    )

    val backgroundColor by animateColorAsState(
        targetValue = if (isPressed) MetalSurface1.copy(alpha = 0.5f) else Color.Transparent,
        animationSpec = tween(100),
        label = "bgColor"
    )

    Box(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = PrimaryBlue.copy(alpha = 0.2f)),
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (enabled) TextPrimary else TextDisabled,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = text,
                color = if (enabled) TextPrimary else TextDisabled,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp
            )
        }
    }
}

/**
 * Icon button with metal glass effect.
 */
@Composable
fun MetalIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tint: Color = TextPrimary,
    size: Dp = 44.dp,
    iconSize: Dp = 24.dp
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val backgroundColor by animateColorAsState(
        targetValue = if (isPressed) MetalSurface2 else Color.Transparent,
        animationSpec = tween(100),
        label = "iconBtnBg"
    )

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true, color = PrimaryBlue.copy(alpha = 0.3f)),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize)
        )
    }
}

// ═══════════════════════════════════════════════════════════════════
// GLASS SURFACES & CARDS
// ═══════════════════════════════════════════════════════════════════

/**
 * Premium glass card with subtle border and depth.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val backgroundColor by animateColorAsState(
        targetValue = if (isPressed) MetalCharcoal else MetalSlate,
        animationSpec = tween(150),
        label = "cardBg"
    )

    Column(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(backgroundColor, MetalNavy)
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        BorderHover.copy(alpha = 0.5f),
                        BorderDefault.copy(alpha = 0.2f)
                    )
                ),
                shape = shape
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = ripple(color = PrimaryBlue.copy(alpha = 0.1f)),
                        onClick = onClick
                    )
                } else Modifier
            )
            .padding(16.dp),
        content = content
    )
}

/**
 * Elevated surface with shadow for modals/sheets.
 */
@Composable
fun MetalSurface(
    modifier: Modifier = Modifier,
    elevation: Dp = 8.dp,
    shape: Shape = RoundedCornerShape(16.dp),
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .shadow(elevation, shape, spotColor = Color.Black.copy(alpha = 0.5f))
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(MetalSlate, MetalNavy)
                )
            )
            .border(
                width = 1.dp,
                color = BorderDefault.copy(alpha = 0.3f),
                shape = shape
            ),
        content = content
    )
}

// ═══════════════════════════════════════════════════════════════════
// INPUT FIELDS
// ═══════════════════════════════════════════════════════════════════

/**
 * Premium text input field with metal styling.
 */
@Composable
fun MetalTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    leadingIcon: ImageVector? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    singleLine: Boolean = true,
    enabled: Boolean = true
) {
    var isFocused by remember { mutableStateOf(false) }

    val borderColor by animateColorAsState(
        targetValue = if (isFocused) PrimaryBlue else BorderDefault,
        animationSpec = tween(200),
        label = "textFieldBorder"
    )

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        placeholder = {
            Text(placeholder, color = TextTertiary)
        },
        leadingIcon = if (leadingIcon != null) {
            { Icon(leadingIcon, null, tint = TextSecondary) }
        } else null,
        trailingIcon = trailingIcon,
        singleLine = singleLine,
        enabled = enabled,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            disabledTextColor = TextDisabled,
            focusedContainerColor = MetalSurface1.copy(alpha = 0.5f),
            unfocusedContainerColor = MetalSlate.copy(alpha = 0.3f),
            disabledContainerColor = MetalSlate.copy(alpha = 0.1f),
            cursorColor = PrimaryBlue,
            focusedBorderColor = borderColor,
            unfocusedBorderColor = BorderDefault,
            disabledBorderColor = BorderDefault.copy(alpha = 0.5f),
            focusedLeadingIconColor = PrimaryBlue,
            unfocusedLeadingIconColor = TextSecondary,
            focusedPlaceholderColor = TextTertiary,
            unfocusedPlaceholderColor = TextTertiary
        ),
        shape = RoundedCornerShape(12.dp)
    )
}

// ═══════════════════════════════════════════════════════════════════
// LIST ITEMS & ROWS
// ═══════════════════════════════════════════════════════════════════

/**
 * Premium conversation row with metal styling.
 */
@Composable
fun MetalListItem(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    badge: Int? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val backgroundColor by animateColorAsState(
        targetValue = if (isPressed) MetalSurface1 else Color.Transparent,
        animationSpec = tween(100),
        label = "listItemBg"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = PrimaryBlue.copy(alpha = 0.1f)),
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingContent != null) {
            leadingContent()
            Spacer(modifier = Modifier.width(12.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TextPrimary,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = TextSecondary,
                    fontSize = 14.sp,
                    maxLines = 1
                )
            }
        }

        if (badge != null && badge > 0) {
            Spacer(modifier = Modifier.width(8.dp))
            Badge(badge)
        }

        if (trailingContent != null) {
            Spacer(modifier = Modifier.width(8.dp))
            trailingContent()
        }
    }
}

/**
 * Notification badge with gradient.
 */
@Composable
fun Badge(count: Int, modifier: Modifier = Modifier) {
    val displayText = if (count > 99) "99+" else count.toString()

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(
                Brush.linearGradient(listOf(PrimaryBlue, SecondaryPurple))
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = displayText,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// ═══════════════════════════════════════════════════════════════════
// AVATAR & STATUS INDICATORS
// ═══════════════════════════════════════════════════════════════════

/**
 * Premium avatar with optional online status.
 */
@Composable
fun MetalAvatar(
    text: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    isOnline: Boolean? = null
) {
    Box(modifier = modifier) {
        // Avatar circle with gradient
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(MetalSurface2, MetalSurface3)
                    )
                )
                .border(1.dp, BorderDefault.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text.take(2).uppercase(),
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value / 2.5).sp
            )
        }

        // Online status indicator
        if (isOnline != null) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .align(Alignment.BottomEnd)
                    .offset(x = (-2).dp, y = (-2).dp)
                    .clip(CircleShape)
                    .background(MetalDark)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(if (isOnline) PresenceOnline else PresenceOffline)
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// DIVIDERS & SEPARATORS
// ═══════════════════════════════════════════════════════════════════

@Composable
fun MetalDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(horizontal = 16.dp),
        thickness = 1.dp,
        color = DividerColor.copy(alpha = 0.5f)
    )
}

// ═══════════════════════════════════════════════════════════════════
// LOADING INDICATORS
// ═══════════════════════════════════════════════════════════════════

@Composable
fun MetalLoadingIndicator(modifier: Modifier = Modifier) {
    CircularProgressIndicator(
        modifier = modifier.size(32.dp),
        color = PrimaryBlue,
        trackColor = MetalSurface2,
        strokeWidth = 3.dp
    )
}

// ═══════════════════════════════════════════════════════════════════
// EMPTY STATES
// ═══════════════════════════════════════════════════════════════════

@Composable
fun MetalEmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = TextTertiary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = subtitle,
            color = TextSecondary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
        if (action != null) {
            Spacer(modifier = Modifier.height(24.dp))
            action()
        }
    }
}
