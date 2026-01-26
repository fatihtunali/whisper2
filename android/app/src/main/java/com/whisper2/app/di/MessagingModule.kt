package com.whisper2.app.di

import com.google.gson.Gson
import com.whisper2.app.core.Constants
import com.whisper2.app.core.Logger
import com.whisper2.app.network.api.*
import com.whisper2.app.network.ws.*
import com.whisper2.app.services.auth.ISessionManager
import com.whisper2.app.services.calls.*
import com.whisper2.app.services.contacts.InMemoryKeyCache
import com.whisper2.app.services.contacts.KeyCache
import com.whisper2.app.services.contacts.KeyLookupResult
import com.whisper2.app.services.contacts.KeyLookupService
import com.whisper2.app.services.attachments.AttachmentCache
import com.whisper2.app.services.attachments.AttachmentService
import com.whisper2.app.services.attachments.BlobHttpClient
import com.whisper2.app.services.attachments.BlobResult
import com.whisper2.app.services.attachments.InMemoryAttachmentCache
import com.whisper2.app.services.messaging.*
import com.whisper2.app.storage.db.dao.ConversationDao
import com.whisper2.app.storage.db.dao.MessageDao
import com.whisper2.app.storage.key.SecurePrefs
import dagger.Module
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * Messaging DI Module
 *
 * Provides messaging-related services:
 * - WhisperApi (HTTP client)
 * - KeyLookupService
 * - WsClient (WebSocket)
 * - OutboxQueue
 * - MessagingService
 */
@Module
@InstallIn(SingletonComponent::class)
object MessagingModule {

    private var instanceCount = 0

    @Provides
    @Singleton
    fun provideSessionTokenProvider(sessionManager: ISessionManager): SessionTokenProvider {
        return SessionTokenProvider { sessionManager.sessionToken }
    }

    @Provides
    @Singleton
    fun provideApiAuthFailureHandler(sessionManager: ISessionManager): ApiAuthFailureHandler {
        return ApiAuthFailureHandler { reason ->
            Logger.warn("API Auth failure: $reason", Logger.Category.NETWORK)
            sessionManager.forceLogout("API auth failure: $reason")
        }
    }

    @Provides
    @Singleton
    fun provideWhisperApi(
        client: OkHttpClient,
        gson: Gson,
        sessionTokenProvider: SessionTokenProvider,
        authFailureHandler: ApiAuthFailureHandler
    ): WhisperApi {
        instanceCount++
        Logger.info("WhisperApi instance created (#$instanceCount)", Logger.Category.NETWORK)
        return OkHttpWhisperApi(
            client = client,
            gson = gson,
            sessionTokenProvider = sessionTokenProvider,
            authFailureHandler = authFailureHandler
        )
    }

    @Provides
    @Singleton
    fun provideKeyCache(): KeyCache {
        instanceCount++
        Logger.info("KeyCache instance created (#$instanceCount)", Logger.Category.NETWORK)
        return InMemoryKeyCache()
    }

    @Provides
    @Singleton
    fun provideKeyLookupService(
        api: WhisperApi,
        cache: KeyCache
    ): KeyLookupService {
        instanceCount++
        Logger.info("KeyLookupService instance created (#$instanceCount)", Logger.Category.NETWORK)
        return KeyLookupService(api, cache)
    }

    @Provides
    @Singleton
    fun providePeerKeyProvider(keyLookupService: KeyLookupService): PeerKeyProvider {
        return object : PeerKeyProvider {
            override fun getSignPublicKey(whisperId: String): ByteArray? {
                // Blocking call for sync API
                val result = runBlocking { keyLookupService.getKeys(whisperId) }
                return (result as? KeyLookupResult.Success)?.keys?.signPublicKey
            }

            override fun getEncPublicKey(whisperId: String): ByteArray? {
                val result = runBlocking { keyLookupService.getKeys(whisperId) }
                return (result as? KeyLookupResult.Success)?.keys?.encPublicKey
            }
        }
    }

