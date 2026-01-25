import Foundation
import Combine

/// SettingsRepository - Protocol for app settings persistence
/// Settings are stored locally (UserDefaults/Keychain) and optionally synced

protocol SettingsRepository {

    // MARK: - Notification Settings

    /// Whether push notifications are enabled
    var notificationsEnabled: Bool { get set }

    /// Whether to show message preview in notifications
    var showMessagePreview: Bool { get set }

    /// Whether to play notification sounds
    var soundEnabled: Bool { get set }

    /// Whether vibration is enabled
    var vibrationEnabled: Bool { get set }

    /// In-app notification banner display
    var inAppBannersEnabled: Bool { get set }

    // MARK: - Privacy Settings

    /// Whether read receipts are sent
    var sendReadReceipts: Bool { get set }

    /// Whether typing indicators are shown
    var showTypingIndicators: Bool { get set }

    /// Whether online status is visible to others
    var showOnlineStatus: Bool { get set }

    /// Whether to show link previews
    var showLinkPreviews: Bool { get set }

    /// Screen lock timeout (in seconds, 0 = immediate, -1 = never)
    var screenLockTimeout: Int { get set }

    // MARK: - Security Settings

    /// Whether app lock (biometric/passcode) is enabled
    var appLockEnabled: Bool { get set }

    /// Whether biometric unlock is allowed
    var biometricUnlockEnabled: Bool { get set }

    /// Whether to require authentication for sensitive actions
    var requireAuthForSensitiveActions: Bool { get set }

    /// Auto-lock when app goes to background
    var lockOnBackground: Bool { get set }

    /// Whether screenshots are allowed
    var allowScreenshots: Bool { get set }

    // MARK: - Media Settings

    /// Whether to auto-download images on WiFi
    var autoDownloadImagesWifi: Bool { get set }

    /// Whether to auto-download images on cellular
    var autoDownloadImagesCellular: Bool { get set }

    /// Whether to auto-download videos on WiFi
    var autoDownloadVideosWifi: Bool { get set }

    /// Whether to auto-download videos on cellular
    var autoDownloadVideosCellular: Bool { get set }

    /// Whether to auto-download documents on WiFi
    var autoDownloadDocumentsWifi: Bool { get set }

    /// Whether to auto-download documents on cellular
    var autoDownloadDocumentsCellular: Bool { get set }

    /// Image quality for sent images (0.0 - 1.0)
    var imageQuality: Double { get set }

    /// Video quality for sent videos
    var videoQuality: VideoQuality { get set }

    // MARK: - Appearance Settings

    /// App theme preference
    var theme: AppTheme { get set }

    /// Accent color
    var accentColor: AccentColor { get set }

    /// Chat bubble style
    var chatBubbleStyle: ChatBubbleStyle { get set }

    /// Font size multiplier (1.0 = default)
    var fontSizeMultiplier: Double { get set }

    /// Whether to use system font
    var useSystemFont: Bool { get set }

    // MARK: - Chat Settings

    /// Whether to send messages with Enter key
    var sendWithEnter: Bool { get set }

    /// Whether to show message timestamps
    var showTimestamps: Bool { get set }

    /// Message grouping interval (seconds)
    var messageGroupingInterval: Int { get set }

    /// Default message expiration (0 = never)
    var defaultMessageExpiration: Int { get set }

    // MARK: - Call Settings

    /// Whether to use proximity sensor during calls
    var useProximitySensor: Bool { get set }

    /// Whether to enable noise cancellation
    var noiseCancellation: Bool { get set }

    /// Default audio output (speaker/earpiece)
    var defaultAudioOutput: AudioOutput { get set }

    // MARK: - Storage Settings

    /// Maximum cache size in MB (0 = unlimited)
    var maxCacheSize: Int { get set }

    /// Whether to store messages locally
    var storeMessagesLocally: Bool { get set }

    /// Auto-delete messages older than (days, 0 = never)
    var autoDeleteMessagesAfter: Int { get set }

    // MARK: - Backup Settings

