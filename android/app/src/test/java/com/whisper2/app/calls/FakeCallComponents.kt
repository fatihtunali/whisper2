package com.whisper2.app.calls

import com.whisper2.app.network.ws.TurnCredentialsPayload
import com.whisper2.app.network.ws.WsRawEnvelope
import com.whisper2.app.services.calls.*
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Fake WS sender for testing
 */
class FakeWsSender : CallService.WsSender, TurnServiceImpl.WsSender {
    val sent = ConcurrentLinkedQueue<String>()
    var shouldFail = false

    override fun send(message: String): Boolean {
        if (shouldFail) return false
        sent.add(message)
        return true
    }

    fun findByType(type: String): String? {
        return sent.find { it.contains("\"type\":\"$type\"") }
    }

    fun countByType(type: String): Int {
        return sent.count { it.contains("\"type\":\"$type\"") }
    }

    fun clear() {
        sent.clear()
    }
}

/**
 * Fake Call UI service for testing
 */
class FakeCallUiService : CallUiService {
    var incomingShownCount = 0
    var ongoingShownCount = 0
    var outgoingShownCount = 0
    var ringingShownCount = 0
    var connectingShownCount = 0
    var dismissedCount = 0
    var errorShownCount = 0

    var lastIncomingCallId: String? = null
    var lastIncomingFrom: String? = null
    var lastIncomingIsVideo: Boolean? = null

    var lastDismissedCallId: String? = null
    var lastDismissedReason: String? = null

    var lastError: String? = null

    override fun showIncomingCall(callId: String, from: String, isVideo: Boolean) {
        incomingShownCount++
        lastIncomingCallId = callId
        lastIncomingFrom = from
        lastIncomingIsVideo = isVideo
    }

    override fun showOngoingCall(callId: String, peerId: String, isVideo: Boolean) {
        ongoingShownCount++
    }

    override fun showOutgoingCall(callId: String, to: String, isVideo: Boolean) {
        outgoingShownCount++
    }

    override fun showRinging(callId: String) {
        ringingShownCount++
    }

    override fun showConnecting(callId: String) {
        connectingShownCount++
    }

    override fun dismissCallUi(callId: String, reason: String) {
        dismissedCount++
        lastDismissedCallId = callId
        lastDismissedReason = reason
    }

    override fun showError(callId: String, error: String) {
        errorShownCount++
        lastError = error
    }

    fun reset() {
        incomingShownCount = 0
        ongoingShownCount = 0
        outgoingShownCount = 0
        ringingShownCount = 0
        connectingShownCount = 0
        dismissedCount = 0
        errorShownCount = 0
        lastIncomingCallId = null
        lastIncomingFrom = null
        lastIncomingIsVideo = null
        lastDismissedCallId = null
        lastDismissedReason = null
        lastError = null
    }
}

/**
 * Fake WebRTC service for testing
 */
class FakeWebRtcService : WebRtcService {
    private var _listener: WebRtcService.Listener? = null
    val listener: WebRtcService.Listener? get() = _listener

    var createPeerConnectionCount = 0
    var createOfferCount = 0
    var createAnswerCount = 0
    var setRemoteDescriptionCount = 0
    var addIceCandidateCount = 0
    var closeCount = 0

    var lastTurnCreds: TurnCredentialsPayload? = null
    var lastRemoteSdp: String? = null
    var lastRemoteSdpType: WebRtcService.SdpType? = null
    var lastIceCandidate: String? = null

    private var _hasRemoteDescription = false

    var autoGenerateLocalSdp = true
    var localSdpToGenerate = "v=0\r\no=- 123 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n"

    override fun setListener(listener: WebRtcService.Listener?) {
        _listener = listener
    }

    override suspend fun createPeerConnection(turnCreds: TurnCredentialsPayload, isVideo: Boolean) {
        createPeerConnectionCount++
        lastTurnCreds = turnCreds
    }

    override suspend fun createOffer() {
        createOfferCount++
        if (autoGenerateLocalSdp) {
            listener?.onLocalDescription(localSdpToGenerate, WebRtcService.SdpType.OFFER)
        }
    }

    override suspend fun createAnswer() {
        createAnswerCount++
        if (autoGenerateLocalSdp) {
            listener?.onLocalDescription(localSdpToGenerate, WebRtcService.SdpType.ANSWER)
        }
    }

