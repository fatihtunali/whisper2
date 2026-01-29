package com.whisper2.app.services.calls

import android.content.Context
import android.os.Bundle
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.DisconnectCause
import com.whisper2.app.core.Logger

/**
 * Represents a single VoIP call connection.
 * This handles the call state and audio routing.
 */
class WhisperConnection(
    private val context: Context
) : Connection() {

    // Callbacks to notify CallService of events
    var onAnswerCallback: ((Boolean) -> Unit)? = null
    var onRejectCallback: (() -> Unit)? = null
    var onDisconnectCallback: (() -> Unit)? = null
    var onHoldCallback: (() -> Unit)? = null
    var onUnholdCallback: (() -> Unit)? = null
    var onMuteCallback: ((Boolean) -> Unit)? = null

    // Call info stored for showing UI
    var callId: String? = null
    var callerId: String? = null
    var callerName: String? = null
    var isVideoCall: Boolean = false

    init {
        Logger.i("[WhisperConnection] Connection created")
        // Set audio mode for VoIP
        audioModeIsVoip = true
    }

    override fun onShowIncomingCallUi() {
        Logger.i("[WhisperConnection] *** onShowIncomingCallUi - isVideoCall=$isVideoCall ***")
        Logger.i("[WhisperConnection] callId=$callId, callerId=$callerId, callerName=$callerName")

        // Validate we have the required info
        val finalCallId = callId ?: ""
        val finalCallerId = callerId ?: ""
        val finalCallerName = callerName ?: finalCallerId.ifEmpty { "Unknown Caller" }

        Logger.i("[WhisperConnection] Starting CallForegroundService with: callId=$finalCallId, callerId=$finalCallerId, callerName=$finalCallerName, isVideo=$isVideoCall")

        // For self-managed VoIP calls, Android does NOT show any system UI
        // We MUST start our own incoming call UI here via CallForegroundService
        CallForegroundService.startIncomingCall(
            context = context,
            callId = finalCallId,
            callerId = finalCallerId,
            callerName = finalCallerName,
            isVideo = isVideoCall
        )
    }

    override fun onAnswer(videoState: Int) {
        Logger.i("[WhisperConnection] onAnswer - videoState: $videoState")
        setActive()
        val isVideo = videoState != 0
        onAnswerCallback?.invoke(isVideo)
    }

    override fun onAnswer() {
        Logger.i("[WhisperConnection] onAnswer (no video state)")
        setActive()
        onAnswerCallback?.invoke(false)
    }

    override fun onReject() {
        Logger.i("[WhisperConnection] onReject")
        setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
        destroy()
        onRejectCallback?.invoke()
        WhisperConnectionService.activeConnection = null
    }

    override fun onReject(rejectReason: Int) {
        Logger.i("[WhisperConnection] onReject - reason: $rejectReason")
        onReject()
    }

    override fun onReject(replyMessage: String?) {
        Logger.i("[WhisperConnection] onReject - replyMessage: $replyMessage")
        onReject()
    }

    override fun onDisconnect() {
        Logger.i("[WhisperConnection] onDisconnect")
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()
        onDisconnectCallback?.invoke()
        WhisperConnectionService.activeConnection = null
    }

    override fun onAbort() {
        Logger.i("[WhisperConnection] onAbort")
        setDisconnected(DisconnectCause(DisconnectCause.CANCELED))
        destroy()
        onDisconnectCallback?.invoke()
        WhisperConnectionService.activeConnection = null
    }

    override fun onHold() {
        Logger.i("[WhisperConnection] onHold")
        setOnHold()
        onHoldCallback?.invoke()
    }

    override fun onUnhold() {
        Logger.i("[WhisperConnection] onUnhold")
        setActive()
        onUnholdCallback?.invoke()
    }

    @Deprecated("Deprecated in Android API")
    override fun onCallAudioStateChanged(state: CallAudioState?) {
        Logger.d("[WhisperConnection] onCallAudioStateChanged - route: ${state?.route}, muted: ${state?.isMuted}")
        state?.let {
            onMuteCallback?.invoke(it.isMuted)
        }
    }

    override fun onPlayDtmfTone(c: Char) {
        Logger.d("[WhisperConnection] onPlayDtmfTone: $c")
    }

    override fun onStopDtmfTone() {
        Logger.d("[WhisperConnection] onStopDtmfTone")
    }

    override fun onExtrasChanged(extras: Bundle?) {
        Logger.d("[WhisperConnection] onExtrasChanged")
        super.onExtrasChanged(extras)
    }

    /**
     * Set the call as active (answered and media flowing)
     */
    fun setCallActive() {
        Logger.i("[WhisperConnection] setCallActive")
        setActive()
    }

    /**
     * End the call from our side
     */
    fun endCall(cause: DisconnectCause = DisconnectCause(DisconnectCause.LOCAL)) {
        Logger.i("[WhisperConnection] endCall - cause: ${cause.code}")
        setDisconnected(cause)
        destroy()
        WhisperConnectionService.activeConnection = null
    }

    /**
     * Remote party ended the call
     */
    fun remoteEnded() {
        Logger.i("[WhisperConnection] remoteEnded")
        setDisconnected(DisconnectCause(DisconnectCause.REMOTE))
        destroy()
        WhisperConnectionService.activeConnection = null
    }
}
