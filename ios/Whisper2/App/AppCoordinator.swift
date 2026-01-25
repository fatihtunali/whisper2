import Foundation
import SwiftUI
import Observation
import UserNotifications

/// AppCoordinator - Navigation and App State Coordinator
/// Manages app state transitions, deep links, and notification handling

@Observable
final class AppCoordinator {
    // MARK: - App State

    enum AppState: Equatable {
        case loading
        case unauthenticated
        case authenticated
    }

    /// Current app state
    private(set) var appState: AppState = .loading

    /// Currently selected tab in main view
    var selectedTab: MainTab = .conversations

    /// Active conversation (for navigation)
    var activeConversationId: String?

    /// Active call state
    var activeCallId: String?

    /// Deep link pending processing
    private var pendingDeepLink: DeepLink?

    // MARK: - Environment Reference

    private weak var environment: AppEnvironment?

    // MARK: - Main Tabs

    enum MainTab: Int, CaseIterable {
        case conversations = 0
        case contacts = 1
        case settings = 2
    }

    // MARK: - Deep Link Types

    enum DeepLink: Equatable {
        case conversation(id: String)
        case group(id: String)
        case contact(whisperId: String)
        case call(callId: String)
        case settings(section: String?)

        init?(url: URL) {
            // whisper2://conversation/abc123
            // whisper2://group/xyz789
            // whisper2://contact/WH2-...
            // whisper2://call/call123
            // whisper2://settings/privacy

            guard let scheme = url.scheme,
                  scheme == "whisper2" || scheme == "https" else {
                return nil
            }

            let pathComponents = url.pathComponents.filter { $0 != "/" }

            guard let firstComponent = pathComponents.first else {
                return nil
            }

            switch firstComponent {
            case "conversation":
                guard pathComponents.count >= 2 else { return nil }
                self = .conversation(id: pathComponents[1])

            case "group":
                guard pathComponents.count >= 2 else { return nil }
                self = .group(id: pathComponents[1])

            case "contact":
                guard pathComponents.count >= 2 else { return nil }
                self = .contact(whisperId: pathComponents[1])

            case "call":
                guard pathComponents.count >= 2 else { return nil }
                self = .call(callId: pathComponents[1])

            case "settings":
                let section = pathComponents.count >= 2 ? pathComponents[1] : nil
                self = .settings(section: section)

            default:
                return nil
            }
        }
    }

    // MARK: - Initialization

    init() {
        logger.info("AppCoordinator initialized", category: .general)
    }

    // MARK: - Environment Setup

    func setEnvironment(_ environment: AppEnvironment) {
        self.environment = environment
    }

    // MARK: - Initial State Check

    func checkInitialState() async {
        logger.info("Checking initial app state", category: .auth)

        guard let environment = environment else {
            logger.error("Environment not set", category: .general)
            await MainActor.run {
                appState = .unauthenticated
            }
            return
        }

        // Simulate loading time for splash screen
        try? await Task.sleep(nanoseconds: 500_000_000) // 0.5 seconds

        // Check if we have a valid session
        if environment.sessionManager.isAuthenticated {
            logger.info("User is authenticated", category: .auth)

            // Connect WebSocket
            await environment.wsClient.connect()

            await MainActor.run {
                appState = .authenticated
            }

            // Process any pending deep link
            if let deepLink = pendingDeepLink {
                await handleDeepLink(deepLink)
                pendingDeepLink = nil
            }
        } else {
            logger.info("User is not authenticated", category: .auth)
            await MainActor.run {
                appState = .unauthenticated
            }
        }
    }

    // MARK: - State Transitions

    @MainActor
    func transitionToAuthenticated() {
        logger.info("Transitioning to authenticated state", category: .auth)
        appState = .authenticated

        environment?.onAuthenticated()

        // Process pending deep link
        if let deepLink = pendingDeepLink {
            Task {
                await handleDeepLink(deepLink)
            }
            pendingDeepLink = nil
        }
    }

    @MainActor
    func transitionToUnauthenticated() {
        logger.info("Transitioning to unauthenticated state", category: .auth)
        appState = .unauthenticated
        activeConversationId = nil
        activeCallId = nil
        selectedTab = .conversations
    }

    // MARK: - Deep Link Handling

    func handleURL(_ url: URL) {
        logger.info("Handling URL: \(url.absoluteString)", category: .general)

        guard let deepLink = DeepLink(url: url) else {
            logger.warning("Invalid deep link URL: \(url)", category: .general)
            return
        }

        if appState == .authenticated {
            Task {
                await handleDeepLink(deepLink)
            }
        } else {
            // Store for later processing after authentication
            pendingDeepLink = deepLink
            logger.info("Deep link queued for after authentication", category: .general)
        }
    }

