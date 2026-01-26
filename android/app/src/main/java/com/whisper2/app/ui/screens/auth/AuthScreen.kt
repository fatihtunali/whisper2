package com.whisper2.app.ui.screens.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.whisper2.app.ui.state.AuthState
import com.whisper2.app.ui.theme.*
import com.whisper2.app.ui.viewmodels.AuthMode
import com.whisper2.app.ui.viewmodels.AuthUiState
import com.whisper2.app.ui.viewmodels.AuthViewModel

/**
 * Auth Screen
 * Edge-to-edge, responsive design matching original React Native app
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(
    onAuthSuccess: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val authState by viewModel.authState.collectAsState()

    // Responsive values
    val spacingLg = WhisperSpacing.lg()
    val spacingMd = WhisperSpacing.md()
    val spacingSm = WhisperSpacing.sm()

    // Navigate on successful auth
    LaunchedEffect(authState) {
        if (authState is AuthState.Authenticated) {
            onAuthSuccess()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WhisperColors.Background)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        // Header (only shown when not in choose mode)
        if (uiState.mode != AuthMode.CHOOSE) {
            AuthHeader(
                title = when (uiState.mode) {
                    AuthMode.REGISTER -> "Create Account"
                    AuthMode.RECOVER -> "Recover Account"
                    AuthMode.CHOOSE -> ""
                },
                onBackClick = { viewModel.backToChoose() }
            )
        }

        // Content
        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
        ) {
            when (uiState.mode) {
                AuthMode.CHOOSE -> ChooseModeContent(
                    onRegisterClick = { viewModel.selectRegisterMode() },
                    onRecoverClick = { viewModel.selectRecoverMode() }
                )
                AuthMode.REGISTER -> RegisterContent(
                    uiState = uiState,
                    onConfirm = { viewModel.confirmMnemonicAndRegister() }
                )
                AuthMode.RECOVER -> RecoverContent(
                    uiState = uiState,
                    onMnemonicChange = { viewModel.updateMnemonic(it) },
                    onRecover = { viewModel.recover() }
                )
            }

            // Error snackbar
            if (uiState.error != null) {
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(spacingMd)
                        .windowInsetsPadding(WindowInsets.navigationBars),
                    containerColor = WhisperColors.Error,
                    contentColor = WhisperColors.TextPrimary,
                    action = {
                        TextButton(onClick = { viewModel.clearError() }) {
                            Text("Dismiss", color = WhisperColors.TextPrimary)
                        }
                    }
                ) {
                    Text(uiState.error!!)
                }
            }
        }
    }
}

@Composable
private fun AuthHeader(
    title: String,
    onBackClick: () -> Unit
) {
    val spacingMd = WhisperSpacing.md()
    val spacingSm = WhisperSpacing.sm()
    val buttonSize = moderateScale(40f)
    val iconSize = moderateScale(24f)

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacingMd, vertical = spacingSm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(buttonSize),
                shape = CircleShape,
                color = Color.Transparent
            ) {
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier.size(buttonSize)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        modifier = Modifier.size(iconSize),
                        tint = WhisperColors.Primary
                    )
                }
            }

            Spacer(modifier = Modifier.width(spacingSm))

            Text(
                text = title,
                fontSize = WhisperFontSize.lg(),
                fontWeight = FontWeight.SemiBold,
                color = WhisperColors.TextPrimary
            )
        }

        HorizontalDivider(color = WhisperColors.Border, thickness = 1.dp)
    }
}

@Composable
private fun ChooseModeContent(
    onRegisterClick: () -> Unit,
    onRecoverClick: () -> Unit
) {
    val spacingLg = WhisperSpacing.lg()
    val spacingMd = WhisperSpacing.md()
    val spacingSm = WhisperSpacing.sm()
    val spacingXxl = WhisperSpacing.xxl()
    val buttonHeight = moderateScale(56f)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(spacingLg)
            .windowInsetsPadding(WindowInsets.navigationBars),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Whisper",
            fontSize = scaleFontSize(48f),
            fontWeight = FontWeight.Bold,
            color = WhisperColors.Primary
        )

        Spacer(modifier = Modifier.height(spacingSm))

        Text(
            text = "Secure, private messaging",
            fontSize = WhisperFontSize.lg(),
            color = WhisperColors.TextSecondary
        )

        Spacer(modifier = Modifier.height(spacingXxl))

        Button(
            onClick = onRegisterClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(buttonHeight),
            colors = ButtonDefaults.buttonColors(
                containerColor = WhisperColors.Primary
            ),
            shape = RoundedCornerShape(WhisperBorderRadius.md())
        ) {
            Text(
                text = "Create New Account",
                fontSize = WhisperFontSize.md(),
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(spacingMd))

        OutlinedButton(
            onClick = onRecoverClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(buttonHeight),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = WhisperColors.Primary
            ),
            border = ButtonDefaults.outlinedButtonBorder.copy(
                brush = androidx.compose.ui.graphics.SolidColor(WhisperColors.Primary)
            ),
            shape = RoundedCornerShape(WhisperBorderRadius.md())
        ) {
            Text(
                text = "Recover Existing Account",
                fontSize = WhisperFontSize.md(),
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun RegisterContent(
    uiState: AuthUiState,
    onConfirm: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    var showMnemonic by remember { mutableStateOf(false) }

    val spacingLg = WhisperSpacing.lg()
    val spacingMd = WhisperSpacing.md()
    val spacingSm = WhisperSpacing.sm()
    val spacingXs = WhisperSpacing.xs()
    val buttonHeight = moderateScale(56f)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(spacingLg)
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.navigationBars),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Your Recovery Phrase",
            fontSize = WhisperFontSize.xl(),
            fontWeight = FontWeight.SemiBold,
            color = WhisperColors.TextPrimary
        )

        Spacer(modifier = Modifier.height(spacingMd))

        Text(
            text = "Write down these ${uiState.mnemonicWords.size} words in order. You'll need them to recover your account.",
            fontSize = WhisperFontSize.md(),
            textAlign = TextAlign.Center,
            color = WhisperColors.TextSecondary
        )

        Spacer(modifier = Modifier.height(spacingLg))

        // Mnemonic display card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = WhisperColors.Surface,
            shape = RoundedCornerShape(WhisperBorderRadius.md())
        ) {
            Column(
                modifier = Modifier.padding(spacingMd)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Recovery Phrase",
                        fontSize = WhisperFontSize.sm(),
                        fontWeight = FontWeight.SemiBold,
                        color = WhisperColors.TextSecondary
                    )
                    Row {
                        IconButton(onClick = { showMnemonic = !showMnemonic }) {
                            Icon(
                                if (showMnemonic) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showMnemonic) "Hide" else "Show",
                                tint = WhisperColors.TextSecondary
                            )
                        }
                        IconButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(uiState.mnemonic))
                            }
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "Copy",
                                tint = WhisperColors.TextSecondary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(spacingSm))

                if (showMnemonic) {
                    val words = uiState.mnemonicWords
                    val rowCount = (words.size + 2) / 3
                    for (row in 0 until rowCount) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            for (col in 0 until 3) {
                                val index = row * 3 + col
                                if (index < words.size) {
                                    Text(
                                        text = "${index + 1}. ${words[index]}",
                                        fontSize = WhisperFontSize.md(),
                                        color = WhisperColors.TextPrimary,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(spacingXs)
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        text = "Tap the eye icon to reveal",
                        fontSize = WhisperFontSize.md(),
                        color = WhisperColors.TextMuted
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(spacingLg))

        // Warning card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = WhisperColors.Error.copy(alpha = 0.1f),
            shape = RoundedCornerShape(WhisperBorderRadius.md())
        ) {
            Text(
                text = "Warning: Never share your recovery phrase. Anyone with these words can access your account.",
                fontSize = WhisperFontSize.sm(),
                color = WhisperColors.Error,
                modifier = Modifier.padding(spacingMd)
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onConfirm,
            modifier = Modifier
                .fillMaxWidth()
                .height(buttonHeight),
            enabled = !uiState.isLoading,
            colors = ButtonDefaults.buttonColors(
                containerColor = WhisperColors.Primary
            ),
            shape = RoundedCornerShape(WhisperBorderRadius.md())
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(moderateScale(24f)),
                    color = WhisperColors.TextPrimary,
                    strokeWidth = 2.dp
                )
            } else {
                Text(
                    text = "I've Saved My Recovery Phrase",
                    fontSize = WhisperFontSize.md(),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun RecoverContent(
    uiState: AuthUiState,
    onMnemonicChange: (String) -> Unit,
    onRecover: () -> Unit
) {
    val spacingLg = WhisperSpacing.lg()
    val spacingMd = WhisperSpacing.md()
    val buttonHeight = moderateScale(56f)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(spacingLg)
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.navigationBars),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Enter Recovery Phrase",
            fontSize = WhisperFontSize.xl(),
            fontWeight = FontWeight.SemiBold,
            color = WhisperColors.TextPrimary
        )

        Spacer(modifier = Modifier.height(spacingMd))

        Text(
            text = "Enter your 12 or 24-word recovery phrase to restore your account.",
            fontSize = WhisperFontSize.md(),
            textAlign = TextAlign.Center,
            color = WhisperColors.TextSecondary
        )

        Spacer(modifier = Modifier.height(spacingLg))

        OutlinedTextField(
            value = uiState.mnemonic,
            onValueChange = onMnemonicChange,
            modifier = Modifier
                .fillMaxWidth()
                .height(moderateScale(150f)),
            label = {
                Text(
                    "Recovery Phrase",
                    color = WhisperColors.TextSecondary
                )
            },
            placeholder = {
                Text(
                    "word1 word2 word3 ...",
                    color = WhisperColors.TextMuted
                )
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { if (uiState.isValidMnemonic) onRecover() }
            ),
            isError = uiState.mnemonic.isNotEmpty() && !uiState.isValidMnemonic,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = WhisperColors.Primary,
                unfocusedBorderColor = WhisperColors.Border,
                errorBorderColor = WhisperColors.Error,
                focusedTextColor = WhisperColors.TextPrimary,
                unfocusedTextColor = WhisperColors.TextPrimary,
                cursorColor = WhisperColors.Primary,
                focusedContainerColor = WhisperColors.Surface,
                unfocusedContainerColor = WhisperColors.Surface
            ),
            shape = RoundedCornerShape(WhisperBorderRadius.md()),
            supportingText = {
                val wordCount = uiState.mnemonicWords.size
                Text(
                    text = when {
                        uiState.mnemonic.isEmpty() -> "Enter 12 or 24 words separated by spaces"
                        wordCount < 12 -> "$wordCount words (need 12 or 24)"
                        wordCount in 13..23 -> "$wordCount words (need 12 or 24)"
                        !uiState.isValidMnemonic -> "Invalid mnemonic"
                        else -> "Valid mnemonic ($wordCount words)"
                    },
                    fontSize = WhisperFontSize.sm(),
                    color = when {
                        uiState.isValidMnemonic -> WhisperColors.Success
                        uiState.mnemonic.isNotEmpty() -> WhisperColors.Error
                        else -> WhisperColors.TextMuted
                    }
                )
            }
        )

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onRecover,
            modifier = Modifier
                .fillMaxWidth()
                .height(buttonHeight),
            enabled = uiState.isValidMnemonic && !uiState.isLoading,
            colors = ButtonDefaults.buttonColors(
                containerColor = WhisperColors.Primary,
                disabledContainerColor = WhisperColors.Surface
            ),
            shape = RoundedCornerShape(WhisperBorderRadius.md())
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(moderateScale(24f)),
                    color = WhisperColors.TextPrimary,
                    strokeWidth = 2.dp
                )
            } else {
                Text(
                    text = "Recover Account",
                    fontSize = WhisperFontSize.md(),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
