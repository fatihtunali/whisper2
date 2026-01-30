import Foundation
import UIKit
import UserNotifications
import PushKit
import CallKit
import Combine

/// Service for managing push notifications (APNS) and VoIP push (PushKit)
/// iOS 26 REQUIREMENT: VoIP push MUST report a call to CallKit or app will be terminated
final class PushNotificationService: NSObject, ObservableObject {
    static let shared = PushNotificationService()

    @Published private(set) var apnsToken: String?
    @Published private(set) var voipToken: String?
    @Published private(set) var notificationPermissionGranted = false

    private let ws = WebSocketService.shared
    private let auth = AuthService.shared
    private var voipRegistry: PKPushRegistry?
    private var cancellables = Set<AnyCancellable>()

    // Callback for incoming VoIP push (call)
    var onIncomingCall: ((CallIncomingPayload) -> Void)?

    // Fallback CXProvider for iOS 26 compliance
    // If onIncomingCall callback fails, we use this to report a fallback call
    private lazy var fallbackProvider: CXProvider = {
        let config = CXProviderConfiguration()
        config.supportsVideo = true
        config.maximumCallsPerCallGroup = 1
        config.supportedHandleTypes = [.generic]
        // Note: CallKit handoff is controlled at system level, not via CXProviderConfiguration
        return CXProvider(configuration: config)
    }()

    private override init() {
        super.init()
        setupVoIPPush()
        setupWebSocketListener()
    }

    // MARK: - Permission Request

    func requestNotificationPermission() async -> Bool {
        let center = UNUserNotificationCenter.current()

        do {
            let granted = try await center.requestAuthorization(options: [.alert, .sound, .badge])
            await MainActor.run {
                self.notificationPermissionGranted = granted
            }

            if granted {
                await registerForRemoteNotifications()
            }

            return granted
        } catch {
            print("Push permission error: \(error)")
            return false
        }
    }

    @MainActor
    private func registerForRemoteNotifications() {
        #if !targetEnvironment(simulator)
        UIApplication.shared.registerForRemoteNotifications()
        #endif
    }

    // MARK: - APNS Token Registration

    func didRegisterForRemoteNotifications(deviceToken: Data) {
        let token = deviceToken.map { String(format: "%02.2hhx", $0) }.joined()
        apnsToken = token
        print("APNS Token: \(token)")

        Task {
            await sendTokensToServer()
        }
    }

    func didFailToRegisterForRemoteNotifications(error: Error) {
        print("Failed to register for remote notifications: \(error)")
    }

    // MARK: - VoIP Push Setup (PushKit)

    private func setupVoIPPush() {
        voipRegistry = PKPushRegistry(queue: .main)
        voipRegistry?.delegate = self
        voipRegistry?.desiredPushTypes = [.voIP]
    }

    // MARK: - Send Tokens to Server

    private func sendTokensToServer() async {
        // Must be authenticated with the server before sending tokens
        guard auth.isAuthenticated else {
            print("Not authenticated, will send push tokens after auth")
            return
        }

        guard let sessionToken = auth.currentUser?.sessionToken else {
            print("No session token, cannot send push tokens")
            return
        }

        guard apnsToken != nil || voipToken != nil else {
            return
        }

        let payload = UpdateTokensPayload(
            sessionToken: sessionToken,
            pushToken: apnsToken,
            voipToken: voipToken
        )

        let frame = WsFrame(type: Constants.MessageType.updateTokens, payload: payload)

        do {
            try await ws.send(frame)
            print("Push tokens sent to server - APNS: \(apnsToken ?? "nil"), VoIP: \(voipToken ?? "nil")")
        } catch {
            print("Failed to send push tokens: \(error)")
        }
    }

    /// Called after authentication completes to send stored tokens
    func sendTokensAfterAuth() async {
        print("Sending push tokens after authentication...")
        await sendTokensToServer()
    }

    // MARK: - WebSocket Listener

    private func setupWebSocketListener() {
        auth.$isAuthenticated
            .combineLatest(ws.$connectionState)
            .sink { [weak self] (isAuthenticated, connectionState) in
                if isAuthenticated && connectionState == .connected {
                    Task {
                        await self?.sendTokensToServer()
                    }
                }
            }
            .store(in: &cancellables)
    }

    // MARK: - Handle Incoming Notifications

