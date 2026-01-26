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
        let outboxPersistence = InMemoryOutboxPersistence()
        let outbox = OutboxQueue(persistence: outboxPersistence, webSocket: wsProvider)
        let dedupPersistence = InMemoryDedupPersistence()
        let deduper = Deduper(persistence: dedupPersistence)

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

        let service = ContactsBackupService.shared
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

        // Store tokens locally
        if let pushToken = pushToken {
            UserDefaults.standard.set(pushToken, forKey: Constants.StorageKey.pushToken)
        }
        if let voipToken = voipToken {
            UserDefaults.standard.set(voipToken, forKey: Constants.StorageKey.voipToken)
        }

        // Send update_tokens message via WebSocket
        guard let sessionToken = KeychainService.shared.sessionToken else {
            logger.warning("Cannot send tokens - no session token", category: .auth)
            return
        }

        let payload: [String: Any] = [
            "protocolVersion": Constants.protocolVersion,
            "cryptoVersion": Constants.cryptoVersion,
            "sessionToken": sessionToken,
            "pushToken": pushToken ?? UserDefaults.standard.string(forKey: Constants.StorageKey.pushToken) ?? "",
            "voipToken": voipToken ?? UserDefaults.standard.string(forKey: Constants.StorageKey.voipToken) ?? ""
        ]

        do {
            let frame: [String: Any] = [
                "type": Constants.MessageType.updateTokens,
                "payload": payload
            ]
            let data = try JSONSerialization.data(withJSONObject: frame)
            try await wsClient.send(data)
            logger.info("Sent token update to server", category: .auth)
        } catch {
            logger.error("Failed to send token update: \(error.localizedDescription)", category: .auth)
        }
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

// NOTE: Notification.Name extensions are defined in Services/Auth/AuthService.swift

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

// NOTE: Deduper is defined in Services/Messaging/Deduper.swift
// NOTE: ContactsBackupService is defined in Services/Contacts/ContactsBackupService.swift
