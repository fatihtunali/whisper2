import Foundation
import Combine

/// Whisper2 WebSocket Message Router
/// Decodes incoming JSON frames and dispatches to registered handlers

// MARK: - Message Handler Protocol

/// Protocol for handling specific message types
protocol WSMessageHandler: AnyObject {
    associatedtype PayloadType: Codable

    /// The message type this handler responds to
    static var messageType: String { get }

    /// Handle the decoded payload
    func handle(payload: PayloadType, requestId: String?) async
}

// MARK: - Router

/// WebSocket message router and dispatcher
actor WSRouter {

    // MARK: - Types

    /// Closure type for message handlers
    typealias MessageHandler = (Data, String?) async -> Void

    /// Pending request tracking
    private struct PendingRequest {
        let requestId: String
        let messageType: String
        let continuation: CheckedContinuation<Data, Error>
        let timeoutTask: Task<Void, Never>
    }

    // MARK: - Properties

    private var handlers: [String: MessageHandler] = [:]
    private var pendingRequests: [String: PendingRequest] = [:]
    private let requestTimeout: TimeInterval

    /// Publisher for all received messages (for observation)
    private let messageSubject = PassthroughSubject<(type: String, data: Data), Never>()
    var messagePublisher: AnyPublisher<(type: String, data: Data), Never> {
        messageSubject.eraseToAnyPublisher()
    }

    /// Publisher for errors
    private let errorSubject = PassthroughSubject<WSError, Never>()
    var errorPublisher: AnyPublisher<WSError, Never> {
        errorSubject.eraseToAnyPublisher()
    }

    // MARK: - Init

    init(requestTimeout: TimeInterval = 30) {
        self.requestTimeout = requestTimeout
    }

    // MARK: - Handler Registration

    /// Register a handler for a specific message type
    func register(type: String, handler: @escaping MessageHandler) {
        handlers[type] = handler
        logger.debug("Registered handler for: \(type)", category: .network)
    }

    /// Register a typed handler
    func register<P: Codable>(type: String, handler: @escaping (P, String?) async -> Void) {
        handlers[type] = { data, requestId in
            let decoder = JSONDecoder()
            if let frame = try? decoder.decode(WSFrame<P>.self, from: data) {
                await handler(frame.payload, requestId)
            } else {
                logger.error("Failed to decode payload for type: \(type)", category: .network)
            }
        }
        logger.debug("Registered typed handler for: \(type)", category: .network)
    }

    /// Unregister a handler
    func unregister(type: String) {
        handlers.removeValue(forKey: type)
        logger.debug("Unregistered handler for: \(type)", category: .network)
    }

    /// Clear all handlers
    func clearHandlers() {
        handlers.removeAll()
        logger.debug("Cleared all message handlers", category: .network)
    }

    // MARK: - Message Routing

    /// Route an incoming message to appropriate handler
    func route(data: Data) async {
        // First, decode the raw frame to get type and requestId
        guard let rawFrame = try? JSONDecoder().decode(WSRawFrame.self, from: data) else {
            logger.error("Failed to decode WebSocket frame", category: .network)
            return
        }

        let messageType = rawFrame.type
        let requestId = rawFrame.requestId

        logger.debug("Routing message type: \(messageType)", category: .network)

        // Publish for observers
        messageSubject.send((type: messageType, data: data))

        // Handle error messages
        if messageType == Constants.MessageType.error {
            await handleError(data: data, requestId: requestId)
            return
        }

        // Check for pending request response
        if let reqId = requestId, let pending = pendingRequests[reqId] {
            pendingRequests.removeValue(forKey: reqId)
            pending.timeoutTask.cancel()
            pending.continuation.resume(returning: data)
            return
        }

        // Dispatch to registered handler
        if let handler = handlers[messageType] {
            await handler(data, requestId)
        } else {
            logger.warning("No handler for message type: \(messageType)", category: .network)
        }
    }

    // MARK: - Request/Response

    /// Send a request and wait for response with matching requestId
    /// The actual sending is done through WSClient, this just tracks the pending request
    func trackRequest(
        requestId: String,
        expectedResponseType: String
    ) async throws -> Data {
        return try await withCheckedThrowingContinuation { continuation in
            let timeoutTask = Task { [weak self] in
                do {
                    try await Task.sleep(nanoseconds: UInt64(self?.requestTimeout ?? 30) * 1_000_000_000)
                    await self?.handleRequestTimeout(requestId: requestId)
                } catch {
                    // Task cancelled
                }
            }

            let pending = PendingRequest(
                requestId: requestId,
                messageType: expectedResponseType,
                continuation: continuation,
                timeoutTask: timeoutTask
            )

            Task {
                await self.storePendingRequest(requestId: requestId, pending: pending)
            }
        }
    }

    private func storePendingRequest(requestId: String, pending: PendingRequest) {
        pendingRequests[requestId] = pending
    }

    private func handleRequestTimeout(requestId: String) {
        guard let pending = pendingRequests.removeValue(forKey: requestId) else { return }
        logger.warning("Request timeout: \(requestId)", category: .network)
        pending.continuation.resume(throwing: NetworkError.timeout)
    }

    /// Cancel a pending request
    func cancelRequest(requestId: String) {
        guard let pending = pendingRequests.removeValue(forKey: requestId) else { return }
        pending.timeoutTask.cancel()
        pending.continuation.resume(throwing: CancellationError())
    }

    /// Cancel all pending requests
    func cancelAllRequests() {
        for (_, pending) in pendingRequests {
            pending.timeoutTask.cancel()
            pending.continuation.resume(throwing: CancellationError())
        }
        pendingRequests.removeAll()
    }

    // MARK: - Error Handling

    private func handleError(data: Data, requestId: String?) async {
        guard let frame = try? JSONDecoder().decode(WSFrame<WSError>.self, from: data) else {
            logger.error("Failed to decode error frame", category: .network)
            return
        }

        let error = frame.payload
        logger.error("WS Error: [\(error.code)] \(error.message)", category: .network)

        // Publish error
        errorSubject.send(error)

        // If this was a response to a pending request, fail it
        if let reqId = requestId ?? error.requestId, let pending = pendingRequests.removeValue(forKey: reqId) {
            pending.timeoutTask.cancel()
            pending.continuation.resume(throwing: NetworkError.serverError(code: error.code, message: error.message))
        }
    }

    // MARK: - Pending Request Info

    /// Get count of pending requests
    var pendingRequestCount: Int {
        pendingRequests.count
    }

    /// Check if a request is pending
    func isRequestPending(_ requestId: String) -> Bool {
        pendingRequests[requestId] != nil
    }
}

