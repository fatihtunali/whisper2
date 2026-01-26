import Foundation
import WebRTC

// Import for KeychainService and AppConnectionManager access

// MARK: - TURN Credentials

/// TURN server credentials returned from the server
struct TurnCredentials: Codable {
    let urls: [String]
    let username: String
    let credential: String
    let ttl: Int

    /// Convert to RTCIceServer array for WebRTC
    var iceServers: [RTCIceServer] {
        return [
            RTCIceServer(
                urlStrings: urls,
                username: username,
                credential: credential
            )
        ]
    }
}

// MARK: - TURN Service

/// Service for fetching TURN credentials from the server
final class TurnService {

    // MARK: - Constants

    /// Default TURN URLs if server doesn't provide any
    private static var defaultTurnUrls: [String] {
        Constants.Turn.urls
    }

    /// Credential cache to avoid unnecessary requests
    private var cachedCredentials: TurnCredentials?
    private var cacheExpiry: Date?

    // MARK: - Public API

    /// Get TURN credentials from the server
    /// - Returns: TURN credentials for WebRTC
    /// - Throws: CallError if credentials cannot be obtained
    func getTurnCredentials() async throws -> TurnCredentials {
        // Check cache first
        if let cached = cachedCredentials,
           let expiry = cacheExpiry,
           Date() < expiry {
            logger.debug("Using cached TURN credentials", category: .calls)
            return cached
        }

        logger.debug("Fetching TURN credentials from server", category: .calls)

        // Fetch TURN credentials via WebSocket
        let credentials = try await fetchFromServer()

        // Cache the credentials (expire 30 seconds before TTL)
        cachedCredentials = credentials
        cacheExpiry = Date().addingTimeInterval(TimeInterval(max(0, credentials.ttl - 30)))

        logger.info("Got TURN credentials, TTL: \(credentials.ttl)s", category: .calls)

        return credentials
    }

    /// Clear cached credentials
    func clearCache() {
        cachedCredentials = nil
        cacheExpiry = nil
        logger.debug("TURN credentials cache cleared", category: .calls)
    }

    // MARK: - Private Methods

    private func fetchFromServer() async throws -> TurnCredentials {
        let keychain = KeychainService.shared

        guard let sessionToken = keychain.sessionToken else {
            throw CallError.notAuthenticated
        }

        // Get WebSocket client from AppConnectionManager
        guard let wsClient = await MainActor.run(body: { AppConnectionManager.shared.getWSClient() }) else {
            throw CallError.turnCredentialsFailed
        }

        let payload: [String: Any] = [
            "protocolVersion": Constants.protocolVersion,
            "cryptoVersion": Constants.cryptoVersion,
            "sessionToken": sessionToken
        ]

        // Send request and wait for response
        let response = try await wsClient.sendAndWait(
            type: Constants.MessageType.getTurnCredentials,
            payload: payload,
            expectedResponseType: Constants.MessageType.turnCredentials,
            timeout: 10
        )

        // Parse response
        guard let urls = response["urls"] as? [String],
              let username = response["username"] as? String,
              let credential = response["credential"] as? String,
              let ttl = response["ttl"] as? Int else {
            throw CallError.turnCredentialsFailed
        }

        return TurnCredentials(
            urls: urls,
            username: username,
            credential: credential,
            ttl: ttl
        )
    }
}

// MARK: - TURN Credentials Request

/// Request payload for get_turn_credentials
struct TurnCredentialsRequest: Codable {
    let protocolVersion: Int
    let cryptoVersion: Int
    let sessionToken: String
}

// MARK: - TURN Credentials Response

/// Response payload from turn_credentials
struct TurnCredentialsResponse: Codable {
    let urls: [String]
    let username: String
    let credential: String
    let ttl: Int
}

// MARK: - Extension for Testing

extension TurnService {
    /// Create a TurnService with mock credentials for testing
    static func withMockCredentials(
        urls: [String] = TurnService.defaultTurnUrls,
        username: String = "testuser",
        credential: String = "testcred",
        ttl: Int = 600
    ) -> TurnService {
        let service = TurnService()
        service.cachedCredentials = TurnCredentials(
            urls: urls,
            username: username,
            credential: credential,
            ttl: ttl
        )
        service.cacheExpiry = Date().addingTimeInterval(TimeInterval(ttl))
        return service
    }
}
