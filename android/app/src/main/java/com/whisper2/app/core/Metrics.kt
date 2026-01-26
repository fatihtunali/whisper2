package com.whisper2.app.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Step 13.1: Observability Metrics
 *
 * Centralized metrics collection for production debugging.
 *
 * Categories:
 * - WebSocket lifecycle (connect/disconnect/reconnect)
 * - Outbox queue metrics
 * - Call metrics
 * - Push metrics
 */
object Metrics {

    /**
     * WebSocket lifecycle metrics
     */
    object Ws {
        private val _connectCount = AtomicInteger(0)
        private val _disconnectCount = AtomicInteger(0)
        private val _reconnectCount = AtomicInteger(0)
        private val _lastConnectTime = AtomicLong(0)
        private val _lastDisconnectTime = AtomicLong(0)
        private val _lastCloseCode = AtomicInteger(0)
        @Volatile private var _lastCloseReason: String? = null
        @Volatile private var _lastReconnectReason: String? = null

        val connectCount: Int get() = _connectCount.get()
        val disconnectCount: Int get() = _disconnectCount.get()
        val reconnectCount: Int get() = _reconnectCount.get()
        val lastConnectTime: Long get() = _lastConnectTime.get()
        val lastDisconnectTime: Long get() = _lastDisconnectTime.get()
        val lastCloseCode: Int get() = _lastCloseCode.get()
        val lastCloseReason: String? get() = _lastCloseReason
        val lastReconnectReason: String? get() = _lastReconnectReason

        fun recordConnect() {
            _connectCount.incrementAndGet()
            _lastConnectTime.set(System.currentTimeMillis())
            Logger.info("WS connected (total=${_connectCount.get()})", Logger.Category.NETWORK)
        }

        fun recordDisconnect(code: Int, reason: String?) {
            _disconnectCount.incrementAndGet()
            _lastDisconnectTime.set(System.currentTimeMillis())
            _lastCloseCode.set(code)
            _lastCloseReason = reason
            Logger.info("WS disconnected: code=$code, reason=$reason", Logger.Category.NETWORK)
        }

        fun recordReconnect(reason: String?) {
            _reconnectCount.incrementAndGet()
            _lastReconnectReason = reason
            Logger.info("WS reconnecting: reason=$reason (attempt=${_reconnectCount.get()})", Logger.Category.NETWORK)
        }

        fun reset() {
            _connectCount.set(0)
            _disconnectCount.set(0)
            _reconnectCount.set(0)
            _lastConnectTime.set(0)
            _lastDisconnectTime.set(0)
            _lastCloseCode.set(0)
            _lastCloseReason = null
            _lastReconnectReason = null
        }
    }

    /**
     * Outbox queue metrics
     */
    object Outbox {
        private val _enqueuedCount = AtomicInteger(0)
        private val _sentCount = AtomicInteger(0)
        private val _failedCount = AtomicInteger(0)
        private val _retryCount = AtomicInteger(0)
        private val _currentQueueSize = AtomicInteger(0)
        private val _oldestItemAge = AtomicLong(0)

        // Retry count histogram (attempt number → count)
        private val retryHistogram = ConcurrentHashMap<Int, AtomicInteger>()

        val enqueuedCount: Int get() = _enqueuedCount.get()
        val sentCount: Int get() = _sentCount.get()
        val failedCount: Int get() = _failedCount.get()
        val retryCount: Int get() = _retryCount.get()
        val currentQueueSize: Int get() = _currentQueueSize.get()
        val oldestItemAge: Long get() = _oldestItemAge.get()

        fun recordEnqueued() {
            _enqueuedCount.incrementAndGet()
            _currentQueueSize.incrementAndGet()
            Logger.debug("Outbox: message enqueued (queue=${_currentQueueSize.get()})", Logger.Category.OUTBOX)
        }

        fun recordSent() {
            _sentCount.incrementAndGet()
            _currentQueueSize.decrementAndGet()
            Logger.debug("Outbox: message sent (queue=${_currentQueueSize.get()})", Logger.Category.OUTBOX)
        }

        fun recordFailed(reason: String?) {
            _failedCount.incrementAndGet()
            _currentQueueSize.decrementAndGet()
            Logger.warn("Outbox: message failed: $reason (failed=${_failedCount.get()})", Logger.Category.OUTBOX)
        }

        fun recordRetry(attemptNumber: Int) {
            _retryCount.incrementAndGet()
            retryHistogram.getOrPut(attemptNumber) { AtomicInteger(0) }.incrementAndGet()
            Logger.debug("Outbox: retry attempt $attemptNumber", Logger.Category.OUTBOX)
        }

        fun updateQueueSize(size: Int) {
            _currentQueueSize.set(size)
        }

        fun updateOldestAge(ageMs: Long) {
            _oldestItemAge.set(ageMs)
        }

        fun getRetryHistogram(): Map<Int, Int> {
            return retryHistogram.mapValues { it.value.get() }
        }

        fun reset() {
            _enqueuedCount.set(0)
            _sentCount.set(0)
            _failedCount.set(0)
            _retryCount.set(0)
            _currentQueueSize.set(0)
            _oldestItemAge.set(0)
            retryHistogram.clear()
        }
    }

    /**
     * Call metrics
     */
    object Call {
        private val _initiatedCount = AtomicInteger(0)
        private val _receivedCount = AtomicInteger(0)
        private val _connectedCount = AtomicInteger(0)
        private val _failedCount = AtomicInteger(0)
        private val _turnUsedCount = AtomicInteger(0)

