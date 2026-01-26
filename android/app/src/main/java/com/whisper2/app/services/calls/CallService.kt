package com.whisper2.app.services.calls

import com.whisper2.app.core.Constants
import com.whisper2.app.network.ws.*
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Step 12: Call Service
 *
 * Manages call state machine, signaling, and WebRTC coordination.
 *
 * States:
 * - IDLE: No active call
 * - OUTGOING_INITIATING: Caller sent call_initiate, waiting for response
 * - RINGING: Caller received call_ringing from callee
 * - INCOMING_RINGING: Callee received call_incoming, showing UI
 * - CONNECTING: WebRTC connection being established
 * - IN_CALL: Call connected
 * - ENDED: Call terminated (terminal state)
 */
class CallService(
    private val wsSender: WsSender,
    private val uiService: CallUiService,
    private val webRtcService: WebRtcService,
    private val turnService: TurnService,
    private val keyStore: KeyStore,
    private val myKeysProvider: MyKeysProvider,
    private val sessionProvider: () -> String?,
    private val cryptoProvider: CallCryptoProvider = DefaultCallCryptoProvider(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {

    /**
     * Interface for sending WebSocket messages
     */
    interface WsSender {
        fun send(message: String): Boolean
    }

    /**
     * Interface for looking up peer public keys
     */
    interface KeyStore {
        fun getSignPublicKey(whisperId: String): ByteArray?
        fun getEncPublicKey(whisperId: String): ByteArray?
    }

    /**
     * Interface for getting own keys
     */
    interface MyKeysProvider {
        fun getWhisperId(): String?
        fun getSignPrivateKey(): ByteArray?
        fun getEncPrivateKey(): ByteArray?
        fun getEncPublicKey(): ByteArray?
    }

    // =========================================================================
    // STATE
    // =========================================================================

    enum class CallState {
        IDLE,
        OUTGOING_INITIATING,
        RINGING,
        INCOMING_RINGING,
        CONNECTING,
        IN_CALL,
        ENDED
    }

    @Volatile
    var state: CallState = CallState.IDLE
        private set

    @Volatile
    var currentCallId: String? = null
        private set

    @Volatile
    var currentPeerId: String? = null
        private set

    @Volatile
    var isVideoCall: Boolean = false
        private set

    // ICE candidate buffer (before remote description is set)
    private val iceBuffer = ConcurrentLinkedQueue<String>()

    // Track if we've ended this call (for idempotency)
    private val endedCallIds = mutableSetOf<String>()

    // =========================================================================
    // PUBLIC API - OUTGOING CALL
    // =========================================================================

    /**
     * Initiate an outgoing call
     *
     * @param to Callee's WhisperID
     * @param isVideo Whether to start video call
     */
    suspend fun initiateCall(to: String, isVideo: Boolean): Result<String> {
        if (state != CallState.IDLE) {
            return Result.failure(CallException.InvalidState("Cannot initiate call in state: $state"))
        }

        val myWhisperId = myKeysProvider.getWhisperId()
            ?: return Result.failure(CallException.NotAuthenticated())
        val sessionToken = sessionProvider()
            ?: return Result.failure(CallException.NotAuthenticated())
        val signPrivateKey = myKeysProvider.getSignPrivateKey()
            ?: return Result.failure(CallException.NotAuthenticated())

        // Get TURN credentials first
        val turnCreds = try {
            turnService.requestTurnCreds()
        } catch (e: Exception) {
            return Result.failure(CallException.TurnFailed(e.message ?: "Failed to get TURN credentials"))
        }

        // Create peer connection
        try {
            webRtcService.createPeerConnection(turnCreds, isVideo)
        } catch (e: Exception) {
            return Result.failure(CallException.WebRtcFailed(e.message ?: "Failed to create peer connection"))
        }

        val callId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()

        // Create SDP offer - this will be delivered via WebRTC callback
        // For now, create a placeholder and wait for real SDP
        // In real impl, this would use a callback pattern

        currentCallId = callId
        currentPeerId = to
        isVideoCall = isVideo
        state = CallState.OUTGOING_INITIATING

        uiService.showOutgoingCall(callId, to, isVideo)

        // Create and send offer
        scope.launch {
            try {
                webRtcService.createOffer()
                // The actual sending happens in WebRTC listener callback
            } catch (e: Exception) {
                handleCallError(callId, e.message ?: "Failed to create offer")
            }
        }

        return Result.success(callId)
    }

    /**
     * Send call_initiate with SDP offer
     * Called from WebRTC listener when local description is ready
     */
    fun sendCallInitiate(sdpOffer: String) {
        val callId = currentCallId ?: return
        val to = currentPeerId ?: return
        val myWhisperId = myKeysProvider.getWhisperId() ?: return
        val sessionToken = sessionProvider() ?: return
        val signPrivateKey = myKeysProvider.getSignPrivateKey() ?: return
        val peerEncPubKey = keyStore.getEncPublicKey(to) ?: return
        val myEncPrivKey = myKeysProvider.getEncPrivateKey() ?: return

        val timestamp = System.currentTimeMillis()
        val nonce = cryptoProvider.generateNonce()
        val plaintext = sdpOffer.toByteArray(Charsets.UTF_8)

        // Encrypt SDP with peer's enc key
        val ciphertext = cryptoProvider.seal(plaintext, nonce, peerEncPubKey, myEncPrivKey)

        val nonceB64 = cryptoProvider.encodeBase64(nonce)
        val ciphertextB64 = cryptoProvider.encodeBase64(ciphertext)

        // Sign
        val sig = cryptoProvider.signCanonical(
            messageType = MESSAGE_TYPE_CALL_INITIATE,
            messageId = callId,
            from = myWhisperId,
            to = to,
            timestamp = timestamp,
            nonce = nonce,
            ciphertext = ciphertext,
            privateKey = signPrivateKey
        )

        val payload = CallPayload(
            protocolVersion = Constants.PROTOCOL_VERSION,
            cryptoVersion = Constants.CRYPTO_VERSION,
            sessionToken = sessionToken,
            callId = callId,
            from = myWhisperId,
            to = to,
            isVideo = isVideoCall,
            timestamp = timestamp,
            nonce = nonceB64,
            ciphertext = ciphertextB64,
            sig = sig
        )

        val message = WsParser.createEnvelope(WsMessageTypes.CALL_INITIATE, payload)
        wsSender.send(message)
    }

    // =========================================================================
    // PUBLIC API - INCOMING CALL
    // =========================================================================

    /**
     * Accept incoming call
     *
     * @param callId Call ID to accept
     */
    suspend fun accept(callId: String): Result<Unit> {
        if (state != CallState.INCOMING_RINGING || currentCallId != callId) {
            return Result.failure(CallException.InvalidState("Cannot accept call in state: $state"))
        }

        val peerId = currentPeerId
            ?: return Result.failure(CallException.InvalidState("No peer ID"))

        // Get TURN credentials
        val turnCreds = try {
            turnService.requestTurnCreds()
        } catch (e: Exception) {
            return Result.failure(CallException.TurnFailed(e.message ?: "Failed to get TURN credentials"))
        }

        state = CallState.CONNECTING
        uiService.showConnecting(callId)

        // Create peer connection
        try {
            webRtcService.createPeerConnection(turnCreds, isVideoCall)
        } catch (e: Exception) {
            handleCallError(callId, e.message ?: "Failed to create peer connection")
            return Result.failure(CallException.WebRtcFailed(e.message ?: ""))
        }

        // Set remote description (the offer we received)
        // Then create answer - handled in onWsMessage flow

        return Result.success(Unit)
    }

    /**
     * Decline/reject incoming call
     *
     * @param callId Call ID to decline
     * @param reason Reason (e.g., "declined", "busy")
     */
    fun decline(callId: String, reason: String = CallEndReason.DECLINED) {
        if (currentCallId != callId) return
        sendCallEnd(callId, reason)
    }

    // =========================================================================
    // PUBLIC API - END CALL
    // =========================================================================

    /**
     * End the current call
     *
     * @param reason End reason
     */
    fun endCall(reason: String = CallEndReason.ENDED) {
        val callId = currentCallId ?: return
        if (state == CallState.IDLE || state == CallState.ENDED) return

        sendCallEnd(callId, reason)
    }

    private fun sendCallEnd(callId: String, reason: String) {
        // Check idempotency
        if (endedCallIds.contains(callId)) return
        endedCallIds.add(callId)

        val peerId = currentPeerId
        val myWhisperId = myKeysProvider.getWhisperId()
        val sessionToken = sessionProvider()
        val signPrivateKey = myKeysProvider.getSignPrivateKey()

        if (peerId != null && myWhisperId != null && sessionToken != null && signPrivateKey != null) {
            val timestamp = System.currentTimeMillis()
            val nonce = cryptoProvider.generateNonce()
            val ciphertext = reason.toByteArray(Charsets.UTF_8) // Minimal ciphertext for end

            val nonceB64 = cryptoProvider.encodeBase64(nonce)
            val ciphertextB64 = cryptoProvider.encodeBase64(ciphertext)

            val sig = cryptoProvider.signCanonical(
                messageType = MESSAGE_TYPE_CALL_END,
                messageId = callId,
                from = myWhisperId,
                to = peerId,
                timestamp = timestamp,
                nonce = nonce,
                ciphertext = ciphertext,
                privateKey = signPrivateKey
            )

            val payload = CallPayload(
                protocolVersion = Constants.PROTOCOL_VERSION,
                cryptoVersion = Constants.CRYPTO_VERSION,
                sessionToken = sessionToken,
                callId = callId,
                from = myWhisperId,
                to = peerId,
                timestamp = timestamp,
                nonce = nonceB64,
                ciphertext = ciphertextB64,
                sig = sig,
                reason = reason
            )

            val message = WsParser.createEnvelope(WsMessageTypes.CALL_END, payload)
            wsSender.send(message)
        }

        cleanup(callId, reason)
    }

    // =========================================================================
    // WS MESSAGE HANDLING
    // =========================================================================

    /**
     * Handle incoming WebSocket message
     */
    fun onWsMessage(envelope: WsRawEnvelope) {
        when (envelope.type) {
            WsMessageTypes.CALL_INCOMING -> handleCallIncoming(envelope)
            WsMessageTypes.CALL_RINGING -> handleCallRinging(envelope)
            WsMessageTypes.CALL_ANSWER -> handleCallAnswer(envelope)
            WsMessageTypes.CALL_ICE_CANDIDATE -> handleIceCandidate(envelope)
            WsMessageTypes.CALL_END -> handleCallEnd(envelope)
        }
    }

    private fun handleCallIncoming(envelope: WsRawEnvelope) {
        val payload = WsParser.parsePayload<CallPayload>(envelope.payload) ?: return

        // Validate nonce (24 bytes)
        val nonceBytes = try {
            cryptoProvider.decodeBase64WithLength(payload.nonce, 24)
        } catch (e: Exception) {
            return // Invalid nonce
        }

        // Validate signature (64 bytes)
        val sigBytes = try {
            cryptoProvider.decodeBase64WithLength(payload.sig, 64)
        } catch (e: Exception) {
            return // Invalid signature
        }

        // Get sender's sign public key
        val senderSignPubKey = keyStore.getSignPublicKey(payload.from) ?: return

        // Verify signature
        val myWhisperId = myKeysProvider.getWhisperId() ?: return
        val valid = cryptoProvider.verifyCanonical(
            signatureB64 = payload.sig,
            messageType = MESSAGE_TYPE_CALL_INITIATE,
            messageId = payload.callId,
            from = payload.from,
            to = myWhisperId,
            timestamp = payload.timestamp,
            nonceB64 = payload.nonce,
            ciphertextB64 = payload.ciphertext,
            publicKey = senderSignPubKey
        )

        if (!valid) return // Signature verification failed

        // Decrypt SDP offer
        val senderEncPubKey = keyStore.getEncPublicKey(payload.from) ?: return
        val myEncPrivKey = myKeysProvider.getEncPrivateKey() ?: return
        val ciphertextBytes = cryptoProvider.decodeBase64(payload.ciphertext)

        val sdpOfferBytes = try {
            cryptoProvider.open(ciphertextBytes, nonceBytes, senderEncPubKey, myEncPrivKey)
        } catch (e: Exception) {
            return // Decryption failed
        }

        val sdpOffer = String(sdpOfferBytes, Charsets.UTF_8)

        // Update state
        currentCallId = payload.callId
        currentPeerId = payload.from
        isVideoCall = payload.isVideo ?: false
        state = CallState.INCOMING_RINGING

        // Store SDP offer for later use when user accepts
        // (In real impl, store this somewhere)

        // Show incoming call UI
        uiService.showIncomingCall(payload.callId, payload.from, isVideoCall)

        // Send ringing
        sendCallRinging(payload.callId)
    }

    private fun sendCallRinging(callId: String) {
        val peerId = currentPeerId ?: return
        val myWhisperId = myKeysProvider.getWhisperId() ?: return
        val sessionToken = sessionProvider() ?: return
        val signPrivateKey = myKeysProvider.getSignPrivateKey() ?: return

        val timestamp = System.currentTimeMillis()
        val nonce = cryptoProvider.generateNonce()
        val ciphertext = "ringing".toByteArray(Charsets.UTF_8)

        val nonceB64 = cryptoProvider.encodeBase64(nonce)
        val ciphertextB64 = cryptoProvider.encodeBase64(ciphertext)

        val sig = cryptoProvider.signCanonical(
            messageType = MESSAGE_TYPE_CALL_RINGING,
            messageId = callId,
            from = myWhisperId,
            to = peerId,
            timestamp = timestamp,
            nonce = nonce,
            ciphertext = ciphertext,
            privateKey = signPrivateKey
        )

        val payload = CallPayload(
            protocolVersion = Constants.PROTOCOL_VERSION,
            cryptoVersion = Constants.CRYPTO_VERSION,
            sessionToken = sessionToken,
            callId = callId,
            from = myWhisperId,
            to = peerId,
            timestamp = timestamp,
            nonce = nonceB64,
            ciphertext = ciphertextB64,
            sig = sig
        )

        val message = WsParser.createEnvelope(WsMessageTypes.CALL_RINGING, payload)
        wsSender.send(message)
    }

    private fun handleCallRinging(envelope: WsRawEnvelope) {
        val payload = WsParser.parsePayload<CallPayload>(envelope.payload) ?: return

        if (payload.callId != currentCallId) return
        if (state != CallState.OUTGOING_INITIATING) return

        state = CallState.RINGING
        uiService.showRinging(payload.callId)
    }

    private fun handleCallAnswer(envelope: WsRawEnvelope) {
        val payload = WsParser.parsePayload<CallPayload>(envelope.payload) ?: return

        if (payload.callId != currentCallId) return
        if (state != CallState.OUTGOING_INITIATING && state != CallState.RINGING) return

        // Validate and verify signature
        val nonceBytes = try {
            cryptoProvider.decodeBase64WithLength(payload.nonce, 24)
        } catch (e: Exception) {
            return
        }

        val senderSignPubKey = keyStore.getSignPublicKey(payload.from) ?: return
        val myWhisperId = myKeysProvider.getWhisperId() ?: return

        val valid = cryptoProvider.verifyCanonical(
            signatureB64 = payload.sig,
            messageType = MESSAGE_TYPE_CALL_ANSWER,
            messageId = payload.callId,
            from = payload.from,
            to = myWhisperId,
            timestamp = payload.timestamp,
            nonceB64 = payload.nonce,
            ciphertextB64 = payload.ciphertext,
            publicKey = senderSignPubKey
        )

        if (!valid) return

        // Decrypt SDP answer
        val senderEncPubKey = keyStore.getEncPublicKey(payload.from) ?: return
        val myEncPrivKey = myKeysProvider.getEncPrivateKey() ?: return
        val ciphertextBytes = cryptoProvider.decodeBase64(payload.ciphertext)

        val sdpAnswerBytes = try {
            cryptoProvider.open(ciphertextBytes, nonceBytes, senderEncPubKey, myEncPrivKey)
        } catch (e: Exception) {
            return
        }

        val sdpAnswer = String(sdpAnswerBytes, Charsets.UTF_8)

        state = CallState.CONNECTING

        // Set remote description
        scope.launch {
            try {
                webRtcService.setRemoteDescription(sdpAnswer, WebRtcService.SdpType.ANSWER)
                flushIceBuffer()
            } catch (e: Exception) {
                handleCallError(payload.callId, e.message ?: "Failed to set remote description")
            }
        }
    }

    private fun handleIceCandidate(envelope: WsRawEnvelope) {
        val payload = WsParser.parsePayload<CallPayload>(envelope.payload) ?: return

        if (payload.callId != currentCallId) return

        // Validate signature
        val nonceBytes = try {
            cryptoProvider.decodeBase64WithLength(payload.nonce, 24)
        } catch (e: Exception) {
            return
        }

        val senderSignPubKey = keyStore.getSignPublicKey(payload.from) ?: return
        val myWhisperId = myKeysProvider.getWhisperId() ?: return

        val valid = cryptoProvider.verifyCanonical(
            signatureB64 = payload.sig,
            messageType = MESSAGE_TYPE_CALL_ICE_CANDIDATE,
            messageId = payload.callId,
            from = payload.from,
            to = myWhisperId,
            timestamp = payload.timestamp,
            nonceB64 = payload.nonce,
            ciphertextB64 = payload.ciphertext,
            publicKey = senderSignPubKey
        )

        if (!valid) return

        // Decrypt ICE candidate
        val senderEncPubKey = keyStore.getEncPublicKey(payload.from) ?: return
        val myEncPrivKey = myKeysProvider.getEncPrivateKey() ?: return
        val ciphertextBytes = cryptoProvider.decodeBase64(payload.ciphertext)

        val candidateBytes = try {
            cryptoProvider.open(ciphertextBytes, nonceBytes, senderEncPubKey, myEncPrivKey)
        } catch (e: Exception) {
            return
        }

        val candidate = String(candidateBytes, Charsets.UTF_8)

        // Buffer if remote description not set yet
        if (!webRtcService.hasRemoteDescription()) {
            iceBuffer.add(candidate)
            return
        }

        // Add ICE candidate
        scope.launch {
            try {
                webRtcService.addIceCandidate(candidate)
            } catch (e: Exception) {
                // Log error but don't fail the call
            }
        }
    }

    private fun flushIceBuffer() {
        scope.launch {
            while (iceBuffer.isNotEmpty()) {
                val candidate = iceBuffer.poll() ?: break
                try {
                    webRtcService.addIceCandidate(candidate)
                } catch (e: Exception) {
                    // Log but continue
                }
            }
        }
    }

    private fun handleCallEnd(envelope: WsRawEnvelope) {
        val payload = WsParser.parsePayload<CallPayload>(envelope.payload) ?: return

        if (payload.callId != currentCallId) return

        // Idempotency check
        if (endedCallIds.contains(payload.callId)) return

        cleanup(payload.callId, payload.reason ?: CallEndReason.ENDED)
    }

    // =========================================================================
    // INTERNAL HELPERS
    // =========================================================================

    private fun cleanup(callId: String, reason: String) {
        if (currentCallId != callId) return

        endedCallIds.add(callId)
        state = CallState.ENDED

        webRtcService.close()
        uiService.dismissCallUi(callId, reason)

        iceBuffer.clear()
        currentCallId = null
        currentPeerId = null
        isVideoCall = false

        // Transition back to IDLE after a short delay
        scope.launch {
            delay(100)
            if (state == CallState.ENDED) {
                state = CallState.IDLE
            }
        }
    }

    private fun handleCallError(callId: String, error: String) {
        uiService.showError(callId, error)
        sendCallEnd(callId, CallEndReason.FAILED)
    }

    /**
     * Called when WebRTC connection is established
     */
    fun onWebRtcConnected() {
        val callId = currentCallId ?: return
        if (state == CallState.CONNECTING) {
            state = CallState.IN_CALL
            uiService.showOngoingCall(callId, currentPeerId ?: "", isVideoCall)
        }
    }

    /**
     * Called when WebRTC connection fails
     */
    fun onWebRtcFailed(error: String) {
        val callId = currentCallId ?: return
        handleCallError(callId, error)
    }

    // =========================================================================
    // EXCEPTIONS
    // =========================================================================

    sealed class CallException(message: String) : Exception(message) {
        class InvalidState(message: String) : CallException(message)
        class NotAuthenticated : CallException("Not authenticated")
        class TurnFailed(message: String) : CallException(message)
        class WebRtcFailed(message: String) : CallException(message)
        class SignatureInvalid : CallException("Signature verification failed")
        class DecryptionFailed : CallException("Decryption failed")
    }

    companion object {
        // Message type constants for canonical signing
        const val MESSAGE_TYPE_CALL_INITIATE = "call_initiate"
        const val MESSAGE_TYPE_CALL_ANSWER = "call_answer"
        const val MESSAGE_TYPE_CALL_RINGING = "call_ringing"
        const val MESSAGE_TYPE_CALL_ICE_CANDIDATE = "call_ice_candidate"
        const val MESSAGE_TYPE_CALL_END = "call_end"
    }
}
