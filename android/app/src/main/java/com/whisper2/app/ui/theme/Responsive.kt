package com.whisper2.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

/**
 * Responsive scaling utilities matching React Native app behavior
 * Based on responsive.ts from original Whisper RN app
 *
 * Base dimensions: iPhone 11 (375 x 812)
 */
object ResponsiveUtils {
    private const val BASE_WIDTH = 375f
    private const val BASE_HEIGHT = 812f
    private const val TABLET_THRESHOLD = 600 // dp
    private const val MAX_CONTENT_WIDTH = 600 // dp for tablets
}

/**
 * Check if current device is a tablet (>= 600dp width)
 */
@Composable
fun isTablet(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.screenWidthDp >= 600
}

/**
 * Check if current device has a small screen (< 360dp width)
 */
@Composable
fun isSmallScreen(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.screenWidthDp < 360
}

/**
 * Get the scale factor based on screen width relative to base width (375)
 */
@Composable
fun getScaleFactor(): Float {
    val configuration = LocalConfiguration.current
    return configuration.screenWidthDp / 375f
}

/**
 * Moderate scale - scales value based on screen width with dampening
 * @param size Base size in dp
 * @param factor Dampening factor (0.5 = 50% of scaling applied). Default varies by device.
 * @return Scaled size in Dp
 */
@Composable
fun moderateScale(size: Float, factor: Float? = null): Dp {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.toFloat()
    val isTabletDevice = screenWidth >= 600

    val actualFactor = factor ?: if (isTabletDevice) 0.6f else 0.5f
    val scale = screenWidth / 375f

    // For tablets, cap the scale to prevent elements from getting too large
    val cappedScale = if (isTabletDevice) min(scale, 2.0f) else scale

    val newSize = size + (size * (cappedScale - 1) * actualFactor)
    return newSize.dp
}

/**
 * Scale font size - accounts for screen width with clamping
 * @param size Base font size in sp
 * @return Scaled font size in TextUnit
 */
@Composable
fun scaleFontSize(size: Float): TextUnit {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.toFloat()
    val isTabletDevice = screenWidth >= 600

    val scale = screenWidth / 375f

    // Clamp font scaling to prevent extremes
    val minScale = 0.85f
    val maxScale = if (isTabletDevice) 1.5f else 1.3f
    val clampedScale = scale.coerceIn(minScale, maxScale)

    return (size * clampedScale).sp
}

/**
 * Get maximum content width for tablets to prevent overly wide layouts
 * @return Max width in Dp, or null for phones (full width)
 */
@Composable
fun getMaxContentWidth(): Dp? {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp

    return if (screenWidth >= 600) {
        min(600, (screenWidth * 0.85f).toInt()).dp
    } else {
        null
    }
}

/**
 * Spacing values that scale with screen size
 */
object WhisperSpacing {
    @Composable
    fun xs(): Dp = moderateScale(4f)

    @Composable
    fun sm(): Dp = moderateScale(8f)

    @Composable
    fun md(): Dp = moderateScale(16f)

    @Composable
    fun lg(): Dp = moderateScale(24f)

    @Composable
    fun xl(): Dp = moderateScale(32f)

    @Composable
    fun xxl(): Dp = moderateScale(48f)
}

/**
 * Font sizes that scale with screen size
 */
object WhisperFontSize {
    @Composable
    fun xs(): TextUnit = scaleFontSize(12f)

    @Composable
    fun sm(): TextUnit = scaleFontSize(14f)

    @Composable
    fun md(): TextUnit = scaleFontSize(16f)

    @Composable
    fun lg(): TextUnit = scaleFontSize(18f)

    @Composable
    fun xl(): TextUnit = scaleFontSize(20f)

    @Composable
    fun xxl(): TextUnit = scaleFontSize(24f)

    @Composable
    fun xxxl(): TextUnit = scaleFontSize(32f)
}

/**
 * Border radius values that scale with screen size
 */
object WhisperBorderRadius {
    @Composable
    fun sm(): Dp = moderateScale(8f)

    @Composable
    fun md(): Dp = moderateScale(12f)

    @Composable
    fun lg(): Dp = moderateScale(16f)

    @Composable
    fun xl(): Dp = moderateScale(24f)

    val full = 9999.dp
}
