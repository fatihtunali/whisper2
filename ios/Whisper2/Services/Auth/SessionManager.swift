import Foundation

/// Whisper2 Session Manager
/// Manages authentication session state, persistence, and token refresh
///
/// Session state is persisted to Keychain for security.
/// Automatically refreshes tokens before expiry when connected.

@Observable
final class SessionManager {
    static let shared = SessionManager()

    // MARK: - Dependencies

    private let keychain = KeychainService.shared

    // MARK: - Protocol Constants

    private let protocolVersion = 1
    private let cryptoVersion = 1

    // MARK: - Configuration

    /// Refresh token when less than this time remains
    private let refreshThreshold: TimeInterval = 5 * 60 // 5 minutes

    /// Minimum time between refresh attempts
    private let refreshCooldown: TimeInterval = 30

    // MARK: - State

    private(set) var currentSession: Session?
    private var lastRefreshAttempt: Date?
    private var refreshTask: Task<Void, Never>?
    private weak var wsConnection: WebSocketConnection?

    // MARK: - Computed Properties

    /// Check if user is currently authenticated with valid session
    var isAuthenticated: Bool {
        guard let session = currentSession else { return false }
        return session.expiry > Date()
    }

    /// Current whisper ID if authenticated
    var whisperId: String? {
        currentSession?.whisperId
    }

    /// Current session token if valid
    var sessionToken: String? {
        guard isAuthenticated else { return nil }
        return currentSession?.token
    }

    /// Time until session expires (nil if no session)
    var timeUntilExpiry: TimeInterval? {
        guard let session = currentSession else { return nil }
        return session.expiry.timeIntervalSinceNow
    }

    /// Check if session needs refresh
    var needsRefresh: Bool {
        guard let remaining = timeUntilExpiry else { return false }
        return remaining < refreshThreshold && remaining > 0
    }

    // MARK: - Initialization

    private init() {
        loadSessionFromKeychain()
    }

    // MARK: - Session Persistence

    /// Store a new session
    func storeSession(token: String, expiry: Date, whisperId: String) throws {
        let session = Session(token: token, expiry: expiry, whisperId: whisperId)
        currentSession = session

        // Persist to keychain
        try keychain.storeSession(token: token, expiry: expiry)
        keychain.whisperId = whisperId

        logger.info("Session stored, expires: \(expiry)", category: .auth)

        // Start refresh monitoring
        startRefreshMonitoring()
    }

    /// Load session from keychain (called on init)
    private func loadSessionFromKeychain() {
        guard let validSession = keychain.getValidSession(),
              let whisperId = keychain.whisperId else {
            currentSession = nil
            return
        }

        currentSession = Session(
            token: validSession.token,
            expiry: validSession.expiry,
            whisperId: whisperId
        )

        if isAuthenticated {
            logger.info("Loaded valid session from keychain", category: .auth)
            startRefreshMonitoring()
        } else {
            logger.info("Loaded expired session from keychain", category: .auth)
            currentSession = nil
        }
    }

    /// Clear session (logout)
    func logout() {
        // Cancel refresh task
        refreshTask?.cancel()
        refreshTask = nil

        // Clear session
        currentSession = nil
        keychain.clearSession()

        logger.info("Session cleared (logout)", category: .auth)

        // Notify observers
        NotificationCenter.default.post(name: .authSessionExpired, object: nil)
    }

    // MARK: - Request Authentication

    /// Attach session token to a URL request
    /// - Parameter request: The request to authenticate
    /// - Returns: Modified request with Authorization header
    /// - Throws: AuthError if not authenticated
    func attachToken(to request: inout URLRequest) throws {
        guard let token = sessionToken else {
            throw AuthError.notAuthenticated
        }

        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
    }

    /// Get authorization header value
    /// - Returns: Bearer token string
    /// - Throws: AuthError if not authenticated
    func authorizationHeader() throws -> String {
        guard let token = sessionToken else {
            throw AuthError.notAuthenticated
        }
        return "Bearer \(token)"
    }

    // MARK: - Session Refresh

    /// Set WebSocket connection for refresh operations
    func setConnection(_ ws: WebSocketConnection?) {
        self.wsConnection = ws
    }

    /// Refresh session if needed
    func refreshIfNeeded() async throws {
        guard needsRefresh else { return }

        // Check cooldown
        if let lastAttempt = lastRefreshAttempt,
           Date().timeIntervalSince(lastAttempt) < refreshCooldown {
            logger.debug("Refresh on cooldown", category: .auth)
            return
        }

        try await refresh()
    }

    /// Force refresh the session token
    func refresh() async throws {
        guard let currentToken = currentSession?.token,
              let ws = wsConnection else {
            throw AuthError.notAuthenticated
        }

        lastRefreshAttempt = Date()

        logger.debug("Refreshing session token", category: .auth)

        // Build refresh payload
        let payload: [String: Any] = [
            "protocolVersion": protocolVersion,
            "cryptoVersion": cryptoVersion,
            "sessionToken": currentToken
        ]

        // Send refresh request
        let response = try await ws.sendAndWait(
            type: Constants.MessageType.sessionRefresh,
            payload: payload,
            expectedResponseType: "session_refresh_ack",
            timeout: Constants.Timeout.httpRequest
        )

        // Parse response
        guard let newToken = response["sessionToken"] as? String,
              let expiresAtMs = response["sessionExpiresAt"] as? Int64,
              let whisperId = currentSession?.whisperId else {
            throw AuthError.sessionExpired
        }

        let newExpiry = Time.dateFromMs(expiresAtMs)

        // Update session
        try storeSession(token: newToken, expiry: newExpiry, whisperId: whisperId)

        logger.info("Session refreshed, new expiry: \(newExpiry)", category: .auth)
    }

    // MARK: - Automatic Refresh Monitoring

    private func startRefreshMonitoring() {
        refreshTask?.cancel()

        refreshTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self = self else { break }

                // Check every minute
                try? await Task.sleep(nanoseconds: 60 * 1_000_000_000)

                guard !Task.isCancelled else { break }

                // Try to refresh if needed
                if self.needsRefresh {
                    do {
                        try await self.refresh()
                    } catch {
                        logger.warning("Auto-refresh failed: \(error.localizedDescription)", category: .auth)
                    }
                }

                // Check if session expired
                if let session = self.currentSession, session.expiry < Date() {
                    logger.warning("Session expired", category: .auth)
                    await MainActor.run {
                        self.logout()
                    }
                    break
                }
            }
        }
    }

    // MARK: - Session Validation

    /// Validate current session is still valid on server
    func validateSession() async -> Bool {
        guard let token = sessionToken else { return false }

        // For now, just check local expiry
        // Server validation happens implicitly with each request
        return isAuthenticated
    }
}

// MARK: - Session Type

struct Session: Equatable {
    let token: String
    let expiry: Date
    let whisperId: String

    var isExpired: Bool {
        expiry < Date()
    }

    var remainingTime: TimeInterval {
        expiry.timeIntervalSinceNow
    }
}

// MARK: - Session Extensions

extension SessionManager {
    /// Get session info for logging (redacted token)
    var debugDescription: String {
        guard let session = currentSession else {
            return "SessionManager: No session"
        }

        let tokenPrefix = String(session.token.prefix(8))
        let remaining = session.remainingTime

        return """
        SessionManager:
          WhisperID: \(session.whisperId)
          Token: \(tokenPrefix)...
          Expires: \(session.expiry)
          Remaining: \(Int(remaining))s
          Authenticated: \(isAuthenticated)
        """
    }
}
