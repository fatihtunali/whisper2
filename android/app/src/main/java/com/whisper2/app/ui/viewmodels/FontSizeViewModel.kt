package com.whisper2.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import com.whisper2.app.data.local.prefs.SecureStorage
import com.whisper2.app.ui.theme.FontSizeOption
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class FontSizeViewModel @Inject constructor(
    private val secureStorage: SecureStorage
) : ViewModel() {

    private val _currentFontSize = MutableStateFlow(FontSizeOption.fromKey(secureStorage.fontSize))
    val currentFontSize: StateFlow<FontSizeOption> = _currentFontSize.asStateFlow()

    fun setFontSize(option: FontSizeOption) {
        secureStorage.fontSize = option.key
        _currentFontSize.value = option
    }

    fun getFontSizeMultiplier(): Float = _currentFontSize.value.multiplier
}