    func handleNotification(_ userInfo: [AnyHashable: Any], completionHandler: @escaping () -> Void) {
        guard let type = userInfo["type"] as? String else {
            completionHandler()
            return
        }

        switch type {
        case "message":
            handleMessageNotification(userInfo)
        case "call":
            handleCallNotification(userInfo)
        case "group":
            handleGroupNotification(userInfo)
        default:
            print("Unknown notification type: \(type)")
        }

        completionHandler()
    }

    private func handleMessageNotification(_ userInfo: [AnyHashable: Any]) {
        NotificationCenter.default.post(
            name: NSNotification.Name("NewMessageNotification"),
            object: nil,
            userInfo: userInfo
        )
    }

    private func handleCallNotification(_ userInfo: [AnyHashable: Any]) {
        NotificationCenter.default.post(
            name: NSNotification.Name("CallNotification"),
            object: nil,
            userInfo: userInfo
        )
    }

    private func handleGroupNotification(_ userInfo: [AnyHashable: Any]) {
        NotificationCenter.default.post(
            name: NSNotification.Name("GroupNotification"),
            object: nil,
            userInfo: userInfo
        )
    }

    // MARK: - Badge Management

    @MainActor
    func updateBadgeCount(_ count: Int) {
        UNUserNotificationCenter.current().setBadgeCount(count)
    }

    @MainActor
    func clearBadge() {
        UNUserNotificationCenter.current().setBadgeCount(0)
    }

    // MARK: - UUID Generation

    /// Generate UUID with CFUUID fallback for guaranteed non-nil result
    /// Uses CFUUIDCreate pattern for extra safety
    private func generateUUID() -> UUID {
        // Primary: Swift UUID (should never fail)
        let swiftUUID = UUID()

        // Fallback: CFUUID for guaranteed generation
        let cfUUID = CFUUIDCreate(kCFAllocatorDefault)
        if let cfString = CFUUIDCreateString(kCFAllocatorDefault, cfUUID) {
            let uuidString = cfString as String
            if let uuid = UUID(uuidString: uuidString) {
                return uuid
            }
        }

        return swiftUUID
    }

    // MARK: - Fallback CallKit Reporting

    /// Report a fallback call directly to CallKit when normal flow fails
    /// This ensures iOS 26 compliance - VoIP push MUST report a call
    private func reportFallbackCall(callId: String, handle: String, isVideo: Bool, endImmediately: Bool) {
        // Convert callId to UUID, or generate a new one
        let callUUID: UUID
        if let uuid = UUID(uuidString: callId) {
            callUUID = uuid
        } else {
            callUUID = generateUUID()
        }

        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .generic, value: handle)
        update.localizedCallerName = handle == "Unknown" ? "Unknown Caller" : handle
        update.hasVideo = isVideo
        update.supportsHolding = false
        update.supportsGrouping = false
        update.supportsUngrouping = false
        update.supportsDTMF = false

        print("FALLBACK: Reporting call to CallKit - UUID: \(callUUID), handle: \(handle)")

        fallbackProvider.reportNewIncomingCall(with: callUUID, update: update) { [weak self] error in
            if let error = error {
                print("FALLBACK: Failed to report incoming call: \(error)")
            } else {
                print("FALLBACK: Incoming call reported successfully - UUID: \(callUUID)")

                // If this was a fallback call due to parse failure, end it immediately
                if endImmediately {
                    print("FALLBACK: Ending fallback call immediately...")
                    self?.fallbackProvider.reportCall(with: callUUID, endedAt: Date(), reason: .failed)
                }
            }
        }
    }
}

// MARK: - PKPushRegistryDelegate (VoIP Push)

extension PushNotificationService: PKPushRegistryDelegate {

    func pushRegistry(_ registry: PKPushRegistry, didUpdate pushCredentials: PKPushCredentials, for type: PKPushType) {
        guard type == .voIP else { return }

        let token = pushCredentials.token.map { String(format: "%02.2hhx", $0) }.joined()
        voipToken = token
        print("VoIP Token: \(token)")

        Task {
            await sendTokensToServer()
        }
    }

