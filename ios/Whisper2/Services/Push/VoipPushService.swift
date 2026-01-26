import Foundation
import PushKit
import UIKit

// MARK: - VoIP Push Service Delegate

protocol VoipPushServiceDelegate: AnyObject {
    /// Called when a VoIP push token is received
    func voipPushService(_ service: VoipPushService, didReceiveToken token: String)

    /// Called when an incoming call push is received
    func voipPushService(_ service: VoipPushService, didReceiveIncomingCall payload: VoipCallPayload)

    /// Called when VoIP push registration fails
    func voipPushService(_ service: VoipPushService, didFailWithError error: Error)
}

// MARK: - VoIP Call Payload

/// Represents an incoming call from a VoIP push notification
struct VoipCallPayload {
    let callId: String
    let callerWhisperId: String
    let callerName: String?
    let isVideo: Bool
    let timestamp: Date

    /// Encrypted SDP offer (base64)
    let encryptedOffer: String?
    let nonce: String?
    let signature: String?
}

// MARK: - VoIP Push Service

/// Service for handling VoIP push notifications via PushKit
///
/// VoIP pushes are high-priority and wake the app even when terminated.
/// They are required for CallKit to show the native incoming call UI.
final class VoipPushService: NSObject {

    // MARK: - Singleton

    static let shared = VoipPushService()

    // MARK: - Properties

    weak var delegate: VoipPushServiceDelegate?

    private var pushRegistry: PKPushRegistry?
    private(set) var voipToken: String?
    private(set) var isRegistered = false

    // MARK: - Initialization

    private override init() {
        super.init()
    }

    // MARK: - Public API

    /// Register for VoIP push notifications
    /// Must be called early in app lifecycle (e.g., didFinishLaunching)
    func registerForVoIPPushes() {
        logger.info("Registering for VoIP pushes", category: .calls)

        // Create push registry on main queue
        let registry = PKPushRegistry(queue: .main)
        registry.delegate = self
        registry.desiredPushTypes = [.voIP]

        pushRegistry = registry

        logger.debug("VoIP push registry created", category: .calls)
    }

    /// Unregister from VoIP push notifications
    func unregister() {
        logger.info("Unregistering from VoIP pushes", category: .calls)

        pushRegistry?.desiredPushTypes = []
        pushRegistry = nil
        voipToken = nil
        isRegistered = false
    }

    /// Get the current VoIP token if available
    /// - Returns: The VoIP token string, or nil if not registered
    func getToken() -> String? {
        return voipToken
    }

    // MARK: - Private Methods

    private func parseCallPayload(from dictionary: [AnyHashable: Any]) -> VoipCallPayload? {
        // Expected payload structure from server:
        // {
        //   "aps": { "alert": "Incoming call" },
        //   "callId": "uuid",
        //   "callerWhisperId": "WSP-XXXX-...",
        //   "callerName": "Display Name",
        //   "isVideo": false,
        //   "timestamp": 1234567890,
        //   "offer": "base64...",  // encrypted SDP
        //   "nonce": "base64...",
        //   "sig": "base64..."
        // }

        guard let callId = dictionary["callId"] as? String,
              let callerWhisperId = dictionary["callerWhisperId"] as? String else {
            logger.warning("Invalid VoIP push payload: missing required fields", category: .calls)
            return nil
        }

        let isVideo = dictionary["isVideo"] as? Bool ?? false
        let callerName = dictionary["callerName"] as? String

        let timestamp: Date
        if let ts = dictionary["timestamp"] as? TimeInterval {
            timestamp = Date(timeIntervalSince1970: ts / 1000) // Convert from milliseconds
        } else {
            timestamp = Date()
        }

        return VoipCallPayload(
            callId: callId,
            callerWhisperId: callerWhisperId,
            callerName: callerName,
            isVideo: isVideo,
            timestamp: timestamp,
            encryptedOffer: dictionary["offer"] as? String,
            nonce: dictionary["nonce"] as? String,
            signature: dictionary["sig"] as? String
        )
    }
}

// MARK: - PKPushRegistryDelegate

extension VoipPushService: PKPushRegistryDelegate {

    func pushRegistry(_ registry: PKPushRegistry, didUpdate pushCredentials: PKPushCredentials, for type: PKPushType) {
        guard type == .voIP else { return }

        // Convert token to hex string
        let token = pushCredentials.token.map { String(format: "%02x", $0) }.joined()

        logger.info("Received VoIP push token: \(token.prefix(20))...", category: .calls)

        voipToken = token
        isRegistered = true

        // Store token for later use
        UserDefaults.standard.set(token, forKey: Constants.StorageKey.voipToken)

        // Notify delegate
        delegate?.voipPushService(self, didReceiveToken: token)

        // Send token to server via WebSocket
        Task {
            await sendTokenToServer(voipToken: token)
        }
    }

