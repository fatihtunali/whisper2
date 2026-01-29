import Foundation

/// View model for settings
@MainActor
final class SettingsViewModel: ObservableObject {
    @Published var whisperId: String?
    @Published var deviceId: String?
    @Published var registeredDate: String?
    @Published var seedPhrase: String?
    @Published var showSeedPhrase = false
    @Published var blockedCount: Int = 0

    private let keychain = KeychainService.shared
    private let authService = AuthService.shared
    private let contactsService = ContactsService.shared

    init() {
        loadSettings()
    }

    func loadSettings() {
        // Use property accessors (not methods)
        whisperId = keychain.whisperId
        deviceId = keychain.deviceId
        blockedCount = contactsService.getBlockedUsers().count

        // Format date if available
        if let user = authService.currentUser {
            let formatter = DateFormatter()
            formatter.dateStyle = .medium
            registeredDate = formatter.string(from: user.createdAt)
        }
    }
    
    func revealSeedPhrase() {
        seedPhrase = keychain.mnemonic
        showSeedPhrase = true
    }
    
    func hideSeedPhrase() {
        seedPhrase = nil
        showSeedPhrase = false
    }
    
    func logout() {
        authService.logout()
    }

    /// Wipe all local data from the device
    func wipeAllData() {
        // Clear all messages
        MessagingService.shared.clearAllData()

        // Clear all contacts
        ContactsService.shared.clearAllData()

        // Clear call history
        CallService.shared.clearCallHistory()

        // Clear group data
        GroupService.shared.clearAllData()

        // Clear all attachments (files, images, audio)
        AttachmentService.shared.clearAllAttachments()

        // Clear keychain (all stored data including keys)
        keychain.clearAllData()

        // Finally logout
        authService.logout()
    }
    
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
}

// UIPasteboard import
import UIKit
