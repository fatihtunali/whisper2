import Foundation

/// Whisper2 Push Token Service
/// Manages APNs and VoIP push tokens
///
/// Responsibilities:
/// - Store push tokens locally
/// - Send tokens to server via WebSocket
/// - Re-send tokens on reconnect
/// - Handle token updates from system

final class TokenService {
    static let shared = TokenService()

    // MARK: - Dependencies

    private let keychain = KeychainService.shared
    private let sessionManager = SessionManager.shared

    // MARK: - Protocol Constants

    private let protocolVersion = 1
    private let cryptoVersion = 1

    // MARK: - State

    private weak var wsConnection: WebSocketConnection?
    private var pendingUpdate = false

    // MARK: - Token Properties

    /// Current APNs push token (hex string)
    var pushToken: String? {
        get { keychain.pushToken }
        set {
            let oldValue = keychain.pushToken
            keychain.pushToken = newValue

            if newValue != oldValue {
                logger.info("Push token updated: \(newValue?.prefix(8) ?? "nil")...", category: .auth)
                scheduleTokenUpdate()
            }
        }
    }

    /// Current VoIP push token (hex string)
    var voipToken: String? {
        get { keychain.voipToken }
        set {
            let oldValue = keychain.voipToken
            keychain.voipToken = newValue

            if newValue != oldValue {
                logger.info("VoIP token updated: \(newValue?.prefix(8) ?? "nil")...", category: .auth)
                scheduleTokenUpdate()
            }
        }
    }

    /// Check if tokens need to be sent to server
    var hasTokensToSend: Bool {
        (pushToken != nil || voipToken != nil) && sessionManager.isAuthenticated
    }

    // MARK: - Initialization

    private init() {
        setupNotificationObservers()
    }

    // MARK: - Connection Management

    /// Set WebSocket connection for sending updates
    func setConnection(_ ws: WebSocketConnection?) {
        self.wsConnection = ws

        // Send pending tokens on new connection
        if ws != nil && pendingUpdate {
            Task {
                await sendTokensToServer()
            }
        }
    }

    /// Called when WebSocket reconnects
    func onReconnect() {
        logger.debug("Token service: handling reconnect", category: .auth)

        // Always re-send tokens on reconnect to ensure server has latest
        Task {
            await sendTokensToServer()
        }
    }

    // MARK: - Token Updates

    /// Update tokens and send to server
    /// - Parameters:
    ///   - pushToken: New APNs push token (hex string)
    ///   - voipToken: New VoIP push token (hex string)
    func updateTokens(pushToken: String?, voipToken: String?) async {
        // Store locally
        if let push = pushToken {
            keychain.pushToken = push
        }
        if let voip = voipToken {
            keychain.voipToken = voip
        }

        // Send to server
        await sendTokensToServer()
    }

    /// Schedule token update (debounced)
    private func scheduleTokenUpdate() {
        pendingUpdate = true

        Task {
            // Small delay to batch multiple updates
            try? await Task.sleep(nanoseconds: 500_000_000) // 0.5s

            if pendingUpdate {
                await sendTokensToServer()
            }
        }
    }

    /// Send current tokens to server
    func sendTokensToServer() async {
        guard sessionManager.isAuthenticated,
              let ws = wsConnection,
              let sessionToken = sessionManager.sessionToken else {
            logger.debug("Cannot send tokens: not connected or not authenticated", category: .auth)
            pendingUpdate = true
            return
        }

        // Build payload
        var payload: [String: Any] = [
            "protocolVersion": protocolVersion,
            "cryptoVersion": cryptoVersion,
            "sessionToken": sessionToken
        ]

        if let push = pushToken {
            payload["pushToken"] = push
        }

        if let voip = voipToken {
            payload["voipToken"] = voip
        }

        // Only send if we have at least one token
        guard pushToken != nil || voipToken != nil else {
            logger.debug("No tokens to send", category: .auth)
            pendingUpdate = false
            return
        }

        do {
            try await ws.send(
                type: Constants.MessageType.updateTokens,
                payload: payload
            )

            pendingUpdate = false
            logger.info("Push tokens sent to server", category: .auth)
        } catch {
            logger.error("Failed to send push tokens: \(error.localizedDescription)", category: .auth)
            pendingUpdate = true
        }
    }

    // MARK: - Token Conversion

    /// Convert Data token to hex string
    static func tokenToHex(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }

    /// Convert hex string to Data token
    static func hexToToken(_ hex: String) -> Data? {
        var data = Data()
        var hex = hex

        while hex.count >= 2 {
            let byteString = String(hex.prefix(2))
            hex = String(hex.dropFirst(2))

            guard let byte = UInt8(byteString, radix: 16) else {
                return nil
            }
            data.append(byte)
        }

        return data
    }

    // MARK: - Notification Observers

    private func setupNotificationObservers() {
        // Observe push token updates from AppDelegate
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handlePushTokenUpdate(_:)),
            name: .pushTokenUpdated,
            object: nil
        )

        // Observe VoIP token updates
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleVoIPTokenUpdate(_:)),
            name: .voipTokenUpdated,
            object: nil
        )
    }

    @objc private func handlePushTokenUpdate(_ notification: Notification) {
        guard let token = notification.userInfo?["token"] as? String else { return }
        pushToken = token
    }

    @objc private func handleVoIPTokenUpdate(_ notification: Notification) {
        guard let token = notification.userInfo?["token"] as? String else { return }
        voipToken = token
    }

    // MARK: - Cleanup

    /// Clear all tokens (on logout)
    func clearTokens() {
        keychain.pushToken = nil
        keychain.voipToken = nil
        pendingUpdate = false
        logger.info("Push tokens cleared", category: .auth)
    }
}

// MARK: - Token Validation

extension TokenService {
    /// Validate push token format
    static func isValidPushToken(_ token: String?) -> Bool {
        guard let token = token else { return false }

        // APNs tokens are 64 hex characters (32 bytes)
        let hexPattern = "^[a-f0-9]{64}$"
        return token.range(of: hexPattern, options: .regularExpression) != nil
    }

    /// Validate VoIP token format
    static func isValidVoIPToken(_ token: String?) -> Bool {
        guard let token = token else { return false }

        // VoIP tokens are also 64 hex characters (32 bytes)
        let hexPattern = "^[a-f0-9]{64}$"
        return token.range(of: hexPattern, options: .regularExpression) != nil
    }
}

// MARK: - Debug

extension TokenService {
    var debugDescription: String {
        let pushStatus = pushToken != nil ? "\(pushToken!.prefix(8))..." : "nil"
        let voipStatus = voipToken != nil ? "\(voipToken!.prefix(8))..." : "nil"

        return """
        TokenService:
          Push Token: \(pushStatus)
          VoIP Token: \(voipStatus)
          Pending Update: \(pendingUpdate)
          Connected: \(wsConnection != nil)
        """
    }
}
