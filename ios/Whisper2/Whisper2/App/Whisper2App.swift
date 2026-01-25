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
    private let voipDelegate = VoIPPushDelegate()

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
                .modelContainer(modelContainer)
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
        let voipRegistry = PKPushRegistry(queue: .main)
        voipRegistry.delegate = voipDelegate
        voipRegistry.desiredPushTypes = [.voIP]

        logger.info("VoIP push registry configured", category: .calls)
    }

    private func setupNotificationHandling() {
        // Set up coordinator to handle notification taps
        pushDelegate.onNotificationTapped = { [weak appCoordinator] response in
            appCoordinator?.handleNotificationTap(response)
        }

        voipDelegate.onIncomingCall = { [weak appEnvironment] payload in
            Task {
                // Extract call info from VoIP payload
                let callPayload = payload.dictionaryPayload

                guard let callId = callPayload["callId"] as? String,
                      let fromWhisperId = callPayload["from"] as? String,
                      let isVideo = callPayload["isVideo"] as? Bool,
                      let offer = callPayload["offer"] as? String,
                      let nonce = callPayload["nonce"] as? String,
                      let sig = callPayload["sig"] as? String else {
                    logger.error("Invalid VoIP push payload", category: .calls)
                    return
                }

                await appEnvironment?.callService.handleIncomingCall(
                    callId: callId,
                    from: fromWhisperId,
                    isVideo: isVideo,
                    offer: offer,
                    nonce: nonce,
                    sig: sig
                )
            }
        }
    }
}

// MARK: - Root View

struct RootView: View {
    @Environment(AppCoordinator.self) private var coordinator
    @Environment(AppEnvironment.self) private var environment

    var body: some View {
        Group {
            switch coordinator.appState {
            case .loading:
                LoadingView()
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

// MARK: - Placeholder Views (to be replaced with actual implementations)

struct LoadingView: View {
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

    var body: some View {
        VStack(spacing: 24) {
            Spacer()

            Image(systemName: "bubble.left.and.bubble.right.fill")
                .font(.system(size: 80))
                .foregroundStyle(.blue)

            Text("Whisper2")
                .font(.largeTitle)
                .fontWeight(.bold)

            Text("Secure, Private Messaging")
                .font(.title3)
                .foregroundStyle(.secondary)

            Spacer()

            Button {
                // Navigate to registration/login flow
                // This will be replaced with actual WelcomeView
            } label: {
                Text("Get Started")
                    .font(.headline)
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .frame(height: 50)
                    .background(.blue)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 32)
        }
    }
}

struct MainTabView: View {
    @Environment(AppCoordinator.self) private var coordinator

    var body: some View {
        @Bindable var coordinatorBinding = coordinator

        TabView(selection: $coordinatorBinding.selectedTab) {
            ConversationsPlaceholderView()
                .tabItem {
                    Label("Chats", systemImage: "message.fill")
                }
                .tag(AppCoordinator.MainTab.conversations)

            ContactsPlaceholderView()
                .tabItem {
                    Label("Contacts", systemImage: "person.2.fill")
                }
                .tag(AppCoordinator.MainTab.contacts)

            SettingsPlaceholderView()
                .tabItem {
                    Label("Settings", systemImage: "gear")
                }
                .tag(AppCoordinator.MainTab.settings)
        }
    }
}

// MARK: - Placeholder Tab Views

struct ConversationsPlaceholderView: View {
    var body: some View {
        NavigationStack {
            Text("Conversations")
                .navigationTitle("Chats")
        }
    }
}

struct ContactsPlaceholderView: View {
    var body: some View {
        NavigationStack {
            Text("Contacts")
                .navigationTitle("Contacts")
        }
    }
}

struct SettingsPlaceholderView: View {
    @Environment(AppEnvironment.self) private var environment
    @Environment(AppCoordinator.self) private var coordinator

    var body: some View {
        NavigationStack {
            List {
                Section("Account") {
                    if let whisperId = environment.sessionManager.whisperId {
                        LabeledContent("Whisper ID", value: whisperId)
                    }
                }

                Section("Actions") {
                    Button("Logout", role: .destructive) {
                        Task {
                            await environment.logout()
                            await MainActor.run {
                                coordinator.transitionToUnauthenticated()
                            }
                        }
                    }
                }
            }
            .navigationTitle("Settings")
        }
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
