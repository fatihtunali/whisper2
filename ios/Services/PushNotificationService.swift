import Foundation
import UIKit
import UserNotifications
import PushKit
import Combine

/// Service for managing push notifications (APNS) and VoIP push (PushKit)
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

        // Send to server
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
        guard let sessionToken = auth.currentUser?.sessionToken else {
            print("No session token, cannot send push tokens")
            return
        }

        // Only send if we have at least one token
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
        // Re-send tokens when we reconnect AND are authenticated
        // Note: Initial token send is done via sendTokensAfterAuth() after login
        auth.$isAuthenticated
            .combineLatest(ws.$connectionState)
            .sink { [weak self] (isAuthenticated, connectionState) in
                // Only send if both authenticated AND connected
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
        // Parse notification type
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
        // Post notification to update UI
        NotificationCenter.default.post(
            name: NSNotification.Name("NewMessageNotification"),
            object: nil,
            userInfo: userInfo
        )
    }

    private func handleCallNotification(_ userInfo: [AnyHashable: Any]) {
        // VoIP calls are handled via PushKit, this is for missed call notifications
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
}

// MARK: - PKPushRegistryDelegate (VoIP Push)

extension PushNotificationService: PKPushRegistryDelegate {

    func pushRegistry(_ registry: PKPushRegistry, didUpdate pushCredentials: PKPushCredentials, for type: PKPushType) {
        guard type == .voIP else { return }

        let token = pushCredentials.token.map { String(format: "%02.2hhx", $0) }.joined()
        voipToken = token
        print("VoIP Token: \(token)")

        // Send to server
        Task {
            await sendTokensToServer()
        }
    }

    func pushRegistry(_ registry: PKPushRegistry, didReceiveIncomingPushWith payload: PKPushPayload, for type: PKPushType, completion: @escaping () -> Void) {
        guard type == .voIP else {
            completion()
            return
        }

        // Parse the call payload from VoIP push
        let dictionaryPayload = payload.dictionaryPayload

        guard let callData = dictionaryPayload["call"] as? [String: Any],
              let callId = callData["callId"] as? String,
              let from = callData["from"] as? String else {
            print("Invalid VoIP push payload")
            completion()
            return
        }

        let isVideo = callData["isVideo"] as? Bool ?? false
        let timestamp = callData["timestamp"] as? Int64 ?? Int64(Date().timeIntervalSince1970 * 1000)
        let nonce = callData["nonce"] as? String ?? ""
        let ciphertext = callData["ciphertext"] as? String ?? ""
        let sig = callData["sig"] as? String ?? ""

        // Create CallIncomingPayload matching protocol
        let callPayload = CallIncomingPayload(
            callId: callId,
            from: from,
            isVideo: isVideo,
            timestamp: timestamp,
            nonce: nonce,
            ciphertext: ciphertext,
            sig: sig
        )

        // Notify CallKit/CallService
        onIncomingCall?(callPayload)

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

    // Called when notification received while app is in foreground
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        let userInfo = notification.request.content.userInfo

        // Check if we should show the notification
        // For example, don't show if user is already in that chat
        let showNotification = shouldShowNotification(userInfo)

        if showNotification {
            completionHandler([.banner, .sound, .badge])
        } else {
            completionHandler([])
        }
    }

    // Called when user taps on notification
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping () -> Void) {
        let userInfo = response.notification.request.content.userInfo

        // Route to appropriate screen
        handleNotificationTap(userInfo)

        completionHandler()
    }

    private func shouldShowNotification(_ userInfo: [AnyHashable: Any]) -> Bool {
        // TODO: Check if user is currently viewing the relevant chat/call
        // For now, always show
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
            // Open call screen or show missed call
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
