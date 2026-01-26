import SwiftUI
import Observation

/// Model representing a contact
struct ContactUI: Identifiable, Equatable, Hashable {
    let id: String
    let whisperId: String
    var displayName: String
    var avatarURL: URL?
    var isOnline: Bool
    var lastSeen: Date?
    var encPublicKey: String? = nil  // X25519 public key for encryption (base64)
    var isBlocked: Bool = false

    static func == (lhs: ContactUI, rhs: ContactUI) -> Bool {
        lhs.id == rhs.id
    }

    func hash(into hasher: inout Hasher) {
        hasher.combine(id)
    }

    /// Get contact's public key as Data
    var encPublicKeyData: Data? {
        guard let keyString = encPublicKey else { return nil }
        return Data(base64Encoded: keyString)
    }
}

/// Manages contacts list - connected to real server
@Observable
final class ContactsViewModel {
    // MARK: - State

    var contacts: [ContactUI] = []
    var filteredContacts: [ContactUI] = []
    var searchText: String = "" {
        didSet { filterContacts() }
    }
    var isLoading = false
    var error: String?

    // Add contact state
    var isAddingContact = false
    var newContactWhisperId: String = ""
    var newContactDisplayName: String = ""
    var addContactError: String?

    // MARK: - Dependencies

    private let keychain = KeychainService.shared

    // MARK: - Computed Properties

    var groupedContacts: [(String, [ContactUI])] {
        let grouped = Dictionary(grouping: filteredContacts) { contact in
            String(contact.displayName.prefix(1)).uppercased()
        }
        return grouped.sorted { $0.key < $1.key }
    }

    var onlineCount: Int {
        contacts.filter { $0.isOnline }.count
    }

