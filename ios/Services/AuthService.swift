import Foundation
import Combine

/// Authentication service - matches server AuthService.ts
final class AuthService: ObservableObject {
    static let shared = AuthService()
    
    @Published private(set) var isAuthenticated = false
    @Published private(set) var whisperId: String?
    @Published private(set) var sessionToken: String?
    
    private let keychain = KeychainService.shared
    private let crypto = CryptoService.shared
    private let ws = WebSocketService.shared
    private var cancellables = Set<AnyCancellable>()
    
    // Keys in memory
    private var encKeyPair: CryptoService.KeyPair?
    private var signKeyPair: CryptoService.KeyPair?
    
    private var pendingChallenge: (id: String, bytes: Data)?
    private var authContinuation: CheckedContinuation<RegisterAckPayload, Error>?
    private var isAuthenticating = false  // Prevent duplicate auth attempts
    private let authLock = NSLock()  // Thread-safe auth state

    // Track which "connection instance" we authenticated on
    // Reset when connection is lost, so we know to re-auth on new connection
    private var authenticatedConnectionId: UUID?
    private var currentConnectionId: UUID?

    private init() {
        setupMessageHandler()
        setupReconnectHandler()
    }

    /// Handle WebSocket reconnections - need to re-authenticate
    private func setupReconnectHandler() {
        ws.$connectionState
            .sink { [weak self] state in
                guard let self = self else { return }

                switch state {
                case .disconnected, .reconnecting:
                    // Reset authenticated state - server requires new auth on each connection
                    if self.isAuthenticated {
                        print("WebSocket disconnected - resetting authentication state")
                        DispatchQueue.main.async {
                            self.isAuthenticated = false
                        }
                    }
                    // Clear connection tracking - this connection is no longer valid
                    self.authLock.lock()
                    self.currentConnectionId = nil
                    self.authenticatedConnectionId = nil
                    self.isAuthenticating = false
                    // Cancel any pending auth continuation
                    if let cont = self.authContinuation {
                        self.authContinuation = nil
                        self.authLock.unlock()
                        cont.resume(throwing: AuthError.registrationFailed("Connection lost"))
                    } else {
                        self.authLock.unlock()
                    }

                case .connected:
                    // Generate a new connection ID for this connection
                    let newConnectionId = UUID()
                    self.authLock.lock()
                    self.currentConnectionId = newConnectionId
                    // Check if we need to authenticate on this NEW connection
                    // Use connection ID instead of isAuthenticated to avoid race conditions
                    let needsAuth = self.authenticatedConnectionId != newConnectionId &&
                                    !self.isAuthenticating &&
                                    self.keychain.whisperId != nil
                    self.authLock.unlock()

                    if needsAuth {
                        print("WebSocket connected (connId: \(newConnectionId.uuidString.prefix(8))) - authenticating...")
                        Task {
                            do {
                                try await self.reconnect()
                            } catch {
                                print("Re-authentication failed: \(error)")
                            }
                        }
                    } else {
                        print("WebSocket connected - already authenticated or auth in progress")
                    }

                case .connecting:
                    break
                }
            }
            .store(in: &cancellables)
    }
    
    /// Current user with keys and session info
    var currentUser: LocalUser? {
        guard let whisperId = whisperId,
              let encKP = encKeyPair,
              let signKP = signKeyPair else {
            return nil
        }
        return LocalUser(
            whisperId: whisperId,
            encPublicKey: encKP.publicKey,
            encPrivateKey: encKP.privateKey,
            signPublicKey: signKP.publicKey,
            signPrivateKey: signKP.privateKey,
            seedPhrase: keychain.mnemonic ?? "",
            sessionToken: sessionToken,
            sessionExpiresAt: nil,
            deviceId: keychain.getOrCreateDeviceId()
        )
    }
    
    private func setupMessageHandler() {
        ws.messagePublisher
            .sink { [weak self] data in
                self?.handleMessage(data)
            }
            .store(in: &cancellables)
    }
    
