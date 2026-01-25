import Foundation

/// Whisper2 Deduper
/// Deduplication service to prevent processing duplicate messages
///
/// Features:
/// - Track processed messageIds per conversation
/// - Ignore duplicates on re-delivery
/// - Persist watermark for crash recovery
/// - Configurable TTL for cleanup

// MARK: - Dedup Entry

/// Entry tracking a processed message
struct DedupEntry: Codable {
    let messageId: String
    let conversationId: String
    let processedAt: Date
}

// MARK: - Dedup Persistence Protocol

/// Protocol for dedup persistence
protocol DedupPersistence {
    /// Check if message was already processed
    func isProcessed(messageId: String, conversationId: String) async -> Bool

    /// Mark message as processed
    func markProcessed(messageId: String, conversationId: String) async throws

    /// Get watermark (latest processed timestamp) for conversation
    func getWatermark(conversationId: String) async -> Int64?

    /// Update watermark for conversation
    func updateWatermark(conversationId: String, timestamp: Int64) async throws

    /// Cleanup old entries beyond TTL
    func cleanup(olderThan: Date) async throws
}

// MARK: - Deduper

/// Actor responsible for message deduplication
actor Deduper {

    // MARK: - Properties

    private let persistence: DedupPersistence

    /// In-memory cache of recently processed message IDs
    /// Key: "conversationId:messageId"
    private var processedCache: Set<String> = []

    /// Maximum cache size before pruning
    private let maxCacheSize = 10_000

    /// TTL for dedup entries (7 days)
    private let entryTTL: TimeInterval = 7 * 24 * 60 * 60

    /// Watermarks per conversation (in-memory cache)
    private var watermarks: [String: Int64] = [:]

    // MARK: - Initialization

    init(persistence: DedupPersistence) {
        self.persistence = persistence
    }

    // MARK: - Public API

    /// Check if a message is a duplicate
    /// - Parameters:
    ///   - messageId: The message ID to check
    ///   - conversationId: The conversation ID
    /// - Returns: true if this message was already processed
    func isDuplicate(messageId: String, conversationId: String) async -> Bool {
        let cacheKey = makeCacheKey(messageId: messageId, conversationId: conversationId)

        // Check in-memory cache first
        if processedCache.contains(cacheKey) {
            return true
        }

        // Check persistence
        return await persistence.isProcessed(messageId: messageId, conversationId: conversationId)
    }

    /// Mark a message as processed
    /// - Parameters:
    ///   - messageId: The message ID
    ///   - conversationId: The conversation ID
    ///   - timestamp: Optional message timestamp for watermark update
    func markProcessed(messageId: String, conversationId: String, timestamp: Int64? = nil) async {
        let cacheKey = makeCacheKey(messageId: messageId, conversationId: conversationId)

        // Add to cache
        processedCache.insert(cacheKey)

        // Prune cache if too large
        if processedCache.count > maxCacheSize {
            pruneCache()
        }

        // Save to persistence
        do {
            try await persistence.markProcessed(messageId: messageId, conversationId: conversationId)
        } catch {
            logger.error(error, message: "Failed to persist dedup entry", category: .messaging)
        }

        // Update watermark if timestamp provided
        if let ts = timestamp {
            await updateWatermark(conversationId: conversationId, timestamp: ts)
        }
    }

    /// Get the watermark (latest processed timestamp) for a conversation
    /// - Parameter conversationId: The conversation ID
    /// - Returns: Latest processed timestamp, or nil if none
    func getWatermark(conversationId: String) async -> Int64? {
        // Check cache first
        if let cached = watermarks[conversationId] {
            return cached
        }

        // Load from persistence
        let watermark = await persistence.getWatermark(conversationId: conversationId)
        if let wm = watermark {
            watermarks[conversationId] = wm
        }
        return watermark
    }

    /// Update the watermark for a conversation
    /// - Parameters:
    ///   - conversationId: The conversation ID
    ///   - timestamp: The new watermark timestamp
    func updateWatermark(conversationId: String, timestamp: Int64) async {
        // Only update if newer than current
        let current = watermarks[conversationId] ?? 0
        guard timestamp > current else { return }

        watermarks[conversationId] = timestamp

        do {
            try await persistence.updateWatermark(conversationId: conversationId, timestamp: timestamp)
        } catch {
            logger.error(error, message: "Failed to update watermark", category: .messaging)
        }
    }

    /// Perform cleanup of old dedup entries
    func performCleanup() async {
        let cutoff = Date().addingTimeInterval(-entryTTL)

        do {
            try await persistence.cleanup(olderThan: cutoff)
            logger.info("Dedup cleanup completed", category: .messaging)
        } catch {
            logger.error(error, message: "Dedup cleanup failed", category: .messaging)
        }
    }

    /// Clear cache for a specific conversation (e.g., on conversation delete)
    func clearConversation(_ conversationId: String) {
        processedCache = processedCache.filter { !$0.hasPrefix("\(conversationId):") }
        watermarks.removeValue(forKey: conversationId)
    }

    /// Clear all cached data
    func clearAll() {
        processedCache.removeAll()
        watermarks.removeAll()
    }

    // MARK: - Private Methods

    private func makeCacheKey(messageId: String, conversationId: String) -> String {
        "\(conversationId):\(messageId)"
    }

    /// Prune oldest entries from cache
    private func pruneCache() {
        // Remove half the entries (simple approach - could be improved with LRU)
        let removeCount = processedCache.count / 2
        let toRemove = Array(processedCache.prefix(removeCount))
        for key in toRemove {
            processedCache.remove(key)
        }
        logger.debug("Pruned \(removeCount) entries from dedup cache", category: .messaging)
    }
}

