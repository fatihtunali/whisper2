package com.whisper2.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.whisper2.app.data.local.prefs.SecureStorage

/**
 * Whisper2 Premium Metal Theme
 * iOS-inspired dark metal aesthetic with refined depth and elegance.
 */

// Premium metal dark color scheme
private val MetalDarkColorScheme = darkColorScheme(
    // Primary colors
    primary = PrimaryBlue,
    onPrimary = Color.White,
    primaryContainer = PrimaryBlueMuted,
    onPrimaryContainer = PrimaryBlueLight,

    // Secondary colors
    secondary = SecondaryPurple,
    onSecondary = Color.White,
    secondaryContainer = SecondaryPurpleDark,
    onSecondaryContainer = SecondaryPurpleLight,

    // Tertiary colors
    tertiary = StatusInfo,
    onTertiary = Color.White,
    tertiaryContainer = StatusInfoMuted,
    onTertiaryContainer = StatusInfoLight,

    // Background colors - deep metal
    background = MetalDark,
    onBackground = TextPrimary,

    // Surface colors - layered depth
    surface = MetalSlate,
    onSurface = TextPrimary,
    surfaceVariant = MetalSurface1,
    onSurfaceVariant = TextSecondary,

    // Surface containers for elevation hierarchy
    surfaceTint = PrimaryBlue,
    inverseSurface = TextPrimary,
    inverseOnSurface = MetalDark,

    // Outline colors
    outline = BorderDefault,
    outlineVariant = BorderHover,

    // Error colors
    error = StatusError,
    onError = Color.White,
    errorContainer = StatusErrorMuted,
    onErrorContainer = StatusErrorLight,

    // Scrim for overlays
    scrim = GlassBlack
)

@Composable
fun Whisper2Theme(
    darkTheme: Boolean = true,  // Always dark for metal aesthetic
    content: @Composable () -> Unit
) {
    val colorScheme = MetalDarkColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Deep metal status bar and navigation bar
            @Suppress("DEPRECATION")
            window.statusBarColor = MetalBlack.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = MetalBlack.toArgb()
            // Light icons on dark background
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}

/**
 * Whisper2 Theme with font size scaling support.
 * Use this version when you need to provide the FontSizeManager through the composition.
 */
@Composable
fun Whisper2ThemeWithFontSize(
    secureStorage: SecureStorage,
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val fontSizeManager = remember(secureStorage) { FontSizeManager(secureStorage) }

    CompositionLocalProvider(LocalFontSizeManager provides fontSizeManager) {
        Whisper2Theme(darkTheme = darkTheme, content = content)
    }
}

/**
 * Custom shapes for premium feel
 */
val Shapes = Shapes(
    // Extra small - chips, badges
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(4),
    // Small - small buttons, input fields
    small = androidx.compose.foundation.shape.RoundedCornerShape(8),
    // Medium - cards, dialogs
    medium = androidx.compose.foundation.shape.RoundedCornerShape(12),
    // Large - bottom sheets, large cards
    large = androidx.compose.foundation.shape.RoundedCornerShape(16),
    // Extra large - full screen dialogs
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(24)
)
