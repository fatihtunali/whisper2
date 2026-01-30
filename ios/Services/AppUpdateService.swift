import Foundation
import UIKit

/// Service to check for app updates and prompt users
final class AppUpdateService {
    static let shared = AppUpdateService()

    // Your App Store ID (get this after publishing to App Store)
    // For now, use bundle ID for lookup
    private let bundleId = Bundle.main.bundleIdentifier ?? "com.aiakademiturkiye.whisper2"

    // Minimum supported version - users below this MUST update
    private let minimumSupportedVersion = "1.0.0"

    private init() {}

    /// Check for updates on app launch
    func checkForUpdates() {
        Task {
            await checkAppStoreVersion()
        }
    }

    /// Check App Store for latest version
    @MainActor
    private func checkAppStoreVersion() async {
        guard let appStoreVersion = await fetchAppStoreVersion() else {
            print("[AppUpdate] Could not fetch App Store version")
            return
        }

        guard let currentVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String else {
            return
        }

        print("[AppUpdate] Current: \(currentVersion), App Store: \(appStoreVersion)")

        // Check if current version is below minimum supported
        if compareVersions(currentVersion, minimumSupportedVersion) == .orderedAscending {
            showForceUpdateAlert(currentVersion: currentVersion, newVersion: appStoreVersion)
            return
        }

        // Check if App Store has newer version
        if compareVersions(currentVersion, appStoreVersion) == .orderedAscending {
            showOptionalUpdateAlert(currentVersion: currentVersion, newVersion: appStoreVersion)
        }
    }

    /// Fetch latest version from App Store
    private func fetchAppStoreVersion() async -> String? {
        let urlString = "https://itunes.apple.com/lookup?bundleId=\(bundleId)"
        guard let url = URL(string: urlString) else { return nil }

        do {
            let (data, _) = try await URLSession.shared.data(from: url)
            let response = try JSONDecoder().decode(AppStoreLookupResponse.self, from: data)

            if let result = response.results.first {
                return result.version
            }
        } catch {
            print("[AppUpdate] Error fetching App Store version: \(error)")
        }

        return nil
    }

    /// Compare two version strings (e.g., "1.2.3" vs "1.3.0")
    private func compareVersions(_ v1: String, _ v2: String) -> ComparisonResult {
        let parts1 = v1.split(separator: ".").compactMap { Int($0) }
        let parts2 = v2.split(separator: ".").compactMap { Int($0) }

        let maxLength = max(parts1.count, parts2.count)

        for i in 0..<maxLength {
            let p1 = i < parts1.count ? parts1[i] : 0
            let p2 = i < parts2.count ? parts2[i] : 0

            if p1 < p2 { return .orderedAscending }
            if p1 > p2 { return .orderedDescending }
        }

        return .orderedSame
    }

    /// Show alert for required update (user cannot dismiss)
    @MainActor
    private func showForceUpdateAlert(currentVersion: String, newVersion: String) {
        guard let windowScene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
              let rootVC = windowScene.windows.first?.rootViewController else {
            return
        }

        let alert = UIAlertController(
            title: "Update Required",
            message: "A critical update is available (v\(newVersion)). Your current version (v\(currentVersion)) is no longer supported. Please update to continue using Whisper2.",
            preferredStyle: .alert
        )

        alert.addAction(UIAlertAction(title: "Update Now", style: .default) { _ in
            self.openAppStore()
        })

        // Present on top of any existing presented view controller
        var presenter = rootVC
        while let presented = presenter.presentedViewController {
            presenter = presented
        }
        presenter.present(alert, animated: true)
    }

    /// Show alert for optional update (user can dismiss)
    @MainActor
    private func showOptionalUpdateAlert(currentVersion: String, newVersion: String) {
        // Check if we've already shown this alert recently
        let lastShownKey = "lastUpdateAlertVersion"
        if UserDefaults.standard.string(forKey: lastShownKey) == newVersion {
            // Already shown for this version, check if 24 hours passed
            let lastShownDate = UserDefaults.standard.object(forKey: "lastUpdateAlertDate") as? Date ?? .distantPast
            if Date().timeIntervalSince(lastShownDate) < 86400 { // 24 hours
                return
            }
        }

        guard let windowScene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
              let rootVC = windowScene.windows.first?.rootViewController else {
            return
        }

        let alert = UIAlertController(
            title: "Update Available",
            message: "A new version of Whisper2 (v\(newVersion)) is available. You're currently on v\(currentVersion).\n\nWould you like to update now?",
            preferredStyle: .alert
        )

        alert.addAction(UIAlertAction(title: "Update", style: .default) { _ in
            self.openAppStore()
        })

        alert.addAction(UIAlertAction(title: "Later", style: .cancel) { _ in
            // Save that we showed this alert
            UserDefaults.standard.set(newVersion, forKey: lastShownKey)
            UserDefaults.standard.set(Date(), forKey: "lastUpdateAlertDate")
        })

        // Present on top of any existing presented view controller
        var presenter = rootVC
        while let presented = presenter.presentedViewController {
            presenter = presented
        }
        presenter.present(alert, animated: true)
    }

    /// Open App Store page for this app
    private func openAppStore() {
        // Use the App Store URL scheme
        // Replace YOUR_APP_ID with actual App ID after publishing
        let appStoreURL = URL(string: "itms-apps://itunes.apple.com/app/id\(getAppId() ?? "0")")
        let webURL = URL(string: "https://apps.apple.com/app/id\(getAppId() ?? "0")")

        if let url = appStoreURL, UIApplication.shared.canOpenURL(url) {
            UIApplication.shared.open(url)
        } else if let url = webURL {
            UIApplication.shared.open(url)
        }
    }

    /// Get App ID from App Store (cached after first lookup)
    private func getAppId() -> String? {
        // If you know your App ID, hardcode it here for better reliability
        // return "123456789"

        // Otherwise, we'll use the cached value from lookup
        return UserDefaults.standard.string(forKey: "appStoreAppId")
    }

    /// Called after first successful lookup to cache App ID
    private func cacheAppId(_ appId: String) {
        UserDefaults.standard.set(appId, forKey: "appStoreAppId")
    }
}

// MARK: - App Store Lookup Response

private struct AppStoreLookupResponse: Codable {
    let resultCount: Int
    let results: [AppStoreResult]
}

private struct AppStoreResult: Codable {
    let trackId: Int
    let version: String
    let trackViewUrl: String
}
