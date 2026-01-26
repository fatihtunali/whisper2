package com.whisper2.app.cleanup

import com.whisper2.app.core.Logger
import com.whisper2.app.core.Metrics
import com.whisper2.app.services.attachments.LruAttachmentCache
import com.whisper2.app.services.cleanup.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Step 13.2: Data Hygiene Tests
 *
 * Gate 1: Cleanup job → deletes old records, keeps new ones
 * Gate 2: Attachment cache eviction when limit exceeded
 */
class DataHygieneTest {

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
    // Gate 1: Cleanup job → deletes old records, keeps new ones
    // ==========================================================================

    @Test
    fun `gate1 cleanup identifies old sent items`() {
        // Use a base time that's large enough to handle 7-day TTL math
        val sevenDays = 7 * 24 * 60 * 60 * 1000L
        val baseTime = sevenDays * 2 // Start at 14 days
        var currentTime = baseTime

        val cleanup = DataCleanup(
            sentTtlMs = sevenDays,
            timeProvider = { currentTime }
        )

        val items = listOf(
            // Sent 10 days ago (past TTL)
            OutboxCleanupItem("1", "msg-1", "sent", baseTime - 10 * 24 * 60 * 60 * 1000L,
                sentAt = baseTime - 10 * 24 * 60 * 60 * 1000L),
            // Sent 1 day ago (recent, within TTL)
            OutboxCleanupItem("2", "msg-2", "sent", baseTime - 1 * 24 * 60 * 60 * 1000L,
                sentAt = baseTime - 1 * 24 * 60 * 60 * 1000L),
            // Not sent (queued)
            OutboxCleanupItem("3", "msg-3", "queued", baseTime - 10 * 24 * 60 * 60 * 1000L, sentAt = null),
            // Failed
            OutboxCleanupItem("4", "msg-4", "failed", baseTime - 10 * 24 * 60 * 60 * 1000L, sentAt = null)
        )

        val toDelete = cleanup.cleanupOutbox(items)

        assertEquals(1, toDelete.size)
        assertTrue(toDelete.contains("1")) // Old sent - should be deleted
        assertFalse(toDelete.contains("2")) // Too recent
        assertFalse(toDelete.contains("3")) // Not sent
        assertFalse(toDelete.contains("4")) // Failed
    }

    @Test
    fun `gate1 cleanup keeps items within TTL`() {
        var currentTime = 1000000L
        val cleanup = DataCleanup(
            sentTtlMs = 60000, // 1 minute for testing
            timeProvider = { currentTime }
        )

        val items = listOf(
            OutboxCleanupItem("1", "msg-1", "sent", currentTime - 30000, sentAt = currentTime - 30000)
        )

        val toDelete = cleanup.cleanupOutbox(items)

        assertTrue("Items within TTL should be kept", toDelete.isEmpty())
    }

    @Test
    fun `gate1 cleanup deletes items past TTL`() {
        var currentTime = 1000000L
        val cleanup = DataCleanup(
            sentTtlMs = 60000, // 1 minute
            timeProvider = { currentTime }
        )

        val items = listOf(
            OutboxCleanupItem("1", "msg-1", "sent", currentTime - 120000, sentAt = currentTime - 120000)
        )

        val toDelete = cleanup.cleanupOutbox(items)

        assertEquals(1, toDelete.size)
        assertEquals("1", toDelete[0])
    }

    @Test
    fun `gate1 cleanup logs and records metrics`() {
        var currentTime = 1000000L
        val cleanup = DataCleanup(
            sentTtlMs = 60000,
            timeProvider = { currentTime }
        )

        val items = listOf(
            OutboxCleanupItem("1", "msg-1", "sent", 100000, sentAt = 100000),
            OutboxCleanupItem("2", "msg-2", "sent", 100000, sentAt = 100000),
            OutboxCleanupItem("3", "msg-3", "sent", 100000, sentAt = 100000)
        )

        cleanup.cleanupOutbox(items)

        // Should have logged
        val cleanupEvents = capturedEvents.filter { it.category == Logger.Category.CLEANUP }
        assertTrue(cleanupEvents.isNotEmpty())

        // Should have recorded metrics
        assertEquals(3, Metrics.Cleanup.outboxCleanedCount)
    }

    @Test
    fun `gate1 cleanup handles empty list`() {
        val cleanup = DataCleanup()
        val toDelete = cleanup.cleanupOutbox(emptyList())
        assertTrue(toDelete.isEmpty())
    }

    @Test
    fun `gate1 shouldCleanup returns true for old sent items`() {
        var currentTime = 1000000L
        val cleanup = DataCleanup(
            sentTtlMs = 60000,
            timeProvider = { currentTime }
        )

        val oldItem = OutboxCleanupItem("1", "msg-1", "sent", 100000, sentAt = 100000)
        assertTrue(cleanup.shouldCleanup(oldItem))

        val newItem = OutboxCleanupItem("2", "msg-2", "sent", currentTime - 30000, sentAt = currentTime - 30000)
        assertFalse(cleanup.shouldCleanup(newItem))
    }