// MARK: - Convenience Extensions

extension WSRouter {
    /// Register handlers for common message types
    func registerDefaultHandlers(
        onMessage: @escaping (MessageReceivedPayload) async -> Void,
        onDelivery: @escaping (MessageDeliveredPayload) async -> Void,
        onGroupEvent: @escaping (GroupUpdatedPayload) async -> Void,
        onCallIncoming: @escaping (CallIncomingPayload) async -> Void,
        onTyping: @escaping (TypingNotificationPayload) async -> Void,
        onPresence: @escaping (PresenceUpdatePayload) async -> Void
    ) {
        // Messaging
        register(type: Constants.MessageType.messageReceived) { (payload: MessageReceivedPayload, _) in
            await onMessage(payload)
        }

        register(type: Constants.MessageType.deliveryReceipt) { (payload: MessageDeliveredPayload, _) in
            await onDelivery(payload)
        }

        // Groups
        register(type: Constants.MessageType.groupUpdated) { (payload: GroupUpdatedPayload, _) in
            await onGroupEvent(payload)
        }

        // Calls
        register(type: Constants.MessageType.callIncoming) { (payload: CallIncomingPayload, _) in
            await onCallIncoming(payload)
        }

        // Presence
        register(type: "typing_notification") { (payload: TypingNotificationPayload, _) in
            await onTyping(payload)
        }

        register(type: "presence_update") { (payload: PresenceUpdatePayload, _) in
            await onPresence(payload)
        }
    }
}

// MARK: - Request ID Generator

extension WSRouter {
    /// Generate a unique request ID
    static func generateRequestId() -> String {
        UUID().uuidString.lowercased()
    }
}
