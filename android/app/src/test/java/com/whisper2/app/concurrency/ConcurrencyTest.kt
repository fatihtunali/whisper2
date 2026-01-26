package com.whisper2.app.concurrency

import com.whisper2.app.core.Logger
import com.whisper2.app.core.Metrics
import com.whisper2.app.services.concurrency.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Step 13.3: Concurrency & Cancellation Tests
 *
 * Gate 1: Concurrent enqueue + disconnect + reconnect preserves invariants
 * Gate 2: Cancellation → no state leak
 */
class ConcurrencyTest {

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
    // Gate 1: SingleFlight coalesces concurrent calls
    // ==========================================================================

    @Test
    fun `gate1 SingleFlight coalesces concurrent calls with same key`() = runTest {
        val singleFlight = SingleFlight<String, Int>()
        val executionCount = AtomicInteger(0)

        val jobs = List(10) { i ->
            async {
                singleFlight.execute("test-key") {
                    delay(50)
                    executionCount.incrementAndGet()
                    42
                }
            }
        }

        val results = jobs.awaitAll()

        // All should get same result
        assertTrue(results.all { it == 42 })
        // Operation should only execute once
        assertEquals(1, executionCount.get())
        // Should have coalesced 9 requests
        assertTrue(singleFlight.coalescedCount >= 9)
    }

    @Test
    fun `gate1 SingleFlight allows different keys concurrently`() = runTest {
        val singleFlight = SingleFlight<String, Int>()
        val executionCount = AtomicInteger(0)

        val jobs = List(3) { i ->
            async {
                singleFlight.execute("key-$i") {
                    delay(50)
                    executionCount.incrementAndGet()
                }
            }
        }

        jobs.awaitAll()

        // Each key should execute independently
        assertEquals(3, executionCount.get())
        assertEquals(0, singleFlight.coalescedCount)
    }

    @Test
    fun `gate1 SingleFlight sequential calls both execute`() = runTest {
        val singleFlight = SingleFlight<String, Int>()
        val executionCount = AtomicInteger(0)

        // First call
        val result1 = singleFlight.execute("key") {
            executionCount.incrementAndGet()
            1
        }

        // Second call after first completes
        val result2 = singleFlight.execute("key") {
            executionCount.incrementAndGet()
            2
        }

        assertEquals(1, result1)
        assertEquals(2, result2)
        assertEquals(2, executionCount.get())
    }

    @Test
    fun `gate1 SingleFlight reports inFlight status`() = runTest {
        val singleFlight = SingleFlight<String, Int>()

        assertFalse(singleFlight.isInFlight("key"))
        assertEquals(0, singleFlight.inFlightCount())

        val job = async {
            singleFlight.execute("key") {
                delay(1000)
                42
            }
        }

        delay(50)
        assertTrue(singleFlight.isInFlight("key"))
        assertEquals(1, singleFlight.inFlightCount())

        job.cancel()
    }

    @Test
    fun `gate1 SingleFlight cancelAll stops operations`() = runTest {
        val singleFlight = SingleFlight<String, Int>()
        val started = AtomicInteger(0)
        val completed = AtomicInteger(0)

        val jobs = List(3) { i ->
            async {
                try {
                    singleFlight.execute("key-$i") {
                        started.incrementAndGet()
                        delay(5000)
                        completed.incrementAndGet()
                        42
                    }
                } catch (e: CancellationException) {
                    // Expected
                }
            }
        }

        delay(100)
        assertTrue(started.get() > 0)

        singleFlight.cancelAll()

        delay(100)
        assertTrue(completed.get() < started.get())
    }

    // ==========================================================================
    // Gate 1: RateLimitedExecutor prevents rapid-fire
    // ==========================================================================

    @Test
    fun `gate1 RateLimitedExecutor limits execution rate`() = runTest {
        // Start with time > minInterval so first call succeeds
        var currentTime = 10000L
        val executor = RateLimitedExecutor(
            minIntervalMs = 1000,
            timeProvider = { currentTime }
        )

        // First execution should succeed (time since epoch > minInterval)
        val result1 = executor.executeIfAllowed { "first" }
        assertEquals("first", result1)
        assertEquals(1, executor.executedCount)

        // Immediate retry should be skipped
        currentTime += 100
        val result2 = executor.executeIfAllowed { "second" }
        assertNull(result2)
        assertEquals(1, executor.skippedCount)

        // After interval, should succeed
        currentTime += 1000
        val result3 = executor.executeIfAllowed { "third" }
        assertEquals("third", result3)
        assertEquals(2, executor.executedCount)
    }

    @Test
    fun `gate1 RateLimitedExecutor forceExecute bypasses limit`() = runTest {
        // Start with time > minInterval so first call succeeds
        var currentTime = 10000L
        val executor = RateLimitedExecutor(
            minIntervalMs = 1000,
            timeProvider = { currentTime }
        )

        executor.executeIfAllowed { "first" }

        currentTime += 100 // Within rate limit

        // Force should work even within limit
        val result = executor.forceExecute { "forced" }
        assertEquals("forced", result)
        assertEquals(2, executor.executedCount)
    }

    // ==========================================================================
    // Gate 2: CancellationSafeState no leak on cancel
    // ==========================================================================

