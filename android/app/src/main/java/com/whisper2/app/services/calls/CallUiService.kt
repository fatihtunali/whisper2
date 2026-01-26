package com.whisper2.app.services.calls

/**
 * Step 12: Call UI Service Interface
 *
 * Abstraction for call UI operations.
 * Allows mocking in tests and platform-specific implementations.
 */
interface CallUiService {

    /**
     * Show incoming call UI (ringing screen)
     *
     * @param callId Unique call identifier
     * @param from Caller's WhisperID
     * @param isVideo Whether this is a video call
     */
    fun showIncomingCall(callId: String, from: String, isVideo: Boolean)

    /**
     * Show ongoing call UI
     *
     * @param callId Unique call identifier
     * @param peerId Remote peer's WhisperID
     * @param isVideo Whether this is a video call
     */
    fun showOngoingCall(callId: String, peerId: String, isVideo: Boolean)

    /**
     * Show outgoing call UI (dialing screen)
     *
     * @param callId Unique call identifier
     * @param to Callee's WhisperID
     * @param isVideo Whether this is a video call
     */
    fun showOutgoingCall(callId: String, to: String, isVideo: Boolean)

    /**
     * Update call UI to ringing state (callee is ringing)
     *
     * @param callId Unique call identifier
     */
    fun showRinging(callId: String)

    /**
     * Update call UI to connecting state
     *
     * @param callId Unique call identifier
     */
    fun showConnecting(callId: String)

    /**
     * Dismiss call UI
     *
     * @param callId Unique call identifier
     * @param reason Reason for dismissal (e.g., "ended", "declined", "busy")
     */
    fun dismissCallUi(callId: String, reason: String)

    /**
     * Show error in call UI
     *
     * @param callId Unique call identifier
     * @param error Error message
     */
    fun showError(callId: String, error: String)
}
