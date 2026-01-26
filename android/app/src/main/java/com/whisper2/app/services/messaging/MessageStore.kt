package com.whisper2.app.services.messaging

import com.whisper2.app.network.ws.PendingMessageItem

/**
 * Message storage interface
 * Step 4: Minimal interface for pending message persistence
 */
interface MessageStore {
    /**
     * Store a pending message
     * @return true if stored (new), false if already exists
     */
    fun store(message: PendingMessageItem): Boolean

    /**
     * Check if message exists by ID
     */
    fun exists(messageId: String): Boolean

    /**
     * Get message by ID
     */
    fun get(messageId: String): PendingMessageItem?

    /**
     * Count of stored messages
     */
    fun count(): Int

    /**
     * Clear all messages (for testing)
     */
    fun clear()
}

/**
 * In-memory implementation for testing
 */
class InMemoryMessageStore : MessageStore {
    private val messages = mutableMapOf<String, PendingMessageItem>()

    @Synchronized
    override fun store(message: PendingMessageItem): Boolean {
        if (messages.containsKey(message.messageId)) {
            return false
        }
        messages[message.messageId] = message
        return true
    }

    @Synchronized
    override fun exists(messageId: String): Boolean {
        return messages.containsKey(messageId)
    }

    @Synchronized
    override fun get(messageId: String): PendingMessageItem? {
        return messages[messageId]
    }

    @Synchronized
    override fun count(): Int = messages.size

    @Synchronized
    override fun clear() {
        messages.clear()
    }
}
