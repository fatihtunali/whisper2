import Foundation

/// Whisper2 OutboxQueue
/// Persistent retry queue for reliable message delivery
///
/// Features:
/// - SwiftData persistence via OutboxQueueItem
/// - Exponential backoff with jitter
/// - Max 5 retries then mark failed
/// - Processes on app launch and WS reconnect

// MARK: - Outbox Item

/// Item in the outbox queue
struct OutboxItem: Codable {
    let messageId: String
    let recipientId: String
    let payload: SendMessagePayload
    let createdAt: Date
    var retryCount: Int = 0
    var nextRetryAt: Date?
    var status: OutboxItemStatus = .pending
    var lastError: String?

    enum CodingKeys: String, CodingKey {
        case messageId, recipientId, payload, createdAt
        case retryCount, nextRetryAt, status, lastError
    }
}

/// Outbox item status
enum OutboxItemStatus: String, Codable {
    case pending
    case sending
    case sent
    case failed
}

// MARK: - Persistence Protocol

/// Protocol for outbox persistence (SwiftData implementation)
protocol OutboxPersistence {
    /// Get all pending items
    func getAllPending() async throws -> [OutboxItem]

    /// Save or update an item
    func save(_ item: OutboxItem) async throws

    /// Delete an item
    func delete(messageId: String) async throws

    /// Update item status
    func updateStatus(messageId: String, status: OutboxItemStatus, error: String?) async throws

    /// Update retry info
    func updateRetry(messageId: String, retryCount: Int, nextRetryAt: Date) async throws
}

// MARK: - OutboxQueue

