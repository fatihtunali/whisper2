import SwiftUI
import UserNotifications

/// Main app entry point
@main
struct Whisper2App: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate
    @StateObject private var coordinator = AppCoordinator()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(coordinator)
                .preferredColorScheme(.dark)
                .onAppear {
                    setupNotifications()
                }
        }
    }

    private func setupNotifications() {
        // Set notification delegate
        UNUserNotificationCenter.current().delegate = PushNotificationService.shared

        // Request permission on first launch
        Task {
            let granted = await PushNotificationService.shared.requestNotificationPermission()
            print("Notification permission: \(granted)")
        }
    }
}

/// App Delegate for handling push notifications
class AppDelegate: NSObject, UIApplicationDelegate {

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        // Initialize CallService early to set up VoIP push callback
        // This ensures the callback is ready before any VoIP push arrives
        _ = CallService.shared
        print("CallService initialized for VoIP push handling")
        return true
    }

    // MARK: - Remote Notifications

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        PushNotificationService.shared.didRegisterForRemoteNotifications(deviceToken: deviceToken)
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        PushNotificationService.shared.didFailToRegisterForRemoteNotifications(error: error)
    }

    func application(_ application: UIApplication, didReceiveRemoteNotification userInfo: [AnyHashable: Any], fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void) {
        // Handle background notification
        PushNotificationService.shared.handleNotification(userInfo) {
            completionHandler(.newData)
        }
    }
}