    // MARK: - Register New Account
    
    func registerNewAccount(mnemonic: String) async throws {
        print("[AuthService] Starting new account registration...")

        // 1. Derive keys from mnemonic
        print("[AuthService] Step 1: Deriving keys from mnemonic...")
        print("[AuthService] Mnemonic word count: \(mnemonic.split(separator: " ").count)")
        print("[AuthService] Mnemonic first word: \(mnemonic.split(separator: " ").first ?? "none")")
        print("[AuthService] Mnemonic last word: \(mnemonic.split(separator: " ").last ?? "none")")
        let derivedKeys = try KeyDerivation.deriveKeys(from: mnemonic)
        print("[AuthService] Keys derived successfully")
        print("[AuthService] encSeed hash: \(derivedKeys.encSeed.prefix(4).map { String(format: "%02x", $0) }.joined())")
        print("[AuthService] signSeed hash: \(derivedKeys.signSeed.prefix(4).map { String(format: "%02x", $0) }.joined())")

        // 2. Generate key pairs
        print("[AuthService] Step 2: Generating key pairs...")
        let encKP = try crypto.generateEncryptionKeyPair(from: derivedKeys.encSeed)
        let signKP = try crypto.generateSigningKeyPair(from: derivedKeys.signSeed)
        print("[AuthService] Key pairs generated")
        print("[AuthService] encPublicKey: \(encKP.publicKey.prefix(8).map { String(format: "%02x", $0) }.joined())...")
        print("[AuthService] signPublicKey: \(signKP.publicKey.prefix(8).map { String(format: "%02x", $0) }.joined())...")

        // 3. Store in keychain
        print("[AuthService] Step 3: Storing keys in keychain...")
        try keychain.storeKeys(
            encPrivateKey: encKP.privateKey,
            encPublicKey: encKP.publicKey,
            signPrivateKey: signKP.privateKey,
            signPublicKey: signKP.publicKey,
            contactsKey: derivedKeys.contactsKey
        )
        keychain.mnemonic = mnemonic
        print("[AuthService] Keys stored in keychain")

        // 4. Keep in memory
        self.encKeyPair = encKP
        self.signKeyPair = signKP

        // 5. Connect and authenticate
        print("[AuthService] Step 5: Connecting to WebSocket...")
        ws.connect()
        try await waitForConnection()
        print("[AuthService] WebSocket connected")

        // 6. Perform auth flow
        print("[AuthService] Step 6: Performing authentication...")
        let ack = try await performAuth(
            whisperId: nil,  // New registration - server assigns ID
            encPublicKey: encKP.publicKey,
            signPublicKey: signKP.publicKey,
            signPrivateKey: signKP.privateKey
        )
        print("[AuthService] Authentication complete, received whisperId: \(ack.whisperId)")

        // 7. Store session and mark connection as authenticated
        print("[AuthService] Step 7: Storing session...")
        authLock.lock()
        authenticatedConnectionId = currentConnectionId
        authLock.unlock()

        await MainActor.run {
            self.whisperId = ack.whisperId
            self.sessionToken = ack.sessionToken
            self.isAuthenticated = true
        }
        keychain.whisperId = ack.whisperId
        keychain.sessionToken = ack.sessionToken
        print("[AuthService] Session stored, isAuthenticated = true, connId: \(currentConnectionId?.uuidString.prefix(8) ?? "nil")")

        // 8. Send push tokens now that we're authenticated
        print("[AuthService] Step 8: Sending push tokens...")
        await PushNotificationService.shared.sendTokensAfterAuth()

        // 9. Fetch any pending messages from server
        print("[AuthService] Step 9: Fetching pending messages...")
        await fetchPendingMessagesAfterAuth()
        print("[AuthService] Registration complete!")
    }

    // MARK: - Recover Account
    
