import Foundation
import Observation

/// AppEnvironment - Dependency Container
/// Holds references to singleton and shared services
/// Observable for SwiftUI binding

@Observable
final class AppEnvironment {
    // MARK: - Singleton Services

    /// Session and authentication state manager
    let sessionManager: SessionManager

    /// WebSocket client for real-time communication
    let wsClient: WSClient

    /// Call service for voice/video calls
    let callService: CallService

    // MARK: - Lazy Services (created when authenticated)

    /// Messaging service - requires full dependency setup
    private var _messagingService: MessagingService?

    /// Contacts backup service
    private var _contactsBackupService: ContactsBackupService?

    // MARK: - Service Accessors

    var messagingService: MessagingService? {
        return _messagingService
    }

    var contactsBackupService: ContactsBackupService? {
        return _contactsBackupService
    }

    // MARK: - State

    /// Whether the app has completed initial setup
    private(set) var isInitialized: Bool = false

    // MARK: - Initialization

    init() {
        logger.info("Initializing AppEnvironment", category: .general)

        // Use shared singleton instances
        self.sessionManager = SessionManager.shared
        self.wsClient = WSClient()
        self.callService = CallService.shared

        // Set up callbacks
        setupSessionCallbacks()
        setupWebSocketCallbacks()

        isInitialized = true
        logger.info("AppEnvironment initialized successfully", category: .general)
    }

    /// Initializer for testing with mock dependencies
    init(
        sessionManager: SessionManager,
        wsClient: WSClient,
        callService: CallService
    ) {
        self.sessionManager = sessionManager
        self.wsClient = wsClient
        self.callService = callService

        setupSessionCallbacks()
        setupWebSocketCallbacks()

        isInitialized = true
    }

    // MARK: - Session Callbacks

    private func setupSessionCallbacks() {
        // Listen for force logout events
        NotificationCenter.default.addObserver(
            forName: .authForceLogout,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.handleForceLogout()
        }

        // Listen for session expiry
        NotificationCenter.default.addObserver(
            forName: .authSessionExpired,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.handleSessionExpired()
        }

        // Listen for token updates
        NotificationCenter.default.addObserver(
            forName: .voipTokenUpdated,
            object: nil,
            queue: .main
        ) { [weak self] notification in
            if let token = notification.userInfo?["token"] as? String {
                Task {
                    await self?.updateServerTokens(voipToken: token)
                }
            }
        }

        NotificationCenter.default.addObserver(
            forName: .pushTokenUpdated,
            object: nil,
            queue: .main
        ) { [weak self] notification in
            if let token = notification.userInfo?["token"] as? String {
                Task {
                    await self?.updateServerTokens(pushToken: token)
                }
            }
        }
    }

    private func setupWebSocketCallbacks() {
        // Set session manager's WebSocket connection reference
        // Note: The actual WSClient is an actor, so we need a protocol bridge
        // This will be set up when connecting
    }

    // MARK: - Service Factory Methods

    /// Create or return the messaging service
    /// Must be called after authentication
    func createMessagingService(
        cryptoProvider: MessagingCryptoProvider,
        contactProvider: ContactProvider,
        persistence: MessagePersistence
    ) -> MessagingService {
        if let existing = _messagingService {
            return existing
        }

        logger.info("Creating MessagingService", category: .messaging)

        // Create WebSocket provider adapter
        let wsProvider = WSClientAdapter(wsClient: wsClient)

        // Create outbox and deduper
        let outbox = OutboxQueue(webSocket: wsProvider)
        let deduper = Deduper()

        let service = MessagingService(
            crypto: cryptoProvider,
            contacts: contactProvider,
            persistence: persistence,
            webSocket: wsProvider,
            outbox: outbox,
            deduper: deduper
        )

        _messagingService = service
        return service
    }

    /// Create or return the contacts backup service
    func createContactsBackupService() -> ContactsBackupService {
        if let existing = _contactsBackupService {
            return existing
        }

        logger.info("Creating ContactsBackupService", category: .storage)

        let service = ContactsBackupService()
        _contactsBackupService = service
        return service
    }

    // MARK: - Session Handling

    private func handleForceLogout() {
        logger.warning("Force logout received", category: .auth)
        Task {
            await clearServices()
        }
    }

    private func handleSessionExpired() {
        logger.warning("Session expired", category: .auth)
        Task {
            await clearServices()
        }
    }

    private func clearServices() async {
        logger.info("Clearing services", category: .general)

        // Disconnect WebSocket
        await wsClient.disconnect()

        // Clear lazy services
        _messagingService = nil
        _contactsBackupService = nil
    }

    private func updateServerTokens(pushToken: String? = nil, voipToken: String? = nil) async {
        guard sessionManager.isAuthenticated else { return }

        // Store tokens locally for now
        // Actual server update happens via WebSocket update_tokens message
        if let pushToken = pushToken {
            UserDefaults.standard.set(pushToken, forKey: Constants.StorageKey.pushToken)
        }
        if let voipToken = voipToken {
            UserDefaults.standard.set(voipToken, forKey: Constants.StorageKey.voipToken)
        }

        logger.info("Tokens stored locally", category: .auth)

        // TODO: Send update_tokens message via WebSocket
    }

