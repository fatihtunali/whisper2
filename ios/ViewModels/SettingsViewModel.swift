import Foundation
import UIKit
import LocalAuthentication
import Combine

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

    @Published var isDeletingAccount = false
    @Published var deleteAccountError: String?

    func logout() {
        authService.logout()
    }

    /// Wipe all data - sends delete request to server first, then clears local data
    /// User cannot recover their account after this operation
    func wipeAllData() async -> Bool {
        isDeletingAccount = true
        deleteAccountError = nil

        do {
            // Step 1: Request server to delete account
            let success = try await deleteAccountOnServer()

            if success {
                // Step 2: Clear all local data
                await MainActor.run {
                    clearLocalData()
                }
                return true
            } else {
                deleteAccountError = "Server failed to delete account"
                return false
            }
        } catch {
            deleteAccountError = error.localizedDescription
            // Even if server fails, allow user to clear local data
            await MainActor.run {
                clearLocalData()
            }
            return true
        }
    }

    /// Send delete_account request to server
    private func deleteAccountOnServer() async throws -> Bool {
        guard let sessionToken = keychain.sessionToken else {
            throw NetworkError.authenticationRequired
        }

        let ws = WebSocketService.shared

        // Ensure we're connected
        guard ws.connectionState == .connected else {
            throw NetworkError.connectionFailed
        }

        // Build delete_account payload
        let payload = DeleteAccountPayload(
            protocolVersion: Constants.protocolVersion,
            cryptoVersion: Constants.cryptoVersion,
            sessionToken: sessionToken,
            confirmPhrase: "DELETE MY ACCOUNT"
        )

        let frame = WsFrame(type: Constants.MessageType.deleteAccount, payload: payload)

        // Send and wait for response (with timeout)
        return try await withCheckedThrowingContinuation { continuation in
            var cancellable: AnyCancellable?
            var hasResumed = false

            // Set up timeout
            DispatchQueue.main.asyncAfter(deadline: .now() + 10) {
                guard !hasResumed else { return }
                hasResumed = true
                cancellable?.cancel()
                continuation.resume(throwing: NetworkError.timeout)
            }

            // Listen for account_deleted response
            cancellable = ws.messagePublisher
                .sink { data in
                    guard !hasResumed else { return }
                    if let response = try? JSONDecoder().decode(AccountDeletedResponse.self, from: data),
                       response.type == Constants.MessageType.accountDeleted {
                        hasResumed = true
                        cancellable?.cancel()
                        continuation.resume(returning: response.payload.success)
                    }
                }

            // Send the request
            Task {
                do {
                    try await ws.send(frame)
                } catch {
                    guard !hasResumed else { return }
                    hasResumed = true
                    cancellable?.cancel()
                    continuation.resume(throwing: error)
                }
            }
        }
    }

    /// Clear all local data (called after server deletion or on failure)
    private func clearLocalData() {
        isDeletingAccount = false
        // Clear cache (important: prevents stale state after wipe)
        settingsManager.clearCache()
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

// MARK: - Account Deletion Models

struct DeleteAccountPayload: Codable {
    let protocolVersion: Int
    let cryptoVersion: Int
    let sessionToken: String
    let confirmPhrase: String
}

struct AccountDeletedResponse: Codable {
    let type: String
    let payload: AccountDeletedPayload
}

struct AccountDeletedPayload: Codable {
    let success: Bool
    let whisperId: String
    let deletedAt: Int64
}
