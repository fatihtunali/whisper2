import SwiftUI
import SwiftData
import UserNotifications
import PushKit

/// Whisper2 Main App Entry Point
/// Handles app lifecycle, SwiftData setup, and push notifications

@main
struct Whisper2App: App {
    // MARK: - Environment
    @State private var appEnvironment = AppEnvironment()
    @State private var appCoordinator = AppCoordinator()

    // MARK: - Scene Phase
    @Environment(\.scenePhase) private var scenePhase

    // MARK: - Push Delegates
    private let pushDelegate = PushNotificationDelegate()
    // Note: VoIP push is handled by VoipPushService.shared

    // MARK: - SwiftData Container
    private let modelContainer: ModelContainer

    init() {
        // Configure SwiftData with all model types
        do {
            let schema = Schema([
                ContactEntity.self,
                ConversationEntity.self,
                MessageEntity.self,
                GroupEntity.self,
                OutboxQueueItem.self
            ])

            let modelConfiguration = ModelConfiguration(
                "Whisper2Store",
                schema: schema,
                isStoredInMemoryOnly: false,
                allowsSave: true
            )

            modelContainer = try ModelContainer(
                for: schema,
                configurations: [modelConfiguration]
            )

            logger.info("SwiftData container initialized", category: .storage)
        } catch {
            logger.fault("Failed to initialize SwiftData: \(error.localizedDescription)", category: .storage)
            fatalError("Failed to initialize SwiftData container: \(error)")
        }

        // Register for push notifications
        registerForPushNotifications()
        registerForVoIPPush()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(appEnvironment)
                .environment(appCoordinator)
                .environmentObject(ThemeManager.shared)
                .modelContainer(modelContainer)
                .preferredColorScheme(ThemeManager.shared.colorScheme)
                .onAppear {
                    appCoordinator.setEnvironment(appEnvironment)
                    setupNotificationHandling()
                }
                .onOpenURL { url in
                    appCoordinator.handleURL(url)
                }
        }
        .onChange(of: scenePhase) { oldPhase, newPhase in
            handleScenePhaseChange(from: oldPhase, to: newPhase)
        }
    }

    // MARK: - Scene Phase Handling

    private func handleScenePhaseChange(from oldPhase: ScenePhase, to newPhase: ScenePhase) {
        logger.info("Scene phase: \(String(describing: oldPhase)) -> \(String(describing: newPhase))", category: .general)

        switch newPhase {
        case .active:
            handleBecameActive()
        case .inactive:
            handleBecameInactive()
        case .background:
            handleEnteredBackground()
        @unknown default:
            break
        }
    }

    private func handleBecameActive() {
        logger.info("App became active", category: .general)

        // Reconnect WebSocket if authenticated
        Task {
            await appEnvironment.connectIfAuthenticated()
        }

        // Clear badge count
        Task { @MainActor in
            UNUserNotificationCenter.current().setBadgeCount(0)
        }
    }

    private func handleBecameInactive() {
        logger.info("App became inactive", category: .general)
    }

    private func handleEnteredBackground() {
        logger.info("App entered background", category: .general)

        // Keep WebSocket alive for a short period
        // iOS will keep the connection for ~30 seconds
        // VoIP push will wake the app for calls
    }

    // MARK: - Push Notification Registration

    private func registerForPushNotifications() {
        UNUserNotificationCenter.current().requestAuthorization(
            options: [.alert, .sound, .badge]
        ) { granted, error in
            if let error = error {
                logger.error("Push authorization error: \(error.localizedDescription)", category: .general)
                return
            }

            if granted {
                logger.info("Push notification authorization granted", category: .general)
                DispatchQueue.main.async {
                    UIApplication.shared.registerForRemoteNotifications()
                }
            } else {
                logger.warning("Push notification authorization denied", category: .general)
            }
        }

        // Set delegate for handling notifications
        UNUserNotificationCenter.current().delegate = pushDelegate
    }

    private func registerForVoIPPush() {
        // Use the shared VoipPushService singleton
        // It handles both registration and CallKit reporting
        VoipPushService.setupAtLaunch()

        logger.info("VoIP push service configured", category: .calls)
    }

    private func setupNotificationHandling() {
        // Set up coordinator to handle notification taps
        pushDelegate.onNotificationTapped = { [weak appCoordinator] response in
            appCoordinator?.handleNotificationTap(response)
        }

        // Connect VoipPushService to CallService
        // CallService will handle incoming calls and report to CallKit
        VoipPushService.shared.delegate = CallService.shared
    }
}

// MARK: - Root View

struct RootView: View {
    @Environment(AppCoordinator.self) private var coordinator
    @Environment(AppEnvironment.self) private var environment

    var body: some View {
        SwiftUI.Group {
            switch coordinator.appState {
            case .loading:
                AppLoadingView()
            case .unauthenticated:
                AuthenticationView()
            case .authenticated:
                MainTabView()
            }
        }
        .animation(.easeInOut(duration: Constants.UI.animationDuration), value: coordinator.appState)
        .task {
            await coordinator.checkInitialState()
        }
    }
}

// MARK: - App State Views

struct AppLoadingView: View {
    var body: some View {
        VStack(spacing: 16) {
            ProgressView()
                .scaleEffect(1.5)
            Text("Loading...")
                .font(.headline)
                .foregroundStyle(.secondary)
        }
    }
}

struct AuthenticationView: View {
    @Environment(AppCoordinator.self) private var coordinator
    @State private var authViewModel = AuthViewModel()

