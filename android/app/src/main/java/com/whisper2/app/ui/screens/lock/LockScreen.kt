package com.whisper2.app.ui.screens.lock

import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.whisper2.app.core.BiometricHelper
import com.whisper2.app.core.Logger
import com.whisper2.app.ui.theme.*
import kotlinx.coroutines.delay

/**
 * Lock screen shown when app returns from background with biometric lock enabled.
 * Displays app branding and prompts for biometric authentication.
 */
@Composable
fun LockScreen(
    onUnlocked: () -> Unit,
    onAuthError: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isAuthenticating by remember { mutableStateOf(false) }
    var showRetryButton by remember { mutableStateOf(false) }

    // Animated logo scale
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    // Start authentication when screen appears
    LaunchedEffect(Unit) {
        delay(300) // Small delay for smooth transition
        if (activity != null && !isAuthenticating) {
            isAuthenticating = true
            startBiometricAuth(activity, onUnlocked) { errorCode, message ->
                isAuthenticating = false
                if (!BiometricHelper.isUserCancelError(errorCode)) {
                    errorMessage = message
                    onAuthError(message)
                }
                showRetryButton = true
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MetalBlack,
                        MetalDark,
                        MetalNavy
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            // Animated lock icon
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .scale(scale)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                MetalGradientStart,
                                MetalGradientMid,
                                MetalGradientEnd
                            )
                        ),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Locked",
                    tint = Color.White,
                    modifier = Modifier.size(56.dp)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // App name
            Text(
                text = "Whisper2",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Secure Messaging",
                fontSize = 16.sp,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Fingerprint icon with instruction
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .background(
                            GlassWhiteMedium,
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Fingerprint,
                        contentDescription = "Biometric",
                        tint = PrimaryBlue,
                        modifier = Modifier.size(40.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Tap to unlock with biometrics",
                    fontSize = 14.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
            }

            // Error message
            AnimatedVisibility(
                visible = errorMessage != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(24.dp))

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = StatusError.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = errorMessage ?: "",
                            color = StatusError,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // Retry button
            AnimatedVisibility(
                visible = showRetryButton,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = {
                            if (activity != null && !isAuthenticating) {
                                errorMessage = null
                                isAuthenticating = true
                                showRetryButton = false
                                startBiometricAuth(activity, onUnlocked) { errorCode, message ->
                                    isAuthenticating = false
                                    if (!BiometricHelper.isUserCancelError(errorCode)) {
                                        errorMessage = message
                                    }
                                    showRetryButton = true
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryBlue
                        ),
                        modifier = Modifier.fillMaxWidth(0.6f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fingerprint,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Try Again")
                    }
                }
            }
        }

        // Version info at bottom
        Text(
            text = "End-to-end encrypted",
            fontSize = 12.sp,
            color = TextTertiary,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        )
    }
}

/**
 * Start biometric authentication.
 */
private fun startBiometricAuth(
    activity: FragmentActivity,
    onSuccess: () -> Unit,
    onError: (Int, String) -> Unit
) {
    BiometricHelper.authenticate(
        activity = activity,
        title = "Unlock Whisper2",
        subtitle = "Verify your identity to continue",
        negativeButtonText = "Cancel",
        callback = object : BiometricHelper.AuthCallback {
            override fun onSuccess() {
                Logger.i("[LockScreen] Biometric authentication successful")
                onSuccess()
            }

            override fun onError(errorCode: Int, errorMessage: String) {
                Logger.w("[LockScreen] Biometric error: $errorCode - $errorMessage")
                onError(errorCode, errorMessage)
            }

            override fun onFailed() {
                Logger.w("[LockScreen] Biometric not recognized")
                // Don't show error for failed attempts - system shows its own message
            }
        }
    )
}

/**
 * Lock screen overlay that can be shown on top of any content.
 */
@Composable
fun LockScreenOverlay(
    isLocked: Boolean,
    onUnlocked: () -> Unit
) {
    AnimatedVisibility(
        visible = isLocked,
        enter = fadeIn(animationSpec = tween(300)),
        exit = fadeOut(animationSpec = tween(300))
    ) {
        LockScreen(
            onUnlocked = onUnlocked
        )
    }
}
