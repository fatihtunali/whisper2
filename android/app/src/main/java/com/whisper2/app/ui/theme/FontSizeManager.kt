package com.whisper2.app.ui.theme

import androidx.compose.runtime.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.whisper2.app.data.local.prefs.SecureStorage

/**
 * Font Size options available in the app.
 */
enum class FontSizeOption(val key: String, val displayName: String, val multiplier: Float) {
    SMALL("small", "Small", 0.85f),
    MEDIUM("medium", "Medium", 1.0f),
    LARGE("large", "Large", 1.15f),
    EXTRA_LARGE("xlarge", "Extra Large", 1.3f);

    companion object {
        fun fromKey(key: String): FontSizeOption {
            return entries.find { it.key == key } ?: MEDIUM
        }
    }
}

/**
 * Manager for app-wide font size scaling.
 * Provides scaled TextUnit values based on user preference.
 */
class FontSizeManager(private val secureStorage: SecureStorage) {

    private val _fontSizeOption = mutableStateOf(FontSizeOption.fromKey(secureStorage.fontSize))
    val fontSizeOption: State<FontSizeOption> = _fontSizeOption

    val multiplier: Float
        get() = _fontSizeOption.value.multiplier

    fun setFontSize(option: FontSizeOption) {
        secureStorage.fontSize = option.key
        _fontSizeOption.value = option
    }

    fun getFontSize(): FontSizeOption = _fontSizeOption.value

    /**
     * Scale a TextUnit by the current font size multiplier.
     */
    fun scale(baseSize: TextUnit): TextUnit {
        return (baseSize.value * multiplier).sp
    }

    /**
     * Scale a sp value by the current font size multiplier.
     */
    fun scale(baseSp: Float): TextUnit {
        return (baseSp * multiplier).sp
    }
}

/**
 * CompositionLocal for providing FontSizeManager throughout the app.
 */
val LocalFontSizeManager = compositionLocalOf<FontSizeManager> {
    error("FontSizeManager not provided")
}

/**
 * Extension function to scale TextUnit with current font size setting.
 */
@Composable
fun TextUnit.scaled(): TextUnit {
    val fontSizeManager = LocalFontSizeManager.current
    return fontSizeManager.scale(this)
}

/**
 * Extension function to scale sp value with current font size setting.
 */
@Composable
fun Int.scaledSp(): TextUnit {
    val fontSizeManager = LocalFontSizeManager.current
    return fontSizeManager.scale(this.toFloat())
}

/**
 * Extension function to scale Float sp value with current font size setting.
 */
@Composable
fun Float.scaledSp(): TextUnit {
    val fontSizeManager = LocalFontSizeManager.current
    return fontSizeManager.scale(this)
}

/**
 * Get scaled TextStyle based on font size preference.
 */
@Composable
fun TextStyle.scaled(): TextStyle {
    val fontSizeManager = LocalFontSizeManager.current
    return this.copy(
        fontSize = fontSizeManager.scale(this.fontSize),
        lineHeight = fontSizeManager.scale(this.lineHeight)
    )
}