    func pushRegistry(_ registry: PKPushRegistry, didReceiveIncomingPushWith payload: PKPushPayload, for type: PKPushType, completion: @escaping () -> Void) {
        print("=== VoIP PUSH RECEIVED ===")
        print("Type: \(type.rawValue)")

        guard type == .voIP else {
            print("Not a VoIP push, ignoring")
            completion()
            return
        }

        // iOS 26 REQUIREMENT: MUST report a call to CallKit on VoIP push or app will be terminated
        // We MUST call reportNewIncomingCall BEFORE calling completion()

        let dictionaryPayload = payload.dictionaryPayload
        print("VoIP push payload: \(dictionaryPayload)")

        // Check if this is a call_end push
        if let pushType = dictionaryPayload["type"] as? String, pushType == "call_end" {
            print("VoIP push: call_end received")
            if let callId = dictionaryPayload["callId"] as? String {
                print("Ending call via push: \(callId)")
                // Report to CallKit that the call ended
                CallService.shared.callKitManager?.reportCallEnded(callId: callId, reason: .remoteEnded)
                // Clean up call state
                CallService.shared.handleRemoteCallEnd(callId: callId)
            }
            completion()
            return
        }

        // Try to parse call data
        if let callData = dictionaryPayload["call"] as? [String: Any],
           let callId = callData["callId"] as? String,
           let from = callData["from"] as? String {

            print("VoIP push parsed: callId=\(callId), from=\(from)")

            let isVideo = callData["isVideo"] as? Bool ?? false
            let timestamp = callData["timestamp"] as? Int64 ?? Int64(Date().timeIntervalSince1970 * 1000)
            let nonce = callData["nonce"] as? String ?? ""
            let ciphertext = callData["ciphertext"] as? String ?? ""
            let sig = callData["sig"] as? String ?? ""

            // CRITICAL: Report to CallKit FIRST, before anything else
            // This ensures iOS doesn't terminate the app
            // Access CallService.shared to ensure it's initialized, then use its CallKitManager
            let callService = CallService.shared
            if let callKitManager = callService.callKitManager {
                print("Reporting to CallKit via CallService.callKitManager...")
                callKitManager.reportIncomingCall(
                    callId: callId,
                    handle: from,
                    displayName: from,
                    hasVideo: isVideo
                )
                print("CallKit reportIncomingCall called successfully")
            } else {
                // CallKitManager not ready - use fallback provider
                print("WARNING: CallKitManager is nil, using fallback provider")
                reportFallbackCall(callId: callId, handle: from, isVideo: isVideo, endImmediately: false)
            }

            // Now notify CallService about the call details (for crypto, WebRTC setup, etc.)
            let callPayload = CallIncomingPayload(
                callId: callId,
                from: from,
                isVideo: isVideo,
                timestamp: timestamp,
                nonce: nonce,
                ciphertext: ciphertext,
                sig: sig
            )
            callService.handleIncomingCallFromPush(callPayload)

        } else {
            // FALLBACK: Failed to parse payload - MUST still report a call to CallKit
            print("ERROR: Invalid VoIP push payload - missing call data")
            print("Available keys: \(dictionaryPayload.keys)")
            print("Using fallback to avoid iOS 26 termination...")

            let fallbackUUID = generateUUID()
            reportFallbackCall(callId: fallbackUUID.uuidString, handle: "Unknown", isVideo: false, endImmediately: true)
        }

        completion()
    }

    func pushRegistry(_ registry: PKPushRegistry, didInvalidatePushTokenFor type: PKPushType) {
        guard type == .voIP else { return }
        voipToken = nil
        print("VoIP token invalidated")
    }
}

// MARK: - UNUserNotificationCenterDelegate

extension PushNotificationService: UNUserNotificationCenterDelegate {

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        let userInfo = notification.request.content.userInfo
        let showNotification = shouldShowNotification(userInfo)

        if showNotification {
            completionHandler([.banner, .sound, .badge])
        } else {
            completionHandler([])
        }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping () -> Void) {
        let userInfo = response.notification.request.content.userInfo
        handleNotificationTap(userInfo)
        completionHandler()
    }

    private func shouldShowNotification(_ userInfo: [AnyHashable: Any]) -> Bool {
        // TODO: Check if user is currently viewing the relevant chat/call
        return true
    }

    private func handleNotificationTap(_ userInfo: [AnyHashable: Any]) {
        guard let type = userInfo["type"] as? String else { return }

        switch type {
        case "message":
            if let senderId = userInfo["senderId"] as? String {
                NotificationCenter.default.post(
                    name: NSNotification.Name("OpenChat"),
                    object: nil,
                    userInfo: ["peerId": senderId]
                )
            }
        case "call":
            break
        case "group":
            if let groupId = userInfo["groupId"] as? String {
                NotificationCenter.default.post(
                    name: NSNotification.Name("OpenGroupChat"),
                    object: nil,
                    userInfo: ["groupId": groupId]
                )
            }
        default:
            break
        }
    }
}
