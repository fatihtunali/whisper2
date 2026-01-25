import Foundation
import CryptoKit

/// Whisper2 Authentication Service
/// Handles registration, recovery, and challenge-response authentication
///
/// Flow:
/// 1. register_begin(deviceId, platform) -> receive challenge
/// 2. sign challenge with Ed25519 private key
/// 3. register_proof(keys, tokens, signature) -> receive ack with sessionToken
///
/// Recovery flow includes whisperId in begin/proof messages

final class AuthService {
    static let shared = AuthService()

    // MARK: - Dependencies

    private let keychain = KeychainService.shared
    private let sessionManager = SessionManager.shared
    private let tokenService = TokenService.shared

    // MARK: - Protocol Constants

    private let protocolVersion = 1
    private let cryptoVersion = 1

    // MARK: - State

    private var pendingChallenge: PendingChallenge?
    private var wsConnection: WebSocketConnection?

    // MARK: - Types

    private struct PendingChallenge {
        let challengeId: String
        let challengeBytes: Data
        let expiresAt: Date
    }

    // MARK: - Initialization

    private init() {}

    // MARK: - Public Interface

    /// Register a new identity or recover an existing one
    /// - Parameters:
    ///   - mnemonic: Optional BIP39 mnemonic for recovery. If nil, generates new mnemonic.
    ///   - ws: Active WebSocket connection
    /// - Returns: Registration result with whisperId and session
    @MainActor
    func register(mnemonic: String?, ws: WebSocketConnection) async throws -> RegistrationResult {
        self.wsConnection = ws

        let crypto = CryptoService.shared

        // Step 1: Initialize CryptoService with mnemonic
        let actualMnemonic: String
        if let provided = mnemonic {
            // Recovery: use provided mnemonic
            actualMnemonic = provided
            logger.info("Using provided mnemonic for recovery", category: .auth)
        } else {
            // New registration: generate new mnemonic
            actualMnemonic = crypto.generateMnemonic()
            logger.info("Generated new mnemonic for registration", category: .auth)
        }

        // Initialize crypto service (derives keys from mnemonic)
        try await crypto.initializeFromMnemonic(actualMnemonic)

        // Get keys for registration payload
        guard let encPublicKey = crypto.encryptionPublicKey,
              let signPublicKey = crypto.signingPublicKey,
              let encPrivateKey = keychain.getData(Constants.StorageKey.encPrivateKey),
              let signPrivateKey = keychain.getData(Constants.StorageKey.signPrivateKey),
              let contactsKey = keychain.getData(Constants.StorageKey.contactsKey) else {
            throw AuthError.registrationFailed(reason: "Failed to initialize keys")
        }

        let keys = CryptoKeys(
            encPrivateKey: encPrivateKey,
            encPublicKey: encPublicKey,
            signPrivateKey: signPrivateKey,
            signPublicKey: signPublicKey,
            contactsKey: contactsKey,
            mnemonic: actualMnemonic
        )

        // Step 2: Get or create device ID
        let deviceId = getOrCreateDeviceId()

        // Step 3: Determine if this is a recovery (check if we have existing whisperId stored)
        let existingWhisperId = keychain.whisperId
        let isRecovery = mnemonic != nil && existingWhisperId != nil

        // Step 4: Start registration flow
        let result = try await performRegistration(
            keys: keys,
            deviceId: deviceId,
            whisperId: isRecovery ? existingWhisperId : nil,
            ws: ws
        )

        // Step 5: Store the server-provided WhisperID
        try crypto.setWhisperId(result.whisperId)

        return result
    }

    /// Handle force_logout message from server (device kicked)
    func handleForceLogout(reason: String) {
        logger.warning("Force logout received: \(reason)", category: .auth)

        // Clear session but keep keys (user can re-authenticate)
        sessionManager.logout()

        // Notify observers
        NotificationCenter.default.post(
            name: .authForceLogout,
            object: nil,
            userInfo: ["reason": reason]
        )
    }

    /// Check if user has valid keys for recovery
    var canRecover: Bool {
        keychain.isRegistered
    }

