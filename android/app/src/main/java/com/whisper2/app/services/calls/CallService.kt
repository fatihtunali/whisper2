package com.whisper2.app.services.calls

import android.content.Context
import android.media.AudioManager
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
import com.whisper2.app.data.network.ws.*
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
    ENDED,
    DECLINED,
    BUSY,
    TIMEOUT,
    FAILED,
    CANCELLED
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

    // Video renderers (set by UI)
    private var localVideoSink: VideoSink? = null
    private var remoteVideoSink: VideoSink? = null

    // Pending ICE candidates (received before remote description set)
    private val pendingIceCandidates = mutableListOf<IceCandidate>()

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
    }

    private fun setupWebRTC() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val encoderFactory = DefaultVideoEncoderFactory(
            EglBase.create().eglBaseContext,
            true,
            true
        )
        val decoderFactory = DefaultVideoDecoderFactory(EglBase.create().eglBaseContext)

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

    suspend fun fetchTurnCredentials() {
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
            // Fetch TURN credentials
            fetchTurnCredentials()
            delay(500) // Wait for credentials

            val callId = UUID.randomUUID().toString().lowercase()

            // Create peer connection and generate offer
            createPeerConnection(isVideo)
            val sdpOffer = createOffer()

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
            // Fetch TURN if needed
            if (_turnCredentials.value == null) {
                fetchTurnCredentials()
                delay(500)
            }

            // Decrypt SDP offer
            val ciphertextData = Base64.decode(incomingPayload.ciphertext, Base64.NO_WRAP)
            val nonceData = Base64.decode(incomingPayload.nonce, Base64.NO_WRAP)

            val sdpOffer = String(
                cryptoService.boxOpen(ciphertextData, nonceData, senderPublicKey, encPrivateKey),
                Charsets.UTF_8
            )

            // Create peer connection and set remote description
            createPeerConnection(incomingPayload.isVideo)

            val remoteDesc = SessionDescription(SessionDescription.Type.OFFER, sdpOffer)
            peerConnection?.setRemoteDescription(SdpObserverAdapter(), remoteDesc)

            // Process pending ICE candidates
            pendingIceCandidates.forEach { candidate ->
                peerConnection?.addIceCandidate(candidate)
            }
            pendingIceCandidates.clear()

            // Create answer
            val sdpAnswer = createAnswer()

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

            // Update state
            _activeCall.value = ActiveCall(
                callId = incomingPayload.callId,
                peerId = incomingPayload.from,
                peerName = contact?.displayName,
                isVideo = incomingPayload.isVideo,
                isOutgoing = false
            )
            _callState.value = CallState.Connecting

            // Configure audio
            configureAudioSession()

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
        cleanup()
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
        remoteVideoSink = sink
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
                            _callState.value = CallState.Connected
                            _activeCall.value?.let { call ->
                                _activeCall.value = call.copy(startTime = System.currentTimeMillis())
                            }
                            startCallDurationTimer()
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
                        sendIceCandidate(it)
                    }
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

            override fun onAddStream(stream: MediaStream?) {
                stream?.videoTracks?.firstOrNull()?.let { remoteVideoTrack ->
                    remoteVideoSink?.let { sink ->
                        remoteVideoTrack.addSink(sink)
                    }
                }
            }

            override fun onRemoveStream(stream: MediaStream?) {}

            override fun onDataChannel(channel: DataChannel?) {}

            override fun onRenegotiationNeeded() {}

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                receiver?.track()?.let { track ->
                    if (track is VideoTrack) {
                        remoteVideoSink?.let { sink ->
                            track.addSink(sink)
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
            val eglBase = EglBase.create()
            surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)

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

    private suspend fun createOffer(): String = suspendCancellableCoroutine { cont ->
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo",
                if (_activeCall.value?.isVideo == true) "true" else "false"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                peerConnection?.setLocalDescription(SdpObserverAdapter(), desc)
                cont.resume(desc?.description ?: "") {}
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                cont.resumeWith(Result.failure(Exception(error)))
            }
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    private suspend fun createAnswer(): String = suspendCancellableCoroutine { cont ->
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo",
                if (_activeCall.value?.isVideo == true) "true" else "false"))
        }

        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                peerConnection?.setLocalDescription(SdpObserverAdapter(), desc)
                cont.resume(desc?.description ?: "") {}
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                cont.resumeWith(Result.failure(Exception(error)))
            }
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    private fun configureAudioSession() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
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
        when (frame.type) {
            Constants.MsgType.TURN_CREDENTIALS -> handleTurnCredentials(frame)
            Constants.MsgType.CALL_INCOMING -> handleCallIncoming(frame)
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
            val payload = gson.fromJson(frame.payload, CallIncomingPayload::class.java)
            pendingIncomingCall = payload

            scope.launch {
                val contact = contactDao.getContactById(payload.from)
                _activeCall.value = ActiveCall(
                    callId = payload.callId,
                    peerId = payload.from,
                    peerName = contact?.displayName,
                    isVideo = payload.isVideo,
                    isOutgoing = false
                )
                _callState.value = CallState.Ringing
            }
        } catch (e: Exception) {
            Logger.e("Failed to handle incoming call", e)
        }
    }

    private fun handleCallAnswer(frame: WsFrame<JsonElement>) {
        try {
            val payload = gson.fromJson(frame.payload, CallAnswerPayload::class.java)
            val encPrivateKey = secureStorage.encPrivateKey ?: return

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

                val remoteDesc = SessionDescription(SessionDescription.Type.ANSWER, sdpAnswer)
                peerConnection?.setRemoteDescription(SdpObserverAdapter(), remoteDesc)

                // Process pending ICE candidates
                pendingIceCandidates.forEach { candidate ->
                    peerConnection?.addIceCandidate(candidate)
                }
                pendingIceCandidates.clear()

                _callState.value = CallState.Connecting
            }
        } catch (e: Exception) {
            Logger.e("Failed to handle call answer", e)
        }
    }

    private fun handleIceCandidate(frame: WsFrame<JsonElement>) {
        try {
            val payload = gson.fromJson(frame.payload, CallIceCandidatePayload::class.java)
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

                // If remote description not set yet, queue the candidate
                if (peerConnection?.remoteDescription == null) {
                    pendingIceCandidates.add(candidate)
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
            val payload = gson.fromJson(frame.payload, CallEndPayload::class.java)
            val reason = try {
                CallEndReason.valueOf(payload.reason.uppercase())
            } catch (e: Exception) {
                CallEndReason.ENDED
            }

            scope.launch {
                _activeCall.value?.let { call ->
                    recordCallToHistory(call, reason)
                }
                _callState.value = CallState.Ended(reason)
                cleanup()
            }
        } catch (e: Exception) {
            Logger.e("Failed to handle call end", e)
        }
    }

    private fun handleCallRinging() {
        _callState.value = CallState.Ringing
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
            CallEndReason.CANCELLED -> "cancelled"
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

    private fun cleanup() {
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

        pendingIceCandidates.clear()
        pendingIncomingCall = null

        _activeCall.value = null
        _callState.value = CallState.Idle

        // Reset audio
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false
    }
}

// SDP Observer adapter
private class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}
