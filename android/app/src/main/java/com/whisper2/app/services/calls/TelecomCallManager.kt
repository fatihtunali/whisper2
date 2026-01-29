package com.whisper2.app.services.calls

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import com.whisper2.app.core.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages VoIP calls using Android's Telecom framework.
 * This is Android's equivalent of iOS CallKit.
 *
 * Uses the traditional TelecomManager API with ConnectionService
 * for reliable incoming call handling.
 */
@Singleton
class TelecomCallManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var telecomManager: TelecomManager? = null
    private var phoneAccountHandle: PhoneAccountHandle? = null

    private val _isRegistered = MutableStateFlow(false)
    val isRegistered: StateFlow<Boolean> = _isRegistered

    // Callbacks for call events (set by CallService)
    var onAnswerCall: ((Boolean) -> Unit)? = null
    var onRejectCall: (() -> Unit)? = null
    var onEndCall: (() -> Unit)? = null

    fun initialize() {
        try {
            Logger.i("[TelecomCallManager] Initializing...")

            telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager

            // Register PhoneAccount with ConnectionService
            registerPhoneAccount()

            Logger.i("[TelecomCallManager] Initialized successfully")
        } catch (e: Exception) {
            Logger.e("[TelecomCallManager] Failed to initialize: ${e.message}", e)
            _isRegistered.value = false
        }
    }

    private fun registerPhoneAccount() {
        try {
            val tm = telecomManager ?: return

            // Create PhoneAccountHandle pointing to our ConnectionService
            val componentName = ComponentName(context, WhisperConnectionService::class.java)
            phoneAccountHandle = PhoneAccountHandle(componentName, "whisper2_voip")

            // Build PhoneAccount with self-managed capability
            val phoneAccount = PhoneAccount.builder(phoneAccountHandle, "Whisper2")
                .setCapabilities(
                    PhoneAccount.CAPABILITY_SELF_MANAGED or
                    PhoneAccount.CAPABILITY_VIDEO_CALLING
                )
                .addSupportedUriScheme("whisper2")
                .addSupportedUriScheme(PhoneAccount.SCHEME_SIP)
                .setShortDescription("Whisper2 Secure Calls")
                .build()

            tm.registerPhoneAccount(phoneAccount)
            _isRegistered.value = true
            Logger.i("[TelecomCallManager] PhoneAccount registered: ${phoneAccountHandle?.id}")
        } catch (e: Exception) {
            Logger.e("[TelecomCallManager] Failed to register PhoneAccount: ${e.message}", e)
            _isRegistered.value = false
        }
    }

    /**
     * Report an incoming call to the Telecom system.
     * This will trigger the system to show the incoming call UI.
     */
    suspend fun reportIncomingCall(
        callId: String,
        callerName: String,
        callerId: String,
        isVideo: Boolean,
        scope: CoroutineScope
    ): Boolean {
        Logger.i("[TelecomCallManager] *** reportIncomingCall - isVideo=$isVideo ***")
        Logger.i("[TelecomCallManager] callId: $callId, caller: $callerName, callerId: $callerId")

        val tm = telecomManager ?: run {
            Logger.e("[TelecomCallManager] TelecomManager not initialized!")
            return false
        }

        val accountHandle = phoneAccountHandle ?: run {
            Logger.e("[TelecomCallManager] PhoneAccountHandle not initialized!")
            return false
        }

        return try {
            // Store call info in shared state BEFORE calling addNewIncomingCall
            // This ensures the info is available even if TelecomManager doesn't pass extras correctly
            WhisperConnectionService.pendingCallInfo = WhisperConnectionService.Companion.PendingCallInfo(
                callId = callId,
                callerId = callerId,
                callerName = callerName,
                isVideo = isVideo
            )
            Logger.i("[TelecomCallManager] *** Stored pendingCallInfo with isVideo=$isVideo ***")

            // Create extras bundle with call information
            val extras = Bundle().apply {
                putString(WhisperConnectionService.EXTRA_CALL_ID, callId)
                putString(WhisperConnectionService.EXTRA_CALLER_NAME, callerName)
                putString(WhisperConnectionService.EXTRA_CALLER_ID, callerId)
                putBoolean(WhisperConnectionService.EXTRA_IS_VIDEO, isVideo)
            }

            val uri = Uri.parse("whisper2:$callerId")
            Logger.i("[TelecomCallManager] Adding incoming call with URI: $uri")

            // Use addNewIncomingCall - this triggers WhisperConnectionService.onCreateIncomingConnection
            tm.addNewIncomingCall(accountHandle, extras)

            Logger.i("[TelecomCallManager] addNewIncomingCall called successfully")

            // Set up connection callbacks when connection is created
            setupConnectionCallbacks()

            true
        } catch (e: SecurityException) {
            Logger.e("[TelecomCallManager] SecurityException - missing permission: ${e.message}", e)
            false
        } catch (e: Exception) {
            Logger.e("[TelecomCallManager] Failed to report incoming call: ${e.message}", e)
            false
        }
    }

    /**
     * Report an outgoing call to the Telecom system.
     */
    suspend fun reportOutgoingCall(
        callId: String,
        calleeName: String,
        calleeId: String,
        isVideo: Boolean,
        scope: CoroutineScope
    ): Boolean {
        Logger.i("[TelecomCallManager] reportOutgoingCall - callId: $callId, callee: $calleeName")

        val tm = telecomManager ?: run {
            Logger.e("[TelecomCallManager] TelecomManager not initialized!")
            return false
        }

        val accountHandle = phoneAccountHandle ?: run {
            Logger.e("[TelecomCallManager] PhoneAccountHandle not initialized!")
            return false
        }

        return try {
            val uri = Uri.parse("whisper2:$calleeId")

            val extras = Bundle().apply {
                putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, accountHandle)
                putString(WhisperConnectionService.EXTRA_CALL_ID, callId)
                putString(WhisperConnectionService.EXTRA_CALLER_NAME, calleeName)
                putString(WhisperConnectionService.EXTRA_CALLER_ID, calleeId)
                putBoolean(WhisperConnectionService.EXTRA_IS_VIDEO, isVideo)
            }

            tm.placeCall(uri, extras)
            Logger.i("[TelecomCallManager] placeCall called successfully")

            setupConnectionCallbacks()
            true
        } catch (e: SecurityException) {
            Logger.e("[TelecomCallManager] SecurityException: ${e.message}", e)
            false
        } catch (e: Exception) {
            Logger.e("[TelecomCallManager] Failed to report outgoing call: ${e.message}", e)
            false
        }
    }

    private fun setupConnectionCallbacks() {
        // Wait for connection to be created by ConnectionService
        // Then set up callbacks
        WhisperConnectionService.activeConnection?.let { connection ->
            connection.onAnswerCallback = { isVideo ->
                Logger.i("[TelecomCallManager] Connection answered callback, isVideo: $isVideo")
                onAnswerCall?.invoke(isVideo)
            }
            connection.onRejectCallback = {
                Logger.i("[TelecomCallManager] Connection rejected callback")
                onRejectCall?.invoke()
            }
            connection.onDisconnectCallback = {
                Logger.i("[TelecomCallManager] Connection disconnected callback")
                onEndCall?.invoke()
            }
        }
    }

    /**
     * Answer the current incoming call.
     */
    suspend fun answerCall(isVideo: Boolean): Boolean {
        val connection = WhisperConnectionService.activeConnection ?: run {
            Logger.e("[TelecomCallManager] No active connection to answer")
            return false
        }

        return try {
            connection.setCallActive()
            Logger.i("[TelecomCallManager] Call answered")
            true
        } catch (e: Exception) {
            Logger.e("[TelecomCallManager] Failed to answer call: ${e.message}", e)
            false
        }
    }

    /**
     * Reject the current incoming call.
     */
    suspend fun rejectCall(): Boolean {
        val connection = WhisperConnectionService.activeConnection ?: run {
            Logger.e("[TelecomCallManager] No active connection to reject")
            return false
        }

        return try {
            connection.endCall(DisconnectCause(DisconnectCause.REJECTED))
            Logger.i("[TelecomCallManager] Call rejected")
            true
        } catch (e: Exception) {
            Logger.e("[TelecomCallManager] Failed to reject call: ${e.message}", e)
            false
        }
    }

    /**
     * End the current call.
     */
    suspend fun endCall(): Boolean {
        val connection = WhisperConnectionService.activeConnection ?: run {
            Logger.e("[TelecomCallManager] No active connection to end")
            return false
        }

        return try {
            connection.endCall(DisconnectCause(DisconnectCause.LOCAL))
            Logger.i("[TelecomCallManager] Call ended")
            true
        } catch (e: Exception) {
            Logger.e("[TelecomCallManager] Failed to end call: ${e.message}", e)
            false
        }
    }

    /**
     * Notify that remote party ended the call.
     */
    fun remoteCallEnded() {
        WhisperConnectionService.activeConnection?.remoteEnded()
    }

    /**
     * End the current call synchronously (for cleanup).
     */
    fun endCallSync() {
        Logger.i("[TelecomCallManager] endCallSync - cleaning up connection")
        WhisperConnectionService.activeConnection?.endCall(DisconnectCause(DisconnectCause.LOCAL))
        WhisperConnectionService.activeConnection = null
    }
}