    override suspend fun setRemoteDescription(sdp: String, type: WebRtcService.SdpType) {
        setRemoteDescriptionCount++
        lastRemoteSdp = sdp
        lastRemoteSdpType = type
        _hasRemoteDescription = true
    }

    override suspend fun addIceCandidate(candidate: String) {
        addIceCandidateCount++
        lastIceCandidate = candidate
    }

    override fun hasRemoteDescription(): Boolean = _hasRemoteDescription

    override fun close() {
        closeCount++
        _hasRemoteDescription = false
    }

    override fun setAudioEnabled(enabled: Boolean) {}
    override fun setVideoEnabled(enabled: Boolean) {}
    override fun switchCamera() {}

    fun reset() {
        createPeerConnectionCount = 0
        createOfferCount = 0
        createAnswerCount = 0
        setRemoteDescriptionCount = 0
        addIceCandidateCount = 0
        closeCount = 0
        lastTurnCreds = null
        lastRemoteSdp = null
        lastRemoteSdpType = null
        lastIceCandidate = null
        _hasRemoteDescription = false
    }

    fun simulateConnected() {
        listener?.onIceConnectionStateChanged(WebRtcService.IceConnectionState.CONNECTED)
        listener?.onConnectionStateChanged(WebRtcService.PeerConnectionState.CONNECTED)
    }

    fun simulateFailed() {
        listener?.onIceConnectionStateChanged(WebRtcService.IceConnectionState.FAILED)
        listener?.onError("Connection failed")
    }
}

/**
 * Fake TURN service for testing
 */
class FakeTurnService(
    private var turnCreds: TurnCredentialsPayload? = null
) : TurnService {
    var requestCount = 0
    var shouldFail = false
    var failError: TurnService.TurnException? = null

    override suspend fun requestTurnCreds(): TurnCredentialsPayload {
        requestCount++
        if (shouldFail) {
            throw failError ?: TurnService.TurnException.Timeout()
        }
        return turnCreds ?: sampleTurnCreds()
    }

    override fun onWsMessage(envelope: WsRawEnvelope) {
        // Not used in fake
    }

    override fun getCachedCreds(): TurnCredentialsPayload? = turnCreds

    fun setTurnCreds(creds: TurnCredentialsPayload) {
        turnCreds = creds
    }

    fun reset() {
        requestCount = 0
        shouldFail = false
        failError = null
    }
}

/**
 * In-memory key store for testing
 */
class InMemoryKeyStore : CallService.KeyStore {
    private val signKeys = mutableMapOf<String, ByteArray>()
    private val encKeys = mutableMapOf<String, ByteArray>()

    override fun getSignPublicKey(whisperId: String): ByteArray? = signKeys[whisperId]
    override fun getEncPublicKey(whisperId: String): ByteArray? = encKeys[whisperId]

    fun putSignKey(whisperId: String, key: ByteArray) {
        signKeys[whisperId] = key
    }

    fun putEncKey(whisperId: String, key: ByteArray) {
        encKeys[whisperId] = key
    }

    fun clear() {
        signKeys.clear()
        encKeys.clear()
    }
}

/**
 * Test keys provider
 */
class TestKeysProvider : CallService.MyKeysProvider {
    @JvmField var whisperId: String? = "WSP-TEST-ABCD-EFGH"
    @JvmField var signPrivateKey: ByteArray? = null
    @JvmField var signPublicKey: ByteArray? = null
    @JvmField var encPrivateKey: ByteArray? = null
    @JvmField var encPublicKey: ByteArray? = null

    override fun getWhisperId(): String? = whisperId
    override fun getSignPrivateKey(): ByteArray? = signPrivateKey
    override fun getEncPrivateKey(): ByteArray? = encPrivateKey
    override fun getEncPublicKey(): ByteArray? = encPublicKey
}

// =============================================================================
// SAMPLE DATA
// =============================================================================

fun sampleTurnCreds(): TurnCredentialsPayload {
    return TurnCredentialsPayload(
        urls = listOf(
            "turn:turn.example.com:3478",
            "turns:turn.example.com:5349"
        ),
        username = "1706286600:WSP-TEST-ABCD-EFGH",
        credential = "dGVzdF9jcmVkZW50aWFs",
        ttl = 600
    )
}

// =============================================================================
// FAKE CRYPTO PROVIDER
// =============================================================================

/**
 * Fake crypto provider for testing.
 * Uses simple XOR for encryption/decryption and deterministic signatures.
 * This avoids needing native libsodium in JVM tests.
 */
