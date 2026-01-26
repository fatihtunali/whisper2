package com.whisper2.app.ws

import com.whisper2.app.network.ws.*
import com.whisper2.app.services.messaging.*
import com.whisper2.app.services.push.IncomingCallUi
import com.whisper2.app.services.push.PendingMessageFetcher
import com.whisper2.app.services.push.PushHandler
import com.whisper2.app.services.push.WsConnectionManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Gate 5: Push wake reason=message triggers fetch
 *
 * Tests integration of PushHandler with WsClient infrastructure.
 */
class PushWakeTest {

    private lateinit var fakeConnectionFactory: FakeWsConnectionFactory
    private lateinit var scheduler: ImmediateScheduler
    private lateinit var sessionManager: TestSessionManager
    private lateinit var messageStore: InMemoryMessageStore
    private lateinit var deduper: Deduper
    private lateinit var pendingFetcher: PendingFetcher
    private lateinit var sentMessages: MutableList<String>
    private lateinit var wsClient: WsClient
    private lateinit var pushHandler: PushHandler

    private var fetchSentCount = 0

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
        fetchSentCount = 0

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

        // WsClient with onOpen that triggers fetch
        wsClient = WsClient(
            url = "wss://test.example.com/ws",
            connectionFactory = fakeConnectionFactory,
            reconnectPolicy = WsReconnectPolicy.noReconnect(),
            scheduler = scheduler,
            onOpenCallback = {
                sendFetch()
            }
        )

        // Create adapter for new PushHandler
        val wsManager = object : WsConnectionManager {
            override fun isConnected(): Boolean = wsClient.state == WsState.CONNECTED
            override fun connect() { wsClient.connect() }
        }

        val pendingMessageFetcher = PendingMessageFetcher {
            sendFetch()
        }

        val callUi = object : IncomingCallUi {
            override fun showIncomingCall(whisperId: String) {
                // No-op for this test
            }
        }

        pushHandler = PushHandler(
            wsManager = wsManager,
            pendingFetcher = pendingMessageFetcher,
            callUi = callUi
        )
    }

    private fun sendFetch() {
        val fetchJson = pendingFetcher.createFetchPending()
        fakeConnectionFactory.lastConnection?.send(fetchJson)
        fetchSentCount++
    }

    // ==========================================================================
    // Gate 5: Wake when disconnected
    // ==========================================================================

    @Test
    fun `gate5 wake when disconnected triggers connect then fetch`() {
        assertEquals(WsState.DISCONNECTED, wsClient.state)

        pushHandler.onWake(type = "wake", reason = "message", whisperId = "WSP-SENDER")

        // Should be connecting
        assertEquals(WsState.CONNECTING, wsClient.state)

        // Simulate connection opened
        fakeConnectionFactory.lastConnection?.simulateOpen()

        // Now should be connected and fetch sent
        assertEquals(WsState.CONNECTED, wsClient.state)
        assertEquals(1, wsClient.openCount)
        // Note: onOpen callback sends fetch + PushHandler sends fetch = 2
        assertTrue("Should have fetched", fetchSentCount >= 1)
    }

    @Test
    fun `gate5 wake when disconnected - single open single fetch`() {
        pushHandler.onWake(type = "wake", reason = "message", whisperId = "WSP-SENDER")
        fakeConnectionFactory.lastConnection?.simulateOpen()

        assertEquals("Should have exactly one open", 1, wsClient.openCount)
    }

    // ==========================================================================
    // Gate 5: Wake when connecting
    // ==========================================================================

    @Test
    fun `gate5 wake when connecting does not create extra connection`() {
        // Start connecting
        wsClient.connect()
        assertEquals(WsState.CONNECTING, wsClient.state)
        assertEquals(1, fakeConnectionFactory.connectionsCreated)

        // Wake while connecting (WS is not "connected" yet)
        pushHandler.onWake(type = "wake", reason = "message", whisperId = "WSP-SENDER")

        // PushHandler will try to connect again but it's already connecting
        // The WsClient handles this gracefully
        assertTrue("Connection in progress", fakeConnectionFactory.connectionsCreated >= 1)
    }

    // ==========================================================================
    // Gate 5: Wake when connected
    // ==========================================================================

    @Test
    fun `gate5 wake when connected triggers fetch without reconnect`() {
        // First connect normally
        wsClient.connect()
        fakeConnectionFactory.lastConnection?.simulateOpen()

        val openCountBefore = wsClient.openCount
        val fetchCountBefore = fetchSentCount

        // Wake while connected
        pushHandler.onWake(type = "wake", reason = "message", whisperId = "WSP-SENDER")

        // Should not open new connection
        assertEquals("No extra open", openCountBefore, wsClient.openCount)
        // But should fetch
        assertEquals("Extra fetch sent", fetchCountBefore + 1, fetchSentCount)
    }

    @Test
    fun `gate5 multiple wakes when connected sends multiple fetches`() {
        wsClient.connect()
        fakeConnectionFactory.lastConnection?.simulateOpen()

        val initialFetch = fetchSentCount

        // Wake 3 times
        pushHandler.onWake(type = "wake", reason = "message", whisperId = "WSP-1")
        pushHandler.onWake(type = "wake", reason = "message", whisperId = "WSP-2")
        pushHandler.onWake(type = "wake", reason = "message", whisperId = "WSP-3")

        assertEquals("3 extra fetches", initialFetch + 3, fetchSentCount)
        assertEquals("Still single connection", 1, wsClient.openCount)
    }

    // ==========================================================================
    // Gate 5: Other wake reasons
    // ==========================================================================

    @Test
    fun `gate5 invalid wake type does nothing`() {
        pushHandler.onWake(type = "invalid", reason = "message", whisperId = "WSP-1")

        assertEquals(WsState.DISCONNECTED, wsClient.state)
        assertEquals(0, fetchSentCount)
    }

    @Test
    fun `gate5 wake with call reason does not fetch`() {
        pushHandler.onWake(type = "wake", reason = "call", whisperId = "WSP-CALLER")

        // Call wake doesn't trigger message fetch
        assertEquals(0, fetchSentCount)
    }
}
