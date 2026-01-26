package com.whisper2.app.services.messaging

/**
 * Message deduplication using LRU cache of message IDs
 *
 * Prevents duplicate message processing when:
 * - Server sends same message twice (reconnect edge case)
 * - Push + fetch race condition
 */
class Deduper(
    private val maxSize: Int = 1000
) {
    private val seenIds = LinkedHashSet<String>()

    /**
     * Check if message ID was already seen
     * If not seen, marks it as seen and returns false
     * If already seen, returns true (duplicate)
     *
     * @return true if duplicate, false if new
     */
    @Synchronized
    fun isDuplicate(messageId: String): Boolean {
        if (seenIds.contains(messageId)) {
            return true
        }

        // LRU eviction if at capacity
        if (seenIds.size >= maxSize) {
            val oldest = seenIds.first()
            seenIds.remove(oldest)
        }

        seenIds.add(messageId)
        return false
    }

    /**
     * Check if message ID was seen without marking
     */
    @Synchronized
    fun wasSeen(messageId: String): Boolean {
        return seenIds.contains(messageId)
    }

    /**
     * Clear all seen IDs (for testing)
     */
    @Synchronized
    fun clear() {
        seenIds.clear()
    }

    /**
     * Current count of tracked IDs (for testing)
     */
    @Synchronized
    fun size(): Int = seenIds.size
}