class FakeCallCryptoProvider : CallCryptoProvider {

    private var nonceCounter = 0

    // Track signatures for verification
    private val signatures = mutableMapOf<String, SignatureData>()

    data class SignatureData(
        val messageType: String,
        val messageId: String,
        val from: String,
        val to: String,
        val timestamp: Long,
        val nonce: ByteArray,
        val ciphertext: ByteArray
    )

    override fun generateNonce(): ByteArray {
        // Generate deterministic nonces for testing
        val nonce = ByteArray(24)
        val counterBytes = (++nonceCounter).toString().toByteArray()
        counterBytes.copyInto(nonce, 0, 0, minOf(counterBytes.size, 24))
        return nonce
    }

    override fun seal(
        plaintext: ByteArray,
        nonce: ByteArray,
        recipientPublicKey: ByteArray,
        senderPrivateKey: ByteArray
    ): ByteArray {
        // Simple XOR with nonce for testing (NOT secure, just for tests)
        return plaintext.mapIndexed { i, b ->
            (b.toInt() xor nonce[i % nonce.size].toInt()).toByte()
        }.toByteArray()
    }

    override fun open(
        ciphertext: ByteArray,
        nonce: ByteArray,
        senderPublicKey: ByteArray,
        recipientPrivateKey: ByteArray
    ): ByteArray {
        // XOR is its own inverse
        return ciphertext.mapIndexed { i, b ->
            (b.toInt() xor nonce[i % nonce.size].toInt()).toByte()
        }.toByteArray()
    }

    override fun signCanonical(
        messageType: String,
        messageId: String,
        from: String,
        to: String,
        timestamp: Long,
        nonce: ByteArray,
        ciphertext: ByteArray,
        privateKey: ByteArray
    ): String {
        // Create canonical message
        val nonceB64 = encodeBase64(nonce)
        val ciphertextB64 = encodeBase64(ciphertext)
        val canonical = "v1\n$messageType\n$messageId\n$from\n$to\n$timestamp\n$nonceB64\n$ciphertextB64\n"

        // Hash it
        val hash = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())

        // Create deterministic 64-byte signature from hash + privateKey hash
        val keyHash = MessageDigest.getInstance("SHA-256").digest(privateKey)
        val signature = ByteArray(64)
        hash.copyInto(signature, 0, 0, 32)
        keyHash.copyInto(signature, 32, 0, 32)

        val sigB64 = encodeBase64(signature)

        // Store for verification
        signatures[sigB64] = SignatureData(messageType, messageId, from, to, timestamp, nonce.copyOf(), ciphertext.copyOf())

        return sigB64
    }

    override fun verifyCanonical(
        signatureB64: String,
        messageType: String,
        messageId: String,
        from: String,
        to: String,
        timestamp: Long,
        nonceB64: String,
        ciphertextB64: String,
        publicKey: ByteArray
    ): Boolean {
        // Check if we have this signature stored (from our own signCanonical)
        val stored = signatures[signatureB64]
        if (stored != null) {
            return stored.messageType == messageType &&
                   stored.messageId == messageId &&
                   stored.from == from &&
                   stored.to == to &&
                   stored.timestamp == timestamp &&
                   encodeBase64(stored.nonce) == nonceB64 &&
                   encodeBase64(stored.ciphertext) == ciphertextB64
        }

        // For test-generated signatures, verify the hash matches
        try {
            val signature = decodeBase64(signatureB64)
            if (signature.size != 64) return false

            val canonical = "v1\n$messageType\n$messageId\n$from\n$to\n$timestamp\n$nonceB64\n$ciphertextB64\n"
            val hash = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())

            // Check first 32 bytes match hash
            for (i in 0 until 32) {
                if (signature[i] != hash[i]) return false
            }
            return true
        } catch (e: Exception) {
            return false
        }
    }

    override fun decodeBase64WithLength(encoded: String, expectedLength: Int): ByteArray {
        val decoded = decodeBase64(encoded)
        if (decoded.size != expectedLength) {
            throw IllegalArgumentException("Expected $expectedLength bytes, got ${decoded.size}")
        }
        return decoded
    }

    override fun encodeBase64(bytes: ByteArray): String {
        return Base64.getEncoder().encodeToString(bytes)
    }

    override fun decodeBase64(encoded: String): ByteArray {
        return Base64.getDecoder().decode(encoded)
    }

    fun reset() {
        nonceCounter = 0
        signatures.clear()
    }
}
