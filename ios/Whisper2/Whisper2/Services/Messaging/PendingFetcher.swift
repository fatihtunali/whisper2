import Foundation

/// Whisper2 PendingFetcher
/// Fetches pending messages from server on WebSocket connect
///
/// Flow:
/// 1. On WS connect, send fetch_pending
/// 2. Server responds with pending_messages (paginated)
/// 3. Process each message through MessagingService
/// 4. Continue fetching until no more pages

// MARK: - Pending Messages Response

/// Response payload from fetch_pending
struct PendingMessagesPayload: Decodable {
    let messages: [MessageReceivedPayload]
    let nextCursor: String?
}

// MARK: - PendingFetcher

/// Actor responsible for fetching pending messages
actor PendingFetcher {

    // MARK: - Properties

    private let webSocket: WebSocketProvider
    private let crypto: MessagingCryptoProvider

    /// Callback to process each fetched message
    var onMessageReceived: ((MessageReceivedPayload) async -> Void)?

    /// Callback when all pending messages are fetched
    var onFetchComplete: (() async -> Void)?

    /// Currently fetching flag
    private var isFetching = false

    /// Current cursor for pagination
    private var currentCursor: String?

    /// Pending response continuation
    private var pendingContinuation: CheckedContinuation<PendingMessagesPayload?, Never>?

    /// Number of messages fetched in current batch
    private var fetchedCount = 0

    /// Default page size
    private let pageLimit = 50

    // MARK: - Initialization

    init(
        webSocket: WebSocketProvider,
        crypto: MessagingCryptoProvider
    ) {
        self.webSocket = webSocket
        self.crypto = crypto
    }

    // MARK: - Public API

    /// Fetch all pending messages
    /// Call this on WebSocket connect
    func fetchPending() async {
        guard !isFetching else {
            logger.debug("Already fetching pending messages", category: .messaging)
            return
        }

        guard webSocket.isConnected else {
            logger.debug("WebSocket not connected, cannot fetch pending", category: .messaging)
            return
        }

        guard let sessionToken = crypto.sessionToken else {
            logger.warning("No session token, cannot fetch pending", category: .messaging)
            return
        }

        isFetching = true
        fetchedCount = 0
        currentCursor = nil

        logger.info("Fetching pending messages", category: .messaging)

        // Fetch all pages
        var hasMore = true
        while hasMore {
            let response = await fetchPage(sessionToken: sessionToken, cursor: currentCursor)

            guard let payload = response else {
                logger.warning("Failed to fetch pending messages page", category: .messaging)
                break
            }

            // Process each message
            for message in payload.messages {
                await onMessageReceived?(message)
                fetchedCount += 1
            }

            // Check for more pages
            if let nextCursor = payload.nextCursor, !nextCursor.isEmpty {
                currentCursor = nextCursor
                hasMore = true
            } else {
                hasMore = false
            }
        }

        isFetching = false
        logger.info("Fetched \(fetchedCount) pending messages", category: .messaging)

        await onFetchComplete?()
    }

    /// Handle pending_messages response from server
    func handlePendingMessagesResponse(_ payload: PendingMessagesPayload) {
        pendingContinuation?.resume(returning: payload)
        pendingContinuation = nil
    }

    /// Called when fetch fails
    func handleFetchError(_ error: String) {
        logger.error("Fetch pending error: \(error)", category: .messaging)
        pendingContinuation?.resume(returning: nil)
        pendingContinuation = nil
    }

    /// Check if currently fetching
    func isCurrentlyFetching() -> Bool {
        isFetching
    }

    // MARK: - Private Methods

    /// Fetch a single page of pending messages
    private func fetchPage(sessionToken: String, cursor: String?) async -> PendingMessagesPayload? {
        let payload = FetchPendingPayload(
            protocolVersion: 1,
            cryptoVersion: 1,
            sessionToken: sessionToken,
            cursor: cursor,
            limit: pageLimit
        )

        // Send request and wait for response
        return await withCheckedContinuation { continuation in
            self.pendingContinuation = continuation

            Task {
                do {
                    try await webSocket.send(type: Constants.MessageType.fetchPending, payload: payload)

                    // Set timeout
                    try await Task.sleep(nanoseconds: 30_000_000_000) // 30 seconds

                    // If we get here, timeout occurred
                    if self.pendingContinuation != nil {
                        logger.warning("Fetch pending timeout", category: .messaging)
                        self.pendingContinuation?.resume(returning: nil)
                        self.pendingContinuation = nil
                    }
                } catch {
                    if !Task.isCancelled {
                        logger.error(error, message: "Failed to send fetch_pending", category: .messaging)
                        self.pendingContinuation?.resume(returning: nil)
                        self.pendingContinuation = nil
                    }
                }
            }
        }
    }
}

// MARK: - PendingFetcher Delegate

/// Protocol for PendingFetcher events
protocol PendingFetcherDelegate: AnyObject {
    /// Called for each message fetched
    func pendingFetcher(_ fetcher: PendingFetcher, didReceiveMessage message: MessageReceivedPayload) async

    /// Called when all pending messages have been fetched
    func pendingFetcherDidComplete(_ fetcher: PendingFetcher) async
}
