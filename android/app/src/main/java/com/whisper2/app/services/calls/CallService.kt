package com.whisper2.app.services.calls

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.whisper2.app.core.Constants
import com.whisper2.app.core.Logger
import com.whisper2.app.crypto.CryptoService
import com.whisper2.app.services.auth.AuthService
import com.whisper2.app.services.auth.AuthState
import com.whisper2.app.data.local.db.dao.CallRecordDao
import com.whisper2.app.data.local.db.dao.ContactDao
import com.whisper2.app.data.local.db.dao.ConversationDao
import com.whisper2.app.data.local.db.dao.MessageDao
import com.whisper2.app.data.local.db.entities.CallRecordEntity
import com.whisper2.app.data.local.db.entities.ConversationEntity
import com.whisper2.app.data.local.db.entities.MessageEntity
import com.whisper2.app.data.local.prefs.SecureStorage
import com.whisper2.app.data.network.ws.CallAnswerNotificationPayload
import com.whisper2.app.data.network.ws.CallAnswerPayload
import com.whisper2.app.data.network.ws.CallEndNotificationPayload
import com.whisper2.app.data.network.ws.CallEndPayload
import com.whisper2.app.data.network.ws.CallIceCandidateNotificationPayload
import com.whisper2.app.data.network.ws.CallIceCandidatePayload
import com.whisper2.app.data.network.ws.CallIncomingPayload
import com.whisper2.app.data.network.ws.CallInitiatePayload
import com.whisper2.app.data.network.ws.GetTurnCredentialsPayload
import com.whisper2.app.data.network.ws.TurnCredentialsPayload
import com.whisper2.app.data.network.ws.WsClientImpl
import com.whisper2.app.data.network.ws.WsFrame
import com.whisper2.app.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.webrtc.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

sealed class CallState {
    object Idle : CallState()
    object Initiating : CallState()
    object Ringing : CallState()
    object Connecting : CallState()
    object Connected : CallState()
    object Reconnecting : CallState()
    data class Ended(val reason: CallEndReason) : CallState()
}

enum class CallEndReason {
    ENDED,      // Normal end
    CANCELLED,  // Caller cancelled before answer
    DECLINED,
    BUSY,
    TIMEOUT,
    FAILED
}

data class ActiveCall(
    val callId: String,
    val peerId: String,
    val peerName: String?,
    val isVideo: Boolean,
    val isOutgoing: Boolean,
    var startTime: Long? = null,
    var isMuted: Boolean = false,
    var isSpeakerOn: Boolean = false,
    var isLocalVideoEnabled: Boolean = true,
    var isRemoteVideoEnabled: Boolean = true
)