    var body: some View {
        WelcomeView(viewModel: authViewModel)
            .onChange(of: authViewModel.state) { _, newState in
                if newState == .authenticated {
                    coordinator.transitionToAuthenticated()
                }
            }
    }
}

struct MainTabView: View {
    @Environment(AppCoordinator.self) private var coordinator
    @StateObject private var themeManager = ThemeManager.shared

    var body: some View {
        @Bindable var coordinatorBinding = coordinator

        TabView(selection: $coordinatorBinding.selectedTab) {
            NavigationStack {
                ChatsListView()
            }
            .tabItem {
                Label("Chats", systemImage: "message.fill")
            }
            .tag(AppCoordinator.MainTab.conversations)

            NavigationStack {
                GroupsListView()
            }
            .tabItem {
                Label("Groups", systemImage: "person.3.fill")
            }
            .tag(AppCoordinator.MainTab.groups)

            NavigationStack {
                ContactsListView()
            }
            .tabItem {
                Label("Contacts", systemImage: "person.2.fill")
            }
            .tag(AppCoordinator.MainTab.contacts)

            NavigationStack {
                SettingsView()
            }
            .tabItem {
                Label("Settings", systemImage: "gearshape.fill")
            }
            .tag(AppCoordinator.MainTab.settings)
        }
        .tint(Color.whisperPrimary)
        .onAppear {
            updateTabBarAppearance()
        }
        .onChange(of: themeManager.themeMode) { _, _ in
            updateTabBarAppearance()
        }
    }

    private func updateTabBarAppearance() {
        let appearance = UITabBarAppearance()
        appearance.configureWithOpaqueBackground()
        appearance.backgroundColor = UIColor(Color.whisperBackground)

        // Update tab bar item colors
        let itemAppearance = UITabBarItemAppearance()
        itemAppearance.normal.iconColor = UIColor(Color.whisperTextMuted)
        itemAppearance.normal.titleTextAttributes = [.foregroundColor: UIColor(Color.whisperTextMuted)]
        itemAppearance.selected.iconColor = UIColor(Color.whisperPrimary)
        itemAppearance.selected.titleTextAttributes = [.foregroundColor: UIColor(Color.whisperPrimary)]

        appearance.stackedLayoutAppearance = itemAppearance
        appearance.inlineLayoutAppearance = itemAppearance
        appearance.compactInlineLayoutAppearance = itemAppearance

        UITabBar.appearance().standardAppearance = appearance
        UITabBar.appearance().scrollEdgeAppearance = appearance
    }
}


// MARK: - Push Notification Delegate

final class PushNotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
    var onNotificationTapped: ((UNNotificationResponse) -> Void)?

    // Handle notification when app is in foreground
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        logger.info("Notification received in foreground", category: .general)

        // Show banner and play sound even when in foreground
        completionHandler([.banner, .sound, .badge])
    }

    // Handle notification tap
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        logger.info("Notification tapped: \(response.notification.request.identifier)", category: .general)

        onNotificationTapped?(response)
        completionHandler()
    }
}

// MARK: - VoIP Push Delegate

final class VoIPPushDelegate: NSObject, PKPushRegistryDelegate {
    var onIncomingCall: ((PKPushPayload) -> Void)?

    func pushRegistry(
        _ registry: PKPushRegistry,
        didUpdate pushCredentials: PKPushCredentials,
        for type: PKPushType
    ) {
        let token = pushCredentials.token.map { String(format: "%02x", $0) }.joined()
        logger.info("VoIP push token received: \(token.prefix(8))...", category: .calls)

        // Store token for sending to server
        UserDefaults.standard.set(token, forKey: Constants.StorageKey.voipToken)

        // Update server with new token
        NotificationCenter.default.post(
            name: .voipTokenUpdated,
            object: nil,
            userInfo: ["token": token]
        )
    }

    func pushRegistry(
        _ registry: PKPushRegistry,
        didReceiveIncomingPushWith payload: PKPushPayload,
        for type: PKPushType,
        completion: @escaping () -> Void
    ) {
        logger.info("VoIP push received", category: .calls)

        guard type == .voIP else {
            completion()
            return
        }

        onIncomingCall?(payload)
        completion()
    }

    func pushRegistry(
        _ registry: PKPushRegistry,
        didInvalidatePushTokenFor type: PKPushType
    ) {
        logger.warning("VoIP push token invalidated", category: .calls)
        UserDefaults.standard.removeObject(forKey: Constants.StorageKey.voipToken)
    }
}

// MARK: - Notification Names

extension Notification.Name {
    static let voipTokenUpdated = Notification.Name("voipTokenUpdated")
    static let pushTokenUpdated = Notification.Name("pushTokenUpdated")
}

// MARK: - App Delegate Adapter for Remote Notifications

class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        let token = deviceToken.map { String(format: "%02x", $0) }.joined()
        logger.info("Push token received: \(token.prefix(8))...", category: .general)

        // Store token
        UserDefaults.standard.set(token, forKey: Constants.StorageKey.pushToken)

        // Notify for server update
        NotificationCenter.default.post(
            name: .pushTokenUpdated,
            object: nil,
            userInfo: ["token": token]
        )
    }

    func application(
        _ application: UIApplication,
        didFailToRegisterForRemoteNotificationsWithError error: Error
    ) {
        logger.error("Failed to register for remote notifications: \(error.localizedDescription)", category: .general)
    }
}
