package com.whisper2.app.ws

import com.whisper2.app.network.ws.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Gate 2: Single active WS connection + lifecycle
 */
class WsConnectionLifecycleTest {

    private lateinit var fakeConnectionFactory: FakeWsConnectionFactory
    private lateinit var scheduler: ImmediateScheduler
    private lateinit var wsClient: WsClient

    @Before
    fun setup() {
        fakeConnectionFactory = FakeWsConnectionFactory()
        scheduler = ImmediateScheduler()

        wsClient = WsClient(
            url = "wss://test.example.com/ws",
            connectionFactory = fakeConnectionFactory,
            reconnectPolicy = WsReconnectPolicy.noReconnect(),
            scheduler = scheduler
        )
    }

    // ==========================================================================
    // Gate 2: Single active connection
    // ==========================================================================

    @Test
    fun `gate2 initial state is DISCONNECTED`() {
        assertEquals(WsState.DISCONNECTED, wsClient.state)
    }

    @Test
    fun `gate2 connect changes state to CONNECTING`() {
        wsClient.connect()

        assertEquals(WsState.CONNECTING, wsClient.state)
        assertEquals(1, fakeConnectionFactory.connectionsCreated)
    }

    @Test
    fun `gate2 connect twice creates only one connection`() {
        wsClient.connect()
        wsClient.connect() // Second call should be ignored

        assertEquals(WsState.CONNECTING, wsClient.state)
        assertEquals("Only one connection should be created", 1, fakeConnectionFactory.connectionsCreated)
    }

    @Test
    fun `gate2 onOpen changes state to CONNECTED`() {
        wsClient.connect()
        fakeConnectionFactory.lastConnection?.simulateOpen()

        assertEquals(WsState.CONNECTED, wsClient.state)
        assertEquals(1, wsClient.openCount)
    }

    @Test
    fun `gate2 connect while connected is ignored`() {
        wsClient.connect()
        fakeConnectionFactory.lastConnection?.simulateOpen()

        // Try to connect again while already connected
        wsClient.connect()

        assertEquals(WsState.CONNECTED, wsClient.state)
        assertEquals("Still only one connection", 1, fakeConnectionFactory.connectionsCreated)
    }

    @Test
    fun `gate2 disconnect changes state to DISCONNECTED`() {
        wsClient.connect()
        fakeConnectionFactory.lastConnection?.simulateOpen()

        wsClient.disconnect()

        assertEquals(WsState.DISCONNECTED, wsClient.state)
    }

    @Test
    fun `gate2 unexpected close changes state to DISCONNECTED`() {
        wsClient.connect()
        fakeConnectionFactory.lastConnection?.simulateOpen()

        // Simulate unexpected close (not 1000)
        fakeConnectionFactory.lastConnection?.simulateClose(1006, "connection lost")

        assertEquals(WsState.DISCONNECTED, wsClient.state)
    }

    @Test
    fun `gate2 isConnected returns correct value`() {
        assertFalse(wsClient.isConnected())

        wsClient.connect()
        assertFalse(wsClient.isConnected()) // Still connecting

        fakeConnectionFactory.lastConnection?.simulateOpen()
        assertTrue(wsClient.isConnected())

        wsClient.disconnect()
        assertFalse(wsClient.isConnected())
    }

    @Test
    fun `gate2 send fails when not connected`() {
        val result = wsClient.send("test message")

        assertFalse("Send should fail when disconnected", result)
    }

    @Test
    fun `gate2 send succeeds when connected`() {
        wsClient.connect()
        fakeConnectionFactory.lastConnection?.simulateOpen()

        val result = wsClient.send("test message")

        assertTrue("Send should succeed when connected", result)
        assertEquals("test message", fakeConnectionFactory.lastConnection?.lastSentMessage)
    }

    @Test
    fun `gate2 open count increments on each connect`() {
        // First connection
        wsClient.connect()
        fakeConnectionFactory.lastConnection?.simulateOpen()
        assertEquals(1, wsClient.openCount)

        // Disconnect
        wsClient.disconnect()

        // Second connection
        wsClient.connect()
        fakeConnectionFactory.lastConnection?.simulateOpen()
        assertEquals(2, wsClient.openCount)
    }
}

// ==========================================================================
// Test Doubles
// ==========================================================================

class FakeWsConnectionFactory : WsConnectionFactory {
    var connectionsCreated = 0
    var lastConnection: FakeWsConnection? = null

    override fun create(url: String, listener: WsListener): WsConnection {
        connectionsCreated++
        val connection = FakeWsConnection(listener)
        lastConnection = connection
        return connection
    }
}

class FakeWsConnection(
    private val listener: WsListener
) : WsConnection {
    private var _isOpen = false
    var lastSentMessage: String? = null
    var closeCode: Int? = null

    override val isOpen: Boolean get() = _isOpen

    override fun connect() {
        // Connection is async - simulateOpen() will complete it
    }

    override fun send(text: String): Boolean {
        if (!_isOpen) return false
        lastSentMessage = text
        return true
    }

    override fun close(code: Int, reason: String?) {
        _isOpen = false
        closeCode = code
    }

    // Test helpers
    fun simulateOpen() {
        _isOpen = true
        listener.onOpen()
    }

    fun simulateClose(code: Int, reason: String?) {
        _isOpen = false
        listener.onClose(code, reason)
    }

    fun simulateMessage(text: String) {
        listener.onMessage(text)
    }

    fun simulateError(error: Throwable) {
        _isOpen = false
        listener.onError(error)
    }
}
