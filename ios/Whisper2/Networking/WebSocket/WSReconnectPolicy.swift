import Foundation

/// WebSocket Reconnection State Machine
/// Manages reconnection attempts with exponential backoff and jitter

// MARK: - Connection State

/// Current state of the WebSocket connection
enum WSConnectionState: Equatable {
    case disconnected
    case connecting
    case connected
    case reconnecting(attempt: Int)

    var isConnected: Bool {
        if case .connected = self { return true }
        return false
    }

    var isConnecting: Bool {
        switch self {
        case .connecting, .reconnecting: return true
        default: return false
        }
    }

    var description: String {
        switch self {
        case .disconnected: return "disconnected"
        case .connecting: return "connecting"
        case .connected: return "connected"
        case .reconnecting(let attempt): return "reconnecting (attempt \(attempt))"
        }
    }
}

// MARK: - Reconnect Policy

/// State machine for managing WebSocket reconnection
actor WSReconnectPolicy {

    // MARK: - Configuration

    private let baseDelay: TimeInterval
    private let maxDelay: TimeInterval
    private let jitterFactor: Double
    private let maxAttempts: Int

    // MARK: - State

    private(set) var state: WSConnectionState = .disconnected
    private var currentAttempt: Int = 0
    private var reconnectTask: Task<Void, Never>?
    private var lastConnectedAt: Date?
    private var stateChangeHandler: ((WSConnectionState) -> Void)?

    // MARK: - Init

    init(
        baseDelay: TimeInterval = Constants.Timeout.wsReconnectBase,
        maxDelay: TimeInterval = Constants.Timeout.wsReconnectMax,
        jitterFactor: Double = 0.25,
        maxAttempts: Int = Int.max
    ) {
        self.baseDelay = baseDelay
        self.maxDelay = maxDelay
        self.jitterFactor = jitterFactor
        self.maxAttempts = maxAttempts
    }

    // MARK: - State Change Handler

    func onStateChange(_ handler: @escaping (WSConnectionState) -> Void) {
        self.stateChangeHandler = handler
    }

    // MARK: - State Transitions

    /// Transition to connecting state (initial connection attempt)
    func beginConnecting() {
        cancelReconnect()
        currentAttempt = 0
        transition(to: .connecting)
    }

    /// Connection succeeded
    func didConnect() {
        cancelReconnect()
        currentAttempt = 0
        lastConnectedAt = Date()
        transition(to: .connected)
    }

    /// Connection failed or was lost
    /// Returns the delay before next reconnection attempt, or nil if max attempts reached
    func didDisconnect() -> TimeInterval? {
        cancelReconnect()

        // If we were connected for a significant time, reset attempt counter
        if let lastConnected = lastConnectedAt,
           Date().timeIntervalSince(lastConnected) > maxDelay {
            currentAttempt = 0
        }

        currentAttempt += 1

        guard currentAttempt <= maxAttempts else {
            transition(to: .disconnected)
            return nil
        }

        transition(to: .reconnecting(attempt: currentAttempt))
        return calculateDelay(forAttempt: currentAttempt)
    }

    /// User explicitly disconnected (no reconnection)
    func didDisconnectManually() {
        cancelReconnect()
        currentAttempt = 0
        transition(to: .disconnected)
    }

    /// Reset the policy state
    func reset() {
        cancelReconnect()
        currentAttempt = 0
        lastConnectedAt = nil
        transition(to: .disconnected)
    }

    // MARK: - Scheduled Reconnection

    /// Schedule a reconnection after the calculated delay
    /// - Parameter reconnectAction: Async closure to perform reconnection
    func scheduleReconnect(action reconnectAction: @escaping () async -> Void) {
        guard case .reconnecting = state else { return }

        let delay = calculateDelay(forAttempt: currentAttempt)

        logger.debug("Scheduling reconnect in \(String(format: "%.2f", delay))s (attempt \(currentAttempt))", category: .network)

        reconnectTask = Task {
            do {
                try await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
                guard !Task.isCancelled else { return }
                await reconnectAction()
            } catch {
                // Task was cancelled
            }
        }
    }

    /// Cancel any pending reconnection
    func cancelReconnect() {
        reconnectTask?.cancel()
        reconnectTask = nil
    }

    // MARK: - Delay Calculation

    /// Calculate delay with exponential backoff and jitter
    private func calculateDelay(forAttempt attempt: Int) -> TimeInterval {
        guard attempt > 0 else { return 0 }

        // Exponential backoff: baseDelay * 2^(attempt-1)
        let exponential = baseDelay * pow(2.0, Double(attempt - 1))
        let capped = min(exponential, maxDelay)

        // Add jitter: +/- jitterFactor
        let jitterRange = capped * jitterFactor
        let jitter = Double.random(in: -jitterRange...jitterRange)

        return max(0, capped + jitter)
    }

    // MARK: - Private

    private func transition(to newState: WSConnectionState) {
        guard state != newState else { return }

        let oldState = state
        state = newState

        logger.info("WS state: \(oldState.description) -> \(newState.description)", category: .network)

        stateChangeHandler?(newState)
    }
}

// MARK: - Reconnection Info

/// Information about the current reconnection state
struct WSReconnectionInfo {
    let attempt: Int
    let delay: TimeInterval
    let nextAttemptAt: Date
}