    @Provides
    @Singleton
    fun provideWsConnectionFactory(client: OkHttpClient): WsConnectionFactory {
        return WsConnectionFactory { url, listener ->
            OkHttpWsConnectionImpl(client, url, listener)
        }
    }

    @Provides
    @Singleton
    fun provideWsMessageRouter(): WsMessageRouter {
        instanceCount++
        Logger.info("WsMessageRouter instance created (#$instanceCount)", Logger.Category.NETWORK)
        return WsMessageRouter()
    }

    @Provides
    @Singleton
    fun provideWsClient(
        connectionFactory: WsConnectionFactory,
        messageRouter: WsMessageRouter
    ): WsClient {
        instanceCount++
        Logger.info("WsClient instance created (#$instanceCount)", Logger.Category.NETWORK)
        return WsClient(
            url = Constants.Server.webSocketUrl,
            connectionFactory = connectionFactory,
            onMessageCallback = { text -> messageRouter.routeMessage(text) }
        )
    }

    @Provides
    @Singleton
    fun provideOutboxQueue(
        sessionManager: ISessionManager,
        securePrefs: SecurePrefs,
        peerKeyProvider: PeerKeyProvider,
        wsClient: WsClient,
        messageDao: MessageDao
    ): OutboxQueue {
        instanceCount++
        Logger.info("OutboxQueue instance created (#$instanceCount)", Logger.Category.MESSAGING)

        return OutboxQueue(
            myWhisperIdProvider = { sessionManager.whisperId },
            sessionTokenProvider = { sessionManager.sessionToken },
            mySignPrivateKeyProvider = MySignPrivateKeyProvider { securePrefs.signPrivateKey },
            myEncPrivateKeyProvider = MyEncPrivateKeyProviderOutbox {
                securePrefs.getBytesOrNull(Constants.StorageKey.ENC_PRIVATE_KEY)
            },
            peerKeyProvider = peerKeyProvider,
            wsSender = WsSender { json -> wsClient.send(json) },
            messageStatusUpdater = MessageStatusUpdater { messageId, status ->
                messageDao.updateStatus(messageId, status)
            },
            authFailureHandler = AuthFailureHandler { reason ->
                Logger.warn("Outbox auth failure: $reason", Logger.Category.AUTH)
                sessionManager.forceLogout("Outbox auth failure: $reason")
            }
        )
    }

    @Provides
    @Singleton
    fun provideMessagingService(
        messageDao: MessageDao,
        conversationDao: ConversationDao,
        securePrefs: SecurePrefs,
        peerKeyProvider: PeerKeyProvider,
        sessionManager: ISessionManager,
        outboxQueue: OutboxQueue
    ): MessagingService {
        instanceCount++
        Logger.info("MessagingService instance created (#$instanceCount)", Logger.Category.MESSAGING)

        return MessagingService(
            messageDao = messageDao,
            conversationDao = conversationDao,
            myEncPrivateKeyProvider = MyEncPrivateKeyProvider {
                securePrefs.getBytesOrNull(Constants.StorageKey.ENC_PRIVATE_KEY)
            },
            peerKeyProvider = peerKeyProvider,
            receiptSender = ReceiptSender { messageId, from, to ->
                // Send delivery receipt via WS (simplified - could be enhanced)
                Logger.debug("Sending delivery receipt for $messageId from $from to $to", Logger.Category.MESSAGING)
            },
            myWhisperIdProvider = { sessionManager.whisperId }
        )
    }

    // =========================================================================
    // UI-G5: Attachment Service
    // =========================================================================

    @Provides
    @Singleton
    fun provideAttachmentsApi(
        client: OkHttpClient,
        gson: Gson,
        sessionTokenProvider: SessionTokenProvider,
        authFailureHandler: ApiAuthFailureHandler
    ): AttachmentsApi {
        instanceCount++
        Logger.info("AttachmentsApi instance created (#$instanceCount)", Logger.Category.NETWORK)
        return OkHttpAttachmentsApi(
            client = client,
            gson = gson,
            sessionTokenProvider = sessionTokenProvider,
            authFailureHandler = authFailureHandler
        )
    }

