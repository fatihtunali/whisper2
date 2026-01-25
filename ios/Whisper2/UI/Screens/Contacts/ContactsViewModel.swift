import SwiftUI
import Observation

/// Model representing a contact
struct Contact: Identifiable, Equatable, Hashable {
    let id: String
    let whisperId: String
    var displayName: String
    var avatarURL: URL?
    var isOnline: Bool
    var lastSeen: Date?

    static func == (lhs: Contact, rhs: Contact) -> Bool {
        lhs.id == rhs.id
    }

    func hash(into hasher: inout Hasher) {
        hasher.combine(id)
    }
}

/// Manages contacts list
@Observable
final class ContactsViewModel {
    // MARK: - State

    var contacts: [Contact] = []
    var filteredContacts: [Contact] = []
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

    // These will be injected when actual services are implemented
    // private let contactsService: ContactsServiceProtocol
    // private let networkService: NetworkServiceProtocol

    // MARK: - Computed Properties

    var groupedContacts: [(String, [Contact])] {
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
            do {
                // Simulate loading
                try await Task.sleep(for: .seconds(1))

                // Placeholder data
                contacts = [
                    Contact(
                        id: "1",
                        whisperId: "WH2-ALICE123",
                        displayName: "Alice Smith",
                        avatarURL: nil,
                        isOnline: true,
                        lastSeen: nil
                    ),
                    Contact(
                        id: "2",
                        whisperId: "WH2-BOB45678",
                        displayName: "Bob Johnson",
                        avatarURL: nil,
                        isOnline: false,
                        lastSeen: Date().addingTimeInterval(-3600)
                    ),
                    Contact(
                        id: "3",
                        whisperId: "WH2-CAROL999",
                        displayName: "Carol Williams",
                        avatarURL: nil,
                        isOnline: true,
                        lastSeen: nil
                    ),
                    Contact(
                        id: "4",
                        whisperId: "WH2-DAVID456",
                        displayName: "David Brown",
                        avatarURL: nil,
                        isOnline: false,
                        lastSeen: Date().addingTimeInterval(-86400)
                    )
                ]

                filterContacts()
                isLoading = false
            } catch {
                self.error = "Failed to load contacts"
                isLoading = false
            }
        }
    }

    func refreshContacts() async {
        // Simulate refresh
        try? await Task.sleep(for: .milliseconds(500))
        loadContacts()
    }

    func addContact() {
        guard canAddContact else { return }

        isAddingContact = true
        addContactError = nil

        let whisperId = newContactWhisperId.trimmingCharacters(in: .whitespacesAndNewlines)
        let displayName = newContactDisplayName.trimmingCharacters(in: .whitespacesAndNewlines)

        Task { @MainActor in
            do {
                // Simulate adding
                try await Task.sleep(for: .seconds(1))

                // Check if already exists
                if contacts.contains(where: { $0.whisperId == whisperId }) {
                    addContactError = "Contact already exists"
                    isAddingContact = false
                    return
                }

                // Add new contact
                let newContact = Contact(
                    id: UUID().uuidString,
                    whisperId: whisperId,
                    displayName: displayName.isEmpty ? whisperId : displayName,
                    avatarURL: nil,
                    isOnline: false,
                    lastSeen: nil
                )

                contacts.append(newContact)
                filterContacts()

                // Reset form
                newContactWhisperId = ""
                newContactDisplayName = ""
                isAddingContact = false
            } catch {
                addContactError = "Failed to add contact. Please check the WhisperID."
                isAddingContact = false
            }
        }
    }

    func deleteContact(_ contact: Contact) {
        contacts.removeAll { $0.id == contact.id }
        filterContacts()
    }

    func updateContactName(_ contact: Contact, newName: String) {
        guard let index = contacts.firstIndex(where: { $0.id == contact.id }) else { return }
        contacts[index].displayName = newName
        filterContacts()
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
