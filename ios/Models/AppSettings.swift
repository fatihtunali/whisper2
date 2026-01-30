import Foundation

/// App-wide user preferences
struct AppSettings: Codable {
    // MARK: - Notifications
    var notificationsEnabled: Bool = true
    var messagePreviewEnabled: Bool = true
    var soundEnabled: Bool = true
    var vibrationEnabled: Bool = true

    // MARK: - Privacy
    var sendReadReceipts: Bool = true
    var showTypingIndicators: Bool = true
    var showOnlineStatus: Bool = true

    // MARK: - Appearance
    var fontSize: FontSize = .medium
    var defaultChatThemeId: String? = nil

    // MARK: - Chat
    var defaultDisappearingTimer: DisappearingMessageTimer = .off
    var enterToSend: Bool = true

    // MARK: - Media
    var autoDownloadPhotos: AutoDownloadOption = .wifiOnly
    var autoDownloadVideos: AutoDownloadOption = .wifiOnly
    var autoDownloadAudio: AutoDownloadOption = .always

    // MARK: - Security
    var appLockEnabled: Bool = false
    var appLockTimeout: AppLockTimeout = .immediate
}

// MARK: - Enums

enum FontSize: String, Codable, CaseIterable {
    case small = "small"
    case medium = "medium"
    case large = "large"
    case extraLarge = "extraLarge"

    var displayName: String {
        switch self {
        case .small: return "Small"
        case .medium: return "Medium"
        case .large: return "Large"
        case .extraLarge: return "Extra Large"
        }
    }

    var scaleFactor: CGFloat {
        switch self {
        case .small: return 0.85
        case .medium: return 1.0
        case .large: return 1.15
        case .extraLarge: return 1.3
        }
    }
}

enum AutoDownloadOption: String, Codable, CaseIterable {
    case always = "always"
    case wifiOnly = "wifiOnly"
    case never = "never"

    var displayName: String {
        switch self {
        case .always: return "Always"
        case .wifiOnly: return "Wi-Fi Only"
        case .never: return "Never"
        }
    }
}

enum AppLockTimeout: String, Codable, CaseIterable {
    case immediate = "immediate"
    case oneMinute = "1min"
    case fiveMinutes = "5min"
    case fifteenMinutes = "15min"
    case oneHour = "1hour"

    var displayName: String {
        switch self {
        case .immediate: return "Immediately"
        case .oneMinute: return "After 1 minute"
        case .fiveMinutes: return "After 5 minutes"
        case .fifteenMinutes: return "After 15 minutes"
        case .oneHour: return "After 1 hour"
        }
    }

    var seconds: TimeInterval {
        switch self {
        case .immediate: return 0
        case .oneMinute: return 60
        case .fiveMinutes: return 300
        case .fifteenMinutes: return 900
        case .oneHour: return 3600
        }
    }
}

// MARK: - Settings Manager

final class AppSettingsManager: ObservableObject {
    static let shared = AppSettingsManager()

    @Published var settings: AppSettings {
        didSet {
            saveSettings()
        }
    }

    private let storageKey = "whisper2.app.settings"
    private let keychain = KeychainService.shared

    private init() {
        // Load settings from storage
        if let data = keychain.getData(forKey: storageKey),
           let decoded = try? JSONDecoder().decode(AppSettings.self, from: data) {
            settings = decoded
        } else {
            settings = AppSettings()
        }
    }

    private func saveSettings() {
        if let data = try? JSONEncoder().encode(settings) {
            keychain.setData(data, forKey: storageKey)
        }
    }

    func resetToDefaults() {
        settings = AppSettings()
    }

    // MARK: - Storage Info

    func calculateStorageUsage() -> StorageUsage {
        var usage = StorageUsage()

        // Get documents directory size
        let fileManager = FileManager.default
        if let documentsURL = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first {
            usage.documents = directorySize(at: documentsURL)
        }

        // Get caches directory size
        if let cachesURL = fileManager.urls(for: .cachesDirectory, in: .userDomainMask).first {
            usage.cache = directorySize(at: cachesURL)
        }

        // Get temp directory size
        let tempURL = fileManager.temporaryDirectory
        usage.temp = directorySize(at: tempURL)

        return usage
    }

    private func directorySize(at url: URL) -> Int64 {
        let fileManager = FileManager.default
        guard let enumerator = fileManager.enumerator(at: url, includingPropertiesForKeys: [.fileSizeKey], options: [.skipsHiddenFiles]) else {
            return 0
        }

        var totalSize: Int64 = 0
        for case let fileURL as URL in enumerator {
            if let size = try? fileURL.resourceValues(forKeys: [.fileSizeKey]).fileSize {
                totalSize += Int64(size)
            }
        }
        return totalSize
    }

    func clearCache() {
        let fileManager = FileManager.default

        // Clear caches directory
        if let cachesURL = fileManager.urls(for: .cachesDirectory, in: .userDomainMask).first {
            try? fileManager.removeItem(at: cachesURL)
            try? fileManager.createDirectory(at: cachesURL, withIntermediateDirectories: true)
        }

        // Clear temp directory
        let tempURL = fileManager.temporaryDirectory
        if let contents = try? fileManager.contentsOfDirectory(at: tempURL, includingPropertiesForKeys: nil) {
            for url in contents {
                try? fileManager.removeItem(at: url)
            }
        }
    }
}

struct StorageUsage {
    var documents: Int64 = 0
    var cache: Int64 = 0
    var temp: Int64 = 0

    var total: Int64 {
        documents + cache + temp
    }

    func formatted(_ bytes: Int64) -> String {
        let formatter = ByteCountFormatter()
        formatter.countStyle = .file
        return formatter.string(fromByteCount: bytes)
    }
}