    @Test
    fun `gate2 CancellationSafeState cleans up on cancellation`() = runTest {
        val state = CancellationSafeState<String>()
        val cleanupCalled = AtomicInteger(0)

        val job = async {
            state.withValue(
                newValue = "active",
                cleanup = { cleanupCalled.incrementAndGet() }
            ) {
                delay(5000) // Long operation
                "result"
            }
        }

        delay(50)
        assertTrue(state.isSet())

        job.cancel()
        delay(50)

        // Cleanup should have been called
        assertEquals(1, cleanupCalled.get())
        assertFalse(state.isSet())
        assertNull(state.get())
    }

    @Test
    fun `gate2 CancellationSafeState normal completion cleans up`() = runTest {
        val state = CancellationSafeState<String>()
        val cleanupCalled = AtomicInteger(0)

        val result = state.withValue(
            newValue = "value",
            cleanup = { cleanupCalled.incrementAndGet() }
        ) {
            "result"
        }

        assertEquals("result", result)
        assertEquals(1, cleanupCalled.get())
        assertFalse(state.isSet())
    }

    @Test
    fun `gate2 CancellationSafeState exception cleans up`() = runTest {
        val state = CancellationSafeState<String>()
        val cleanupCalled = AtomicInteger(0)

        try {
            state.withValue(
                newValue = "value",
                cleanup = { cleanupCalled.incrementAndGet() }
            ) {
                throw RuntimeException("test error")
            }
            fail("Should have thrown")
        } catch (e: RuntimeException) {
            // Expected
        }

        assertEquals(1, cleanupCalled.get())
        assertFalse(state.isSet())
    }

    // ==========================================================================
    // Gate 1: Concurrent operations preserve invariants
    // ==========================================================================

    @Test
    fun `gate1 concurrent access to LruDeduplicator is safe`() = runTest {
        val dedup = com.whisper2.app.services.cleanup.LruDeduplicator(maxSize = 100)
        val errors = CopyOnWriteArrayList<Throwable>()

        val jobs = List(100) { i ->
            async(Dispatchers.Default) {
                try {
                    repeat(100) { j ->
                        val id = "msg-$i-$j"
                        dedup.markSeen(id)
                        dedup.isDuplicate(id)
                    }
                } catch (e: Throwable) {
                    errors.add(e)
                }
            }
        }

        jobs.awaitAll()

        assertTrue("No errors should occur", errors.isEmpty())
        assertTrue("Dedup should have items", dedup.size() > 0)
        assertTrue("Dedup should be within limit", dedup.size() <= 100)
    }

    @Test
    fun `gate1 concurrent access to LruAttachmentCache is safe`() = runTest {
        val cache = com.whisper2.app.services.attachments.LruAttachmentCache(
            maxEntries = 50,
            maxTotalBytes = 10000
        )
        val errors = CopyOnWriteArrayList<Throwable>()

        val jobs = List(50) { i ->
            async(Dispatchers.Default) {
                try {
                    repeat(50) { j ->
                        val key = "key-$i-$j"
                        cache.put(key, ByteArray(100))
                        cache.getIfPresent(key)
                        if (j % 10 == 0) {
                            cache.invalidate(key)
                        }
                    }
                } catch (e: Throwable) {
                    errors.add(e)
                }
            }
        }

        jobs.awaitAll()

        assertTrue("No errors should occur: ${errors.firstOrNull()}", errors.isEmpty())
        assertTrue("Cache should be within entry limit", cache.size() <= 50)
        assertTrue("Cache should be within byte limit", cache.totalBytes() <= 10000)
    }

    // ==========================================================================
    // Gate 2: Simulated concurrent WS disconnect + reconnect + enqueue
    // ==========================================================================

    @Test
    fun `gate2 simulated concurrent operations preserve queue invariants`() = runTest {
        val queue = FakeOutboxQueue()
        val errors = CopyOnWriteArrayList<Throwable>()

        // Simulate concurrent operations
        val jobs = List(10) { threadId ->
            async(Dispatchers.Default) {
                try {
                    repeat(10) { i ->
                        // Random operation
                        when ((threadId + i) % 4) {
                            0 -> queue.enqueue("msg-$threadId-$i")
                            1 -> queue.processQueue()
                            2 -> queue.onDisconnect()
                            3 -> queue.resume()
                        }
                        delay(1)
                    }
                } catch (e: Throwable) {
                    errors.add(e)
                }
            }
        }

        jobs.awaitAll()

        assertTrue("No errors should occur: ${errors.firstOrNull()}", errors.isEmpty())

        // Invariants: queue size should be non-negative
        assertTrue(queue.size() >= 0)
        // Invariants: no duplicate processing (single-flight)
        assertTrue(queue.duplicateProcessCount == 0)
    }

    // ==========================================================================
    // Helper classes
    // ==========================================================================

    /**
     * Fake outbox queue for concurrency testing
     */
    class FakeOutboxQueue {
        private val items = CopyOnWriteArrayList<String>()

        @Volatile
        private var isProcessing = false

        @Volatile
        private var isPaused = false

        var duplicateProcessCount = 0
            private set

        @Synchronized
        fun enqueue(item: String) {
            items.add(item)
        }

        @Synchronized
        fun processQueue() {
            if (isPaused) return

            if (isProcessing) {
                duplicateProcessCount++
                return
            }

            isProcessing = true
            try {
                // Simulate processing
                if (items.isNotEmpty()) {
                    items.removeAt(0)
                }
            } finally {
                isProcessing = false
            }
        }

        @Synchronized
        fun onDisconnect() {
            isPaused = true
        }

        @Synchronized
        fun resume() {
            isPaused = false
        }

        fun size(): Int = items.size
    }
}