    @Test
    fun `gate1 shouldCleanup returns false for non-sent items`() {
        val cleanup = DataCleanup(sentTtlMs = 60000, timeProvider = { 1000000L })

        val queued = OutboxCleanupItem("1", "msg-1", "queued", 100000, sentAt = null)
        assertFalse(cleanup.shouldCleanup(queued))

        val sending = OutboxCleanupItem("2", "msg-2", "sending", 100000, sentAt = null)
        assertFalse(cleanup.shouldCleanup(sending))

        val failed = OutboxCleanupItem("3", "msg-3", "failed", 100000, sentAt = null)
        assertFalse(cleanup.shouldCleanup(failed))
    }

    // ==========================================================================
    // Gate 1: Dedup cleanup
    // ==========================================================================

    @Test
    fun `gate1 dedup cleanup identifies old records`() {
        val ttlMs = 100000L // 100 seconds for testing
        var currentTime = 500000L
        val cleanup = DataCleanup(timeProvider = { currentTime })

        val records = listOf(
            DedupeCleanupRecord("msg-1", 350000), // 150s ago -> past TTL (should delete)
            DedupeCleanupRecord("msg-2", 450000), // 50s ago -> within TTL (should keep)
            DedupeCleanupRecord("msg-3", 200000)  // 300s ago -> past TTL (should delete)
        )

        val toDelete = cleanup.cleanupDedup(records, ttlMs = ttlMs)

        assertEquals(2, toDelete.size)
        assertTrue(toDelete.contains("msg-1"))
        assertTrue(toDelete.contains("msg-3"))
        assertFalse(toDelete.contains("msg-2"))
    }

    // ==========================================================================
    // Gate 1: LRU Deduplicator
    // ==========================================================================

    @Test
    fun `gate1 LruDeduplicator tracks seen messages`() {
        val dedup = LruDeduplicator(maxSize = 100)

        assertFalse(dedup.isDuplicate("msg-1"))
        assertTrue(dedup.markSeen("msg-1"))
        assertTrue(dedup.isDuplicate("msg-1"))
    }

    @Test
    fun `gate1 LruDeduplicator evicts oldest when over limit`() {
        val dedup = LruDeduplicator(maxSize = 3)

        dedup.markSeen("msg-1")
        dedup.markSeen("msg-2")
        dedup.markSeen("msg-3")
        assertEquals(3, dedup.size())

        // Adding 4th should evict oldest (msg-1)
        dedup.markSeen("msg-4")
        assertEquals(3, dedup.size())
        assertFalse("msg-1 should be evicted", dedup.isDuplicate("msg-1"))
        assertTrue(dedup.isDuplicate("msg-2"))
        assertTrue(dedup.isDuplicate("msg-3"))
        assertTrue(dedup.isDuplicate("msg-4"))
    }

    @Test
    fun `gate1 LruDeduplicator access updates order`() {
        val dedup = LruDeduplicator(maxSize = 3)

        dedup.markSeen("msg-1")
        dedup.markSeen("msg-2")
        dedup.markSeen("msg-3")

        // Access msg-1 to make it recently used
        dedup.isDuplicate("msg-1")

        // Add msg-4, should evict msg-2 (now oldest)
        dedup.markSeen("msg-4")

        assertTrue("msg-1 should still exist (was accessed)", dedup.isDuplicate("msg-1"))
        assertFalse("msg-2 should be evicted", dedup.isDuplicate("msg-2"))
    }

    // ==========================================================================
    // Gate 2: Attachment cache eviction when limit exceeded
    // ==========================================================================

    @Test
    fun `gate2 LruAttachmentCache evicts when entry limit exceeded`() {
        val cache = LruAttachmentCache(maxEntries = 3, maxTotalBytes = Long.MAX_VALUE)

        cache.put("key1", ByteArray(100))
        cache.put("key2", ByteArray(100))
        cache.put("key3", ByteArray(100))
        assertEquals(3, cache.size())

        // Adding 4th should evict oldest
        cache.put("key4", ByteArray(100))
        assertEquals(3, cache.size())
        assertNull("key1 should be evicted", cache.getIfPresent("key1"))
        assertNotNull(cache.getIfPresent("key4"))
    }

    @Test
    fun `gate2 LruAttachmentCache evicts when byte limit exceeded`() {
        val cache = LruAttachmentCache(maxEntries = 100, maxTotalBytes = 250)

        cache.put("key1", ByteArray(100))
        cache.put("key2", ByteArray(100))
        assertEquals(200, cache.totalBytes())

        // Adding 100 more bytes would exceed 250 limit
        cache.put("key3", ByteArray(100))

        // Should have evicted key1 to make room
        assertTrue(cache.totalBytes() <= 250)
        assertNull("key1 should be evicted", cache.getIfPresent("key1"))
    }