    // MARK: - Public Methods

    /// Called when user logs out
    func logout() async {
        logger.info("User logout initiated", category: .auth)

        // Clear session
        sessionManager.logout()

        // Clear services
        await clearServices()

        logger.info("Logout completed", category: .auth)
    }

    /// Called after successful authentication
    func onAuthenticated() {
        logger.info("User authenticated, connecting WebSocket", category: .auth)

        Task {
            await wsClient.connect()
        }
    }

    /// Connect WebSocket if authenticated
    func connectIfAuthenticated() async {
        guard sessionManager.isAuthenticated else {
            logger.debug("Not authenticated, skipping WebSocket connect", category: .network)
            return
        }

        await wsClient.connect()
    }

    /// Disconnect WebSocket
    func disconnect() async {
        await wsClient.disconnect()
    }
}

// MARK: - Notification Names

extension Notification.Name {
    static let authForceLogout = Notification.Name("authForceLogout")
    static let authSessionExpired = Notification.Name("authSessionExpired")
}

// MARK: - WebSocket Provider Adapter

/// Adapts the actor-based WSClient to the WebSocketProvider protocol
final class WSClientAdapter: WebSocketProvider, @unchecked Sendable {
    private let wsClient: WSClient

    var isConnected: Bool {
        // This is a synchronous check - in practice we'd track state
        false // Will be updated when state changes
    }

    init(wsClient: WSClient) {
        self.wsClient = wsClient
    }

    func send(type: String, payload: Encodable) async throws {
        let encoder = JSONEncoder()
        let payloadData = try encoder.encode(AnyEncodable(payload))

        // Build the frame
        let frame: [String: Any] = [
            "type": type,
            "payload": try JSONSerialization.jsonObject(with: payloadData)
        ]

        let data = try JSONSerialization.data(withJSONObject: frame)
        try await wsClient.send(data)
    }
}

// MARK: - AnyEncodable Helper

struct AnyEncodable: Encodable {
    private let _encode: (Encoder) throws -> Void

    init(_ wrapped: Encodable) {
        _encode = wrapped.encode
    }

    func encode(to encoder: Encoder) throws {
        try _encode(encoder)
    }
}

// MARK: - Deduper

/// Message deduplication helper
actor Deduper {
    private var processedMessages: Set<String> = []
    private let maxCacheSize = 10000

    /// Check if a message has already been processed
    func isDuplicate(messageId: String, conversationId: String) -> Bool {
        let key = "\(conversationId):\(messageId)"
        return processedMessages.contains(key)
    }

    /// Mark a message as processed
    func markProcessed(messageId: String, conversationId: String) {
        let key = "\(conversationId):\(messageId)"
        processedMessages.insert(key)

        // Trim cache if too large
        if processedMessages.count > maxCacheSize {
            // Remove oldest entries (this is a simple approach)
            let toRemove = processedMessages.count - maxCacheSize
            for _ in 0..<toRemove {
                if let first = processedMessages.first {
                    processedMessages.remove(first)
                }
            }
        }
    }

    /// Clear all processed messages
    func clear() {
        processedMessages.removeAll()
    }
}

// MARK: - Contacts Backup Service

/// Handles encrypted backup and restore of contacts
final class ContactsBackupService: @unchecked Sendable {
    // MARK: - Dependencies

    private let keychain = KeychainService.shared
    private let session = SessionManager.shared

    // MARK: - API Endpoints

    private let backupEndpoint = "/api/contacts/backup"
    private let restoreEndpoint = "/api/contacts/restore"

    // MARK: - Public Methods

    /// Backup contacts to server
    /// - Parameter contacts: Encrypted contacts data
    func backup(_ contacts: Data) async throws {
        guard session.isAuthenticated,
              let token = session.sessionToken else {
            throw AuthError.notAuthenticated
        }

        var request = URLRequest(url: Constants.Server.httpBaseURL.appendingPathComponent(backupEndpoint))
        request.httpMethod = "PUT"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        request.httpBody = contacts

        let (_, response) = try await URLSession.shared.data(for: request)

        guard let httpResponse = response as? HTTPURLResponse,
              (200...299).contains(httpResponse.statusCode) else {
            throw NetworkError.httpError(statusCode: (response as? HTTPURLResponse)?.statusCode ?? 0, message: nil)
        }

        logger.info("Contacts backup successful", category: .storage)
    }

    /// Restore contacts from server
    /// - Returns: Encrypted contacts data
    func restore() async throws -> Data {
        guard session.isAuthenticated,
              let token = session.sessionToken else {
            throw AuthError.notAuthenticated
        }

        var request = URLRequest(url: Constants.Server.httpBaseURL.appendingPathComponent(restoreEndpoint))
        request.httpMethod = "GET"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

        let (data, response) = try await URLSession.shared.data(for: request)

        guard let httpResponse = response as? HTTPURLResponse,
              (200...299).contains(httpResponse.statusCode) else {
            throw NetworkError.httpError(statusCode: (response as? HTTPURLResponse)?.statusCode ?? 0, message: nil)
        }

        logger.info("Contacts restore successful, \(data.count) bytes", category: .storage)
        return data
    }
}
