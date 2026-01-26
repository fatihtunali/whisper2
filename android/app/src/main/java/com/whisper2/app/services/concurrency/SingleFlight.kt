package com.whisper2.app.services.concurrency

import com.whisper2.app.core.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Step 13.3: Single-Flight Pattern
 *
 * Ensures only one instance of a keyed operation runs at a time.
 * Subsequent calls with the same key wait for the first to complete.
 *
 * Prevents race conditions in:
 * - WS reconnect
 * - Outbox processing
 * - Pending fetch
 */
class SingleFlight<K, V> {

    private val mutex = Mutex()
    private val inFlight = ConcurrentHashMap<K, Deferred<V>>()

    // Stats for testing
    private val _coalescedCount = AtomicInteger(0)
    val coalescedCount: Int get() = _coalescedCount.get()

    /**
     * Execute operation, coalescing concurrent calls with same key
     *
     * @param key Operation key
     * @param operation The suspend operation to execute
     * @return Result of the operation
     */
    suspend fun execute(key: K, operation: suspend () -> V): V {
        // Check if already in flight
        val existing = inFlight[key]
        if (existing != null && existing.isActive) {
            _coalescedCount.incrementAndGet()
            Logger.debug("SingleFlight: coalescing request for key=$key", Logger.Category.NETWORK)
            return existing.await()
        }

        // Need to start new operation
        return mutex.withLock {
            // Double-check under lock
            val existingUnderLock = inFlight[key]
            if (existingUnderLock != null && existingUnderLock.isActive) {
                _coalescedCount.incrementAndGet()
                return@withLock existingUnderLock.await()
            }

            // Start new operation
            val scope = CoroutineScope(currentCoroutineContext())
            val deferred = scope.async {
                try {
                    operation()
                } finally {
                    inFlight.remove(key)
                }
            }
            inFlight[key] = deferred
            deferred.await()
        }
    }

    /**
     * Cancel all in-flight operations
     */
    fun cancelAll() {
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
    }

    /**
     * Check if operation is in flight
     */
    fun isInFlight(key: K): Boolean {
        val deferred = inFlight[key]
        return deferred != null && deferred.isActive
    }

    /**
     * Get count of in-flight operations
     */
    fun inFlightCount(): Int = inFlight.count { it.value.isActive }

    /**
     * Reset stats
     */
    fun resetStats() {
        _coalescedCount.set(0)
    }
}

/**
 * Cancellation-safe state holder
 *
 * Ensures state is not leaked on cancellation
 */
class CancellationSafeState<T> {

    private val mutex = Mutex()
    private var value: T? = null
    private var isSet = AtomicBoolean(false)

    /**
     * Set value with cleanup on cancellation
     */
    suspend fun <R> withValue(newValue: T, cleanup: (T) -> Unit = {}, block: suspend (T) -> R): R {
        mutex.withLock {
            value = newValue
            isSet.set(true)
        }

        try {
            return block(newValue)
        } finally {
            mutex.withLock {
                if (isSet.get()) {
                    value?.let { cleanup(it) }
                    value = null
                    isSet.set(false)
                }
            }
        }
    }

    /**
     * Get current value (if set)
     */
    suspend fun get(): T? = mutex.withLock { value }

    /**
     * Check if value is set
     */
    fun isSet(): Boolean = isSet.get()

    /**
     * Clear value
     */
    suspend fun clear() {
        mutex.withLock {
            value = null
            isSet.set(false)
        }
    }
}

/**
 * Rate-limited executor for operations that shouldn't run too frequently
 */
class RateLimitedExecutor(
    private val minIntervalMs: Long = 1000L,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {

    private var lastExecutionTime: Long = 0
    private val mutex = Mutex()

    // Stats
    private val _executedCount = AtomicInteger(0)
    private val _skippedCount = AtomicInteger(0)

    val executedCount: Int get() = _executedCount.get()
    val skippedCount: Int get() = _skippedCount.get()

    /**
     * Execute if enough time has passed since last execution
     *
     * @param operation Operation to execute
     * @return true if executed, false if rate limited
     */
    suspend fun <T> executeIfAllowed(operation: suspend () -> T): T? {
        mutex.withLock {
            val now = timeProvider()
            if (now - lastExecutionTime < minIntervalMs) {
                _skippedCount.incrementAndGet()
                Logger.debug("RateLimitedExecutor: skipped (too soon)", Logger.Category.NETWORK)
                return null
            }
            lastExecutionTime = now
        }

        _executedCount.incrementAndGet()
        return operation()
    }

    /**
     * Force execute regardless of rate limit
     */
    suspend fun <T> forceExecute(operation: suspend () -> T): T {
        mutex.withLock {
            lastExecutionTime = timeProvider()
        }
        _executedCount.incrementAndGet()
        return operation()
    }

    /**
     * Reset for testing
     */
    fun reset() {
        lastExecutionTime = 0
        _executedCount.set(0)
        _skippedCount.set(0)
    }
}

/**
 * Helper for ensuring cleanup happens even on cancellation
 */
inline fun <T> withCleanup(setup: () -> T, cleanup: (T) -> Unit, block: (T) -> Unit) {
    val resource = setup()
    try {
        block(resource)
    } finally {
        cleanup(resource)
    }
}
