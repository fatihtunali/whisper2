import Foundation
import UIKit
import LocalAuthentication

/// View model for settings
@MainActor
final class SettingsViewModel: ObservableObject {
    @Published var whisperId: String?
    @Published var deviceId: String?
    @Published var registeredDate: String?
    @Published var seedPhrase: String?
    @Published var showSeedPhrase = false
    @Published var blockedCount: Int = 0
    @Published var storageUsage: StorageUsage = StorageUsage()
    @Published var isCalculatingStorage = false
    @Published var biometricType: BiometricType = .none

    // Settings bindings
    @Published var appSettings: AppSettings {
        didSet {
            AppSettingsManager.shared.settings = appSettings
        }
    }

    private let keychain = KeychainService.shared
    private let authService = AuthService.shared
    private let contactsService = ContactsService.shared
    private let settingsManager = AppSettingsManager.shared

    init() {
        self.appSettings = settingsManager.settings
        loadSettings()
        checkBiometricType()
    }

    func loadSettings() {
        whisperId = keychain.whisperId
        deviceId = keychain.deviceId
        blockedCount = contactsService.getBlockedUsers().count

        if let user = authService.currentUser {
            let formatter = DateFormatter()
            formatter.dateStyle = .medium
            registeredDate = formatter.string(from: user.createdAt)
        }
    }

    // MARK: - Biometrics

    enum BiometricType {
        case none
        case touchID
        case faceID
    }

    private func checkBiometricType() {
        let context = LAContext()
        var error: NSError?

        if context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error) {
            switch context.biometryType {
            case .faceID:
                biometricType = .faceID
            case .touchID:
                biometricType = .touchID
            default:
                biometricType = .none
            }
        } else {
            biometricType = .none
        }
    }

    var biometricName: String {
        switch biometricType {
        case .faceID: return "Face ID"
        case .touchID: return "Touch ID"
        case .none: return "Biometrics"
        }
    }

    var biometricIcon: String {
        switch biometricType {
        case .faceID: return "faceid"
        case .touchID: return "touchid"
        case .none: return "lock.fill"
        }
    }

    // MARK: - Seed Phrase

    func revealSeedPhrase() {
        seedPhrase = keychain.mnemonic
        showSeedPhrase = true
    }

    func hideSeedPhrase() {
        seedPhrase = nil
        showSeedPhrase = false
    }

    // MARK: - Account Actions

    func logout() {
        authService.logout()
    }

    func wipeAllData() {
        MessagingService.shared.clearAllData()
        ContactsService.shared.clearAllData()
        CallService.shared.clearCallHistory()
        GroupService.shared.clearAllData()
        AttachmentService.shared.clearAllAttachments()
        keychain.clearAllData()
        authService.logout()
    }

    // MARK: - Storage

    func calculateStorageUsage() {
        isCalculatingStorage = true
        Task {
            let usage = settingsManager.calculateStorageUsage()
            await MainActor.run {
                self.storageUsage = usage
                self.isCalculatingStorage = false
            }
        }
    }

    func clearCache() {
        settingsManager.clearCache()
        calculateStorageUsage()
    }

    func resetSettingsToDefaults() {
        settingsManager.resetToDefaults()
        appSettings = settingsManager.settings
    }

    // MARK: - Clipboard

    func copyWhisperId() {
        if let id = whisperId {
            UIPasteboard.general.string = id
        }
    }

    func copySeedPhrase() {
        if let phrase = seedPhrase {
            UIPasteboard.general.string = phrase
        }
    }

    // MARK: - App Version

    var appVersion: String {
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
        let build = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "1"
        return "\(version) (\(build))"
    }
}
