package com.whisper2.app.observability

import com.whisper2.app.core.Logger
import com.whisper2.app.core.Metrics
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Step 13.1 Gate 1: Log events captured via Logger interface
 *
 * Tests that log events are properly captured and can be observed
 * through the LogEventListener interface.
 */
class ObservabilityTest {

    private lateinit var capturedEvents: CopyOnWriteArrayList<Logger.LogEvent>

    @Before
    fun setup() {
        capturedEvents = CopyOnWriteArrayList()
        Logger.androidLogEnabled = false
        Logger.setListener { event -> capturedEvents.add(event) }
        Metrics.resetAll()
    }

    @After
    fun teardown() {
        Logger.clearListener()
        Logger.androidLogEnabled = true
    }

    // ==========================================================================
    // Gate 1: Log events captured via Logger interface
    // ==========================================================================

    @Test
    fun `gate1 debug log captured with category`() {
        Logger.debug("Test debug message", Logger.Category.NETWORK)

        assertEquals(1, capturedEvents.size)
        val event = capturedEvents[0]
        assertEquals(Logger.Level.DEBUG, event.level)
        assertEquals(Logger.Category.NETWORK, event.category)
        assertEquals("Test debug message", event.message)
        assertNull(event.throwable)
    }

    @Test
    fun `gate1 info log captured with category`() {
        Logger.info("Test info message", Logger.Category.AUTH)

        assertEquals(1, capturedEvents.size)
        val event = capturedEvents[0]
        assertEquals(Logger.Level.INFO, event.level)
        assertEquals(Logger.Category.AUTH, event.category)
        assertEquals("Test info message", event.message)
    }

    @Test
    fun `gate1 warn log captured with throwable`() {
        val exception = RuntimeException("Test error")
        Logger.warn("Test warning", Logger.Category.MESSAGING, exception)

        assertEquals(1, capturedEvents.size)
        val event = capturedEvents[0]
        assertEquals(Logger.Level.WARN, event.level)
        assertEquals(Logger.Category.MESSAGING, event.category)
        assertEquals("Test warning", event.message)
        assertEquals(exception, event.throwable)
    }

    @Test
    fun `gate1 error log captured with throwable`() {
        val exception = IllegalStateException("Critical error")
        Logger.error("Test error", Logger.Category.CRYPTO, exception)

        assertEquals(1, capturedEvents.size)
        val event = capturedEvents[0]
        assertEquals(Logger.Level.ERROR, event.level)
        assertEquals(Logger.Category.CRYPTO, event.category)
        assertEquals("Test error", event.message)
        assertEquals(exception, event.throwable)
    }

    @Test
    fun `gate1 multiple logs captured in order`() {
        Logger.debug("First", Logger.Category.GENERAL)
        Logger.info("Second", Logger.Category.NETWORK)
        Logger.warn("Third", Logger.Category.AUTH)
        Logger.error("Fourth", Logger.Category.CALL)

        assertEquals(4, capturedEvents.size)
        assertEquals("First", capturedEvents[0].message)
        assertEquals("Second", capturedEvents[1].message)
        assertEquals("Third", capturedEvents[2].message)
        assertEquals("Fourth", capturedEvents[3].message)
    }

    @Test
    fun `gate1 log timestamp is populated`() {
        val before = System.currentTimeMillis()
        Logger.info("Test", Logger.Category.GENERAL)
        val after = System.currentTimeMillis()

        val event = capturedEvents[0]
        assertTrue(event.timestamp >= before)
        assertTrue(event.timestamp <= after)
    }

    @Test
    fun `gate1 new categories exist`() {
        Logger.debug("Outbox event", Logger.Category.OUTBOX)
        Logger.debug("Push event", Logger.Category.PUSH)
        Logger.debug("Cleanup event", Logger.Category.CLEANUP)

        assertEquals(3, capturedEvents.size)
        assertEquals(Logger.Category.OUTBOX, capturedEvents[0].category)
        assertEquals(Logger.Category.PUSH, capturedEvents[1].category)
        assertEquals(Logger.Category.CLEANUP, capturedEvents[2].category)
    }

    @Test
    fun `gate1 listener cleared stops capture`() {
        Logger.debug("Should capture", Logger.Category.GENERAL)
        assertEquals(1, capturedEvents.size)

        Logger.clearListener()

        Logger.debug("Should not capture", Logger.Category.GENERAL)
        assertEquals(1, capturedEvents.size) // Still 1
    }

    // ==========================================================================
    // Gate 1: PII masking
    // ==========================================================================

    @Test
    fun `gate1 maskPii hides WhisperID`() {
        val masked = Logger.maskPii("Contact WSP-ABCD-EFGH-IJKL joined")
        assertEquals("Contact WSP-****-****-IJKL joined", masked)
    }

    @Test
    fun `gate1 maskedId returns masked ID`() {
        val masked = Logger.maskedId("WSP-ABCD-EFGH-IJKL")
        assertEquals("WSP-****-****-IJKL", masked)
    }

    @Test
    fun `gate1 maskedId handles null`() {
        val masked = Logger.maskedId(null)
        assertEquals("<null>", masked)
    }

    @Test
    fun `gate1 maskedId handles non-WSP format`() {
        val masked = Logger.maskedId("not-a-whisper-id")
        assertEquals("not-a-whisper-id", masked)
    }

    // ==========================================================================
    // Gate 1: Metrics recording triggers logs
    // ==========================================================================

