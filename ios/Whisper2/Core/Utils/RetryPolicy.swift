import Foundation

/// Retry policy with exponential backoff and jitter
/// Used for WebSocket reconnection and message outbox

struct RetryPolicy {
    let maxAttempts: Int
    let baseDelay: TimeInterval
    let maxDelay: TimeInterval
    let jitterFactor: Double

    init(
        maxAttempts: Int = 5,
        baseDelay: TimeInterval = 1.0,
        maxDelay: TimeInterval = 30.0,
        jitterFactor: Double = 0.2
    ) {
        self.maxAttempts = maxAttempts
        self.baseDelay = baseDelay
        self.maxDelay = maxDelay
        self.jitterFactor = jitterFactor
    }

    /// Calculate delay for attempt number (0-indexed)
    func delay(forAttempt attempt: Int) -> TimeInterval {
        guard attempt > 0 else { return 0 }

        // Exponential backoff: baseDelay * 2^attempt
        let exponential = baseDelay * pow(2.0, Double(attempt - 1))
        let capped = min(exponential, maxDelay)

        // Add jitter: +-jitterFactor
        let jitterRange = capped * jitterFactor
        let jitter = Double.random(in: -jitterRange...jitterRange)

        return max(0, capped + jitter)
    }

    /// Check if should retry given attempt number
    func shouldRetry(attempt: Int) -> Bool {
        attempt < maxAttempts
    }

    /// Get next retry date
    func nextRetryDate(forAttempt attempt: Int) -> Date {
        Date().addingTimeInterval(delay(forAttempt: attempt))
    }
}

// MARK: - Predefined Policies
extension RetryPolicy {
    /// WebSocket reconnection policy
    static let webSocket = RetryPolicy(
        maxAttempts: Int.max, // Infinite retries
        baseDelay: Constants.Timeout.wsReconnectBase,
        maxDelay: Constants.Timeout.wsReconnectMax,
        jitterFactor: 0.25
    )

    /// Message outbox retry policy
    static let outbox = RetryPolicy(
        maxAttempts: Constants.Limits.outboxMaxRetries,
        baseDelay: 2.0,
        maxDelay: 60.0,
        jitterFactor: 0.2
    )

    /// HTTP request retry policy
    static let http = RetryPolicy(
        maxAttempts: 3,
        baseDelay: 1.0,
        maxDelay: 10.0,
        jitterFactor: 0.1
    )

    /// No retry
    static let none = RetryPolicy(maxAttempts: 1, baseDelay: 0, maxDelay: 0, jitterFactor: 0)
}

// MARK: - Retry Executor
actor RetryExecutor<T> {
    private let policy: RetryPolicy
    private let operation: () async throws -> T
    private let shouldRetryError: (Error) -> Bool

    init(
        policy: RetryPolicy,
        shouldRetryError: @escaping (Error) -> Bool = { _ in true },
        operation: @escaping () async throws -> T
    ) {
        self.policy = policy
        self.operation = operation
        self.shouldRetryError = shouldRetryError
    }

    func execute() async throws -> T {
        var lastError: Error?

        for attempt in 0..<policy.maxAttempts {
            do {
                if attempt > 0 {
                    let delay = policy.delay(forAttempt: attempt)
                    logger.debug("Retry attempt \(attempt), waiting \(delay)s", category: .network)
                    try await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
                }

                return try await operation()
            } catch {
                lastError = error

                if !shouldRetryError(error) {
                    throw error
                }

                if !policy.shouldRetry(attempt: attempt + 1) {
                    throw error
                }

                logger.warning("Operation failed (attempt \(attempt + 1)): \(error.localizedDescription)", category: .network)
            }
        }

        throw lastError ?? NetworkError.timeout
    }
}