    /// Send VoIP token to server via WebSocket update_tokens message
    @MainActor
    private func sendTokenToServer(voipToken: String?, pushToken: String? = nil) async {
        guard let wsClient = AppConnectionManager.shared.getWSClient() else {
            logger.warning("Cannot send token - WebSocket not connected", category: .calls)
            return
        }

        guard let sessionToken = KeychainService.shared.sessionToken else {
            logger.warning("Cannot send token - not authenticated", category: .calls)
            return
        }

        var payload: [String: Any] = [
            "protocolVersion": Constants.protocolVersion,
            "cryptoVersion": Constants.cryptoVersion,
            "sessionToken": sessionToken
        ]

        if let voip = voipToken {
            payload["voipToken"] = voip
        }
        if let push = pushToken {
            payload["pushToken"] = push
        }

        do {
            let frame: [String: Any] = [
                "type": Constants.MessageType.updateTokens,
                "payload": payload
            ]
            let data = try JSONSerialization.data(withJSONObject: frame)
            try await wsClient.send(data)
            logger.info("Sent token update to server", category: .calls)
        } catch {
            logger.error(error, message: "Failed to send token update", category: .calls)
        }
    }

    func pushRegistry(_ registry: PKPushRegistry, didInvalidatePushTokenFor type: PKPushType) {
        guard type == .voIP else { return }

        logger.warning("VoIP push token invalidated", category: .calls)

        voipToken = nil
        isRegistered = false

        UserDefaults.standard.removeObject(forKey: Constants.StorageKey.voipToken)

        // Notify server that token is invalidated (send empty token)
        Task {
            await sendTokenToServer(voipToken: "")
        }
    }

    func pushRegistry(
        _ registry: PKPushRegistry,
        didReceiveIncomingPushWith payload: PKPushPayload,
        for type: PKPushType,
        completion: @escaping () -> Void
    ) {
        guard type == .voIP else {
            completion()
            return
        }

        logger.info("Received VoIP push notification", category: .calls)

        let payloadDict = payload.dictionaryPayload

        // CRITICAL: iOS 13+ requires reporting to CallKit IMMEDIATELY
        // Failure to do so will cause the app to be terminated and
        // potentially banned from receiving VoIP pushes

        guard let callPayload = parseCallPayload(from: payloadDict) else {
            logger.error("Failed to parse VoIP push payload", category: .calls)

            // Must still report something to CallKit to avoid app termination
            // Report a fake call that immediately ends
            reportFakeCallForRecovery()
            completion()
            return
        }

        // CRITICAL: Report to CallKit FIRST, SYNCHRONOUSLY, before any async work
        // The delegate will handle CallKit reporting
        if let del = delegate {
            // Delegate is responsible for reporting to CallKit immediately
            del.voipPushService(self, didReceiveIncomingCall: callPayload)
        } else {
            // No delegate - report directly to CallKit to avoid termination
            logger.warning("No VoipPushService delegate set - reporting directly to CallKit", category: .calls)
            let uuid = UUID(uuidString: callPayload.callId) ?? UUID()
            let handle = callPayload.callerName ?? callPayload.callerWhisperId
            let callKitService = CallKitService()
            callKitService.reportIncomingCall(uuid: uuid, handle: handle, hasVideo: callPayload.isVideo) { error in
                if let error = error {
                    logger.error(error, message: "Failed to report to CallKit", category: .calls)
                }
            }
        }

        // Connect WebSocket if needed and fetch full call details
        // This happens AFTER CallKit is notified
        ensureWebSocketConnected { [weak self] in
            // After WebSocket is connected, the full call_incoming message
            // will be received via WebSocket with complete SDP offer
            logger.debug("WebSocket connected for incoming call", category: .calls)
            completion()
        }
    }

    // MARK: - iOS 12 and earlier (deprecated but may still be called)

    func pushRegistry(_ registry: PKPushRegistry, didReceiveIncomingPushWith payload: PKPushPayload, for type: PKPushType) {
        // Forward to the completion handler version
        pushRegistry(registry, didReceiveIncomingPushWith: payload, for: type) { }
    }

    // MARK: - Private Helpers

    private func ensureWebSocketConnected(completion: @escaping () -> Void) {
        Task { @MainActor in
            let connectionManager = AppConnectionManager.shared

            // Check if already connected
            if connectionManager.connectionState == .connected {
                completion()
                return
            }

            // Try to connect
            logger.info("Connecting WebSocket for incoming call", category: .calls)
            await connectionManager.connect()

            // Wait briefly for connection
            try? await Task.sleep(nanoseconds: 500_000_000) // 500ms

            completion()
        }
    }

    private func reportFakeCallForRecovery() {
        // iOS requires CallKit to be notified for every VoIP push
        // If we can't parse the payload, report a call that immediately ends
        // This prevents the system from penalizing the app

        logger.warning("Reporting fake call for recovery", category: .calls)

        let uuid = UUID()
        let callKitService = CallKitService()

        callKitService.reportIncomingCall(uuid: uuid, handle: "Unknown") { error in
            if error == nil {
                // Immediately end the fake call
                callKitService.reportCallEnded(uuid: uuid, reason: .failed)
            }
        }
    }
}

// MARK: - VoIP Push Payload Keys

extension VoipPushService {
    /// Standard payload keys
    enum PayloadKey {
        static let callId = "callId"
        static let callerWhisperId = "callerWhisperId"
        static let callerName = "callerName"
        static let isVideo = "isVideo"
        static let timestamp = "timestamp"
        static let offer = "offer"
        static let nonce = "nonce"
        static let signature = "sig"
    }
}

// MARK: - App Lifecycle Integration

extension VoipPushService {
    /// Call this from AppDelegate's didFinishLaunching
    /// VoIP push registration must happen early for reliable delivery
    static func setupAtLaunch() {
        VoipPushService.shared.registerForVoIPPushes()
    }
}