    func recoverAccount(mnemonic: String) async throws {
        guard KeyDerivation.isValidMnemonic(mnemonic) else {
            throw CryptoError.invalidMnemonic
        }
        
        // Same as register but whisperId may be provided for recovery
        // For now, treat same as new (server matches by public keys)
        try await registerNewAccount(mnemonic: mnemonic)
    }
    
    // MARK: - Reconnect with existing session

    func reconnect() async throws {
        // Prevent duplicate auth attempts with thread-safe check
        authLock.lock()
        guard !isAuthenticating else {
            authLock.unlock()
            print("Authentication already in progress, skipping")
            return
        }
        isAuthenticating = true
        authLock.unlock()

        defer {
            authLock.lock()
            isAuthenticating = false
            authLock.unlock()
        }

        guard let encPriv = keychain.getData(forKey: Constants.StorageKey.encPrivateKey),
              let encPub = keychain.getData(forKey: Constants.StorageKey.encPublicKey),
              let signPriv = keychain.getData(forKey: Constants.StorageKey.signPrivateKey),
              let signPub = keychain.getData(forKey: Constants.StorageKey.signPublicKey),
              let savedWhisperId = keychain.whisperId else {
            throw AuthError.notAuthenticated
        }

        self.encKeyPair = CryptoService.KeyPair(publicKey: encPub, privateKey: encPriv)
        self.signKeyPair = CryptoService.KeyPair(publicKey: signPub, privateKey: signPriv)

        // Only connect if not already connected
        if ws.connectionState != .connected {
            ws.connect()
            try await waitForConnection()
        }

        let ack = try await performAuth(
            whisperId: savedWhisperId,  // Recovery
            encPublicKey: encPub,
            signPublicKey: signPub,
            signPrivateKey: signPriv
        )

        // Mark this connection as authenticated
        authLock.lock()
        authenticatedConnectionId = currentConnectionId
        authLock.unlock()

        await MainActor.run {
            self.whisperId = ack.whisperId
            self.sessionToken = ack.sessionToken
            self.isAuthenticated = true
        }
        keychain.sessionToken = ack.sessionToken

        print("Authentication complete on connection \(currentConnectionId?.uuidString.prefix(8) ?? "nil")")

        // Send push tokens now that we're authenticated
        await PushNotificationService.shared.sendTokensAfterAuth()

        // Fetch any pending messages from server
        await fetchPendingMessagesAfterAuth()
    }

    /// Fetch pending messages after authentication
    private func fetchPendingMessagesAfterAuth() async {
        do {
            print("Fetching pending messages after authentication...")
            try await MessagingService.shared.fetchPendingMessages()
            print("Pending messages fetch requested")
        } catch {
            print("Failed to fetch pending messages: \(error)")
        }
    }

    // MARK: - Logout
    
    func logout() {
        if let token = sessionToken {
            let payload = LogoutPayload(sessionToken: token)
            let frame = WsFrame(type: Constants.MessageType.logout, payload: payload)
            Task { try? await ws.send(frame) }
        }
        
        ws.disconnect()
        keychain.clearAll()
        
        encKeyPair = nil
        signKeyPair = nil
        whisperId = nil
        sessionToken = nil
        isAuthenticated = false
    }
    
    // MARK: - Private
    
