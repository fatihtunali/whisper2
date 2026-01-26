package com.whisper2.app.ws

import com.whisper2.app.network.ws.*
import com.whisper2.app.services.auth.ISessionManager
import com.whisper2.app.services.messaging.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Gate 3: onOpen sends fetch_pending automatically (once per open)
 */
class FetchPendingOnConnectTest {

    private lateinit var fakeConnectionFactory: FakeWsConnectionFactory
    private lateinit var scheduler: ImmediateScheduler
    private lateinit var sessionManager: TestSessionManager
    private lateinit var messageStore: InMemoryMessageStore
    private lateinit var deduper: Deduper
    private lateinit var pendingFetcher: PendingFetcher
    private lateinit var sentMessages: MutableList<String>
    private lateinit var wsClient: WsClient

    @Before
    fun setup() {
        fakeConnectionFactory = FakeWsConnectionFactory()
        scheduler = ImmediateScheduler()
        sessionManager = TestSessionManager()
        sessionManager.sessionToken = "sess_test_token"
        sessionManager._whisperId = "WSP-MY-ID"
        messageStore = InMemoryMessageStore()
        deduper = Deduper()
        sentMessages = mutableListOf()

        val messageSender = MessageSender { json ->
            sentMessages.add(json)
            true
        }

        pendingFetcher = PendingFetcher(
            sessionManager = sessionManager,
            messageStore = messageStore,
            deduper = deduper,
            messageSender = messageSender,
            myWhisperId = { sessionManager.whisperId }
        )

        // Create WsClient with onOpen callback that triggers fetch
        wsClient = WsClient(
            url = "wss://test.example.com/ws",
            connectionFactory = fakeConnectionFactory,
            reconnectPolicy = WsReconnectPolicy.noReconnect(),
            scheduler = scheduler,
            onOpenCallback = {
                val fetchJson = pendingFetcher.createFetchPending()
                fakeConnectionFactory.lastConnection?.send(fetchJson)
            }
        )
    }

    // ==========================================================================
    // Gate 3: Auto fetch on connect
    // ==========================================================================

    @Test
    fun `gate3 onOpen sends fetch_pending once`() {
        wsClient.connect()
        fakeConnectionFactory.lastConnection?.simulateOpen()

        // Verify fetch_pending was sent
        val sentMessage = fakeConnectionFactory.lastConnection?.lastSentMessage
        assertNotNull("Message should be sent on open", sentMessage)
        assertTrue("Should be fetch_pending", sentMessage!!.contains("\"type\":\"fetch_pending\""))

        // Verify fetch count
        assertEquals(1, pendingFetcher.fetchCount)
    }

    @Test
    fun `gate3 fetch_pending contains required fields`() {
        wsClient.connect()
        fakeConnectionFactory.lastConnection?.simulateOpen()

        val sentMessage = fakeConnectionFactory.lastConnection?.lastSentMessage!!

        assertTrue("Has sessionToken", sentMessage.contains("\"sessionToken\":\"sess_test_token\""))
        assertTrue("Has protocolVersion", sentMessage.contains("\"protocolVersion\":1"))
        assertTrue("Has cryptoVersion", sentMessage.contains("\"cryptoVersion\":1"))
        assertTrue("Has limit", sentMessage.contains("\"limit\":50"))
    }

    @Test
    fun `gate3 reconnect sends fetch_pending again`() {
        // First connection
        wsClient.connect()
        fakeConnectionFactory.lastConnection?.simulateOpen()
        assertEquals(1, pendingFetcher.fetchCount)

        // Disconnect
        wsClient.disconnect()

        // Reconnect
        wsClient.connect()
        fakeConnectionFactory.lastConnection?.simulateOpen()

        // Verify fetch was sent again
        assertEquals(2, pendingFetcher.fetchCount)
    }

    @Test
    fun `gate3 open count equals fetch count per open`() {
        // Connect 3 times
        for (i in 1..3) {
            wsClient.connect()
            fakeConnectionFactory.lastConnection?.simulateOpen()
            wsClient.disconnect()
        }

        assertEquals(3, wsClient.openCount)
        assertEquals(3, pendingFetcher.fetchCount)
    }

    @Test
    fun `gate3 no fetch if connect fails before open`() {
        wsClient.connect()

        // Simulate error before open
        fakeConnectionFactory.lastConnection?.simulateError(Exception("Connection failed"))

        assertEquals(0, pendingFetcher.fetchCount)
    }
}

// ==========================================================================
// Test Doubles
// ==========================================================================

class TestSessionManager : ISessionManager {
    override var isLoggedIn: Boolean = true
    override var sessionToken: String? = null
    override var deviceId: String? = "test-device-id"
    var _whisperId: String? = null
    override val whisperId: String? get() = _whisperId
    override var sessionExpiresAt: Long? = null
    override var serverTime: Long? = null

    override fun saveSession(token: String, deviceId: String) {
        this.sessionToken = token
        this.deviceId = deviceId
    }

    override fun saveFullSession(
        whisperId: String,
        sessionToken: String,
        sessionExpiresAt: Long,
        serverTime: Long
    ) {
        this._whisperId = whisperId
        this.sessionToken = sessionToken
        this.sessionExpiresAt = sessionExpiresAt
        this.serverTime = serverTime
    }

    override fun updateToken(newToken: String) {
        this.sessionToken = newToken
    }

    override fun forceLogout(reason: String) {
        sessionToken = null
        _whisperId = null
    }

    override fun softLogout() {
        sessionToken = null
    }
}
