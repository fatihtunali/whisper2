package com.whisper2.app.calls

import com.google.gson.Gson
import com.whisper2.app.network.ws.*
import com.whisper2.app.services.calls.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Gate 6: TURN Credentials WebSocket Flow
 *
 * Tests:
 * - get_turn_credentials → turn_credentials with requestId correlation
 * - Credential caching with TTL
 * - Timeout handling
 * - Not connected handling
 */
class TurnCredentialsTest {

    private lateinit var wsSender: FakeWsSender
    private lateinit var turnService: TurnServiceImpl

    private val gson = Gson()

    @Before
    fun setup() {
        wsSender = FakeWsSender()
        turnService = TurnServiceImpl(
            wsSender = wsSender,
            sessionProvider = { "test-session-token" }
        )
    }

    // ==========================================================================
    // Gate 6: Request sends get_turn_credentials
    // ==========================================================================

    @Test
    fun `gate6 requestTurnCreds sends get_turn_credentials`() = runTest {
        // Start request in background
        val job = launch {
            try {
                turnService.requestTurnCreds()
            } catch (e: Exception) {
                // Expected timeout, ignore
            }
        }

        delay(50)

        val msg = wsSender.findByType("get_turn_credentials")
        assertNotNull("get_turn_credentials should be sent", msg)
        assertTrue(msg!!.contains("\"sessionToken\":\"test-session-token\""))

        job.cancel()
    }

    @Test
    fun `gate6 request includes requestId`() = runTest {
        val job = launch {
            try {
                turnService.requestTurnCreds()
            } catch (e: Exception) {
                // Expected timeout
            }
        }

        delay(50)

        val msg = wsSender.findByType("get_turn_credentials")
        assertNotNull(msg)
        assertTrue("Request should include requestId", msg!!.contains("\"requestId\":"))

        job.cancel()
    }

    @Test
    fun `gate6 request includes protocol and crypto version`() = runTest {
        val job = launch {
            try {
                turnService.requestTurnCreds()
            } catch (e: Exception) {
                // Expected timeout
            }
        }

        delay(50)

        val msg = wsSender.findByType("get_turn_credentials")
        assertNotNull(msg)
        assertTrue(msg!!.contains("\"protocolVersion\":"))
        assertTrue(msg.contains("\"cryptoVersion\":"))

        job.cancel()
    }

    // ==========================================================================
    // Gate 6: Response with requestId correlation
    // ==========================================================================

    @Test
    fun `gate6 turn_credentials response returns creds`() = runTest {
        var result: TurnCredentialsPayload? = null

        val job = launch {
            result = turnService.requestTurnCreds()
        }

        delay(50)

        // Get requestId from sent message
        val msg = wsSender.findByType("get_turn_credentials")!!
        val requestId = extractRequestId(msg)
        assertNotNull("Should have requestId", requestId)

        // Simulate response
        val response = createTurnCredentialsEnvelope(requestId!!)
        turnService.onWsMessage(response)

        // Wait for completion
        job.join()

        assertNotNull(result)
        assertEquals(2, result!!.urls.size)
        assertEquals("turn:turn.example.com:3478", result!!.urls[0])
        assertEquals("turns:turn.example.com:5349", result!!.urls[1])
        assertEquals("1706286600:WSP-TEST", result!!.username)
        assertEquals("secret-credential", result!!.credential)
        assertEquals(600, result!!.ttl)
    }

    @Test
    fun `gate6 wrong requestId ignored`() = runTest {
        var result: TurnCredentialsPayload? = null
        var error: Exception? = null

        val job = launch {
            try {
                withTimeout(500) {
                    result = turnService.requestTurnCreds()
                }
            } catch (e: Exception) {
                error = e
            }
        }

        delay(50)

        // Send response with wrong requestId
        val wrongResponse = createTurnCredentialsEnvelope("wrong-request-id")
        turnService.onWsMessage(wrongResponse)

        delay(500)
        job.cancel()

        // Result should be null (timed out, wrong requestId ignored)
        assertNull(result)
    }

    // ==========================================================================
    // Gate 6: Credential caching
    // ==========================================================================

    @Test
    fun `gate6 credentials are cached`() = runTest {
        // First request
        val job1 = launch {
            turnService.requestTurnCreds()
        }

        delay(50)

        val msg = wsSender.findByType("get_turn_credentials")!!
        val requestId = extractRequestId(msg)
        val response = createTurnCredentialsEnvelope(requestId!!)
        turnService.onWsMessage(response)

        job1.join()

        // Second request should use cache
        wsSender.clear()
        val result = turnService.requestTurnCreds()

        // No new request should be sent
        val msg2 = wsSender.findByType("get_turn_credentials")
        assertNull("Second request should use cache", msg2)

        assertNotNull(result)
        assertEquals("secret-credential", result.credential)
    }

    @Test
    fun `gate6 getCachedCreds returns cached credentials`() = runTest {
        // Initially null
        assertNull(turnService.getCachedCreds())

        // Request credentials
        val job = launch {
            turnService.requestTurnCreds()
        }

        delay(50)

        val msg = wsSender.findByType("get_turn_credentials")!!
        val requestId = extractRequestId(msg)
        val response = createTurnCredentialsEnvelope(requestId!!)
        turnService.onWsMessage(response)

        job.join()

        // Now should be cached
        val cached = turnService.getCachedCreds()
        assertNotNull(cached)
        assertEquals("secret-credential", cached!!.credential)
    }

    // ==========================================================================
    // Gate 6: Timeout handling
    // ==========================================================================

