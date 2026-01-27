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
    
    private init() {
        setupMessageHandler()
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
        // 1. Derive keys from mnemonic
        let derivedKeys = try KeyDerivation.deriveKeys(from: mnemonic)
        
        // 2. Generate key pairs
        let encKP = try crypto.generateEncryptionKeyPair(from: derivedKeys.encSeed)
        let signKP = try crypto.generateSigningKeyPair(from: derivedKeys.signSeed)
        
        // 3. Store in keychain
        try keychain.storeKeys(
            encPrivateKey: encKP.privateKey,
            encPublicKey: encKP.publicKey,
            signPrivateKey: signKP.privateKey,
            signPublicKey: signKP.publicKey,
            contactsKey: derivedKeys.contactsKey
        )
        keychain.mnemonic = mnemonic
        
        // 4. Keep in memory
        self.encKeyPair = encKP
        self.signKeyPair = signKP
        
        // 5. Connect and authenticate
        ws.connect()
        try await waitForConnection()
        
        // 6. Perform auth flow
        let ack = try await performAuth(
            whisperId: nil,  // New registration - server assigns ID
            encPublicKey: encKP.publicKey,
            signPublicKey: signKP.publicKey,
            signPrivateKey: signKP.privateKey
        )
        
        // 7. Store session
        await MainActor.run {
            self.whisperId = ack.whisperId
            self.sessionToken = ack.sessionToken
            self.isAuthenticated = true
        }
        keychain.whisperId = ack.whisperId
        keychain.sessionToken = ack.sessionToken

        // 8. Send push tokens now that we're authenticated
        await PushNotificationService.shared.sendTokensAfterAuth()

        // 9. Fetch any pending messages from server
        await fetchPendingMessagesAfterAuth()
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
        guard let encPriv = keychain.getData(forKey: Constants.StorageKey.encPrivateKey),
              let encPub = keychain.getData(forKey: Constants.StorageKey.encPublicKey),
              let signPriv = keychain.getData(forKey: Constants.StorageKey.signPrivateKey),
              let signPub = keychain.getData(forKey: Constants.StorageKey.signPublicKey),
              let savedWhisperId = keychain.whisperId else {
            throw AuthError.notAuthenticated
        }
        
        self.encKeyPair = CryptoService.KeyPair(publicKey: encPub, privateKey: encPriv)
        self.signKeyPair = CryptoService.KeyPair(publicKey: signPub, privateKey: signPriv)
        
        ws.connect()
        try await waitForConnection()
        
        let ack = try await performAuth(
            whisperId: savedWhisperId,  // Recovery
            encPublicKey: encPub,
            signPublicKey: signPub,
            signPrivateKey: signPriv
        )
        
        await MainActor.run {
            self.whisperId = ack.whisperId
            self.sessionToken = ack.sessionToken
            self.isAuthenticated = true
        }
        keychain.sessionToken = ack.sessionToken

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
        
        // Wait for ack
        return try await withCheckedThrowingContinuation { continuation in
            self.authContinuation = continuation
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
                authContinuation?.resume(returning: frame.payload)
                authContinuation = nil
            }
            
        case Constants.MessageType.error:
            if let frame = try? JSONDecoder().decode(WsFrame<ErrorPayload>.self, from: data) {
                authContinuation?.resume(throwing: AuthError.registrationFailed(frame.payload.message))
                authContinuation = nil
            }
            
        default:
            break
        }
    }
}