    // MARK: - Registration Flow

    private func performRegistration(
        keys: CryptoKeys,
        deviceId: String,
        whisperId: String?,
        ws: WebSocketConnection
    ) async throws -> RegistrationResult {
        // Step 1: Send register_begin
        let challengeResponse = try await sendRegisterBegin(
            deviceId: deviceId,
            whisperId: whisperId,
            ws: ws
        )

        // Step 2: Sign the challenge
        let signature = try signChallenge(
            challengeBytes: challengeResponse.challengeBytes,
            signPrivateKey: keys.signPrivateKey
        )

        // Step 3: Send register_proof
        let ackResponse = try await sendRegisterProof(
            challengeId: challengeResponse.challengeId,
            deviceId: deviceId,
            whisperId: whisperId,
            keys: keys,
            signature: signature,
            ws: ws
        )

        // Step 4: Store keys and session
        try storeRegistrationData(keys: keys, ack: ackResponse)

        logger.info("Registration successful: \(ackResponse.whisperId)", category: .auth)

        return RegistrationResult(
            whisperId: ackResponse.whisperId,
            sessionToken: ackResponse.sessionToken,
            sessionExpiresAt: Time.dateFromMs(ackResponse.sessionExpiresAt),
            serverTime: Time.dateFromMs(ackResponse.serverTime),
            isRecovery: whisperId != nil
        )
    }

    // MARK: - Step 1: Register Begin

    private func sendRegisterBegin(
        deviceId: String,
        whisperId: String?,
        ws: WebSocketConnection
    ) async throws -> ChallengeResponse {
        // Build payload
        var payload: [String: Any] = [
            "protocolVersion": protocolVersion,
            "cryptoVersion": cryptoVersion,
            "deviceId": deviceId,
            "platform": "ios"
        ]

        if let whisperId = whisperId {
            payload["whisperId"] = whisperId
        }

        logger.debug("Sending register_begin", category: .auth)

        // Send and wait for response
        let response = try await ws.sendAndWait(
            type: Constants.MessageType.registerBegin,
            payload: payload,
            expectedResponseType: Constants.MessageType.registerChallenge,
            timeout: Constants.Timeout.httpRequest
        )

        // Parse challenge response
        guard let challengeId = response["challengeId"] as? String,
              let challengeBase64 = response["challenge"] as? String,
              let expiresAtMs = response["expiresAt"] as? Int64 else {
            throw AuthError.invalidChallenge
        }

        guard let challengeBytes = try? Base64.decode(challengeBase64) else {
            throw AuthError.invalidChallenge
        }

        let expiresAt = Time.dateFromMs(expiresAtMs)

        // Verify challenge hasn't expired
        guard expiresAt > Date() else {
            throw AuthError.invalidChallenge
        }

        logger.debug("Received challenge: \(challengeId)", category: .auth)

        return ChallengeResponse(
            challengeId: challengeId,
            challengeBytes: challengeBytes,
            expiresAt: expiresAt
        )
    }

    // MARK: - Step 2: Sign Challenge

    private func signChallenge(challengeBytes: Data, signPrivateKey: Data) throws -> Data {
        // Use CanonicalSigning.signChallenge which:
        // 1. Hashes the challenge bytes with SHA256
        // 2. Signs the hash with Ed25519
        // This MUST match server's verifyChallengeSignature in crypto.ts
        let signature = try CanonicalSigning.signChallenge(challengeBytes, privateKey: signPrivateKey)

        logger.debug("Signed challenge", category: .auth)

        return signature
    }

    // MARK: - Step 3: Register Proof