        // Timing metrics (latest values)
        private val _lastTimeToRingingMs = AtomicLong(0)
        private val _lastTimeToConnectMs = AtomicLong(0)

        // ICE failure reasons
        private val iceFailReasons = ConcurrentHashMap<String, AtomicInteger>()

        val initiatedCount: Int get() = _initiatedCount.get()
        val receivedCount: Int get() = _receivedCount.get()
        val connectedCount: Int get() = _connectedCount.get()
        val failedCount: Int get() = _failedCount.get()
        val turnUsedCount: Int get() = _turnUsedCount.get()
        val lastTimeToRingingMs: Long get() = _lastTimeToRingingMs.get()
        val lastTimeToConnectMs: Long get() = _lastTimeToConnectMs.get()

        fun recordInitiated() {
            _initiatedCount.incrementAndGet()
            Logger.info("Call: initiated (total=${_initiatedCount.get()})", Logger.Category.CALL)
        }

        fun recordReceived() {
            _receivedCount.incrementAndGet()
            Logger.info("Call: received (total=${_receivedCount.get()})", Logger.Category.CALL)
        }

        fun recordConnected(timeToConnectMs: Long) {
            _connectedCount.incrementAndGet()
            _lastTimeToConnectMs.set(timeToConnectMs)
            Logger.info("Call: connected in ${timeToConnectMs}ms", Logger.Category.CALL)
        }

        fun recordRinging(timeToRingingMs: Long) {
            _lastTimeToRingingMs.set(timeToRingingMs)
            Logger.debug("Call: ringing after ${timeToRingingMs}ms", Logger.Category.CALL)
        }

        fun recordFailed(reason: String?) {
            _failedCount.incrementAndGet()
            if (reason != null) {
                iceFailReasons.getOrPut(reason) { AtomicInteger(0) }.incrementAndGet()
            }
            Logger.warn("Call: failed: $reason", Logger.Category.CALL)
        }

        fun recordTurnUsed() {
            _turnUsedCount.incrementAndGet()
            Logger.debug("Call: TURN relay used", Logger.Category.CALL)
        }

        fun getIceFailReasons(): Map<String, Int> {
            return iceFailReasons.mapValues { it.value.get() }
        }

        fun reset() {
            _initiatedCount.set(0)
            _receivedCount.set(0)
            _connectedCount.set(0)
            _failedCount.set(0)
            _turnUsedCount.set(0)
            _lastTimeToRingingMs.set(0)
            _lastTimeToConnectMs.set(0)
            iceFailReasons.clear()
        }
    }

    /**
     * Push notification metrics
     */
    object Push {
        private val _wakeCount = AtomicInteger(0)
        private val _wakeMessageCount = AtomicInteger(0)
        private val _wakeCallCount = AtomicInteger(0)
        private val _ignoredCount = AtomicInteger(0)
        private val _contentIgnoredCount = AtomicInteger(0)

        val wakeCount: Int get() = _wakeCount.get()
        val wakeMessageCount: Int get() = _wakeMessageCount.get()
        val wakeCallCount: Int get() = _wakeCallCount.get()
        val ignoredCount: Int get() = _ignoredCount.get()
        val contentIgnoredCount: Int get() = _contentIgnoredCount.get()

        fun recordWake(type: String) {
            _wakeCount.incrementAndGet()
            when (type) {
                "message" -> _wakeMessageCount.incrementAndGet()
                "call" -> _wakeCallCount.incrementAndGet()
            }
            Logger.debug("Push: wake received type=$type (total=${_wakeCount.get()})", Logger.Category.PUSH)
        }

        fun recordIgnored(reason: String?) {
            _ignoredCount.incrementAndGet()
            Logger.debug("Push: ignored reason=$reason", Logger.Category.PUSH)
        }

        fun recordContentIgnored() {
            _contentIgnoredCount.incrementAndGet()
            Logger.debug("Push: content field ignored (security)", Logger.Category.PUSH)
        }

        fun reset() {
            _wakeCount.set(0)
            _wakeMessageCount.set(0)
            _wakeCallCount.set(0)
            _ignoredCount.set(0)
            _contentIgnoredCount.set(0)
        }
    }

    /**
     * Data cleanup metrics
     */
    object Cleanup {
        private val _outboxCleanedCount = AtomicInteger(0)
        private val _attachmentsEvictedCount = AtomicInteger(0)
        private val _lastCleanupTime = AtomicLong(0)

        val outboxCleanedCount: Int get() = _outboxCleanedCount.get()
        val attachmentsEvictedCount: Int get() = _attachmentsEvictedCount.get()
        val lastCleanupTime: Long get() = _lastCleanupTime.get()

        fun recordOutboxCleaned(count: Int) {
            _outboxCleanedCount.addAndGet(count)
            _lastCleanupTime.set(System.currentTimeMillis())
            Logger.info("Cleanup: removed $count old outbox items", Logger.Category.CLEANUP)
        }

        fun recordAttachmentsEvicted(count: Int) {
            _attachmentsEvictedCount.addAndGet(count)
            Logger.debug("Cleanup: evicted $count attachments from cache", Logger.Category.CLEANUP)
        }

        fun reset() {
            _outboxCleanedCount.set(0)
            _attachmentsEvictedCount.set(0)
            _lastCleanupTime.set(0)
        }
    }

    /**
     * Reset all metrics (for testing)
     */
    fun resetAll() {
        Ws.reset()
        Outbox.reset()
        Call.reset()
        Push.reset()
        Cleanup.reset()
    }
}