    @Test
    fun `gate2 LruAttachmentCache access updates LRU order`() {
        val cache = LruAttachmentCache(maxEntries = 3, maxTotalBytes = Long.MAX_VALUE)

        cache.put("key1", ByteArray(100))
        cache.put("key2", ByteArray(100))
        cache.put("key3", ByteArray(100))

        // Access key1 to make it recently used
        cache.getIfPresent("key1")

        // Add key4, should evict key2 (now oldest)
        cache.put("key4", ByteArray(100))

        assertNotNull("key1 should still exist", cache.getIfPresent("key1"))
        assertNull("key2 should be evicted", cache.getIfPresent("key2"))
        assertNotNull(cache.getIfPresent("key3"))
        assertNotNull(cache.getIfPresent("key4"))
    }

    @Test
    fun `gate2 LruAttachmentCache eviction records metrics`() {
        val cache = LruAttachmentCache(maxEntries = 2, maxTotalBytes = Long.MAX_VALUE)

        cache.put("key1", ByteArray(100))
        cache.put("key2", ByteArray(100))
        cache.put("key3", ByteArray(100)) // Triggers eviction

        assertEquals(1, cache.evictionCount)
        assertTrue(Metrics.Cleanup.attachmentsEvictedCount >= 1)
    }

    @Test
    fun `gate2 LruAttachmentCache logs eviction`() {
        val cache = LruAttachmentCache(maxEntries = 2, maxTotalBytes = Long.MAX_VALUE)

        cache.put("key1", ByteArray(100))
        cache.put("key2", ByteArray(100))
        cache.put("key3", ByteArray(100))

        val evictionLogs = capturedEvents.filter {
            it.category == Logger.Category.CLEANUP && it.message.contains("evicted")
        }
        assertTrue(evictionLogs.isNotEmpty())
    }

    @Test
    fun `gate2 LruAttachmentCache stats accurate`() {
        val cache = LruAttachmentCache(maxEntries = 10, maxTotalBytes = 1000)

        cache.put("key1", ByteArray(100))
        cache.put("key2", ByteArray(200))

        val stats = cache.stats()
        assertEquals(2, stats.entries)
        assertEquals(300, stats.totalBytes)
        assertEquals(10, stats.maxEntries)
        assertEquals(1000, stats.maxTotalBytes)
        assertEquals(2, stats.putCount)
    }

    @Test
    fun `gate2 LruAttachmentCache hit and miss tracking`() {
        val cache = LruAttachmentCache()

        cache.put("key1", ByteArray(100))

        cache.getIfPresent("key1") // Hit
        cache.getIfPresent("key1") // Hit
        cache.getIfPresent("key2") // Miss

        val stats = cache.stats()
        assertEquals(2, stats.hitCount)
        assertEquals(1, stats.missCount)
        assertEquals(2.0 / 3.0, stats.hitRate, 0.01)
    }

    @Test
    fun `gate2 LruAttachmentCache update existing key`() {
        val cache = LruAttachmentCache(maxEntries = 10, maxTotalBytes = 500)

        cache.put("key1", ByteArray(100))
        assertEquals(100, cache.totalBytes())

        // Update with larger value
        cache.put("key1", ByteArray(200))
        assertEquals(200, cache.totalBytes())
        assertEquals(1, cache.size())
    }

    @Test
    fun `gate2 LruAttachmentCache invalidate removes entry`() {
        val cache = LruAttachmentCache()

        cache.put("key1", ByteArray(100))
        cache.put("key2", ByteArray(100))
        assertEquals(2, cache.size())
        assertEquals(200, cache.totalBytes())

        cache.invalidate("key1")

        assertEquals(1, cache.size())
        assertEquals(100, cache.totalBytes())
        assertNull(cache.getIfPresent("key1"))
    }

    @Test
    fun `gate2 LruAttachmentCache clear removes all`() {
        val cache = LruAttachmentCache()

        cache.put("key1", ByteArray(100))
        cache.put("key2", ByteArray(100))

        cache.clear()

        assertEquals(0, cache.size())
        assertEquals(0, cache.totalBytes())
    }

    @Test
    fun `gate2 large item evicts multiple smaller items`() {
        val cache = LruAttachmentCache(maxEntries = 100, maxTotalBytes = 300)

        cache.put("key1", ByteArray(100))
        cache.put("key2", ByteArray(100))
        cache.put("key3", ByteArray(100))
        assertEquals(300, cache.totalBytes())

        // Adding 200-byte item should evict 2 items to make room
        cache.put("key4", ByteArray(200))

        assertTrue(cache.totalBytes() <= 300)
        // key1 and key2 should be evicted (oldest first)
        assertNull(cache.getIfPresent("key1"))
        assertNull(cache.getIfPresent("key2"))
    }
}