@Singleton
class CallService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wsClient: WsClientImpl,
    private val secureStorage: SecureStorage,
    private val cryptoService: CryptoService,
    private val contactDao: ContactDao,
    private val callRecordDao: CallRecordDao,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val gson: Gson,
    private val authService: dagger.Lazy<AuthService>,
    @ApplicationScope private val scope: CoroutineScope
) {
    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private val _activeCall = MutableStateFlow<ActiveCall?>(null)
    val activeCall: StateFlow<ActiveCall?> = _activeCall.asStateFlow()

    private val _turnCredentials = MutableStateFlow<TurnCredentialsPayload?>(null)
    private var turnCredentialsReceivedAt: Long = 0  // Timestamp when credentials were received

    // WebRTC components
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    // Shared EGL context for video rendering
    private var eglBase: EglBase? = null
    val eglBaseContext: EglBase.Context?
        get() = eglBase?.eglBaseContext

    // Video renderers (set by UI)
    private var localVideoSink: VideoSink? = null
    private var remoteVideoSink: VideoSink? = null

    // Store remote video track to add sink later when UI is ready
    private var remoteVideoTrack: VideoTrack? = null

    // CRITICAL: Must hold reference to remote MediaStream to prevent GC from disposing tracks!
    // See: https://github.com/GetStream/webrtc-android/issues/176
    private var remoteMediaStream: MediaStream? = null

    // Pending ICE candidates (received before remote description set)
    private val pendingRemoteIceCandidates = mutableListOf<IceCandidate>()

    // Pending local ICE candidates (generated before offer sent)
    private val pendingLocalIceCandidates = mutableListOf<IceCandidate>()
    private var localSdpSent = false

    // Track received flags for proper Connected state
    private var remoteAudioTrackReceived = false
    private var remoteVideoTrackReceived = false

    // Flag to prevent double-adding sink (onAddTrack and onAddStream both fire)
    private var remoteVideoSinkAdded = false

    // Incoming call payload (stored for answering)
    private var pendingIncomingCall: CallIncomingPayload? = null

    // Call duration timer
    private var callDurationJob: Job? = null
    private val _callDuration = MutableStateFlow(0L)
    val callDuration: StateFlow<Long> = _callDuration.asStateFlow()

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    init {
        // Clean up any stale call state from previous app session
        cleanupStaleState()
        setupWebRTC()
        setupMessageHandler()
        setupAuthStateMonitor()
    }

    /**
     * Monitor auth state and pre-fetch TURN credentials when authenticated.
     * This ensures credentials are ready before any call.
     */
    private fun setupAuthStateMonitor() {
        scope.launch {
            authService.get().authState.collect { state ->
                if (state is AuthState.Authenticated) {
                    // Pre-fetch TURN credentials on login
                    if (!areTurnCredentialsValid()) {
                        Logger.i("[CallService] User authenticated - pre-fetching TURN credentials")
                        fetchTurnCredentials()
                    }
                }
            }
        }
    }

    /**
     * Clean up any stale call state that may have persisted from a previous app session.
     * This handles cases where the app was killed during an active call.
     */
    private fun cleanupStaleState() {
        Logger.i("[CallService] Cleaning up stale call state on app start")

        // Check if there was an active call from previous session
        val staleCallId = secureStorage.activeCallId
        val staleCallPeerId = secureStorage.activeCallPeerId

        if (staleCallId != null && staleCallPeerId != null) {
            Logger.i("[CallService] Found stale call: $staleCallId with peer: $staleCallPeerId - sending cleanup to server")
            // Send call_end to server to clean up server-side state
            scope.launch {
                try {
                    sendStaleCallCleanup(staleCallId, staleCallPeerId)
                } catch (e: Exception) {
                    Logger.e("[CallService] Failed to send stale call cleanup", e)
                }
            }
        }

        // Clear persisted call info
        secureStorage.clearActiveCall()

        // Reset all call state
        _callState.value = CallState.Idle
        _activeCall.value = null
        _turnCredentials.value = null
        _callDuration.value = 0

        // Clear any pending data
        pendingRemoteIceCandidates.clear()
        pendingLocalIceCandidates.clear()
        pendingIncomingCall = null

        // Reset flags
        localSdpSent = false
        remoteAudioTrackReceived = false
        remoteVideoTrackReceived = false
        remoteVideoSinkAdded = false
        callEndedByRemote = false

        // Stop any lingering foreground service
        try {
            CallForegroundService.stopService(context)
        } catch (e: Exception) {
            Logger.w("[CallService] Error stopping foreground service during cleanup: ${e.message}")
        }

        Logger.i("[CallService] Stale state cleanup complete")
    }

    /**
     * Send call_end to server for a stale call that wasn't properly ended.
     */
    private suspend fun sendStaleCallCleanup(callId: String, peerId: String) {
        val whisperId = secureStorage.whisperId ?: return
        val sessionToken = secureStorage.sessionToken ?: return
        val encPrivateKey = secureStorage.encPrivateKey ?: return
        val signPrivateKey = secureStorage.signPrivateKey ?: return

        val contact = contactDao.getContactById(peerId)
        val recipientPublicKey = contact?.encPublicKey?.let {
            Base64.decode(it.replace(" ", "+").trim(), Base64.NO_WRAP)
        }

        if (recipientPublicKey != null) {
            try {
                val message = "end"
                val nonce = cryptoService.generateNonce()
                val ciphertext = cryptoService.boxSeal(
                    message.toByteArray(Charsets.UTF_8),
                    nonce,
                    recipientPublicKey,
                    encPrivateKey
                )

                val timestamp = System.currentTimeMillis()

                val signature = cryptoService.signMessage(
                    Constants.MsgType.CALL_END,
                    callId,
                    whisperId,
                    peerId,
                    timestamp,
                    nonce,
                    ciphertext,
                    signPrivateKey
                )

                Logger.i("[CallService] Sending stale call cleanup for callId: $callId, reason: 'failed'")

                val payload = CallEndPayload(
                    sessionToken = sessionToken,
                    callId = callId,
                    from = whisperId,
                    to = peerId,
                    timestamp = timestamp,
                    nonce = Base64.encodeToString(nonce, Base64.NO_WRAP),
                    ciphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
                    sig = Base64.encodeToString(signature, Base64.NO_WRAP),
                    reason = "failed"  // Stale calls are treated as failed
                )

                wsClient.send(WsFrame(Constants.MsgType.CALL_END, payload = payload))
                Logger.i("[CallService] Stale call cleanup sent successfully")
            } catch (e: Exception) {
                Logger.e("[CallService] Failed to send stale call cleanup", e)
            }
        } else {
            Logger.w("[CallService] Cannot send stale call cleanup - no public key for peer: $peerId")
        }
    }

    private fun setupWebRTC() {
        try {
            val options = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(options)

            // Create shared EGL base
            eglBase = EglBase.create()
            val eglContext = eglBase!!.eglBaseContext

            // SAFE ENCODER CREATION
            val encoderFactory = try {
                DefaultVideoEncoderFactory(eglContext, true, false)
            } catch (e: Exception) {
                Logger.e("[CallService] Failed to create DefaultVideoEncoderFactory", e)
                throw e // Critical failure
            }

            // Using Threema's WebRTC library which has proper decoder support
            val decoderFactory = DefaultVideoDecoderFactory(eglContext)
            Logger.i("[CallService] Using DefaultVideoDecoderFactory for video decoding")

            peerConnectionFactory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()

            Logger.i("[CallService] WebRTC initialized successfully")
        } catch (e: Exception) {
            Logger.e("[CallService] CRITICAL: WebRTC initialization failed", e)
        }
    }

    private fun setupMessageHandler() {
        scope.launch {
            wsClient.messages.collect { frame ->
                handleMessage(frame)
            }
        }
    }

    // MARK: - TURN Credentials

    private suspend fun fetchTurnCredentials() {
        val sessionToken = secureStorage.sessionToken ?: return

        val payload = GetTurnCredentialsPayload(sessionToken = sessionToken)
        wsClient.send(WsFrame(Constants.MsgType.GET_TURN_CREDENTIALS, payload = payload))
    }

    /**
     * Fetch TURN credentials and wait for them to arrive.
     * Uses cached credentials if still valid (with 60s buffer before expiry).
     * Returns true if credentials are available, false if timeout.
     */
    private suspend fun fetchAndWaitForTurnCredentials(timeout: Long = 5_000): Boolean {
        // Use cached credentials if still valid
        if (areTurnCredentialsValid()) {
            Logger.i("[CallService] Using cached TURN credentials")
            return true
        }

        // Clear expired credentials
        _turnCredentials.value = null

        Logger.i("[CallService] Fetching fresh TURN credentials...")
        fetchTurnCredentials()

        // Poll until credentials arrive or timeout
        val startTime = System.currentTimeMillis()
        val checkInterval = 100L

        while (System.currentTimeMillis() - startTime < timeout) {
            if (_turnCredentials.value != null) {
                Logger.i("[CallService] TURN credentials received after ${System.currentTimeMillis() - startTime}ms")
                return true
            }
            delay(checkInterval)
        }

        Logger.e("[CallService] TURN credentials timeout after ${timeout}ms")
        return false
    }

    // MARK: - Wait for Authentication

    /**
     * Wait for the WebSocket connection to be authenticated.
     * When the app wakes from a push notification, the WS may be connecting but not yet authenticated.
     * This ensures we don't send call commands before the challenge-response completes.
     */
    private suspend fun waitForAuthentication(timeout: Long = 10_000): Boolean {
        val startTime = System.currentTimeMillis()
        val checkInterval = 100L

        while (System.currentTimeMillis() - startTime < timeout) {
            val currentState = authService.get().authState.value
            if (currentState is AuthState.Authenticated) {
                return true
            }
            // If explicitly unauthenticated or error, trigger reconnect
            if (currentState is AuthState.Unauthenticated || currentState is AuthState.Error) {
                Logger.i("[CallService] Auth state is $currentState, triggering reconnect")
                authService.get().reconnect()
            }
            delay(checkInterval)
        }
        return false
    }

    // MARK: - Initiate Call

    suspend fun initiateCall(peerId: String, isVideo: Boolean): Result<Unit> {
        val whisperId = secureStorage.whisperId ?: return Result.failure(Exception("Not authenticated"))
        val sessionToken = secureStorage.sessionToken ?: return Result.failure(Exception("No session"))
        val encPrivateKey = secureStorage.encPrivateKey ?: return Result.failure(Exception("No encryption key"))
        val signPrivateKey = secureStorage.signPrivateKey ?: return Result.failure(Exception("No signing key"))

        // Get recipient's public key
        val contact = contactDao.getContactById(peerId)
        val recipientPublicKey = contact?.encPublicKey?.let {
            Base64.decode(it.replace(" ", "+").trim(), Base64.NO_WRAP)
        } ?: return Result.failure(Exception("Contact public key not found"))

        return try {
            // Reset track flags
            remoteAudioTrackReceived = false
            remoteVideoTrackReceived = false
            remoteVideoSinkAdded = false
            localSdpSent = false

            // Fetch TURN credentials and wait for them
            if (!fetchAndWaitForTurnCredentials(5000)) {
                return Result.failure(Exception("Failed to get TURN credentials"))
            }

            val callId = UUID.randomUUID().toString().lowercase()

            // RULE 2: Initialize audio routing BEFORE createPeerConnection
            initializeAudioRouting(isVideo)

            // Create peer connection and generate offer
            createPeerConnection(isVideo)
            val sdpOffer = createOffer(isVideo)

            // Log SDP details for debugging
            val hasVideoMLine = sdpOffer.contains("m=video")
            val hasAudioMLine = sdpOffer.contains("m=audio")
            Logger.i("[CallService] SDP Offer created - hasAudio=$hasAudioMLine, hasVideo=$hasVideoMLine, isVideoCall=$isVideo")
            if (isVideo && !hasVideoMLine) {
                Logger.e("[CallService] CRITICAL: Video call but NO m=video in SDP! localVideoTrack=${localVideoTrack != null}")
            }

            // Encrypt SDP
            val nonce = cryptoService.generateNonce()
            val ciphertext = cryptoService.boxSeal(
                sdpOffer.toByteArray(Charsets.UTF_8),
                nonce,
                recipientPublicKey,
                encPrivateKey
            )

            val timestamp = System.currentTimeMillis()

            // Sign
            val signature = cryptoService.signMessage(
                Constants.MsgType.CALL_INITIATE,
                callId,
                whisperId,
                peerId,
                timestamp,
                nonce,
                ciphertext,
                signPrivateKey
            )

            // Send initiate
            val payload = CallInitiatePayload(
                sessionToken = sessionToken,
                callId = callId,
                from = whisperId,
                to = peerId,
                isVideo = isVideo,
                timestamp = timestamp,
                nonce = Base64.encodeToString(nonce, Base64.NO_WRAP),
                ciphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
                sig = Base64.encodeToString(signature, Base64.NO_WRAP)
            )

            wsClient.send(WsFrame(Constants.MsgType.CALL_INITIATE, payload = payload))
            localSdpSent = true

            // Flush any pending local ICE candidates now that offer is sent
            pendingLocalIceCandidates.forEach { sendIceCandidate(it) }
            pendingLocalIceCandidates.clear()

            // Update state
            _activeCall.value = ActiveCall(
                callId = callId,
                peerId = peerId,
                peerName = contact?.displayName,
                isVideo = isVideo,
                isOutgoing = true
            )
            _callState.value = CallState.Initiating

            // Persist active call info for cleanup on app restart
            secureStorage.activeCallId = callId
            secureStorage.activeCallPeerId = peerId

            // Configure audio
            configureAudioSession()

            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e("Failed to initiate call", e)
            cleanup()
            Result.failure(e)
        }
    }

    // MARK: - Answer Call

    suspend fun answerCall(): Result<Unit> {
        // Prevent double-calling answerCall (UI + Telecom callback race) - atomic check-and-set
        if (!answerCallTriggered.compareAndSet(false, true)) {
            Logger.i("[CallService] answerCall already in progress, ignoring duplicate call")
            return Result.success(Unit)
        }

        val incomingPayload = pendingIncomingCall ?: return Result.failure(Exception("No incoming call"))
        Logger.i("[CallService] *** answerCall - pendingIncomingCall.isVideo=${incomingPayload.isVideo} ***")

        // CRITICAL: Wait for WebSocket authentication before answering
        // When app wakes from push notification, the connection may not be authenticated yet
        Logger.i("[CallService] Waiting for WebSocket authentication...")
        val authenticated = waitForAuthentication(timeout = 10_000)
        if (!authenticated) {
            Logger.e("[CallService] Failed to authenticate WebSocket before answering call")
            return Result.failure(Exception("WebSocket not authenticated"))
        }
        Logger.i("[CallService] WebSocket authenticated, proceeding with answer")

        val whisperId = secureStorage.whisperId ?: return Result.failure(Exception("Not authenticated"))
        val sessionToken = secureStorage.sessionToken ?: return Result.failure(Exception("No session"))
        val encPrivateKey = secureStorage.encPrivateKey ?: return Result.failure(Exception("No encryption key"))
        val signPrivateKey = secureStorage.signPrivateKey ?: return Result.failure(Exception("No signing key"))

        // Get sender's public key
        val contact = contactDao.getContactById(incomingPayload.from)
        val senderPublicKey = contact?.encPublicKey?.let {
            Base64.decode(it.replace(" ", "+").trim(), Base64.NO_WRAP)
        } ?: return Result.failure(Exception("Sender public key not found"))

        return try {
            // Reset track flags
            remoteAudioTrackReceived = false
            remoteVideoTrackReceived = false
            remoteVideoSinkAdded = false
            localSdpSent = false

            // Fetch TURN credentials and wait for them
            if (!fetchAndWaitForTurnCredentials(5000)) {
                return Result.failure(Exception("Failed to get TURN credentials"))
            }

            // Decrypt SDP offer
            val ciphertextData = Base64.decode(incomingPayload.ciphertext, Base64.NO_WRAP)
            val nonceData = Base64.decode(incomingPayload.nonce, Base64.NO_WRAP)

            val sdpOffer = String(
                cryptoService.boxOpen(ciphertextData, nonceData, senderPublicKey, encPrivateKey),
                Charsets.UTF_8
            )

            // Log received offer SDP to verify m=video line
            val hasVideoMLine = sdpOffer.contains("m=video")
            val hasAudioMLine = sdpOffer.contains("m=audio")
            Logger.i("[CallService] Received offer SDP - isVideo=${incomingPayload.isVideo}, hasAudio=$hasAudioMLine, hasVideo=$hasVideoMLine")
            if (incomingPayload.isVideo && !hasVideoMLine) {
                Logger.e("[CallService] CRITICAL: Video call but received offer has NO m=video line!")
            }
            if (incomingPayload.isVideo) {
                Logger.i("[CallService] SDP Offer preview (first 500 chars): ${sdpOffer.take(500)}")
            }

            // RULE 2: Initialize audio routing BEFORE createPeerConnection
            initializeAudioRouting(incomingPayload.isVideo)

            // Create peer connection and set remote description
            createPeerConnection(incomingPayload.isVideo)

            val remoteDesc = SessionDescription(SessionDescription.Type.OFFER, sdpOffer)
            peerConnection?.setRemoteDescription(SdpObserverAdapter(), remoteDesc)

            // Process pending remote ICE candidates (buffered while ringing)
            pendingRemoteIceCandidates.forEach { candidate ->
                peerConnection?.addIceCandidate(candidate)
            }
            pendingRemoteIceCandidates.clear()

            // Create answer with correct isVideo flag
            val sdpAnswer = createAnswer(incomingPayload.isVideo)

            // Log answer SDP details
            val answerHasVideo = sdpAnswer.contains("m=video")
            val answerHasAudio = sdpAnswer.contains("m=audio")
            Logger.i("[CallService] SDP Answer created - hasAudio=$answerHasAudio, hasVideo=$answerHasVideo, isVideoCall=${incomingPayload.isVideo}")
            if (incomingPayload.isVideo && !answerHasVideo) {
                Logger.e("[CallService] CRITICAL: Video call but answer has NO m=video line! localVideoTrack=${localVideoTrack != null}")
            }

            // Encrypt answer
            val nonce = cryptoService.generateNonce()
            val ciphertext = cryptoService.boxSeal(
                sdpAnswer.toByteArray(Charsets.UTF_8),
                nonce,
                senderPublicKey,
                encPrivateKey
            )

            val timestamp = System.currentTimeMillis()

            // Sign
            val signature = cryptoService.signMessage(
                Constants.MsgType.CALL_ANSWER,
                incomingPayload.callId,
                whisperId,
                incomingPayload.from,
                timestamp,
                nonce,
                ciphertext,
                signPrivateKey
            )

            // Send answer
            val payload = CallAnswerPayload(
                sessionToken = sessionToken,
                callId = incomingPayload.callId,
                from = whisperId,
                to = incomingPayload.from,
                timestamp = timestamp,
                nonce = Base64.encodeToString(nonce, Base64.NO_WRAP),
                ciphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
                sig = Base64.encodeToString(signature, Base64.NO_WRAP)
            )

            wsClient.send(WsFrame(Constants.MsgType.CALL_ANSWER, payload = payload))
            localSdpSent = true

            // Flush any pending local ICE candidates
            pendingLocalIceCandidates.forEach { sendIceCandidate(it) }
            pendingLocalIceCandidates.clear()

            // Update state
            _activeCall.value = ActiveCall(
                callId = incomingPayload.callId,
                peerId = incomingPayload.from,
                peerName = contact?.displayName,
                isVideo = incomingPayload.isVideo,
                isOutgoing = false
            )
            _callState.value = CallState.Connecting

            // Persist active call info for cleanup on app restart
            secureStorage.activeCallId = incomingPayload.callId
            secureStorage.activeCallPeerId = incomingPayload.from

            pendingIncomingCall = null

            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e("Failed to answer call", e)
            cleanup()
            Result.failure(e)
        }
    }

    // MARK: - Decline Call

    suspend fun declineCall() {
        val incomingPayload = pendingIncomingCall ?: return
        endCall(CallEndReason.DECLINED)
        pendingIncomingCall = null
    }

    // MARK: - End Call

    // Flag to track if call was ended by remote party (prevents duplicate call_end)
    private var callEndedByRemote = false

    suspend fun endCall(reason: CallEndReason = CallEndReason.ENDED) {
        // Prevent sending duplicate call_end if already ended by remote
        if (callEndedByRemote) {
            Logger.i("[CallService] Call already ended by remote, skipping endCall() to prevent duplicate")
            callEndedByRemote = false  // Reset for next call
            return
        }

        val call = _activeCall.value ?: pendingIncomingCall?.let {
            ActiveCall(it.callId, it.from, null, it.isVideo, false)
        } ?: return

        val whisperId = secureStorage.whisperId ?: return
        val sessionToken = secureStorage.sessionToken ?: return
        val encPrivateKey = secureStorage.encPrivateKey ?: return
        val signPrivateKey = secureStorage.signPrivateKey ?: return

        val contact = contactDao.getContactById(call.peerId)
        val recipientPublicKey = contact?.encPublicKey?.let {
            Base64.decode(it.replace(" ", "+").trim(), Base64.NO_WRAP)
        }

        if (recipientPublicKey != null) {
            try {
                val message = "end"
                val nonce = cryptoService.generateNonce()
                val ciphertext = cryptoService.boxSeal(
                    message.toByteArray(Charsets.UTF_8),
                    nonce,
                    recipientPublicKey,
                    encPrivateKey
                )

                val timestamp = System.currentTimeMillis()

                val signature = cryptoService.signMessage(
                    Constants.MsgType.CALL_END,
                    call.callId,
                    whisperId,
                    call.peerId,
                    timestamp,
                    nonce,
                    ciphertext,
                    signPrivateKey
                )

                val reasonString = reason.name.lowercase()
                Logger.i("[CallService] Sending call_end with reason: '$reasonString' (enum: $reason)")

                val payload = CallEndPayload(
                    sessionToken = sessionToken,
                    callId = call.callId,
                    from = whisperId,
                    to = call.peerId,
                    timestamp = timestamp,
                    nonce = Base64.encodeToString(nonce, Base64.NO_WRAP),
                    ciphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
                    sig = Base64.encodeToString(signature, Base64.NO_WRAP),
                    reason = reasonString
                )

                wsClient.send(WsFrame(Constants.MsgType.CALL_END, payload = payload))
            } catch (e: Exception) {
                Logger.e("Failed to send call end", e)
            }
        }

        // Clear persisted call info
        secureStorage.clearActiveCall()

        _callState.value = CallState.Ended(reason)
        recordCallToHistory(call, reason)

        // Stop foreground service
        CallForegroundService.stopService(context)

        cleanupResources()  // Don't reset state - let screen see Ended state
    }

    // MARK: - Call Controls

    fun toggleMute() {
        localAudioTrack?.setEnabled(!(localAudioTrack?.enabled() ?: false))
        _activeCall.value = _activeCall.value?.copy(isMuted = !(localAudioTrack?.enabled() ?: true))
    }

    fun toggleSpeaker() {
        val currentCall = _activeCall.value ?: return
        val newSpeakerState = !currentCall.isSpeakerOn

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            if (newSpeakerState) {
                val speakerDevice = devices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speakerDevice != null) {
                    val result = audioManager.setCommunicationDevice(speakerDevice)
                    Logger.i("[CallService] setCommunicationDevice (Speaker) result: $result")
                }
            } else {
                audioManager.clearCommunicationDevice()
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = newSpeakerState
        }

        _activeCall.value = currentCall.copy(isSpeakerOn = newSpeakerState)
    }

    fun toggleLocalVideo() {
        localVideoTrack?.setEnabled(!(localVideoTrack?.enabled() ?: false))
        _activeCall.value = _activeCall.value?.copy(isLocalVideoEnabled = localVideoTrack?.enabled() ?: false)
    }

    fun switchCamera() {
        videoCapturer?.switchCamera(null)
    }

    fun setLocalVideoSink(sink: VideoSink?) {
        synchronized(this) {
            Logger.i("[CallService] setLocalVideoSink called: sink=${if (sink != null) "SET" else "NULL"}, localVideoTrack=${if (localVideoTrack != null) "EXISTS" else "NULL"}")

            // Remove old sink from track before setting new one (prevents GL crash on released renderer)
            val oldSink = localVideoSink
            if (oldSink != null && oldSink != sink) {
                localVideoTrack?.let { track ->
                    try {
                        Logger.i("[CallService] Removing old local video sink from track")
                        track.removeSink(oldSink)
                    } catch (e: Exception) {
                        Logger.w("[CallService] Error removing old local sink: ${e.message}")
                    }
                }
            }

            localVideoSink = sink
            if (sink != null) {
                localVideoTrack?.let { track ->
                    Logger.i("[CallService] Adding local video sink to existing track")
                    track.addSink(sink)
                }
            }
        }
    }

    fun setRemoteVideoSink(sink: VideoSink?) {
        synchronized(this) {
            Logger.i("[CallService] setRemoteVideoSink called: sink=${if (sink != null) "SET" else "NULL"}, remoteVideoTrack=${if (remoteVideoTrack != null) "EXISTS" else "NULL"}, sinkAdded=$remoteVideoSinkAdded")

            // Remove old sink from track before setting new one (prevents GL crash on released renderer)
            val oldSink = remoteVideoSink
            if (oldSink != null && oldSink != sink) {
                remoteVideoTrack?.let { track ->
                    try {
                        Logger.i("[CallService] Removing old remote video sink from track")
                        track.removeSink(oldSink)
                    } catch (e: Exception) {
                        Logger.w("[CallService] Error removing old remote sink: ${e.message}")
                    }
                }
                remoteVideoSinkAdded = false  // Reset flag since we removed the sink
            }

            remoteVideoSink = sink
            // If remote track already received and sink not yet added, add sink now
            if (sink != null && !remoteVideoSinkAdded) {
                remoteVideoTrack?.let { track ->
                    Logger.i("[CallService] Adding sink to existing remote video track, enabled=${track.enabled()}")
                    track.setEnabled(true)
                    track.addSink(sink)
                    remoteVideoSinkAdded = true
                } ?: Logger.w("[CallService] setRemoteVideoSink: no remoteVideoTrack yet, will add when track arrives")
            }
        }
    }

    // MARK: - WebRTC Setup

    private fun createPeerConnection(isVideo: Boolean) {
        val factory = peerConnectionFactory ?: return

        val iceServers = mutableListOf<PeerConnection.IceServer>()

        // TURN credentials are REQUIRED for relay-only mode
        // Check if credentials exist AND are still valid
        if (!areTurnCredentialsValid()) {
            Logger.e("[CallService] Cannot create peer connection: TURN credentials not available or expired")
            // Request TURN credentials and retry
            scope.launch {
                if (fetchAndWaitForTurnCredentials(5000)) {
                    createPeerConnection(isVideo)
                } else {
                    Logger.e("[CallService] Failed to get TURN credentials, cannot proceed")
                    _callState.value = CallState.Ended(CallEndReason.FAILED)
                }
            }
            return
        }

        val turn = _turnCredentials.value!!

        // Add TURN servers
        turn.urls.forEach { url ->
            iceServers.add(
                PeerConnection.IceServer.builder(url)
                    .setUsername(turn.username)
                    .setPassword(turn.credential)
                    .createIceServer()
            )
        }

        if (iceServers.isEmpty()) {
            Logger.e("[CallService] No ICE servers configured, cannot create peer connection")
            _callState.value = CallState.Ended(CallEndReason.FAILED)
            return
        }

        Logger.i("[CallService] Creating peer connection with ${iceServers.size} TURN servers")

        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            // Force all traffic through TURN relay - no direct P2P connections
            iceTransportsType = PeerConnection.IceTransportsType.RELAY
            // Reliability settings
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE      // Bundle all media into single transport
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE      // Multiplex RTP/RTCP on same port
            iceCandidatePoolSize = 1                                   // Pre-gather candidates for faster connection
        }

        peerConnection = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                Logger.d("Signaling state: $state")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Logger.i("[CallService] ICE connection state changed: $state")
                scope.launch {
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> {
                            // RULE 1: Only set Connected when media is actually flowing
                            Logger.i("[CallService] ICE CONNECTED/COMPLETED - transitioning to Connected state")
                            checkAndSetConnected()
                        }
                        PeerConnection.IceConnectionState.CHECKING -> {
                            Logger.i("[CallService] ICE CHECKING - connection in progress")
                            // Start a fallback timer in case CONNECTED event doesn't fire
                            startConnectionFallbackTimer()
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            _callState.value = CallState.Reconnecting
                        }
                        PeerConnection.IceConnectionState.FAILED -> {
                            endCall(CallEndReason.FAILED)
                        }
                        else -> {}
                    }
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                Logger.d("ICE gathering state: $state")
            }

            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    scope.launch {
                        // RULE 3: Buffer local ICE until SDP is sent
                        if (localSdpSent) {
                            sendIceCandidate(it)
                        } else {
                            Logger.d("Buffering local ICE candidate until SDP sent")
                            pendingLocalIceCandidates.add(it)
                        }
                    }
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

            override fun onAddStream(stream: MediaStream?) {
                Logger.i("[CallService] onAddStream: video tracks=${stream?.videoTracks?.size}, audio tracks=${stream?.audioTracks?.size}")

                // CRITICAL: Store reference to prevent GC from disposing the stream and its tracks!
                remoteMediaStream = stream

                // Handle audio track
                stream?.audioTracks?.firstOrNull()?.let { audioTrack ->
                    Logger.i("[CallService] onAddStream: got remote audio track, enabled=${audioTrack.enabled()}")
                    audioTrack.setEnabled(true)
                    remoteAudioTrackReceived = true
                    scope.launch { checkAndSetConnected() }
                }

                // Handle video track
                stream?.videoTracks?.firstOrNull()?.let { videoTrack ->
                    Logger.i("[CallService] onAddStream: GOT REMOTE VIDEO TRACK! enabled=${videoTrack.enabled()}, id=${videoTrack.id()}")
                    videoTrack.setEnabled(true)
                    synchronized(this@CallService) {
                        remoteVideoTrack = videoTrack
                        remoteVideoTrackReceived = true
                        // Only add sink if not already added (onAddTrack may have already done it)
                        if (!remoteVideoSinkAdded) {
                            remoteVideoSink?.let { sink ->
                                Logger.i("[CallService] Adding remote video sink from onAddStream")
                                videoTrack.addSink(sink)
                                remoteVideoSinkAdded = true
                            } ?: Logger.w("[CallService] onAddStream: remoteVideoSink is NULL, will add sink later")
                        } else {
                            Logger.i("[CallService] onAddStream: sink already added by onAddTrack, skipping")
                        }
                    }
                    scope.launch { checkAndSetConnected() }
                }
            }

            override fun onRemoveStream(stream: MediaStream?) {
                remoteVideoTrack = null
                remoteMediaStream = null
            }

            override fun onDataChannel(channel: DataChannel?) {}

            override fun onRenegotiationNeeded() {}

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                val trackKind = receiver?.track()?.kind()
                val trackId = receiver?.track()?.id()
                val trackEnabled = receiver?.track()?.enabled()
                Logger.i("[CallService] onAddTrack: kind=$trackKind, id=$trackId, enabled=$trackEnabled, streams=${streams?.size}")

                receiver?.track()?.let { track ->
                    when (track) {
                        is VideoTrack -> {
                            Logger.i("[CallService] onAddTrack: GOT REMOTE VIDEO TRACK! enabled=${track.enabled()}, id=${track.id()}")
                            track.setEnabled(true)
                            synchronized(this@CallService) {
                                remoteVideoTrack = track
                                remoteVideoTrackReceived = true
                                // Only add sink if not already added
                                if (!remoteVideoSinkAdded) {
                                    remoteVideoSink?.let { sink ->
                                        Logger.i("[CallService] Adding remote video sink to track NOW")
                                        track.addSink(sink)
                                        remoteVideoSinkAdded = true
                                    } ?: Logger.w("[CallService] onAddTrack: remoteVideoSink is NULL! Will add sink when UI sets it")
                                } else {
                                    Logger.i("[CallService] onAddTrack: sink already added, skipping")
                                }
                            }
                            scope.launch { checkAndSetConnected() }
                        }
                        is AudioTrack -> {
                            Logger.i("[CallService] onAddTrack: got remote audio track, enabled=${track.enabled()}")
                            track.setEnabled(true)
                            remoteAudioTrackReceived = true
                            scope.launch { checkAndSetConnected() }
                        }
                    }
                }
            }
        })

        // Add audio track
        val audioConstraints = MediaConstraints()
        val audioSource = factory.createAudioSource(audioConstraints)
        localAudioTrack = factory.createAudioTrack("audio0", audioSource)
        localAudioTrack?.let { track ->
            peerConnection?.addTrack(track, listOf("stream0"))
        }

        // Add video track if video call
        if (isVideo) {
            val eglContext = eglBase?.eglBaseContext
            if (eglContext == null) {
                Logger.e("[CallService] EGL context is null, cannot create video track")
                return
            }

            try {
                surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglContext)
                if (surfaceTextureHelper == null) {
                    Logger.e("[CallService] Failed to create SurfaceTextureHelper")
                    return
                }

                val videoSource = factory.createVideoSource(false)
                videoCapturer = createCameraCapturer()

                if (videoCapturer == null) {
                    Logger.e("[CallService] Failed to create camera capturer - no camera available")
                    // Continue without video - don't crash the call
                } else {
                    videoCapturer?.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)

                    try {
                        videoCapturer?.startCapture(1280, 720, 30)
                        Logger.i("[CallService] Camera capture started successfully")
                    } catch (e: Exception) {
                        Logger.e("[CallService] Failed to start camera capture", e)
                        // Continue without video capture
                        try {
                            videoCapturer?.dispose()
                        } catch (ignored: Exception) {}
                        videoCapturer = null
                    }
                }

                // Only create video track if camera capturer succeeded
                if (videoCapturer != null) {
                    localVideoTrack = factory.createVideoTrack("video0", videoSource)
                    localVideoTrack?.let { track ->
                        peerConnection?.addTrack(track, listOf("stream0"))
                        Logger.i("[CallService] Local video track added to peer connection")
                        synchronized(this@CallService) {
                            localVideoSink?.let { sink ->
                                track.addSink(sink)
                                Logger.i("[CallService] Local video sink attached (was waiting)")
                            } ?: Logger.w("[CallService] No local video sink set yet - will attach when UI sets it")
                        }
                    }
                } else {
                    Logger.e("[CallService] Skipping video track creation - no camera capturer")
                }
            } catch (e: Exception) {
                Logger.e("[CallService] Failed to initialize video components", e)
                // Clean up partial video state
                try {
                    videoCapturer?.stopCapture()
                } catch (ignored: Exception) {}
                try {
                    videoCapturer?.dispose()
                } catch (ignored: Exception) {}
                videoCapturer = null
                surfaceTextureHelper?.dispose()
                surfaceTextureHelper = null
                // Don't return - continue with audio-only if video fails
            }
        }
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        try {
            val camera2Enumerator = Camera2Enumerator(context)
            val deviceNames = camera2Enumerator.deviceNames

            // Try front camera first
            for (deviceName in deviceNames) {
                if (camera2Enumerator.isFrontFacing(deviceName)) {
                    return camera2Enumerator.createCapturer(deviceName, null)
                }
            }

            // Fall back to any camera
            for (deviceName in deviceNames) {
                return camera2Enumerator.createCapturer(deviceName, null)
            }
        } catch (e: Exception) {
            Logger.e("[CallService] Failed to create camera capturer", e)
        }
        return null
    }

    private suspend fun createOffer(isVideo: Boolean): String = suspendCancellableCoroutine { cont ->
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (isVideo) "true" else "false"))
        }
        Logger.d("[CallService] createOffer: isVideo=$isVideo, OfferToReceiveVideo=${if (isVideo) "true" else "false"}")

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                // Log SDP to verify m=video line exists
                var sdp = desc?.description ?: ""
                val hasVideoMLine = sdp.contains("m=video")
                Logger.i("[CallService] Created offer - hasVideoMLine=$hasVideoMLine")
                if (isVideo && !hasVideoMLine) {
                    Logger.e("[CallService] WARNING: Video call but SDP has no m=video line!")
                }

                // Prefer H.264 codec - better hardware decoder support on most Android devices
                // VP8 hardware decoder on some devices (MediaTek) has JNI issues
                if (isVideo) {
                    sdp = preferH264Codec(sdp)
                }

                val modifiedDesc = SessionDescription(desc?.type, sdp)
                peerConnection?.setLocalDescription(SdpObserverAdapter(), modifiedDesc)
                cont.resume(sdp) {}
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                cont.resumeWith(Result.failure(Exception(error)))
            }
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    private suspend fun createAnswer(isVideo: Boolean): String = suspendCancellableCoroutine { cont ->
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (isVideo) "true" else "false"))
        }
        Logger.d("[CallService] createAnswer: isVideo=$isVideo, OfferToReceiveVideo=${if (isVideo) "true" else "false"}")

        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                // Log SDP to verify m=video line exists
                var sdp = desc?.description ?: ""
                val hasVideoMLine = sdp.contains("m=video")
                Logger.i("[CallService] Created answer - hasVideoMLine=$hasVideoMLine")
                if (isVideo && !hasVideoMLine) {
                    Logger.e("[CallService] WARNING: Video call but answer SDP has no m=video line!")
                }

                // Prefer H.264 codec - better hardware decoder support on most Android devices
                // VP8 hardware decoder on some devices (MediaTek) has JNI issues
                if (isVideo) {
                    sdp = preferH264Codec(sdp)
                }

                val modifiedDesc = SessionDescription(desc?.type, sdp)
                peerConnection?.setLocalDescription(SdpObserverAdapter(), modifiedDesc)
                cont.resume(sdp) {}
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                cont.resumeWith(Result.failure(Exception(error)))
            }
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    /**
     * Prefer H.264 codec for better hardware decoder support.
     * VP8 hardware decoder on some devices (MediaTek) has JNI issues.
     * H.264 has wider hardware support.
     */
    private fun preferH264Codec(sdp: String): String {
        val lines = sdp.split("\r\n").toMutableList()
        var videoMLineIndex = -1
        var h264PayloadType: String? = null

        // Find the m=video line and H.264 payload type
        for (i in lines.indices) {
            val line = lines[i]
            if (line.startsWith("m=video")) {
                videoMLineIndex = i
            }
            // Find H.264 payload type from rtpmap
            if (line.contains("H264/90000")) {
                val match = Regex("a=rtpmap:(\\d+) H264/90000").find(line)
                h264PayloadType = match?.groupValues?.get(1)
                Logger.d("[CallService] Found H264 payload type: $h264PayloadType")
            }
        }

        if (videoMLineIndex == -1 || h264PayloadType == null) {
            Logger.d("[CallService] No video line or H264 not found, returning original SDP")
            return sdp
        }

        // Reorder payload types in m=video line to put H.264 first
        val mLine = lines[videoMLineIndex]
        val parts = mLine.split(" ").toMutableList()
        if (parts.size > 3) {
            // Format: m=video PORT PROTO PAYLOAD_TYPES...
            val payloadTypes = parts.subList(3, parts.size).toMutableList()
            if (payloadTypes.remove(h264PayloadType)) {
                payloadTypes.add(0, h264PayloadType)
                val newMLine = parts.subList(0, 3).joinToString(" ") + " " + payloadTypes.joinToString(" ")
                lines[videoMLineIndex] = newMLine
                Logger.i("[CallService] Reordered codecs to prefer H264: $newMLine")
            }
        }

        return lines.joinToString("\r\n")
    }

    /**
     * Log all transceivers for debugging
     */
    private fun logTransceivers(context: String) {
        peerConnection?.transceivers?.forEachIndexed { index, transceiver ->
            val mid = transceiver.mid
            val direction = transceiver.direction
            val currentDirection = transceiver.currentDirection
            val mediaType = transceiver.mediaType
            val receiver = transceiver.receiver
            val trackKind = receiver.track()?.kind()
            val trackEnabled = receiver.track()?.enabled()
            Logger.i("[CallService] Transceiver[$index] $context: mid=$mid, mediaType=$mediaType, direction=$direction, currentDirection=$currentDirection, trackKind=$trackKind, trackEnabled=$trackEnabled")
        }
    }

    /**
     * RULE 2: Audio routing must start BEFORE SDP/createPeerConnection
     */
    private fun initializeAudioRouting(isVideo: Boolean) {
        Logger.d("[CallService] Initializing audio routing, isVideo=$isVideo")

        // Request audio focus
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .build()
            audioManager.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }

        // Set communication mode BEFORE creating peer connection
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        // Speaker on for video, off for audio
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = isVideo

        Logger.d("[CallService] Audio routing initialized: mode=${audioManager.mode}, speaker=$isVideo")
    }

    private fun configureAudioSession() {
        // Legacy - now handled by initializeAudioRouting
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = _activeCall.value?.isVideo == true
    }

    // MARK: - ICE Candidate

    private suspend fun sendIceCandidate(candidate: IceCandidate) {
        val call = _activeCall.value ?: return
        val whisperId = secureStorage.whisperId ?: return
        val sessionToken = secureStorage.sessionToken ?: return
        val encPrivateKey = secureStorage.encPrivateKey ?: return
        val signPrivateKey = secureStorage.signPrivateKey ?: return

        val contact = contactDao.getContactById(call.peerId)
        val recipientPublicKey = contact?.encPublicKey?.let {
            Base64.decode(it.replace(" ", "+").trim(), Base64.NO_WRAP)
        } ?: return

        try {
            val candidateJson = JSONObject().apply {
                put("sdpMLineIndex", candidate.sdpMLineIndex)
                put("sdpMid", candidate.sdpMid)
                put("candidate", candidate.sdp)
            }.toString()

            val nonce = cryptoService.generateNonce()
            val ciphertext = cryptoService.boxSeal(
                candidateJson.toByteArray(Charsets.UTF_8),
                nonce,
                recipientPublicKey,
                encPrivateKey
            )

            val timestamp = System.currentTimeMillis()

            val signature = cryptoService.signMessage(
                Constants.MsgType.CALL_ICE_CANDIDATE,
                call.callId,
                whisperId,
                call.peerId,
                timestamp,
                nonce,
                ciphertext,
                signPrivateKey
            )

            val payload = CallIceCandidatePayload(
                sessionToken = sessionToken,
                callId = call.callId,
                from = whisperId,
                to = call.peerId,
                timestamp = timestamp,
                nonce = Base64.encodeToString(nonce, Base64.NO_WRAP),
                ciphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
                sig = Base64.encodeToString(signature, Base64.NO_WRAP)
            )

            wsClient.send(WsFrame(Constants.MsgType.CALL_ICE_CANDIDATE, payload = payload))
        } catch (e: Exception) {
            Logger.e("Failed to send ICE candidate", e)
        }
    }

    // MARK: - Message Handling

    private fun handleMessage(frame: WsFrame<JsonElement>) {
        Logger.d("[CallService] Received message: ${frame.type}")
        when (frame.type) {
            Constants.MsgType.TURN_CREDENTIALS -> handleTurnCredentials(frame)
            Constants.MsgType.CALL_INCOMING -> {
                Logger.i("[CallService] INCOMING CALL RECEIVED!")
                handleCallIncoming(frame)
            }
            Constants.MsgType.CALL_ANSWER -> handleCallAnswer(frame)
            Constants.MsgType.CALL_ICE_CANDIDATE -> handleIceCandidate(frame)
            Constants.MsgType.CALL_END -> handleCallEnd(frame)
            Constants.MsgType.CALL_RINGING -> handleCallRinging()
        }
    }

    private fun handleTurnCredentials(frame: WsFrame<JsonElement>) {
        try {
            val payload = gson.fromJson(frame.payload, TurnCredentialsPayload::class.java)
            _turnCredentials.value = payload
            turnCredentialsReceivedAt = System.currentTimeMillis()
            Logger.i("[CallService] TURN credentials received, TTL=${payload.ttl}s")
        } catch (e: Exception) {
            Logger.e("Failed to parse TURN credentials", e)
        }
    }

    /**
     * Check if cached TURN credentials are still valid.
     * Returns true if credentials exist and haven't expired (with 60s buffer).
     */
    private fun areTurnCredentialsValid(): Boolean {
        val creds = _turnCredentials.value ?: return false
        val elapsedSeconds = (System.currentTimeMillis() - turnCredentialsReceivedAt) / 1000
        val isValid = elapsedSeconds < (creds.ttl - 60)  // 60 second buffer before expiry
        if (!isValid) {
            Logger.i("[CallService] TURN credentials expired (elapsed=${elapsedSeconds}s, ttl=${creds.ttl}s)")
        }
        return isValid
    }

    private fun handleCallIncoming(frame: WsFrame<JsonElement>) {
        try {
            Logger.d("[CallService] Parsing incoming call payload")

            // SAFE BOOLEAN EXTRACTION
            var isVideoFromJson = false
            try {
                if (frame.payload.isJsonObject) {
                    val jsonObj = frame.payload.asJsonObject
                    if (jsonObj.has("isVideo")) {
                        isVideoFromJson = jsonObj.get("isVideo").asBoolean
                    }
                }
            } catch (e: Exception) {
                Logger.e("[CallService] Manual JSON parsing failed, relying on Gson", e)
            }

            val payload = gson.fromJson(frame.payload, CallIncomingPayload::class.java)

            // Combine parsed data safely
            val finalIsVideo = if (frame.payload.isJsonObject && frame.payload.asJsonObject.has("isVideo")) {
                isVideoFromJson
            } else {
                payload.isVideo
            }

            val correctedPayload = payload.copy(isVideo = finalIsVideo)
            pendingIncomingCall = correctedPayload

            Logger.i("[CallService] Incoming Call processed - isVideo: $finalIsVideo")

            scope.launch {
                val contact = contactDao.getContactById(correctedPayload.from)
                Logger.d("[CallService] Contact lookup: ${contact?.displayName ?: "not found"}")

                _activeCall.value = ActiveCall(
                    callId = correctedPayload.callId,
                    peerId = correctedPayload.from,
                    peerName = contact?.displayName,
                    isVideo = correctedPayload.isVideo,
                    isOutgoing = false
                )
                _callState.value = CallState.Ringing
                Logger.i("[CallService] ActiveCall created with isVideo=${correctedPayload.isVideo}")

                // Pre-fetch TURN credentials while user is seeing the incoming call UI
                // This ensures credentials are ready when user answers
                if (_turnCredentials.value == null) {
                    Logger.i("[CallService] Pre-fetching TURN credentials for incoming call")
                    fetchTurnCredentials()
                }

                // Start foreground service to show incoming call notification with ringtone/vibration
                try {
                    Logger.i("[CallService] Starting CallForegroundService for incoming call")
                    CallForegroundService.startIncomingCall(
                        context = context,
                        callId = correctedPayload.callId,
                        callerId = correctedPayload.from,
                        callerName = contact?.displayName,
                        isVideo = correctedPayload.isVideo
                    )
                } catch (e: Exception) {
                    Logger.e("[CallService] Failed to start CallForegroundService", e)
                }
            }
        } catch (e: Exception) {
            Logger.e("[CallService] handleCallIncoming CRASHED", e)
        }
    }

    private fun handleCallAnswer(frame: WsFrame<JsonElement>) {
        try {
            // Server sends minimal payload: { callId, from, timestamp, nonce, ciphertext, sig }
            val payload = gson.fromJson(frame.payload, CallAnswerNotificationPayload::class.java)
            val encPrivateKey = secureStorage.encPrivateKey ?: return

            Logger.d("Received call_answer: callId=${payload.callId}, from=${payload.from}")

            scope.launch {
                val contact = contactDao.getContactById(payload.from)
                val senderPublicKey = contact?.encPublicKey?.let {
                    Base64.decode(it.replace(" ", "+").trim(), Base64.NO_WRAP)
                } ?: return@launch

                // Decrypt SDP answer
                val ciphertextData = Base64.decode(payload.ciphertext, Base64.NO_WRAP)
                val nonceData = Base64.decode(payload.nonce, Base64.NO_WRAP)

                val sdpAnswer = String(
                    cryptoService.boxOpen(ciphertextData, nonceData, senderPublicKey, encPrivateKey),
                    Charsets.UTF_8
                )

                // Log received answer SDP to verify m=video line
                val hasVideoMLine = sdpAnswer.contains("m=video")
                val isVideo = _activeCall.value?.isVideo == true
                Logger.i("[CallService] Received answer SDP - isVideo=$isVideo, hasVideoMLine=$hasVideoMLine")
                if (isVideo && !hasVideoMLine) {
                    Logger.e("[CallService] WARNING: Video call but received answer has no m=video line!")
                }

                val remoteDesc = SessionDescription(SessionDescription.Type.ANSWER, sdpAnswer)
                peerConnection?.setRemoteDescription(SdpObserverAdapter(), remoteDesc)

                // Log transceivers after remote SDP is set
                logTransceivers("after setRemoteDescription (answer)")

                // Process pending remote ICE candidates
                pendingRemoteIceCandidates.forEach { candidate ->
                    peerConnection?.addIceCandidate(candidate)
                }
                pendingRemoteIceCandidates.clear()

                _callState.value = CallState.Connecting
            }
        } catch (e: Exception) {
            Logger.e("Failed to handle call answer", e)
        }
    }

    private fun handleIceCandidate(frame: WsFrame<JsonElement>) {
        try {
            // Server sends minimal payload: { callId, from, timestamp, nonce, ciphertext, sig }
            val payload = gson.fromJson(frame.payload, CallIceCandidateNotificationPayload::class.java)
            val encPrivateKey = secureStorage.encPrivateKey ?: return

            scope.launch {
                val contact = contactDao.getContactById(payload.from)
                val senderPublicKey = contact?.encPublicKey?.let {
                    Base64.decode(it.replace(" ", "+").trim(), Base64.NO_WRAP)
                } ?: return@launch

                val ciphertextData = Base64.decode(payload.ciphertext, Base64.NO_WRAP)
                val nonceData = Base64.decode(payload.nonce, Base64.NO_WRAP)

                val candidateString = String(
                    cryptoService.boxOpen(ciphertextData, nonceData, senderPublicKey, encPrivateKey),
                    Charsets.UTF_8
                )

                val json = JSONObject(candidateString)
                val sdp = json.getString("candidate")
                val sdpMLineIndex = json.getInt("sdpMLineIndex")
                val sdpMid = json.optString("sdpMid")

                val candidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)

                // RULE 3: If peer connection doesn't exist or remote description not set, buffer
                if (peerConnection == null || peerConnection?.remoteDescription == null) {
                    Logger.d("Buffering remote ICE candidate until PC + remote SDP ready")
                    pendingRemoteIceCandidates.add(candidate)
                } else {
                    peerConnection?.addIceCandidate(candidate)
                }
            }
        } catch (e: Exception) {
            Logger.e("Failed to handle ICE candidate", e)
        }
    }

    private fun handleCallEnd(frame: WsFrame<JsonElement>) {
        try {
            // Server sends minimal payload: { callId, from, reason }
            val payload = gson.fromJson(frame.payload, CallEndNotificationPayload::class.java)
            val reason = try {
                CallEndReason.valueOf(payload.reason.uppercase())
            } catch (e: Exception) {
                CallEndReason.ENDED
            }

            Logger.d("Received call_end: callId=${payload.callId}, from=${payload.from}, reason=${payload.reason}")

            // Mark that call was ended by remote to prevent duplicate call_end messages
            // This flag will be checked if endCall() is called (e.g., from UI cleanup or notification)
            callEndedByRemote = true

            scope.launch {
                _activeCall.value?.let { call ->
                    recordCallToHistory(call, reason)
                }

                // Clear persisted call info
                secureStorage.clearActiveCall()

                _callState.value = CallState.Ended(reason)

                // Remote ended - clean up foreground service
                CallForegroundService.stopService(context)

                cleanupResources()  // Don't reset state - let screen see Ended state
            }
        } catch (e: Exception) {
            Logger.e("Failed to handle call end", e)
        }
    }

    private fun handleCallRinging() {
        _callState.value = CallState.Ringing
    }

    /**
     * Check if we can transition to Connected state.
     * For audio calls: ICE connected is sufficient (track events may not fire reliably)
     * For video calls: ICE connected + remote video track received
     */
    private fun checkAndSetConnected() {
        val iceState = peerConnection?.iceConnectionState()
        val isIceConnected = iceState == PeerConnection.IceConnectionState.CONNECTED ||
                             iceState == PeerConnection.IceConnectionState.COMPLETED

        val isVideo = _activeCall.value?.isVideo == true

        // For audio calls: ICE connected is enough (audio flows automatically)
        // For video calls: need video track to show remote video
        val mediaReady = if (isVideo) {
            // For video, we need the track to render video, but if ICE is connected
            // audio should be working, so we can still transition
            remoteVideoTrackReceived || remoteAudioTrackReceived
        } else {
            // For audio calls, ICE connected means audio is flowing
            // Track events may not fire reliably on all devices
            true
        }

        Logger.d("[CallService] checkAndSetConnected: ICE=$isIceConnected, isVideo=$isVideo, mediaReady=$mediaReady, audioTrack=$remoteAudioTrackReceived, videoTrack=$remoteVideoTrackReceived")

        if (isIceConnected && mediaReady && _callState.value != CallState.Connected) {
            Logger.i("[CallService] ICE connected - setting state to Connected (isVideo=$isVideo)")
            _callState.value = CallState.Connected
            _activeCall.value?.let { call ->
                _activeCall.value = call.copy(startTime = System.currentTimeMillis())
            }
            startCallDurationTimer()
        }
    }

    // MARK: - Connection Fallback Timer

    private var connectionFallbackJob: Job? = null

    /**
     * Fallback timer to transition to Connected state if ICE CONNECTED event doesn't fire.
     * Some devices/networks don't reliably report ICE state changes.
     */
    private fun startConnectionFallbackTimer() {
        connectionFallbackJob?.cancel()
        connectionFallbackJob = scope.launch {
            delay(3000) // Wait 3 seconds
            val iceState = peerConnection?.iceConnectionState()
            Logger.i("[CallService] Connection fallback timer fired - ICE state: $iceState, callState: ${_callState.value}")

            // If we're still in Connecting state but ICE is in a reasonable state, force Connected
            if (_callState.value == CallState.Connecting) {
                when (iceState) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED,
                    PeerConnection.IceConnectionState.CHECKING -> {
                        Logger.i("[CallService] Fallback: forcing Connected state (ICE=$iceState)")
                        _callState.value = CallState.Connected
                        _activeCall.value?.let { call ->
                            _activeCall.value = call.copy(startTime = System.currentTimeMillis())
                        }
                        startCallDurationTimer()
                    }
                    else -> {
                        Logger.w("[CallService] Fallback: ICE state $iceState not suitable for Connected")
                    }
                }
            }
        }
    }

    // MARK: - Call Duration Timer

    private fun startCallDurationTimer() {
        callDurationJob?.cancel()
        connectionFallbackJob?.cancel() // Cancel fallback if we're starting the timer
        callDurationJob = scope.launch {
            while (isActive && _callState.value == CallState.Connected) {
                delay(1000)
                _callDuration.value += 1
            }
        }
    }

    // MARK: - Call History

    private suspend fun recordCallToHistory(call: ActiveCall, reason: CallEndReason) {
        val status = when (reason) {
            CallEndReason.ENDED -> if (call.startTime != null) "completed" else "cancelled"
            CallEndReason.CANCELLED -> "cancelled"
            CallEndReason.DECLINED -> if (call.isOutgoing) "no_answer" else "declined"
            CallEndReason.BUSY -> "busy"
            CallEndReason.TIMEOUT -> if (call.isOutgoing) "no_answer" else "missed"
            CallEndReason.FAILED -> "failed"
        }

        val duration = call.startTime?.let {
            ((System.currentTimeMillis() - it) / 1000).toInt()
        }

        val record = CallRecordEntity(
            callId = call.callId,
            peerId = call.peerId,
            peerName = call.peerName,
            isVideo = call.isVideo,
            direction = if (call.isOutgoing) "outgoing" else "incoming",
            status = status,
            duration = duration,
            startedAt = call.startTime ?: System.currentTimeMillis(),
            endedAt = System.currentTimeMillis()
        )

        callRecordDao.insert(record)

        // Save call as a message in the conversation for chat history
        saveCallMessage(call, status, duration)
    }

    /**
     * Save a call record as a message in the conversation.
     * This allows call history to appear in the chat timeline.
     */
    private suspend fun saveCallMessage(call: ActiveCall, outcome: String, duration: Int?) {
        val whisperId = secureStorage.whisperId ?: return
        val timestamp = System.currentTimeMillis()

        // Ensure conversation exists
        val existingConversation = conversationDao.getConversationById(call.peerId)
        if (existingConversation == null) {
            // Create conversation if it doesn't exist
            val contact = contactDao.getContactById(call.peerId)
            val conversation = ConversationEntity(
                peerId = call.peerId,
                peerNickname = contact?.displayName ?: call.peerName,
                lastMessagePreview = formatCallSummary(call.isVideo, outcome, duration),
                lastMessageTimestamp = timestamp,
                unreadCount = 0
            )
            conversationDao.insert(conversation)
        } else {
            // Update conversation's last message
            conversationDao.updateLastMessage(
                conversationId = call.peerId,
                message = formatCallSummary(call.isVideo, outcome, duration),
                timestamp = timestamp
            )
        }

        // Create call metadata JSON
        val callMetadata = JSONObject().apply {
            put("type", if (call.isVideo) "video" else "voice")
            put("outcome", outcome)
            put("duration", duration ?: 0)
        }.toString()

        // Create message entity for the call
        val messageId = "${call.callId}-msg"
        val message = MessageEntity(
            id = messageId,
            conversationId = call.peerId,
            from = if (call.isOutgoing) whisperId else call.peerId,
            to = if (call.isOutgoing) call.peerId else whisperId,
            contentType = "call",
            content = callMetadata,
            timestamp = timestamp,
            status = "delivered",
            direction = if (call.isOutgoing) "outgoing" else "incoming"
        )

        messageDao.insert(message)
        Logger.i("[CallService] Saved call message to conversation: ${call.peerId}, outcome: $outcome")
    }

    /**
     * Format a brief summary of the call for conversation preview.
     */
    private fun formatCallSummary(isVideo: Boolean, outcome: String, duration: Int?): String {
        val callType = if (isVideo) "Video call" else "Voice call"
        return when (outcome) {
            "completed", "ended", "answered" -> {
                if (duration != null && duration > 0) {
                    "$callType (${formatDurationBrief(duration)})"
                } else {
                    callType
                }
            }
            "missed" -> "Missed $callType"
            "declined" -> "Declined $callType"
            "no_answer" -> "$callType - No answer"
            "cancelled" -> "Cancelled $callType"
            "busy" -> "$callType - Busy"
            "failed" -> "$callType - Failed"
            else -> callType
        }
    }

    /**
     * Format duration in seconds to brief human-readable string.
     */
    private fun formatDurationBrief(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60

        return when {
            hours > 0 -> String.format("%d:%02d:%02d", hours, minutes, secs)
            minutes > 0 -> String.format("%d:%02d", minutes, secs)
            else -> String.format("0:%02d", secs)
        }
    }

    // MARK: - Cleanup

    private fun cleanupResources() {
        callDurationJob?.cancel()
        callDurationJob = null
        connectionFallbackJob?.cancel()
        connectionFallbackJob = null
        _callDuration.value = 0
        answerCallTriggered.set(false)  // Reset for next call

        // Stop and dispose video capturer with proper exception handling
        try {
            videoCapturer?.stopCapture()
        } catch (e: Exception) {
            Logger.w("[CallService] Exception stopping video capturer: ${e.message}")
        }
        try {
            videoCapturer?.dispose()
        } catch (e: Exception) {
            Logger.w("[CallService] Exception disposing video capturer: ${e.message}")
        }
        videoCapturer = null

        try {
            surfaceTextureHelper?.dispose()
        } catch (e: Exception) {
            Logger.w("[CallService] Exception disposing surfaceTextureHelper: ${e.message}")
        }
        surfaceTextureHelper = null

        try {
            localVideoTrack?.dispose()
        } catch (e: Exception) {
            Logger.w("[CallService] Exception disposing localVideoTrack: ${e.message}")
        }
        localVideoTrack = null

        try {
            localAudioTrack?.dispose()
        } catch (e: Exception) {
            Logger.w("[CallService] Exception disposing localAudioTrack: ${e.message}")
        }
        localAudioTrack = null

        try {
            peerConnection?.close()
        } catch (e: Exception) {
            Logger.w("[CallService] Exception closing peer connection: ${e.message}")
        }
        peerConnection = null

        pendingRemoteIceCandidates.clear()
        pendingLocalIceCandidates.clear()
        pendingIncomingCall = null
        remoteVideoTrack = null
        remoteMediaStream = null

        // Reset flags
        localSdpSent = false
        remoteAudioTrackReceived = false
        remoteVideoTrackReceived = false
        remoteVideoSinkAdded = false
        callEndedByRemote = false  // Reset for next call

        // Reset audio
        audioManager.mode = AudioManager.MODE_NORMAL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
        }
    }

    private fun cleanup() {
        cleanupResources()
        secureStorage.clearActiveCall()
        _activeCall.value = null
        _callState.value = CallState.Idle
    }

    // Called after screen navigates away
    fun resetCallState() {
        _activeCall.value = null
        _callState.value = CallState.Idle
    }

    /**
     * Atomic flag to prevent double-calling answerCall() (thread-safe).
     * This can happen if UI and notification both trigger answer simultaneously.
     */
    private val answerCallTriggered = AtomicBoolean(false)
}

// SDP Observer adapter
private class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}