/// Actor managing the outbox queue with reliable delivery
actor OutboxQueue {

    // MARK: - Properties

    private let persistence: OutboxPersistence
    private let webSocket: WebSocketProvider
    private let retryPolicy: RetryPolicy

    /// In-memory cache of pending items
    private var pendingItems: [String: OutboxItem] = [:]

    /// Currently processing flag
    private var isProcessing = false

    /// Timer for scheduled retries
    private var retryTask: Task<Void, Never>?

    // MARK: - Callbacks

    /// Called when a message is successfully sent
    var onMessageSent: ((String) async -> Void)?

    /// Called when a message permanently fails
    var onMessageFailed: ((String, String) async -> Void)?

    // MARK: - Initialization

    init(
        persistence: OutboxPersistence,
        webSocket: WebSocketProvider,
        retryPolicy: RetryPolicy = .outbox
    ) {
        self.persistence = persistence
        self.webSocket = webSocket
        self.retryPolicy = retryPolicy
    }

    // MARK: - Public API

    /// Enqueue a message for sending
    /// - Parameter item: The outbox item to enqueue
    func enqueue(_ item: OutboxItem) async {
        var mutableItem = item
        mutableItem.status = .pending
        mutableItem.nextRetryAt = Date()

        // Save to persistence
        do {
            try await persistence.save(mutableItem)
            pendingItems[item.messageId] = mutableItem
            logger.debug("Enqueued message: \(item.messageId)", category: .messaging)
        } catch {
            logger.error(error, message: "Failed to enqueue message", category: .messaging)
        }
    }

    /// Process the queue - attempt to send pending messages
    func processQueue() async {
        guard !isProcessing else {
            logger.debug("Queue already processing", category: .messaging)
            return
        }

        guard webSocket.isConnected else {
            logger.debug("WebSocket not connected, skipping queue processing", category: .messaging)
            scheduleNextRetry()
            return
        }

        isProcessing = true
        defer { isProcessing = false }

        logger.debug("Processing outbox queue", category: .messaging)

        // Load pending items from persistence if cache is empty
        if pendingItems.isEmpty {
            do {
                let items = try await persistence.getAllPending()
                for item in items {
                    pendingItems[item.messageId] = item
                }
                logger.debug("Loaded \(items.count) pending items from persistence", category: .messaging)
            } catch {
                logger.error(error, message: "Failed to load pending items", category: .messaging)
            }
        }

        // Get items ready to send (nextRetryAt <= now)
        let now = Date()
        let readyItems = pendingItems.values.filter { item in
            item.status == .pending &&
            (item.nextRetryAt == nil || item.nextRetryAt! <= now)
        }.sorted { $0.createdAt < $1.createdAt }

        guard !readyItems.isEmpty else {
            scheduleNextRetry()
            return
        }

        logger.info("Sending \(readyItems.count) queued messages", category: .messaging)

        for item in readyItems {
            await sendItem(item)
        }

        scheduleNextRetry()
    }

    /// Called when WebSocket connects - trigger queue processing
    func onWebSocketConnected() async {
        logger.info("WebSocket connected, processing outbox", category: .messaging)
        await processQueue()
    }

    /// Handle acknowledgment from server that message was sent
    func handleAck(messageId: String) async {
        guard var item = pendingItems[messageId] else { return }

        item.status = .sent
        pendingItems.removeValue(forKey: messageId)

        do {
            try await persistence.delete(messageId: messageId)
        } catch {
            logger.error(error, message: "Failed to delete sent item", category: .messaging)
        }

        await onMessageSent?(messageId)
        logger.info("Message sent successfully: \(messageId)", category: .messaging)
    }

    /// Handle error from server for a message
    func handleError(messageId: String, error: String) async {
        guard var item = pendingItems[messageId] else { return }

        // Check if we should retry
        item.retryCount += 1

        if retryPolicy.shouldRetry(attempt: item.retryCount) {
            // Schedule retry with exponential backoff
            let delay = retryPolicy.delay(forAttempt: item.retryCount)
            item.nextRetryAt = Date().addingTimeInterval(delay)
            item.status = .pending
            item.lastError = error

            pendingItems[messageId] = item

            do {
                try await persistence.updateRetry(
                    messageId: messageId,
                    retryCount: item.retryCount,
                    nextRetryAt: item.nextRetryAt!
                )
            } catch {
                logger.error(error, message: "Failed to update retry info", category: .messaging)
            }

            logger.warning(
                "Message \(messageId) failed (attempt \(item.retryCount)), retry in \(delay)s: \(error)",
                category: .messaging
            )

            scheduleNextRetry()
        } else {
            // Max retries exceeded - mark as failed
            item.status = .failed
            item.lastError = error
            pendingItems.removeValue(forKey: messageId)

            do {
                try await persistence.updateStatus(messageId: messageId, status: .failed, error: error)
            } catch {
                logger.error(error, message: "Failed to update failed status", category: .messaging)
            }

            await onMessageFailed?(messageId, error)
            logger.error("Message permanently failed after \(item.retryCount) retries: \(messageId)", category: .messaging)
        }
    }

    /// Get count of pending messages
    func pendingCount() -> Int {
        pendingItems.values.filter { $0.status == .pending }.count
    }

    /// Get all pending message IDs
    func pendingMessageIds() -> [String] {
        pendingItems.values.filter { $0.status == .pending }.map(\.messageId)
    }

    // MARK: - Private Methods

    /// Attempt to send a single item
    private func sendItem(_ item: OutboxItem) async {
        var mutableItem = item
        mutableItem.status = .sending
        pendingItems[item.messageId] = mutableItem

        do {
            try await webSocket.send(type: Constants.MessageType.sendMessage, payload: item.payload)
            // Note: Success is confirmed via handleAck when server responds
            logger.debug("Message sent to WebSocket: \(item.messageId)", category: .messaging)
        } catch {
            // Immediate send failure - schedule retry
            await handleError(messageId: item.messageId, error: error.localizedDescription)
        }
    }

    /// Schedule the next retry check
    private func scheduleNextRetry() {
        // Cancel existing task
        retryTask?.cancel()

        // Find the next item that needs retry
        let now = Date()
        let nextRetry = pendingItems.values
            .filter { $0.status == .pending && $0.nextRetryAt != nil }
            .compactMap { $0.nextRetryAt }
            .filter { $0 > now }
            .min()

        guard let nextDate = nextRetry else { return }

        let delay = nextDate.timeIntervalSince(now)
        guard delay > 0 else {
            // Already past - process immediately
            Task {
                await processQueue()
            }
            return
        }

        retryTask = Task {
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            guard !Task.isCancelled else { return }
            await processQueue()
        }

        logger.debug("Next retry scheduled in \(delay)s", category: .messaging)
    }
}

// MARK: - In-Memory Persistence (for testing)

/// In-memory implementation for testing
actor InMemoryOutboxPersistence: OutboxPersistence {
    private var items: [String: OutboxItem] = [:]

    func getAllPending() async throws -> [OutboxItem] {
        items.values.filter { $0.status == .pending }
    }

    func save(_ item: OutboxItem) async throws {
        items[item.messageId] = item
    }

    func delete(messageId: String) async throws {
        items.removeValue(forKey: messageId)
    }

    func updateStatus(messageId: String, status: OutboxItemStatus, error: String?) async throws {
        guard var item = items[messageId] else { return }
        item.status = status
        item.lastError = error
        items[messageId] = item
    }

    func updateRetry(messageId: String, retryCount: Int, nextRetryAt: Date) async throws {
        guard var item = items[messageId] else { return }
        item.retryCount = retryCount
        item.nextRetryAt = nextRetryAt
        items[messageId] = item
    }
}