    private func waitForConnection() async throws {
        for _ in 0..<50 {
            if ws.connectionState == .connected {
                return
            }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        throw NetworkError.timeout
    }
    
    private func performAuth(
        whisperId: String?,
        encPublicKey: Data,
        signPublicKey: Data,
        signPrivateKey: Data
    ) async throws -> RegisterAckPayload {
        let deviceId = keychain.getOrCreateDeviceId()

        // Cancel any existing continuation to prevent leaks
        authLock.lock()
        if let existingContinuation = authContinuation {
            authContinuation = nil
            authLock.unlock()
            existingContinuation.resume(throwing: AuthError.registrationFailed("Auth cancelled - new attempt started"))
        } else {
            authLock.unlock()
        }

        // Clear any stale challenge
        pendingChallenge = nil

        // Step 1: Send register_begin
        let beginPayload = RegisterBeginPayload(deviceId: deviceId, whisperId: whisperId)
        let beginFrame = WsFrame(type: Constants.MessageType.registerBegin, payload: beginPayload)
        try await ws.send(beginFrame)

        // Wait for challenge
        let challenge = try await waitForChallenge()

        // Step 2: Sign challenge - SHA256(challengeBytes) then sign
        let challengeBytes = Data(base64Encoded: challenge.challenge)!
        let signature = try crypto.signChallenge(challengeBytes, privateKey: signPrivateKey)

        // Step 3: Send register_proof
        let proofPayload = RegisterProofPayload(
            challengeId: challenge.challengeId,
            deviceId: deviceId,
            whisperId: whisperId,
            encPublicKey: encPublicKey.base64EncodedString(),
            signPublicKey: signPublicKey.base64EncodedString(),
            signature: signature.base64EncodedString()
        )
        let proofFrame = WsFrame(type: Constants.MessageType.registerProof, payload: proofPayload)
        try await ws.send(proofFrame)

        // Wait for ack with timeout
        return try await withCheckedThrowingContinuation { continuation in
            authLock.lock()
            self.authContinuation = continuation
            authLock.unlock()

            // Set a timeout to prevent hanging forever
            Task {
                try? await Task.sleep(nanoseconds: 10_000_000_000) // 10 seconds
                self.authLock.lock()
                if let cont = self.authContinuation {
                    self.authContinuation = nil
                    self.authLock.unlock()
                    cont.resume(throwing: AuthError.registrationFailed("Auth timeout"))
                } else {
                    self.authLock.unlock()
                }
            }
        }
    }
    
    private func waitForChallenge() async throws -> RegisterChallengePayload {
        for _ in 0..<100 {
            if let challenge = pendingChallenge {
                pendingChallenge = nil
                return RegisterChallengePayload(
                    challengeId: challenge.id,
                    challenge: challenge.bytes.base64EncodedString(),
                    expiresAt: Int64(Date().timeIntervalSince1970 * 1000) + 60000
                )
            }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        throw AuthError.invalidChallenge
    }
    
    private func handleMessage(_ data: Data) {
        guard let raw = try? JSONDecoder().decode(RawWsFrame.self, from: data) else { return }
        
        switch raw.type {
        case Constants.MessageType.registerChallenge:
            if let frame = try? JSONDecoder().decode(WsFrame<RegisterChallengePayload>.self, from: data),
               let bytes = Data(base64Encoded: frame.payload.challenge) {
                pendingChallenge = (frame.payload.challengeId, bytes)
            }
            
        case Constants.MessageType.registerAck:
            if let frame = try? JSONDecoder().decode(WsFrame<RegisterAckPayload>.self, from: data) {
                authLock.lock()
                let cont = authContinuation
                authContinuation = nil
                authLock.unlock()
                cont?.resume(returning: frame.payload)
            }

        case Constants.MessageType.error:
            if let frame = try? JSONDecoder().decode(WsFrame<ErrorPayload>.self, from: data) {
                authLock.lock()
                let cont = authContinuation
                authContinuation = nil
                authLock.unlock()
                cont?.resume(throwing: AuthError.registrationFailed(frame.payload.message))
            }

        case Constants.MessageType.sessionRefreshAck:
            if let frame = try? JSONDecoder().decode(WsFrame<SessionRefreshAckPayload>.self, from: data) {
                // Update stored session token and expiry
                keychain.setString(frame.payload.sessionToken, forKey: Constants.StorageKey.sessionToken)
                print("[AuthService] Session refreshed: expires at \(frame.payload.sessionExpiresAt)")
            }

        default:
            break
        }
    }
}