    @Provides
    @Singleton
    fun provideBlobHttpClient(client: OkHttpClient): BlobHttpClient {
        instanceCount++
        Logger.info("BlobHttpClient instance created (#$instanceCount)", Logger.Category.NETWORK)
        return OkHttpBlobClient(client)
    }

    @Provides
    @Singleton
    fun provideAttachmentCache(): AttachmentCache {
        instanceCount++
        Logger.info("AttachmentCache instance created (#$instanceCount)", Logger.Category.MESSAGING)
        return InMemoryAttachmentCache()
    }

    @Provides
    @Singleton
    fun provideAttachmentService(
        api: AttachmentsApi,
        blobClient: BlobHttpClient,
        cache: AttachmentCache
    ): AttachmentService {
        instanceCount++
        Logger.info("AttachmentService instance created (#$instanceCount)", Logger.Category.MESSAGING)
        return AttachmentService(api, blobClient, cache)
    }

    // =========================================================================
    // Call Service
    // =========================================================================

    @Provides
    @Singleton
    fun provideCallUiService(): CallUiService {
        instanceCount++
        Logger.info("CallUiService instance created (#$instanceCount)", Logger.Category.CALL)
        // Stub implementation - real implementation would use Android's Telecom/CallKit
        return object : CallUiService {
            override fun showIncomingCall(callId: String, from: String, isVideo: Boolean) {
                Logger.info("Show incoming call: $callId from $from, video=$isVideo", Logger.Category.CALL)
            }
            override fun showOngoingCall(callId: String, peerId: String, isVideo: Boolean) {
                Logger.info("Show ongoing call: $callId with $peerId, video=$isVideo", Logger.Category.CALL)
            }
            override fun showOutgoingCall(callId: String, to: String, isVideo: Boolean) {
                Logger.info("Show outgoing call: $callId to $to, video=$isVideo", Logger.Category.CALL)
            }
            override fun showRinging(callId: String) {
                Logger.info("Show ringing: $callId", Logger.Category.CALL)
            }
            override fun showConnecting(callId: String) {
                Logger.info("Show connecting: $callId", Logger.Category.CALL)
            }
            override fun dismissCallUi(callId: String, reason: String) {
                Logger.info("Dismiss call UI: $callId, reason=$reason", Logger.Category.CALL)
            }
            override fun showError(callId: String, error: String) {
                Logger.warn("Call error: $callId - $error", Logger.Category.CALL)
            }
        }
    }

    @Provides
    @Singleton
    fun provideWebRtcService(): WebRtcService {
        instanceCount++
        Logger.info("WebRtcService instance created (#$instanceCount)", Logger.Category.CALL)
        // Stub implementation - real implementation would use WebRTC library
        return object : WebRtcService {
            private var listener: WebRtcService.Listener? = null
            private var hasRemoteDesc = false

            override fun setListener(listener: WebRtcService.Listener?) { this.listener = listener }
            override suspend fun createPeerConnection(turnCreds: com.whisper2.app.network.ws.TurnCredentialsPayload, isVideo: Boolean) {}
            override suspend fun createOffer() {
                listener?.onLocalDescription("{\"type\":\"offer\",\"sdp\":\"stub\"}", WebRtcService.SdpType.OFFER)
            }
            override suspend fun createAnswer() {
                listener?.onLocalDescription("{\"type\":\"answer\",\"sdp\":\"stub\"}", WebRtcService.SdpType.ANSWER)
            }
            override suspend fun setRemoteDescription(sdp: String, type: WebRtcService.SdpType) { hasRemoteDesc = true }
            override suspend fun addIceCandidate(candidate: String) {}
            override fun hasRemoteDescription(): Boolean = hasRemoteDesc
            override fun close() { hasRemoteDesc = false }
            override fun setAudioEnabled(enabled: Boolean) {}
            override fun setVideoEnabled(enabled: Boolean) {}
            override fun switchCamera() {}
        }
    }