    var canAddContact: Bool {
        !newContactWhisperId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    // MARK: - Actions

    func loadContacts() {
        isLoading = true
        error = nil

        Task { @MainActor in
            // Load contacts from local storage (ContactsBackupService manages persistence)
            let backupService = ContactsBackupService.shared
            let backupContacts = backupService.getLocalContacts()

            // Convert BackupContact to ContactUI
            contacts = backupContacts.map { backup in
                ContactUI(
                    id: backup.whisperId, // Use whisperId as id
                    whisperId: backup.whisperId,
                    displayName: backup.displayName ?? backup.whisperId,
                    avatarURL: nil,
                    isOnline: false,
                    lastSeen: nil,
                    encPublicKey: nil, // Will be fetched when needed
                    isBlocked: isContactBlocked(backup.whisperId)
                )
            }

            // Fetch public keys for all contacts in background
            for i in contacts.indices {
                if let publicKey = try? await fetchUserPublicKey(contacts[i].whisperId) {
                    contacts[i].encPublicKey = publicKey
                }
            }

            filterContacts()
            isLoading = false
            logger.debug("Contacts loaded: \(contacts.count) contacts", category: .messaging)
        }
    }

    func refreshContacts() async {
        loadContacts()
    }

    func addContact() {
        guard canAddContact else { return }

        isAddingContact = true
        addContactError = nil

        let whisperId = newContactWhisperId.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        let displayName = newContactDisplayName.trimmingCharacters(in: .whitespacesAndNewlines)

        // Validate WhisperId format (WSP-XXXX-XXXX-XXXX)
        let whisperIdPattern = #"^WSP-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$"#
        guard whisperId.range(of: whisperIdPattern, options: .regularExpression) != nil else {
            addContactError = "Invalid WhisperID format. Expected: WSP-XXXX-XXXX-XXXX"
            isAddingContact = false
            return
        }

        Task { @MainActor in
            // Check if already exists locally
            if contacts.contains(where: { $0.whisperId == whisperId }) {
                addContactError = "Contact already exists"
                isAddingContact = false
                return
            }

            // Fetch user's public key from server (also verifies user exists)
            var encPublicKey: String? = nil
            do {
                encPublicKey = try await fetchUserPublicKey(whisperId)
                if encPublicKey == nil {
                    addContactError = "WhisperID not found. User may not exist."
                    isAddingContact = false
                    return
                }
            } catch {
                addContactError = "Could not verify WhisperID: \(error.localizedDescription)"
                isAddingContact = false
                return
            }

            // Add new contact locally with their public key
            let newContact = ContactUI(
                id: whisperId, // Use whisperId as id for consistency
                whisperId: whisperId,
                displayName: displayName.isEmpty ? whisperId : displayName,
                avatarURL: nil,
                isOnline: false,
                lastSeen: nil,
                encPublicKey: encPublicKey
            )

            contacts.append(newContact)
            filterContacts()

            // Persist to ContactsBackupService
            let backupContact = BackupContact(
                whisperId: whisperId,
                displayName: displayName.isEmpty ? nil : displayName,
                flags: 0
            )
            ContactsBackupService.shared.addContact(backupContact)

            logger.info("Contact added: \(whisperId) (with public key)", category: .messaging)

            // Reset form
            newContactWhisperId = ""
            newContactDisplayName = ""
            isAddingContact = false
        }
    }

    /// Fetches user's public keys from server. Returns encPublicKey if user exists, nil if not found.
    private func fetchUserPublicKey(_ whisperId: String) async throws -> String? {
        let urlString = "\(Constants.Server.baseURL)/users/\(whisperId)/keys"
        guard let url = URL(string: urlString) else {
            throw NSError(domain: "ContactsViewModel", code: 1, userInfo: [NSLocalizedDescriptionKey: "Invalid URL"])
        }

        // Get session token for authentication
        guard let sessionToken = SessionManager.shared.sessionToken else {
            throw NSError(domain: "ContactsViewModel", code: 5, userInfo: [NSLocalizedDescriptionKey: "Not authenticated"])
        }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 10
        request.setValue("Bearer \(sessionToken)", forHTTPHeaderField: "Authorization")

        let (data, response) = try await URLSession.shared.data(for: request)

        guard let httpResponse = response as? HTTPURLResponse else {
            throw NSError(domain: "ContactsViewModel", code: 2, userInfo: [NSLocalizedDescriptionKey: "Invalid response"])
        }

        // 404 = user not found
        if httpResponse.statusCode == 404 {
            return nil
        }

        // 200 = user exists, parse keys
        guard httpResponse.statusCode == 200 else {
            throw NSError(domain: "ContactsViewModel", code: 3, userInfo: [NSLocalizedDescriptionKey: "Server error: \(httpResponse.statusCode)"])
        }

        // Parse JSON response for encPublicKey
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let encPublicKey = json["encPublicKey"] as? String else {
            throw NSError(domain: "ContactsViewModel", code: 4, userInfo: [NSLocalizedDescriptionKey: "Invalid response format"])
        }

        return encPublicKey
    }

    func deleteContact(_ contact: ContactUI) {
        contacts.removeAll { $0.id == contact.id }
        filterContacts()

        // Persist deletion to ContactsBackupService
        ContactsBackupService.shared.removeContact(contact.whisperId)
    }

    func updateContactName(_ contact: ContactUI, newName: String) {
        guard let index = contacts.firstIndex(where: { $0.id == contact.id }) else { return }
        contacts[index].displayName = newName
        filterContacts()
    }

    func blockContact(_ contact: ContactUI) {
        guard let index = contacts.firstIndex(where: { $0.id == contact.id }) else { return }
        contacts[index].isBlocked = true
        filterContacts()

        // Persist block status
        let key = "whisper2.blocked.\(contact.whisperId)"
        UserDefaults.standard.set(true, forKey: key)
        logger.info("Contact blocked: \(contact.whisperId)", category: .messaging)
    }

    func unblockContact(_ contact: ContactUI) {
        guard let index = contacts.firstIndex(where: { $0.id == contact.id }) else { return }
        contacts[index].isBlocked = false
        filterContacts()

        // Persist unblock status
        let key = "whisper2.blocked.\(contact.whisperId)"
        UserDefaults.standard.removeObject(forKey: key)
        logger.info("Contact unblocked: \(contact.whisperId)", category: .messaging)
    }

    func isContactBlocked(_ whisperId: String) -> Bool {
        let key = "whisper2.blocked.\(whisperId)"
        return UserDefaults.standard.bool(forKey: key)
    }

    // MARK: - Private Methods

    private func filterContacts() {
        if searchText.isEmpty {
            filteredContacts = contacts.sorted { $0.displayName < $1.displayName }
        } else {
            filteredContacts = contacts
                .filter { contact in
                    contact.displayName.localizedCaseInsensitiveContains(searchText) ||
                    contact.whisperId.localizedCaseInsensitiveContains(searchText)
                }
                .sorted { $0.displayName < $1.displayName }
        }
    }

    func clearError() {
        error = nil
    }

    func clearAddContactError() {
        addContactError = nil
    }

    func resetAddContactForm() {
        newContactWhisperId = ""
        newContactDisplayName = ""
        addContactError = nil
        isAddingContact = false
    }
}