// MARK: - In-Memory Persistence (for testing)

/// In-memory implementation for testing
actor InMemoryDedupPersistence: DedupPersistence {
    private var entries: [String: DedupEntry] = [:]
    private var watermarks: [String: Int64] = [:]

    func isProcessed(messageId: String, conversationId: String) async -> Bool {
        let key = "\(conversationId):\(messageId)"
        return entries[key] != nil
    }

    func markProcessed(messageId: String, conversationId: String) async throws {
        let key = "\(conversationId):\(messageId)"
        entries[key] = DedupEntry(
            messageId: messageId,
            conversationId: conversationId,
            processedAt: Date()
        )
    }

    func getWatermark(conversationId: String) async -> Int64? {
        watermarks[conversationId]
    }

    func updateWatermark(conversationId: String, timestamp: Int64) async throws {
        let current = watermarks[conversationId] ?? 0
        if timestamp > current {
            watermarks[conversationId] = timestamp
        }
    }

    func cleanup(olderThan: Date) async throws {
        entries = entries.filter { $0.value.processedAt > olderThan }
    }
}

// MARK: - UserDefaults Persistence (simple persistence)

/// Simple UserDefaults-based persistence for dedup
/// Suitable for development/testing, consider SwiftData for production
final class UserDefaultsDedupPersistence: DedupPersistence {
    private let defaults: UserDefaults
    private let entriesKey = "whisper2.dedup.entries"
    private let watermarksKey = "whisper2.dedup.watermarks"

    private let queue = DispatchQueue(label: "com.whisper2.dedup.persistence")

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func isProcessed(messageId: String, conversationId: String) async -> Bool {
        queue.sync {
            let entries = loadEntries()
            let key = "\(conversationId):\(messageId)"
            return entries[key] != nil
        }
    }

    func markProcessed(messageId: String, conversationId: String) async throws {
        queue.sync {
            var entries = loadEntries()
            let key = "\(conversationId):\(messageId)"
            entries[key] = DedupEntry(
                messageId: messageId,
                conversationId: conversationId,
                processedAt: Date()
            )
            saveEntries(entries)
        }
    }

    func getWatermark(conversationId: String) async -> Int64? {
        queue.sync {
            let watermarks = loadWatermarks()
            return watermarks[conversationId]
        }
    }

    func updateWatermark(conversationId: String, timestamp: Int64) async throws {
        queue.sync {
            var watermarks = loadWatermarks()
            let current = watermarks[conversationId] ?? 0
            if timestamp > current {
                watermarks[conversationId] = timestamp
                saveWatermarks(watermarks)
            }
        }
    }

    func cleanup(olderThan: Date) async throws {
        queue.sync {
            var entries = loadEntries()
            entries = entries.filter { $0.value.processedAt > olderThan }
            saveEntries(entries)
        }
    }

    // MARK: - Private Helpers

    private func loadEntries() -> [String: DedupEntry] {
        guard let data = defaults.data(forKey: entriesKey),
              let entries = try? JSONDecoder().decode([String: DedupEntry].self, from: data) else {
            return [:]
        }
        return entries
    }

    private func saveEntries(_ entries: [String: DedupEntry]) {
        if let data = try? JSONEncoder().encode(entries) {
            defaults.set(data, forKey: entriesKey)
        }
    }

    private func loadWatermarks() -> [String: Int64] {
        guard let data = defaults.data(forKey: watermarksKey),
              let watermarks = try? JSONDecoder().decode([String: Int64].self, from: data) else {
            return [:]
        }
        return watermarks
    }

    private func saveWatermarks(_ watermarks: [String: Int64]) {
        if let data = try? JSONEncoder().encode(watermarks) {
            defaults.set(data, forKey: watermarksKey)
        }
    }
}