    @Provides
    @Singleton
    fun provideTurnService(wsClient: WsClient, sessionManager: ISessionManager): TurnService {
        instanceCount++
        Logger.info("TurnService instance created (#$instanceCount)", Logger.Category.CALL)
        return TurnServiceImpl(
            wsSender = object : TurnServiceImpl.WsSender {
                override fun send(message: String): Boolean = wsClient.send(message)
            },
            sessionProvider = { sessionManager.sessionToken }
        )
    }

    @Provides
    @Singleton
    fun provideCallService(
        wsClient: WsClient,
        uiService: CallUiService,
        webRtcService: WebRtcService,
        turnService: TurnService,
        peerKeyProvider: PeerKeyProvider,
        securePrefs: SecurePrefs,
        sessionManager: ISessionManager
    ): CallService {
        instanceCount++
        Logger.info("CallService instance created (#$instanceCount)", Logger.Category.CALL)

        return CallService(
            wsSender = object : CallService.WsSender {
                override fun send(message: String): Boolean = wsClient.send(message)
            },
            uiService = uiService,
            webRtcService = webRtcService,
            turnService = turnService,
            keyStore = object : CallService.KeyStore {
                override fun getSignPublicKey(whisperId: String): ByteArray? {
                    return peerKeyProvider.getSignPublicKey(whisperId)
                }
                override fun getEncPublicKey(whisperId: String): ByteArray? {
                    return peerKeyProvider.getEncPublicKey(whisperId)
                }
            },
            myKeysProvider = object : CallService.MyKeysProvider {
                override fun getWhisperId(): String? = sessionManager.whisperId
                override fun getSignPrivateKey(): ByteArray? = securePrefs.signPrivateKey
                override fun getEncPrivateKey(): ByteArray? =
                    securePrefs.getBytesOrNull(Constants.StorageKey.ENC_PRIVATE_KEY)
                override fun getEncPublicKey(): ByteArray? =
                    securePrefs.getBytesOrNull(Constants.StorageKey.ENC_PUBLIC_KEY)
            },
            sessionProvider = { sessionManager.sessionToken }
        )
    }
}

/**
 * OkHttp implementation of AttachmentsApi
 */
