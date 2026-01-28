package com.whisper2.app.ui.screens.calls

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whisper2.app.services.calls.ActiveCall
import com.whisper2.app.services.calls.CallEndReason
import com.whisper2.app.services.calls.CallService
import com.whisper2.app.services.calls.CallState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CallViewModel @Inject constructor(
    private val callService: CallService
) : ViewModel() {

    val callState: StateFlow<CallState> = callService.callState
    val activeCall: StateFlow<ActiveCall?> = callService.activeCall
    val callDuration: StateFlow<Long> = callService.callDuration

    fun initiateCall(peerId: String, isVideo: Boolean) {
        viewModelScope.launch {
            callService.initiateCall(peerId, isVideo)
        }
    }

    fun answerCall() {
        viewModelScope.launch {
            callService.answerCall()
        }
    }

    fun declineCall() {
        viewModelScope.launch {
            callService.declineCall()
        }
    }

    fun endCall() {
        viewModelScope.launch {
            callService.endCall()
        }
    }

    fun toggleMute() {
        callService.toggleMute()
    }

    fun toggleSpeaker() {
        callService.toggleSpeaker()
    }

    fun toggleLocalVideo() {
        callService.toggleLocalVideo()
    }

    fun switchCamera() {
        callService.switchCamera()
    }
}
