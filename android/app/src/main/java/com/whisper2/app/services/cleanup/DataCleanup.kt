package com.whisper2.app.services.cleanup

import com.whisper2.app.core.Logger
import com.whisper2.app.core.Metrics

/**
 * Step 13.2: Data Cleanup Service
 *
 * Handles TTL-based cleanup of:
 * - Sent outbox records (after successful delivery)
 * - Old message deduplication records
 *
 * Gate 1: cleanup job → deletes old records, keeps new ones
 */
class DataCleanup(
    private val sentTtlMs: Long = DEFAULT_SENT_TTL_MS,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {

    companion object {
        // Default: keep sent items for 7 days
        const val DEFAULT_SENT_TTL_MS = 7L * 24 * 60 * 60 * 1000

        // Default: cleanup interval 1 hour
        const val DEFAULT_CLEANUP_INTERVAL_MS = 60L * 60 * 1000
    }

    /**
     * Clean up sent outbox items older than TTL
     *
     * @param items List of OutboxCleanupItem to evaluate
     * @return List of item IDs that should be deleted
     */
    fun cleanupOutbox(items: List<OutboxCleanupItem>): List<String> {
        val now = timeProvider()
        val cutoffTime = now - sentTtlMs

        val toDelete = items.filter { item ->
            // Only delete SENT items that are older than TTL
            item.status == "sent" && item.sentAt != null && item.sentAt < cutoffTime
        }.map { it.id }

        if (toDelete.isNotEmpty()) {
            Logger.info("Cleanup: identified ${toDelete.size} old outbox items for deletion", Logger.Category.CLEANUP)
            Metrics.Cleanup.recordOutboxCleaned(toDelete.size)
        }

        return toDelete
    }

    /**
     * Clean up message deduplication records older than TTL
     *
     * @param records List of DedupeCleanupRecord to evaluate
     * @param ttlMs TTL for dedup records (default 24h)
     * @return List of message IDs that should be deleted from dedup cache
     */
    fun cleanupDedup(records: List<DedupeCleanupRecord>, ttlMs: Long = 24 * 60 * 60 * 1000): List<String> {
        val now = timeProvider()
        val cutoffTime = now - ttlMs

        val toDelete = records.filter { record ->
            record.receivedAt < cutoffTime
        }.map { it.messageId }

        if (toDelete.isNotEmpty()) {
            Logger.debug("Cleanup: identified ${toDelete.size} old dedup records for deletion", Logger.Category.CLEANUP)
        }

        return toDelete
    }

    /**
     * Check if an item should be cleaned up
     */
    fun shouldCleanup(item: OutboxCleanupItem): Boolean {
        if (item.status != "sent" || item.sentAt == null) {
            return false
        }
        val now = timeProvider()
        return item.sentAt < (now - sentTtlMs)
    }

    /**
     * Clean up local data on logout
     *
     * Called when user logs out to clear sensitive cached data.
     * Database and secure storage clearing is handled by SessionManager.
     */
    fun cleanupOnLogout() {
        Logger.info("Cleanup on logout initiated", Logger.Category.CLEANUP)
        // Note: Actual database/secure storage clearing is handled by SessionManager.forceLogout()
        // This method is for any additional cleanup tasks (caches, temp files, etc.)
    }
}

/**
 * Data class for outbox cleanup evaluation
 */
data class OutboxCleanupItem(
    val id: String,
    val messageId: String,
    val status: String, // "queued", "sending", "sent", "failed"
    val createdAt: Long,
    val sentAt: Long? = null
)

/**
 * Data class for dedup cleanup evaluation
 */
data class DedupeCleanupRecord(
    val messageId: String,
    val receivedAt: Long
)

/**
 * LRU Deduplicator with configurable max size
 *
 * For Step 13.2 - prevents unbounded growth of dedup cache
 */
class LruDeduplicator(
    private val maxSize: Int = DEFAULT_MAX_SIZE
) {

    companion object {
        const val DEFAULT_MAX_SIZE = 10000
    }

    // LRU map with access-order
    private val seen = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            val shouldRemove = size > maxSize
            if (shouldRemove && eldest != null) {
                Logger.debug("Dedup: evicting oldest entry ${eldest.key}", Logger.Category.CLEANUP)
            }
            return shouldRemove
        }
    }

    var evictionCount = 0
        private set

    /**
     * Check if message was already seen
     * Returns true if duplicate (already seen)
     * Note: Uses get() to update access order for LRU
     */
    @Synchronized
    fun isDuplicate(messageId: String): Boolean {
        return seen.get(messageId) != null
    }

    /**
     * Mark message as seen
     * Returns true if newly added, false if duplicate
     */
    @Synchronized
    fun markSeen(messageId: String, timestamp: Long = System.currentTimeMillis()): Boolean {
        val wasNew = !seen.containsKey(messageId)
        val sizeBefore = seen.size
        seen[messageId] = timestamp
        if (seen.size < sizeBefore) {
            evictionCount++
        }
        return wasNew
    }

    /**
     * Get current size
     */
    @Synchronized
    fun size(): Int = seen.size

    /**
     * Clear all entries
     */
    @Synchronized
    fun clear() {
        seen.clear()
    }

    /**
     * Remove specific entry (for cleanup)
     */
    @Synchronized
    fun remove(messageId: String): Boolean {
        return seen.remove(messageId) != null
    }

    /**
     * Get all entries as cleanup records
     */
    @Synchronized
    fun getAllRecords(): List<DedupeCleanupRecord> {
        return seen.map { (messageId, timestamp) ->
            DedupeCleanupRecord(messageId, timestamp)
        }
    }
}