    /// Whether auto-backup is enabled
    var autoBackupEnabled: Bool { get set }

    /// Backup frequency in days
    var backupFrequency: Int { get set }

    /// Last backup date
    var lastBackupDate: Date? { get set }

    /// Whether to include media in backups
    var includeMediaInBackup: Bool { get set }

    // MARK: - Methods

    /// Save all settings
    func save() async throws

    /// Reset all settings to defaults
    func resetToDefaults() async throws

    /// Export settings as dictionary
    func export() -> [String: Any]

    /// Import settings from dictionary
    func importSettings(_ settings: [String: Any]) async throws

    // MARK: - Reactive

    /// Publisher for settings changes
    func settingsChangedPublisher() -> AnyPublisher<Void, Never>
}

// MARK: - Supporting Types

enum VideoQuality: String, Codable, CaseIterable {
    case low
    case medium
    case high
    case original

    var displayName: String {
        switch self {
        case .low: return "Low (360p)"
        case .medium: return "Medium (720p)"
        case .high: return "High (1080p)"
        case .original: return "Original"
        }
    }

    var maxResolution: Int {
        switch self {
        case .low: return 360
        case .medium: return 720
        case .high: return 1080
        case .original: return Int.max
        }
    }
}

enum AppTheme: String, Codable, CaseIterable {
    case system
    case light
    case dark

    var displayName: String {
        switch self {
        case .system: return "System"
        case .light: return "Light"
        case .dark: return "Dark"
        }
    }
}

enum AccentColor: String, Codable, CaseIterable {
    case blue
    case purple
    case green
    case orange
    case pink
    case red
    case teal

    var displayName: String {
        rawValue.capitalized
    }
}

enum ChatBubbleStyle: String, Codable, CaseIterable {
    case rounded
    case square
    case minimal

    var displayName: String {
        rawValue.capitalized
    }
}

enum AudioOutput: String, Codable, CaseIterable {
    case earpiece
    case speaker
    case automatic

    var displayName: String {
        switch self {
        case .earpiece: return "Earpiece"
        case .speaker: return "Speaker"
        case .automatic: return "Automatic"
        }
    }
}

// MARK: - Default Settings

struct DefaultSettings {
    // Notifications
    static let notificationsEnabled = true
    static let showMessagePreview = true
    static let soundEnabled = true
    static let vibrationEnabled = true
    static let inAppBannersEnabled = true

    // Privacy
    static let sendReadReceipts = true
    static let showTypingIndicators = true
    static let showOnlineStatus = true
    static let showLinkPreviews = true
    static let screenLockTimeout = 0

    // Security
    static let appLockEnabled = false
    static let biometricUnlockEnabled = true
    static let requireAuthForSensitiveActions = false
    static let lockOnBackground = false
    static let allowScreenshots = false

    // Media
    static let autoDownloadImagesWifi = true
    static let autoDownloadImagesCellular = true
    static let autoDownloadVideosWifi = true
    static let autoDownloadVideosCellular = false
    static let autoDownloadDocumentsWifi = true
    static let autoDownloadDocumentsCellular = false
    static let imageQuality = 0.8
    static let videoQuality = VideoQuality.high

    // Appearance
    static let theme = AppTheme.system
    static let accentColor = AccentColor.blue
    static let chatBubbleStyle = ChatBubbleStyle.rounded
    static let fontSizeMultiplier = 1.0
    static let useSystemFont = true

    // Chat
    static let sendWithEnter = true
    static let showTimestamps = true
    static let messageGroupingInterval = 300
    static let defaultMessageExpiration = 0

    // Calls
    static let useProximitySensor = true
    static let noiseCancellation = true
    static let defaultAudioOutput = AudioOutput.automatic

    // Storage
    static let maxCacheSize = 500
    static let storeMessagesLocally = true
    static let autoDeleteMessagesAfter = 0

    // Backup
    static let autoBackupEnabled = false
    static let backupFrequency = 7
    static let includeMediaInBackup = true
}
