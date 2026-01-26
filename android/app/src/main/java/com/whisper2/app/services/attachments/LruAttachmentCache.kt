package com.whisper2.app.services.attachments

import com.whisper2.app.core.Logger
import com.whisper2.app.core.Metrics
import java.util.LinkedHashMap

/**
 * Step 13.2: LRU Attachment Cache
 *
 * Implements AttachmentCache with:
 * - LRU eviction policy
 * - Maximum entry count limit
 * - Maximum total size limit (bytes)
 *
 * When limits are exceeded, oldest accessed entries are evicted first.
 */
class LruAttachmentCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES
) : AttachmentCache {

    companion object {
        const val DEFAULT_MAX_ENTRIES = 100
        const val DEFAULT_MAX_TOTAL_BYTES = 50L * 1024 * 1024 // 50 MB
    }

    // LRU map with access-order (true = access order, false = insertion order)
    private val cache = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean {
            // Don't auto-remove here, we handle it manually for size tracking
            return false
        }
    }

    private var currentTotalBytes: Long = 0

    // Stats for testing
    var evictionCount = 0
        private set
    var putCount = 0
        private set
    var hitCount = 0
        private set
    var missCount = 0
        private set

    @Synchronized
    override fun getIfPresent(objectKey: String): ByteArray? {
        val data = cache[objectKey]
        if (data != null) {
            hitCount++
            Logger.debug("AttachmentCache: hit for $objectKey", Logger.Category.STORAGE)
        } else {
            missCount++
        }
        return data
    }

    @Synchronized
    override fun put(objectKey: String, data: ByteArray) {
        putCount++

        // If key already exists, remove old size first
        val existing = cache[objectKey]
        if (existing != null) {
            currentTotalBytes -= existing.size
        }

        // Evict if necessary before adding
        evictIfNeeded(data.size.toLong())

        // Add new entry
        cache[objectKey] = data
        currentTotalBytes += data.size

        Logger.debug("AttachmentCache: put $objectKey (${data.size} bytes, total=$currentTotalBytes)", Logger.Category.STORAGE)
    }

    @Synchronized
    override fun invalidate(objectKey: String) {
        val removed = cache.remove(objectKey)
        if (removed != null) {
            currentTotalBytes -= removed.size
            Logger.debug("AttachmentCache: invalidated $objectKey", Logger.Category.STORAGE)
        }
    }

    @Synchronized
    override fun clear() {
        cache.clear()
        currentTotalBytes = 0
        Logger.info("AttachmentCache: cleared", Logger.Category.STORAGE)
    }

    @Synchronized
    override fun size(): Int = cache.size

    /**
     * Get current total bytes in cache
     */
    @Synchronized
    fun totalBytes(): Long = currentTotalBytes

    /**
     * Get stats snapshot
     */
    @Synchronized
    fun stats(): CacheStats {
        return CacheStats(
            entries = cache.size,
            totalBytes = currentTotalBytes,
            maxEntries = maxEntries,
            maxTotalBytes = maxTotalBytes,
            hitCount = hitCount,
            missCount = missCount,
            evictionCount = evictionCount,
            putCount = putCount
        )
    }

    /**
     * Reset stats (for testing)
     */
    @Synchronized
    fun resetStats() {
        evictionCount = 0
        putCount = 0
        hitCount = 0
        missCount = 0
    }

    private fun evictIfNeeded(incomingBytes: Long) {
        var evictedThisRound = 0

        // Evict while over entry limit (leave room for new entry)
        while (cache.size >= maxEntries && cache.isNotEmpty()) {
            evictOldest()
            evictedThisRound++
        }

        // Evict while over byte limit (after adding new entry would exceed limit)
        while (currentTotalBytes + incomingBytes > maxTotalBytes && cache.isNotEmpty()) {
            evictOldest()
            evictedThisRound++
        }

        if (evictedThisRound > 0) {
            Metrics.Cleanup.recordAttachmentsEvicted(evictedThisRound)
        }
    }

    private fun evictOldest() {
        // In access-order LinkedHashMap, iterator starts from oldest
        val eldest = cache.entries.iterator()
        if (eldest.hasNext()) {
            val entry = eldest.next()
            val key = entry.key
            val size = entry.value.size
            eldest.remove()
            currentTotalBytes -= size
            evictionCount++
            Logger.debug("AttachmentCache: evicted $key ($size bytes)", Logger.Category.CLEANUP)
        }
    }

    /**
     * Cache statistics snapshot
     */
    data class CacheStats(
        val entries: Int,
        val totalBytes: Long,
        val maxEntries: Int,
        val maxTotalBytes: Long,
        val hitCount: Int,
        val missCount: Int,
        val evictionCount: Int,
        val putCount: Int
    ) {
        val hitRate: Double get() = if (hitCount + missCount > 0) {
            hitCount.toDouble() / (hitCount + missCount)
        } else 0.0
    }
}