    private func sendRegisterProof(
        challengeId: String,
        deviceId: String,
        whisperId: String?,
        keys: CryptoKeys,
        signature: Data,
        ws: WebSocketConnection
    ) async throws -> RegisterAckResponse {
        // Build payload
        var payload: [String: Any] = [
            "protocolVersion": protocolVersion,
            "cryptoVersion": cryptoVersion,
            "challengeId": challengeId,
            "deviceId": deviceId,
            "platform": "ios",
            "encPublicKey": keys.encPublicKey.base64,
            "signPublicKey": keys.signPublicKey.base64,
            "signature": signature.base64
        ]

        if let whisperId = whisperId {
            payload["whisperId"] = whisperId
        }

        // Add push tokens if available
        if let pushToken = tokenService.pushToken {
            payload["pushToken"] = pushToken
        }
        if let voipToken = tokenService.voipToken {
            payload["voipToken"] = voipToken
        }

        logger.debug("Sending register_proof", category: .auth)

        // Send and wait for response
        let response = try await ws.sendAndWait(
            type: Constants.MessageType.registerProof,
            payload: payload,
            expectedResponseType: Constants.MessageType.registerAck,
            timeout: Constants.Timeout.httpRequest
        )

        // Parse ack response
        guard let success = response["success"] as? Bool, success,
              let returnedWhisperId = response["whisperId"] as? String,
              let sessionToken = response["sessionToken"] as? String,
              let sessionExpiresAt = response["sessionExpiresAt"] as? Int64,
              let serverTime = response["serverTime"] as? Int64 else {
            throw AuthError.registrationFailed(reason: "Invalid ack response")
        }

        logger.debug("Received register_ack: \(returnedWhisperId)", category: .auth)

        return RegisterAckResponse(
            whisperId: returnedWhisperId,
            sessionToken: sessionToken,
            sessionExpiresAt: sessionExpiresAt,
            serverTime: serverTime
        )
    }

    // MARK: - Storage

    private func storeRegistrationData(keys: CryptoKeys, ack: RegisterAckResponse) throws {
        // Store keys
        try keychain.storeKeys(
            encPrivateKey: keys.encPrivateKey,
            encPublicKey: keys.encPublicKey,
            signPrivateKey: keys.signPrivateKey,
            signPublicKey: keys.signPublicKey,
            contactsKey: keys.contactsKey
        )

        // Store whisper ID
        keychain.whisperId = ack.whisperId

        // Store session
        let expiryDate = Time.dateFromMs(ack.sessionExpiresAt)
        try sessionManager.storeSession(
            token: ack.sessionToken,
            expiry: expiryDate,
            whisperId: ack.whisperId
        )

        logger.info("Stored registration data", category: .auth)
    }

    // MARK: - Device ID

    private func getOrCreateDeviceId() -> String {
        if let existing = keychain.deviceId {
            return existing
        }

        let newDeviceId = UUID().uuidString.lowercased()
        keychain.deviceId = newDeviceId
        logger.info("Created new device ID: \(newDeviceId.prefix(8))...", category: .auth)
        return newDeviceId
    }
}

// MARK: - Response Types

private struct ChallengeResponse {
    let challengeId: String
    let challengeBytes: Data
    let expiresAt: Date
}

private struct RegisterAckResponse {
    let whisperId: String
    let sessionToken: String
    let sessionExpiresAt: Int64
    let serverTime: Int64
}

// MARK: - Public Result Types

struct RegistrationResult {
    let whisperId: String
    let sessionToken: String
    let sessionExpiresAt: Date
    let serverTime: Date
    let isRecovery: Bool
}

// MARK: - WebSocket Connection Protocol

/// Protocol for WebSocket communication
/// To be implemented by the actual WebSocket client
protocol WebSocketConnection {
    /// Send a message and wait for a specific response type
    func sendAndWait(
        type: String,
        payload: [String: Any],
        expectedResponseType: String,
        timeout: TimeInterval
    ) async throws -> [String: Any]

    /// Send a message without waiting for response
    func send(type: String, payload: [String: Any]) async throws
}

// MARK: - Notification Names

extension Notification.Name {
    static let authForceLogout = Notification.Name("whisper2.auth.forceLogout")
    static let authSessionExpired = Notification.Name("whisper2.auth.sessionExpired")
}

// MARK: - CryptoKeys

/// Cryptographic key material for registration
struct CryptoKeys {
    let encPrivateKey: Data
    let encPublicKey: Data
    let signPrivateKey: Data
    let signPublicKey: Data
    let contactsKey: Data
    let mnemonic: String?
}
