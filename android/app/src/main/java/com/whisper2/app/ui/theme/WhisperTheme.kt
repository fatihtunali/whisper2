package com.whisper2.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Whisper2 Color Palette
 * Dark indigo theme matching original React Native app
 */
object WhisperColors {
    // Primary - Indigo
    val Primary = Color(0xFF6366F1)        // Indigo-500
    val PrimaryDark = Color(0xFF4F46E5)    // Indigo-600
    val PrimaryLight = Color(0xFF818CF8)   // Indigo-400

    // Background - Dark
    val Background = Color(0xFF030712)     // Gray-950
    val Surface = Color(0xFF111827)        // Gray-900
    val SurfaceLight = Color(0xFF1F2937)   // Gray-800

    // Text
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFF9CA3AF)  // Gray-400
    val TextMuted = Color(0xFF6B7280)      // Gray-500

    // Border
    val Border = Color(0xFF374151)         // Gray-700
    val BorderLight = Color(0xFF4B5563)    // Gray-600

    // Status colors
    val Success = Color(0xFF22C55E)        // Green-500
    val Warning = Color(0xFFF59E0B)        // Amber-500
    val Error = Color(0xFFEF4444)          // Red-500

    // Message bubbles
    val MessageSent = Color(0xFF6366F1)    // Indigo-500
    val MessageReceived = Color(0xFF1F2937) // Gray-800
}

private val DarkColorScheme = darkColorScheme(
    primary = WhisperColors.Primary,
    onPrimary = Color.White,
    primaryContainer = WhisperColors.PrimaryDark,
    onPrimaryContainer = Color.White,
    secondary = WhisperColors.Primary,
    onSecondary = Color.White,
    background = WhisperColors.Background,
    onBackground = WhisperColors.TextPrimary,
    surface = WhisperColors.Surface,
    onSurface = WhisperColors.TextPrimary,
    surfaceVariant = WhisperColors.SurfaceLight,
    onSurfaceVariant = WhisperColors.TextSecondary,
    error = WhisperColors.Error,
    onError = Color.White,
    tertiary = WhisperColors.Success,
    onTertiary = Color.White,
    outline = WhisperColors.Border
)

// Light theme uses same dark colors for consistency with original app
private val LightColorScheme = DarkColorScheme

@Composable
fun WhisperTheme(
    darkTheme: Boolean = true, // Always use dark theme to match original
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window

            // Set dark icons/light background = false (we have dark background)
            val controller = WindowInsetsControllerCompat(window, view)
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false

            // Make system bars transparent for edge-to-edge (suppress deprecation as this is still needed for older APIs)
            @Suppress("DEPRECATION")
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

/**
 * Extension colors for message bubbles
 */
@Composable
fun outgoingBubbleColor(): Color = WhisperColors.MessageSent

@Composable
fun incomingBubbleColor(): Color = WhisperColors.MessageReceived

/**
 * Text color on outgoing bubble (always white)
 */
@Composable
fun outgoingTextColor(): Color = Color.White

/**
 * Text color on incoming bubble
 */
@Composable
fun incomingTextColor(): Color = WhisperColors.TextPrimary
