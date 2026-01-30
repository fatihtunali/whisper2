package com.whisper2.app.core

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Helper class for biometric authentication.
 * Supports fingerprint, face recognition, and device credentials.
 */
object BiometricHelper {

    /**
     * Biometric availability status.
     */
    enum class BiometricStatus {
        AVAILABLE,           // Biometric hardware is available and enrolled
        NO_HARDWARE,         // No biometric hardware
        HARDWARE_UNAVAILABLE,// Hardware unavailable (temporarily)
        NOT_ENROLLED,        // No biometrics enrolled
        SECURITY_UPDATE_REQUIRED, // Security vulnerability requires update
        UNSUPPORTED          // Biometric not supported on this device
    }

    /**
     * Authentication result callback.
     */
    interface AuthCallback {
        fun onSuccess()
        fun onError(errorCode: Int, errorMessage: String)
        fun onFailed() // User presented biometric but it didn't match
    }

    /**
     * Check if biometric authentication is available on this device.
     * Checks for both Class 3 (strong) biometrics and device credentials.
     */
    fun checkBiometricAvailability(context: Context): BiometricStatus {
        val biometricManager = BiometricManager.from(context)

        // First check for strong biometrics (fingerprint/face with crypto)
        val strongResult = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)

        return when (strongResult) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricStatus.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
                // Check if weak biometric is available as fallback
                val weakResult = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                when (weakResult) {
                    BiometricManager.BIOMETRIC_SUCCESS -> BiometricStatus.AVAILABLE
                    else -> BiometricStatus.NO_HARDWARE
                }
            }
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricStatus.HARDWARE_UNAVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricStatus.NOT_ENROLLED
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> BiometricStatus.SECURITY_UPDATE_REQUIRED
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> BiometricStatus.UNSUPPORTED
            BiometricManager.BIOMETRIC_STATUS_UNKNOWN -> BiometricStatus.UNSUPPORTED
            else -> BiometricStatus.UNSUPPORTED
        }
    }

    /**
     * Check if any biometric is available (including weak biometrics and device credentials).
     */
    fun isBiometricAvailable(context: Context): Boolean {
        return checkBiometricAvailability(context) == BiometricStatus.AVAILABLE
    }

    /**
     * Get human-readable description for biometric status.
     */
    fun getStatusDescription(status: BiometricStatus): String {
        return when (status) {
            BiometricStatus.AVAILABLE -> "Biometric authentication is available"
            BiometricStatus.NO_HARDWARE -> "This device doesn't have biometric hardware"
            BiometricStatus.HARDWARE_UNAVAILABLE -> "Biometric hardware is temporarily unavailable"
            BiometricStatus.NOT_ENROLLED -> "No biometrics enrolled. Please set up fingerprint or face unlock in device settings"
            BiometricStatus.SECURITY_UPDATE_REQUIRED -> "A security update is required to use biometrics"
            BiometricStatus.UNSUPPORTED -> "Biometric authentication is not supported on this device"
        }
    }

    /**
     * Show biometric authentication prompt.
     *
     * @param activity The FragmentActivity to show the prompt in
     * @param title Title of the biometric prompt
     * @param subtitle Subtitle of the biometric prompt
     * @param description Optional description text
     * @param negativeButtonText Text for the negative/cancel button
     * @param callback Authentication result callback
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String = "Unlock Whisper2",
        subtitle: String = "Use your fingerprint or face to unlock",
        description: String? = null,
        negativeButtonText: String = "Cancel",
        callback: AuthCallback
    ) {
        val executor = ContextCompat.getMainExecutor(activity)

        val biometricPrompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    Logger.i("[BiometricHelper] Authentication succeeded")
                    callback.onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Logger.w("[BiometricHelper] Authentication error: $errorCode - $errString")
                    callback.onError(errorCode, errString.toString())
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Logger.w("[BiometricHelper] Authentication failed (biometric not recognized)")
                    callback.onFailed()
                }
            }
        )

        val promptInfoBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(negativeButtonText)
            // Allow both strong and weak biometrics for better device compatibility
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK)

        description?.let { promptInfoBuilder.setDescription(it) }

        try {
            biometricPrompt.authenticate(promptInfoBuilder.build())
        } catch (e: Exception) {
            Logger.e("[BiometricHelper] Failed to show biometric prompt", e)
            callback.onError(-1, "Failed to show biometric prompt: ${e.message}")
        }
    }

    /**
     * Show biometric authentication prompt with device credential fallback.
     * This allows users to use PIN/pattern/password if biometric fails.
     *
     * Note: When using device credentials, negativeButtonText cannot be set.
     */
    fun authenticateWithCredentialFallback(
        activity: FragmentActivity,
        title: String = "Unlock Whisper2",
        subtitle: String = "Authenticate to continue",
        description: String? = null,
        callback: AuthCallback
    ) {
        val executor = ContextCompat.getMainExecutor(activity)

        val biometricPrompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    Logger.i("[BiometricHelper] Authentication succeeded (with credential fallback)")
                    callback.onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Logger.w("[BiometricHelper] Authentication error: $errorCode - $errString")
                    callback.onError(errorCode, errString.toString())
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Logger.w("[BiometricHelper] Authentication failed")
                    callback.onFailed()
                }
            }
        )

        val promptInfoBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            // Note: Cannot set negative button when DEVICE_CREDENTIAL is allowed

        description?.let { promptInfoBuilder.setDescription(it) }

        try {
            biometricPrompt.authenticate(promptInfoBuilder.build())
        } catch (e: Exception) {
            Logger.e("[BiometricHelper] Failed to show biometric prompt", e)
            callback.onError(-1, "Failed to show biometric prompt: ${e.message}")
        }
    }

    /**
     * Check if device credentials (PIN/pattern/password) are set up.
     */
    fun isDeviceCredentialAvailable(context: Context): Boolean {
        val biometricManager = BiometricManager.from(context)
        val result = biometricManager.canAuthenticate(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        return result == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Get the type of biometric available on the device.
     * Returns a user-friendly string describing available biometrics.
     */
    fun getBiometricTypeName(context: Context): String {
        // Android doesn't provide a direct API to get biometric type
        // We return a generic name
        return when (checkBiometricAvailability(context)) {
            BiometricStatus.AVAILABLE -> "Fingerprint / Face"
            else -> "Biometric"
        }
    }

    /**
     * Common error codes from BiometricPrompt.
     */
    object ErrorCodes {
        const val ERROR_CANCELED = BiometricPrompt.ERROR_CANCELED
        const val ERROR_HW_NOT_PRESENT = BiometricPrompt.ERROR_HW_NOT_PRESENT
        const val ERROR_HW_UNAVAILABLE = BiometricPrompt.ERROR_HW_UNAVAILABLE
        const val ERROR_LOCKOUT = BiometricPrompt.ERROR_LOCKOUT // Too many attempts
        const val ERROR_LOCKOUT_PERMANENT = BiometricPrompt.ERROR_LOCKOUT_PERMANENT
        const val ERROR_NEGATIVE_BUTTON = BiometricPrompt.ERROR_NEGATIVE_BUTTON // User pressed cancel
        const val ERROR_NO_BIOMETRICS = BiometricPrompt.ERROR_NO_BIOMETRICS
        const val ERROR_NO_DEVICE_CREDENTIAL = BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL
        const val ERROR_NO_SPACE = BiometricPrompt.ERROR_NO_SPACE
        const val ERROR_TIMEOUT = BiometricPrompt.ERROR_TIMEOUT
        const val ERROR_UNABLE_TO_PROCESS = BiometricPrompt.ERROR_UNABLE_TO_PROCESS
        const val ERROR_USER_CANCELED = BiometricPrompt.ERROR_USER_CANCELED
        const val ERROR_VENDOR = BiometricPrompt.ERROR_VENDOR
    }

    /**
     * Check if the error is user-initiated (cancel/negative button).
     */
    fun isUserCancelError(errorCode: Int): Boolean {
        return errorCode == ErrorCodes.ERROR_CANCELED ||
               errorCode == ErrorCodes.ERROR_NEGATIVE_BUTTON ||
               errorCode == ErrorCodes.ERROR_USER_CANCELED
    }

    /**
     * Check if the error is a lockout (too many failed attempts).
     */
    fun isLockoutError(errorCode: Int): Boolean {
        return errorCode == ErrorCodes.ERROR_LOCKOUT ||
               errorCode == ErrorCodes.ERROR_LOCKOUT_PERMANENT
    }
}