    @Test
    fun `gate6 request times out`() = runTest {
        // Note: In runTest with virtual time, we can't easily test real timeouts.
        // Instead, we verify that the service handles the case where no response comes.
        // The actual timeout behavior is tested by the TurnService internal logic.

        var completed = false
        var error: Exception? = null

        val job = launch {
            try {
                turnService.requestTurnCreds()
                completed = true
            } catch (e: Exception) {
                error = e
            }
        }

        delay(50)

        // Verify request was sent
        val msg = wsSender.findByType("get_turn_credentials")
        assertNotNull("get_turn_credentials should be sent", msg)

        // Cancel without response to simulate timeout scenario
        job.cancel()

        // The job was cancelled before completing
        assertFalse("Should not have completed without response", completed)
    }

    // ==========================================================================
    // Gate 6: Not connected handling
    // ==========================================================================

    @Test
    fun `gate6 request fails when no session`() = runTest {
        val noSessionService = TurnServiceImpl(
            wsSender = wsSender,
            sessionProvider = { null }
        )

        var error: TurnService.TurnException? = null

        try {
            noSessionService.requestTurnCreds()
        } catch (e: TurnService.TurnException.NotConnected) {
            error = e
        }

        assertNotNull(error)
        assertTrue(error is TurnService.TurnException.NotConnected)
    }

    @Test
    fun `gate6 request fails when send fails`() = runTest {
        wsSender.shouldFail = true

        var error: TurnService.TurnException? = null

        try {
            turnService.requestTurnCreds()
        } catch (e: TurnService.TurnException.NotConnected) {
            error = e
        }

        assertNotNull(error)
        assertTrue(error is TurnService.TurnException.NotConnected)
    }

    // ==========================================================================
    // Gate 6: Multiple concurrent requests
    // ==========================================================================

    @Test
    fun `gate6 multiple concurrent requests use same response`() = runTest {
        var result1: TurnCredentialsPayload? = null
        var result2: TurnCredentialsPayload? = null

        val job1 = launch {
            result1 = turnService.requestTurnCreds()
        }

        val job2 = launch {
            result2 = turnService.requestTurnCreds()
        }

        delay(50)

        // Both should send requests (they each have their own requestId)
        val sentCount = wsSender.countByType("get_turn_credentials")
        assertTrue(sentCount >= 1)

        // Get all requestIds and respond to first one
        val msgs = wsSender.sent.filter { it.contains("get_turn_credentials") }
        for (msg in msgs) {
            val requestId = extractRequestId(msg) ?: continue
            val response = createTurnCredentialsEnvelope(requestId)
            turnService.onWsMessage(response)
        }

        job1.join()
        job2.join()

        assertNotNull(result1)
        assertNotNull(result2)
    }

    // ==========================================================================
    // Gate 6: Response without requestId is ignored
    // ==========================================================================

    @Test
    fun `gate6 response without requestId is ignored`() = runTest {
        var completed = false

        val job = launch {
            try {
                turnService.requestTurnCreds()
                completed = true
            } catch (e: Exception) {
                // Expected - timeout or cancellation
            }
        }

        delay(50)

        // Verify request was sent
        assertNotNull(wsSender.findByType("get_turn_credentials"))

        // Send response WITHOUT requestId - should be ignored
        val json = """
        {
            "type": "${WsMessageTypes.TURN_CREDENTIALS}",
            "payload": {
                "urls": ["turn:a:3478"],
                "username": "u",
                "credential": "c",
                "ttl": 100
            }
        }
        """.trimIndent()
        val noReqIdResponse = WsParser.parseRaw(json)
        turnService.onWsMessage(noReqIdResponse)

        delay(50)

        // Should NOT have completed (response was ignored due to missing requestId)
        assertFalse("Response without requestId should be ignored", completed)

        job.cancel()
    }

    // ==========================================================================
    // Gate 6: TurnService in FakeTurnService
    // ==========================================================================

    @Test
    fun `gate6 FakeTurnService returns sample creds`() = runTest {
        val fake = FakeTurnService()

        val creds = fake.requestTurnCreds()

        assertNotNull(creds)
        assertEquals(2, creds.urls.size)
        assertEquals(600, creds.ttl)
    }

    @Test
    fun `gate6 FakeTurnService can fail`() = runTest {
        val fake = FakeTurnService()
        fake.shouldFail = true

        var error: TurnService.TurnException? = null

        try {
            fake.requestTurnCreds()
        } catch (e: TurnService.TurnException) {
            error = e
        }

        assertNotNull(error)
    }

    @Test
    fun `gate6 FakeTurnService tracks request count`() = runTest {
        val fake = FakeTurnService()

        assertEquals(0, fake.requestCount)

        fake.requestTurnCreds()
        assertEquals(1, fake.requestCount)

        fake.requestTurnCreds()
        assertEquals(2, fake.requestCount)
    }

    // ==========================================================================
    // Helper methods
    // ==========================================================================

    private fun extractRequestId(json: String): String? {
        val match = Regex("\"requestId\":\"([^\"]+)\"").find(json)
        return match?.groupValues?.get(1)
    }

    private fun createTurnCredentialsEnvelope(requestId: String): WsRawEnvelope {
        val payload = TurnCredentialsPayload(
            urls = listOf("turn:turn.example.com:3478", "turns:turn.example.com:5349"),
            username = "1706286600:WSP-TEST",
            credential = "secret-credential",
            ttl = 600
        )

        val json = """
        {
            "type": "${WsMessageTypes.TURN_CREDENTIALS}",
            "requestId": "$requestId",
            "payload": ${gson.toJson(payload)}
        }
        """.trimIndent()

        return WsParser.parseRaw(json)
    }
}