    @Test
    fun `gate1 WS metrics record logs`() {
        Metrics.Ws.recordConnect()

        val wsEvents = capturedEvents.filter { it.category == Logger.Category.NETWORK }
        assertTrue(wsEvents.isNotEmpty())
        assertTrue(wsEvents.any { it.message.contains("connected") })
    }

    @Test
    fun `gate1 WS disconnect logs with code and reason`() {
        Metrics.Ws.recordDisconnect(1001, "going away")

        val event = capturedEvents.find { it.message.contains("disconnected") }
        assertNotNull(event)
        assertTrue(event!!.message.contains("1001"))
        assertTrue(event.message.contains("going away"))
    }

    @Test
    fun `gate1 Outbox metrics record logs`() {
        Metrics.Outbox.recordEnqueued()
        Metrics.Outbox.recordSent()
        Metrics.Outbox.recordFailed("timeout")

        val outboxEvents = capturedEvents.filter { it.category == Logger.Category.OUTBOX }
        assertEquals(3, outboxEvents.size)
    }

    @Test
    fun `gate1 Call metrics record logs`() {
        Metrics.Call.recordInitiated()
        Metrics.Call.recordConnected(1500)
        Metrics.Call.recordFailed("ICE_FAILED")

        val callEvents = capturedEvents.filter { it.category == Logger.Category.CALL }
        assertTrue(callEvents.size >= 3)
        assertTrue(callEvents.any { it.message.contains("initiated") })
        assertTrue(callEvents.any { it.message.contains("connected") })
        assertTrue(callEvents.any { it.message.contains("failed") })
    }

    @Test
    fun `gate1 Push metrics record logs`() {
        Metrics.Push.recordWake("message")
        Metrics.Push.recordContentIgnored()

        val pushEvents = capturedEvents.filter { it.category == Logger.Category.PUSH }
        assertEquals(2, pushEvents.size)
    }

    @Test
    fun `gate1 Cleanup metrics record logs`() {
        Metrics.Cleanup.recordOutboxCleaned(5)
        Metrics.Cleanup.recordAttachmentsEvicted(3)

        val cleanupEvents = capturedEvents.filter { it.category == Logger.Category.CLEANUP }
        assertEquals(2, cleanupEvents.size)
    }

    // ==========================================================================
    // Metrics value tests
    // ==========================================================================

    @Test
    fun `metrics WS counters increment`() {
        assertEquals(0, Metrics.Ws.connectCount)
        Metrics.Ws.recordConnect()
        assertEquals(1, Metrics.Ws.connectCount)
        Metrics.Ws.recordConnect()
        assertEquals(2, Metrics.Ws.connectCount)
    }

    @Test
    fun `metrics Outbox counters track queue`() {
        Metrics.Outbox.recordEnqueued()
        Metrics.Outbox.recordEnqueued()
        assertEquals(2, Metrics.Outbox.enqueuedCount)
        assertEquals(2, Metrics.Outbox.currentQueueSize)

        Metrics.Outbox.recordSent()
        assertEquals(1, Metrics.Outbox.sentCount)
        assertEquals(1, Metrics.Outbox.currentQueueSize)
    }

    @Test
    fun `metrics Outbox retry histogram`() {
        Metrics.Outbox.recordRetry(1)
        Metrics.Outbox.recordRetry(1)
        Metrics.Outbox.recordRetry(2)
        Metrics.Outbox.recordRetry(3)

        val histogram = Metrics.Outbox.getRetryHistogram()
        assertEquals(2, histogram[1])
        assertEquals(1, histogram[2])
        assertEquals(1, histogram[3])
    }

    @Test
    fun `metrics Call timing recorded`() {
        Metrics.Call.recordRinging(500)
        assertEquals(500, Metrics.Call.lastTimeToRingingMs)

        Metrics.Call.recordConnected(2000)
        assertEquals(2000, Metrics.Call.lastTimeToConnectMs)
    }

    @Test
    fun `metrics Call ICE failures tracked`() {
        Metrics.Call.recordFailed("ICE_TIMEOUT")
        Metrics.Call.recordFailed("ICE_TIMEOUT")
        Metrics.Call.recordFailed("NO_NETWORK")

        val reasons = Metrics.Call.getIceFailReasons()
        assertEquals(2, reasons["ICE_TIMEOUT"])
        assertEquals(1, reasons["NO_NETWORK"])
    }

    @Test
    fun `metrics Push counts breakdown`() {
        Metrics.Push.recordWake("message")
        Metrics.Push.recordWake("message")
        Metrics.Push.recordWake("call")

        assertEquals(3, Metrics.Push.wakeCount)
        assertEquals(2, Metrics.Push.wakeMessageCount)
        assertEquals(1, Metrics.Push.wakeCallCount)
    }

    @Test
    fun `metrics reset clears all`() {
        Metrics.Ws.recordConnect()
        Metrics.Outbox.recordEnqueued()
        Metrics.Call.recordInitiated()
        Metrics.Push.recordWake("message")
        Metrics.Cleanup.recordOutboxCleaned(1)

        Metrics.resetAll()

        assertEquals(0, Metrics.Ws.connectCount)
        assertEquals(0, Metrics.Outbox.enqueuedCount)
        assertEquals(0, Metrics.Call.initiatedCount)
        assertEquals(0, Metrics.Push.wakeCount)
        assertEquals(0, Metrics.Cleanup.outboxCleanedCount)
    }
}
