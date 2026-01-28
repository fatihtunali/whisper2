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
import com.whisper2.app.data.local.db.dao.CallRecordDao
import com.whisper2.app.data.local.db.dao.ContactDao
import com.whisper2.app.data.local.db.entities.CallRecordEntity
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
    ENDED,      // Normal end or cancelled
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
    private val gson: Gson,
    private val telecomCallManager: TelecomCallManager,
    @ApplicationScope private val scope: CoroutineScope
) {
    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private val _activeCall = MutableStateFlow<ActiveCall?>(null)
    val activeCall: StateFlow<ActiveCall?> = _activeCall.asStateFlow()

    private val _turnCredentials = MutableStateFlow<TurnCredentialsPayload?>(null)

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

    // Pending ICE candidates (received before remote description set)
    private val pendingRemoteIceCandidates = mutableListOf<IceCandidate>()

    // Pending local ICE candidates (generated before offer sent)
    private val pendingLocalIceCandidates = mutableListOf<IceCandidate>()
    private var localSdpSent = false

    // Track received flags for proper Connected state
    private var remoteAudioTrackReceived = false
    private var remoteVideoTrackReceived = false

    // Incoming call payload (stored for answering)
    private var pendingIncomingCall: CallIncomingPayload? = null

    // Call duration timer
    private var callDurationJob: Job? = null
    private val _callDuration = MutableStateFlow(0L)
    val callDuration: StateFlow<Long> = _callDuration.asStateFlow()

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    init {
        setupWebRTC()
        setupMessageHandler()
        // Initialize Telecom integration (Android's CallKit equivalent)
        telecomCallManager.initialize()
    }

    private fun setupWebRTC() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        // Create shared EGL base
        eglBase = EglBase.create()
        val eglContext = eglBase!!.eglBaseContext

        // Use hardware encoder with fallback
        val encoderFactory = DefaultVideoEncoderFactory(
            eglContext,
            true,  // enableIntelVp8Encoder
            true   // enableH264HighProfile
        )

        // Create a robust decoder factory that handles H.264 from iOS
        // Use hardware decoder first (HardwareVideoDecoderFactory) with software fallback
        val hardwareDecoderFactory = HardwareVideoDecoderFactory(eglContext)
        val softwareDecoderFactory = SoftwareVideoDecoderFactory()

        // Wrap in a fallback factory that tries hardware first, then software
        val decoderFactory = object : VideoDecoderFactory {
            override fun createDecoder(codecInfo: VideoCodecInfo): VideoDecoder? {
                Logger.d("[CallService] Creating decoder for codec: ${codecInfo.name}")
                return try {
                    // Try hardware decoder first
                    hardwareDecoderFactory.createDecoder(codecInfo)?.also {
                        Logger.d("[CallService] Using hardware decoder for ${codecInfo.name}")
                    } ?: run {
                        // Fallback to software decoder
                        Logger.d("[CallService] Hardware decoder unavailable, trying software for ${codecInfo.name}")
                        softwareDecoderFactory.createDecoder(codecInfo)
                    }
                } catch (e: Exception) {
                    Logger.e("[CallService] Hardware decoder failed, using software: ${e.message}")
                    try {
                        softwareDecoderFactory.createDecoder(codecInfo)
                    } catch (e2: Exception) {
                        Logger.e("[CallService] Software decoder also failed: ${e2.message}")
                        null
                    }
                }
            }

            override fun getSupportedCodecs(): Array<VideoCodecInfo> {
                // Combine codecs from both factories, prioritizing hardware
                val hwCodecs = hardwareDecoderFactory.supportedCodecs.toMutableList()
                val swCodecs = softwareDecoderFactory.supportedCodecs
                for (codec in swCodecs) {
                    if (hwCodecs.none { it.name == codec.name }) {
                        hwCodecs.add(codec)
                    }
                }
                Logger.d("[CallService] Supported decoder codecs: ${hwCodecs.map { it.name }}")
                return hwCodecs.toTypedArray()
            }
        }

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
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

    // MARK: - Initiate Call

    suspend fun initiateCall(peerId: String, isVideo: Boolean): Result<Unit> {
        val whisperId = secureStorage.whisperId ?: return Result.failure(Exception("Not authenticated"))
        val sessionToken = secureStorage.sessionToken ?: return Result.failure(Exception("No session"))
        val encPrivateKey = secureStorage.encPrivateKey ?: return Result.failure(Exception("No encryption key"))
        val signPrivateKey = secureStorage.signPrivateKey ?: return Result.failure(Exception("No signing key"))

        // Get recipient's public key
        val contact = contactDao.getContactById(peerId)
        val recipientPublicKey = contact?.encPublicKey?.let {
            Base64.decode(it, Base64.NO_WRAP)
        } ?: return Result.failure(Exception("Contact public key not found"))

        return try {
            // Reset track flags
            remoteAudioTrackReceived = false
            remoteVideoTrackReceived = false
            localSdpSent = false

            // Fetch TURN credentials
            if (_turnCredentials.value == null) {
                fetchTurnCredentials()
                delay(1000) // Wait for credentials
            }

            val callId = UUID.randomUUID().toString().lowercase()

            // RULE 2: Initialize audio routing BEFORE createPeerConnection
            initializeAudioRouting(isVideo)

            // Create peer connection and generate offer
            createPeerConnection(isVideo)
            val sdpOffer = createOffer(isVideo)

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
        val incomingPayload = pendingIncomingCall ?: return Result.failure(Exception("No incoming call"))
        val whisperId = secureStorage.whisperId ?: return Result.failure(Exception("Not authenticated"))
        val sessionToken = secureStorage.sessionToken ?: return Result.failure(Exception("No session"))
        val encPrivateKey = secureStorage.encPrivateKey ?: return Result.failure(Exception("No encryption key"))
        val signPrivateKey = secureStorage.signPrivateKey ?: return Result.failure(Exception("No signing key"))

        // Get sender's public key
        val contact = contactDao.getContactById(incomingPayload.from)
        val senderPublicKey = contact?.encPublicKey?.let {
            Base64.decode(it, Base64.NO_WRAP)
        } ?: return Result.failure(Exception("Sender public key not found"))

        return try {
            // Reset track flags
            remoteAudioTrackReceived = false
            remoteVideoTrackReceived = false
            localSdpSent = false

            // Fetch TURN if needed
            if (_turnCredentials.value == null) {
                fetchTurnCredentials()
                delay(1000)
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
            Logger.i("[CallService] Received offer SDP - isVideo=${incomingPayload.isVideo}, hasVideoMLine=$hasVideoMLine")
            if (incomingPayload.isVideo && !hasVideoMLine) {
                Logger.e("[CallService] WARNING: Video call but received offer has no m=video line!")
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

    suspend fun endCall(reason: CallEndReason = CallEndReason.ENDED) {
        val call = _activeCall.value ?: pendingIncomingCall?.let {
            ActiveCall(it.callId, it.from, null, it.isVideo, false)
        } ?: return

        val whisperId = secureStorage.whisperId ?: return
        val sessionToken = secureStorage.sessionToken ?: return
        val encPrivateKey = secureStorage.encPrivateKey ?: return
        val signPrivateKey = secureStorage.signPrivateKey ?: return

        val contact = contactDao.getContactById(call.peerId)
        val recipientPublicKey = contact?.encPublicKey?.let {
            Base64.decode(it, Base64.NO_WRAP)
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

                val payload = CallEndPayload(
                    sessionToken = sessionToken,
                    callId = call.callId,
                    from = whisperId,
                    to = call.peerId,
                    timestamp = timestamp,
                    nonce = Base64.encodeToString(nonce, Base64.NO_WRAP),
                    ciphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
                    sig = Base64.encodeToString(signature, Base64.NO_WRAP),
                    reason = reason.name.lowercase()
                )

                wsClient.send(WsFrame(Constants.MsgType.CALL_END, payload = payload))
            } catch (e: Exception) {
                Logger.e("Failed to send call end", e)
            }
        }

        _callState.value = CallState.Ended(reason)
        recordCallToHistory(call, reason)

        // End Telecom connection
        telecomCallManager.endCallSync()

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
        audioManager.isSpeakerphoneOn = newSpeakerState
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
        localVideoSink = sink
        localVideoTrack?.addSink(sink)
    }

    fun setRemoteVideoSink(sink: VideoSink?) {
        synchronized(this) {
            Logger.i("[CallService] setRemoteVideoSink called: sink=${if (sink != null) "SET" else "NULL"}, remoteVideoTrack=${if (remoteVideoTrack != null) "EXISTS" else "NULL"}")
            remoteVideoSink = sink
            // If remote track already received, add sink now
            if (sink != null) {
                remoteVideoTrack?.let { track ->
                    Logger.i("[CallService] Adding sink to existing remote video track, enabled=${track.enabled()}")
                    track.setEnabled(true)
                    track.addSink(sink)
                } ?: Logger.w("[CallService] setRemoteVideoSink: no remoteVideoTrack yet, will add when track arrives")
            }
        }
    }

    // MARK: - WebRTC Setup

    private fun createPeerConnection(isVideo: Boolean) {
        val factory = peerConnectionFactory ?: return

        val iceServers = mutableListOf<PeerConnection.IceServer>()

        // Add TURN servers if available
        _turnCredentials.value?.let { turn ->
            turn.urls.forEach { url ->
                iceServers.add(
                    PeerConnection.IceServer.builder(url)
                        .setUsername(turn.username)
                        .setPassword(turn.credential)
                        .createIceServer()
                )
            }
        }

        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                Logger.d("Signaling state: $state")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Logger.d("ICE connection state: $state")
                scope.launch {
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> {
                            // RULE 1: Only set Connected when media is actually flowing
                            checkAndSetConnected()
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
                        remoteVideoSink?.let { sink ->
                            Logger.i("[CallService] Adding remote video sink from onAddStream")
                            videoTrack.addSink(sink)
                        } ?: Logger.w("[CallService] onAddStream: remoteVideoSink is NULL, will add sink later")
                    }
                    scope.launch { checkAndSetConnected() }
                }
            }

            override fun onRemoveStream(stream: MediaStream?) {
                remoteVideoTrack = null
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
                                remoteVideoSink?.let { sink ->
                                    Logger.i("[CallService] Adding remote video sink to track NOW")
                                    track.addSink(sink)
                                } ?: Logger.w("[CallService] onAddTrack: remoteVideoSink is NULL! Will add sink when UI sets it")
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
            val eglContext = eglBase?.eglBaseContext ?: return
            surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglContext)

            val videoSource = factory.createVideoSource(false)
            videoCapturer = createCameraCapturer()
            videoCapturer?.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
            videoCapturer?.startCapture(1280, 720, 30)

            localVideoTrack = factory.createVideoTrack("video0", videoSource)
            localVideoTrack?.let { track ->
                peerConnection?.addTrack(track, listOf("stream0"))
                localVideoSink?.let { sink ->
                    track.addSink(sink)
                }
            }
        }
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
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
                val sdp = desc?.description ?: ""
                val hasVideoMLine = sdp.contains("m=video")
                Logger.i("[CallService] Created offer - hasVideoMLine=$hasVideoMLine")
                if (isVideo && !hasVideoMLine) {
                    Logger.e("[CallService] WARNING: Video call but SDP has no m=video line!")
                }
                peerConnection?.setLocalDescription(SdpObserverAdapter(), desc)
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
                val sdp = desc?.description ?: ""
                val hasVideoMLine = sdp.contains("m=video")
                Logger.i("[CallService] Created answer - hasVideoMLine=$hasVideoMLine")
                if (isVideo && !hasVideoMLine) {
                    Logger.e("[CallService] WARNING: Video call but answer SDP has no m=video line!")
                }
                peerConnection?.setLocalDescription(SdpObserverAdapter(), desc)
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
        audioManager.isSpeakerphoneOn = isVideo

        Logger.d("[CallService] Audio routing initialized: mode=${audioManager.mode}, speaker=$isVideo")
    }

    private fun configureAudioSession() {
        // Legacy - now handled by initializeAudioRouting
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
            Base64.decode(it, Base64.NO_WRAP)
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
        } catch (e: Exception) {
            Logger.e("Failed to parse TURN credentials", e)
        }
    }

    private fun handleCallIncoming(frame: WsFrame<JsonElement>) {
        try {
            Logger.d("[CallService] Parsing incoming call payload: ${frame.payload}")
            val payload = gson.fromJson(frame.payload, CallIncomingPayload::class.java)
            Logger.i("[CallService] Incoming call - callId: ${payload.callId}, from: ${payload.from}, isVideo: ${payload.isVideo}")
            pendingIncomingCall = payload

            scope.launch {
                val contact = contactDao.getContactById(payload.from)
                Logger.d("[CallService] Contact lookup: ${contact?.displayName ?: "not found"}")

                _activeCall.value = ActiveCall(
                    callId = payload.callId,
                    peerId = payload.from,
                    peerName = contact?.displayName,
                    isVideo = payload.isVideo,
                    isOutgoing = false
                )
                _callState.value = CallState.Ringing
                Logger.i("[CallService] Call state set to RINGING")

                // Note: We handle answer/decline in IncomingCallActivity directly
                // Only set up onEndCall for when user hangs up during an active call
                telecomCallManager.onEndCall = {
                    Logger.i("[CallService] Telecom onEndCall callback")
                    scope.launch {
                        endCall(CallEndReason.ENDED)
                    }
                }

                // Report incoming call to Telecom system (shows system call UI)
                Logger.i("[CallService] About to report incoming call to Telecom...")
                Logger.i("[CallService] Payload - callId: ${payload.callId}, from: ${payload.from}, isVideo: ${payload.isVideo}")
                Logger.i("[CallService] Payload - nonce length: ${payload.nonce.length}, ciphertext length: ${payload.ciphertext.length}")

                val telecomSuccess = telecomCallManager.reportIncomingCall(
                    callId = payload.callId,
                    callerName = contact?.displayName ?: payload.from,
                    callerId = payload.from,
                    isVideo = payload.isVideo,
                    scope = scope
                )
                Logger.i("[CallService] Telecom reportIncomingCall result: $telecomSuccess")

                // After reporting, set up connection callbacks (connection may be created asynchronously)
                delay(100) // Small delay to allow ConnectionService to create connection
                setupConnectionCallbacks()
            }
        } catch (e: Exception) {
            Logger.e("[CallService] Failed to handle incoming call", e)
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
                    Base64.decode(it, Base64.NO_WRAP)
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
                    Base64.decode(it, Base64.NO_WRAP)
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

            scope.launch {
                _activeCall.value?.let { call ->
                    recordCallToHistory(call, reason)
                }
                _callState.value = CallState.Ended(reason)

                // Remote ended - clean up Telecom and foreground service
                telecomCallManager.remoteCallEnded()
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
     * RULE 1: Check if we can transition to Connected state
     * Connected = ICE connected + tracks received
     */
    private fun checkAndSetConnected() {
        val iceState = peerConnection?.iceConnectionState()
        val isIceConnected = iceState == PeerConnection.IceConnectionState.CONNECTED ||
                             iceState == PeerConnection.IceConnectionState.COMPLETED

        val isVideo = _activeCall.value?.isVideo == true

        // For audio calls: need ICE + audio track
        // For video calls: need ICE + video track (audio optional)
        val mediaReady = if (isVideo) {
            remoteVideoTrackReceived
        } else {
            remoteAudioTrackReceived
        }

        Logger.d("[CallService] checkAndSetConnected: ICE=$isIceConnected, mediaReady=$mediaReady, audioTrack=$remoteAudioTrackReceived, videoTrack=$remoteVideoTrackReceived")

        if (isIceConnected && mediaReady && _callState.value != CallState.Connected) {
            Logger.i("[CallService] Media flowing - setting state to Connected")
            _callState.value = CallState.Connected
            _activeCall.value?.let { call ->
                _activeCall.value = call.copy(startTime = System.currentTimeMillis())
            }
            startCallDurationTimer()
        }
    }

    // MARK: - Call Duration Timer

    private fun startCallDurationTimer() {
        callDurationJob?.cancel()
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
    }

    // MARK: - Cleanup

    private fun cleanupResources() {
        callDurationJob?.cancel()
        callDurationJob = null
        _callDuration.value = 0

        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        videoCapturer = null

        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null

        localVideoTrack?.dispose()
        localVideoTrack = null
        localAudioTrack?.dispose()
        localAudioTrack = null

        peerConnection?.close()
        peerConnection = null

        pendingRemoteIceCandidates.clear()
        pendingLocalIceCandidates.clear()
        pendingIncomingCall = null
        remoteVideoTrack = null

        // Reset flags
        localSdpSent = false
        remoteAudioTrackReceived = false
        remoteVideoTrackReceived = false

        // Reset audio
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false
    }

    private fun cleanup() {
        cleanupResources()
        _activeCall.value = null
        _callState.value = CallState.Idle
    }

    // Called after screen navigates away
    fun resetCallState() {
        _activeCall.value = null
        _callState.value = CallState.Idle
    }

    // Set up callbacks on the WhisperConnection (after it's created by ConnectionService)
    private fun setupConnectionCallbacks() {
        WhisperConnectionService.activeConnection?.let { connection ->
            Logger.i("[CallService] Setting up WhisperConnection callbacks")

            // Handle answer from wearable/external device (Telecom triggers this)
            connection.onAnswerCallback = { isVideo ->
                Logger.i("[CallService] WhisperConnection onAnswerCallback from external device")
                scope.launch { answerCall() }
            }

            // Handle reject from wearable/external device
            connection.onRejectCallback = {
                Logger.i("[CallService] WhisperConnection onRejectCallback from external device")
                scope.launch { declineCall() }
            }

            // Handle disconnect during active call
            connection.onDisconnectCallback = {
                Logger.i("[CallService] WhisperConnection onDisconnectCallback")
                scope.launch { endCall(CallEndReason.ENDED) }
            }
        } ?: Logger.w("[CallService] No active connection yet to set callbacks on")
    }

    /**
     * Set the Telecom connection to active state (call UI -> this should be called when user answers)
     */
    fun setConnectionActive() {
        WhisperConnectionService.activeConnection?.let { connection ->
            Logger.i("[CallService] Setting Telecom connection to ACTIVE")
            connection.setActive()
        }
    }
}

// SDP Observer adapter
private class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}