private class OkHttpAttachmentsApi(
    private val client: OkHttpClient,
    private val gson: Gson,
    private val sessionTokenProvider: SessionTokenProvider,
    private val authFailureHandler: ApiAuthFailureHandler,
    private val baseUrl: String = Constants.Server.httpBaseUrl
) : AttachmentsApi {

    override suspend fun presignUpload(request: PresignUploadRequest): ApiResult<PresignUploadResponse> {
        return withContext(Dispatchers.IO) {
            try {
                val token = sessionTokenProvider.getSessionToken()
                if (token == null) {
                    authFailureHandler.onAuthFailure("No session token")
                    return@withContext ApiResult.Error(ApiErrorResponse.AUTH_FAILED, "No session token", 401)
                }

                val json = gson.toJson(request)
                val body = json.toRequestBody("application/json".toMediaType())

                val httpRequest = okhttp3.Request.Builder()
                    .url("$baseUrl/attachments/presign/upload")
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build()

                val response = client.newCall(httpRequest).execute()
                val responseBody = response.body?.string()

                if (response.isSuccessful && responseBody != null) {
                    val data = gson.fromJson(responseBody, PresignUploadResponse::class.java)
                    ApiResult.Success(data)
                } else {
                    if (response.code == 401) {
                        authFailureHandler.onAuthFailure("Unauthorized")
                    }
                    val error = try {
                        responseBody?.let { gson.fromJson(it, ApiErrorResponse::class.java) }
                    } catch (e: Exception) {
                        null
                    }
                    ApiResult.Error(
                        error?.code ?: ApiErrorResponse.INTERNAL_ERROR,
                        error?.message ?: "HTTP ${response.code}",
                        response.code
                    )
                }
            } catch (e: Exception) {
                ApiResult.Error(ApiErrorResponse.NETWORK_ERROR, e.message ?: "Network error", 0)
            }
        }
    }

    override suspend fun presignDownload(request: PresignDownloadRequest): ApiResult<PresignDownloadResponse> {
        return withContext(Dispatchers.IO) {
            try {
                val token = sessionTokenProvider.getSessionToken()
                if (token == null) {
                    authFailureHandler.onAuthFailure("No session token")
                    return@withContext ApiResult.Error(ApiErrorResponse.AUTH_FAILED, "No session token", 401)
                }

                val json = gson.toJson(request)
                val body = json.toRequestBody("application/json".toMediaType())

                val httpRequest = okhttp3.Request.Builder()
                    .url("$baseUrl/attachments/presign/download")
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build()

                val response = client.newCall(httpRequest).execute()
                val responseBody = response.body?.string()

                if (response.isSuccessful && responseBody != null) {
                    val data = gson.fromJson(responseBody, PresignDownloadResponse::class.java)
                    ApiResult.Success(data)
                } else {
                    if (response.code == 401) {
                        authFailureHandler.onAuthFailure("Unauthorized")
                    }
                    val error = try {
                        responseBody?.let { gson.fromJson(it, ApiErrorResponse::class.java) }
                    } catch (e: Exception) {
                        null
                    }
                    ApiResult.Error(
                        error?.code ?: ApiErrorResponse.INTERNAL_ERROR,
                        error?.message ?: "HTTP ${response.code}",
                        response.code
                    )
                }
            } catch (e: Exception) {
                ApiResult.Error(ApiErrorResponse.NETWORK_ERROR, e.message ?: "Network error", 0)
            }
        }
    }
}

/**
 * OkHttp implementation of BlobHttpClient
 */
private class OkHttpBlobClient(private val client: OkHttpClient) : BlobHttpClient {

    override suspend fun put(url: String, body: ByteArray, headers: Map<String, String>): BlobResult {
        return withContext(Dispatchers.IO) {
            try {
                val contentType = headers["Content-Type"] ?: "application/octet-stream"
                val requestBody = body.toRequestBody(contentType.toMediaType())

                val requestBuilder = okhttp3.Request.Builder()
                    .url(url)
                    .put(requestBody)

                headers.forEach { (key, value) ->
                    requestBuilder.addHeader(key, value)
                }

                val response = client.newCall(requestBuilder.build()).execute()
                if (response.isSuccessful) {
                    BlobResult.success(httpCode = response.code)
                } else {
                    BlobResult.failure(response.code)
                }
            } catch (e: Exception) {
                BlobResult.failure(0)
            }
        }
    }

    override suspend fun get(url: String): BlobResult {
        return withContext(Dispatchers.IO) {
            try {
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.bytes()
                    BlobResult.success(body, response.code)
                } else {
                    BlobResult.failure(response.code)
                }
            } catch (e: Exception) {
                BlobResult.failure(0)
            }
        }
    }
}

/**
 * OkHttp WebSocket connection implementation for DI
 */
private class OkHttpWsConnectionImpl(
    private val client: OkHttpClient,
    private val url: String,
    private val listener: WsListener
) : WsConnection {

    private var webSocket: okhttp3.WebSocket? = null

    override fun connect() {
        val request = okhttp3.Request.Builder()
            .url(url)
            .build()

        webSocket = client.newWebSocket(request, object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                listener.onOpen()
            }

            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                listener.onMessage(text)
            }

            override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                listener.onClose(code, reason)
            }

            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                listener.onError(t)
            }
        })
    }

    override fun send(text: String): Boolean {
        return webSocket?.send(text) ?: false
    }

    override fun close(code: Int, reason: String?) {
        webSocket?.close(code, reason)
    }

    override val isOpen: Boolean
        get() = webSocket != null
}