    @MainActor
    private func handleDeepLink(_ deepLink: DeepLink) async {
        logger.info("Processing deep link: \(deepLink)", category: .general)

        switch deepLink {
        case .conversation(let id):
            selectedTab = .conversations
            activeConversationId = id

        case .group(let id):
            selectedTab = .conversations
            activeConversationId = id

        case .contact(let whisperId):
            selectedTab = .contacts
            // Navigate to contact detail
            NotificationCenter.default.post(
                name: .navigateToContact,
                object: nil,
                userInfo: ["whisperId": whisperId]
            )

        case .call(let callId):
            activeCallId = callId
            // Present call UI
            NotificationCenter.default.post(
                name: .presentCallUI,
                object: nil,
                userInfo: ["callId": callId]
            )

        case .settings(let section):
            selectedTab = .settings
            if let section = section {
                NotificationCenter.default.post(
                    name: .navigateToSettingsSection,
                    object: nil,
                    userInfo: ["section": section]
                )
            }
        }
    }

    // MARK: - Notification Handling

    func handleNotificationTap(_ response: UNNotificationResponse) {
        logger.info("Handling notification tap", category: .general)

        let userInfo = response.notification.request.content.userInfo

        // Extract notification type and data
        guard let type = userInfo["type"] as? String else {
            logger.warning("Notification missing type", category: .general)
            return
        }

        switch type {
        case "message":
            if let conversationId = userInfo["conversationId"] as? String {
                Task { @MainActor in
                    if appState == .authenticated {
                        selectedTab = .conversations
                        activeConversationId = conversationId
                    } else {
                        pendingDeepLink = .conversation(id: conversationId)
                    }
                }
            }

        case "group_message":
            if let groupId = userInfo["groupId"] as? String {
                Task { @MainActor in
                    if appState == .authenticated {
                        selectedTab = .conversations
                        activeConversationId = groupId
                    } else {
                        pendingDeepLink = .group(id: groupId)
                    }
                }
            }

        case "call":
            if let callId = userInfo["callId"] as? String {
                Task { @MainActor in
                    if appState == .authenticated {
                        activeCallId = callId
                        NotificationCenter.default.post(
                            name: .presentCallUI,
                            object: nil,
                            userInfo: ["callId": callId]
                        )
                    } else {
                        pendingDeepLink = .call(callId: callId)
                    }
                }
            }

        case "missed_call":
            if let conversationId = userInfo["conversationId"] as? String {
                Task { @MainActor in
                    if appState == .authenticated {
                        selectedTab = .conversations
                        activeConversationId = conversationId
                    } else {
                        pendingDeepLink = .conversation(id: conversationId)
                    }
                }
            }

        default:
            logger.warning("Unknown notification type: \(type)", category: .general)
        }
    }

    // MARK: - Call Handling

    @MainActor
    func handleIncomingCall(callId: String, callerName: String, callerWhisperId: String, isVideo: Bool) {
        logger.info("Handling incoming call from \(callerWhisperId)", category: .calls)

        activeCallId = callId

        NotificationCenter.default.post(
            name: .incomingCall,
            object: nil,
            userInfo: [
                "callId": callId,
                "callerName": callerName,
                "callerWhisperId": callerWhisperId,
                "isVideo": isVideo
            ]
        )
    }

    @MainActor
    func handleCallEnded() {
        logger.info("Call ended", category: .calls)
        activeCallId = nil

        NotificationCenter.default.post(
            name: .callEnded,
            object: nil
        )
    }

    // MARK: - Navigation Helpers

    @MainActor
    func navigateToConversation(_ id: String) {
        selectedTab = .conversations
        activeConversationId = id
    }

    @MainActor
    func navigateToNewConversation(with whisperId: String) {
        selectedTab = .conversations

        NotificationCenter.default.post(
            name: .startNewConversation,
            object: nil,
            userInfo: ["whisperId": whisperId]
        )
    }

    @MainActor
    func navigateToSettings() {
        selectedTab = .settings
    }

    @MainActor
    func dismissActiveConversation() {
        activeConversationId = nil
    }
}

// MARK: - Navigation Notification Names

extension Notification.Name {
    static let navigateToContact = Notification.Name("navigateToContact")
    static let navigateToSettingsSection = Notification.Name("navigateToSettingsSection")
    static let presentCallUI = Notification.Name("presentCallUI")
    static let incomingCall = Notification.Name("incomingCall")
    static let callEnded = Notification.Name("callEnded")
    static let startNewConversation = Notification.Name("startNewConversation")
}

// MARK: - Scene Phase Extension for URL Handling

extension Whisper2App {
    func handleOpenURL(_ url: URL) {
        appCoordinator.handleURL(url)
    }
}
