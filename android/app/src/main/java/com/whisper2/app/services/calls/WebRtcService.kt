package com.whisper2.app.services.calls

import com.whisper2.app.network.ws.TurnCredentialsPayload

/**
 * Step 12: WebRTC Service Interface
 *
 * Abstraction for WebRTC peer connection operations.
 * Allows mocking in tests without native WebRTC dependencies.
 */
interface WebRtcService {

    /**
     * WebRTC event listener
     */
    interface Listener {
        fun onLocalDescription(sdp: String, type: SdpType)
        fun onIceCandidate(candidate: String)
        fun onIceConnectionStateChanged(state: IceConnectionState)
        fun onConnectionStateChanged(state: PeerConnectionState)
        fun onError(error: String)
    }

    /**
     * SDP types
     */
    enum class SdpType {
        OFFER,
        ANSWER
    }

    /**
     * ICE connection states
     */
    enum class IceConnectionState {
        NEW,
        CHECKING,
        CONNECTED,
        COMPLETED,
        FAILED,
        DISCONNECTED,
        CLOSED
    }

    /**
     * Peer connection states
     */
    enum class PeerConnectionState {
        NEW,
        CONNECTING,
        CONNECTED,
        DISCONNECTED,
        FAILED,
        CLOSED
    }

    /**
     * Set the event listener
     */
    fun setListener(listener: Listener?)

    /**
     * Create and initialize peer connection with TURN credentials
     *
     * @param turnCreds TURN server credentials
     * @param isVideo Whether to enable video
     */
    suspend fun createPeerConnection(turnCreds: TurnCredentialsPayload, isVideo: Boolean)

    /**
     * Create SDP offer (for outgoing calls)
     * Result delivered via listener.onLocalDescription
     */
    suspend fun createOffer()

    /**
     * Create SDP answer (for incoming calls, after setting remote offer)
     * Result delivered via listener.onLocalDescription
     */
    suspend fun createAnswer()

    /**
     * Set remote SDP description
     *
     * @param sdp SDP string
     * @param type SDP type (offer or answer)
     */
    suspend fun setRemoteDescription(sdp: String, type: SdpType)

    /**
     * Add ICE candidate from remote peer
     *
     * @param candidate ICE candidate JSON string
     */
    suspend fun addIceCandidate(candidate: String)

    /**
     * Check if remote description has been set
     */
    fun hasRemoteDescription(): Boolean

    /**
     * Close peer connection and release resources
     */
    fun close()

    /**
     * Mute/unmute local audio
     */
    fun setAudioEnabled(enabled: Boolean)

    /**
     * Enable/disable local video
     */
    fun setVideoEnabled(enabled: Boolean)

    /**
     * Switch camera (front/back)
     */
    fun switchCamera()
}
