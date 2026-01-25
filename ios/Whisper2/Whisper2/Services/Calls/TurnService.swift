import Foundation
import WebRTC

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

        // In a real implementation, this would call the WebSocket service
        // to send get_turn_credentials and wait for turn_credentials response

        // For now, create a placeholder that will be replaced with actual implementation
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
        // TODO: Implement actual WebSocket call
        // This is a placeholder that shows the expected message flow

        /*
        let request = TurnCredentialsRequest(
            protocolVersion: Constants.protocolVersion,
            cryptoVersion: Constants.cryptoVersion,
            sessionToken: sessionToken
        )

        let response = try await websocketService.sendAndWait(
            type: Constants.MessageType.getTurnCredentials,
            payload: request,
            expectType: Constants.MessageType.turnCredentials
        )

        return TurnCredentials(
            urls: response.urls,
            username: response.username,
            credential: response.credential,
            ttl: response.ttl
        )
        */

        // Placeholder: throw error until WebSocket integration is complete
        throw CallError.turnCredentialsFailed

        // For testing, you could return mock credentials:
        // return TurnCredentials(
        //     urls: Self.defaultTurnUrls,
        //     username: "test",
        //     credential: "test",
        //     ttl: 600
        // )
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
