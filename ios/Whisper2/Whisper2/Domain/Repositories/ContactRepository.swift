import Foundation
import Combine

/// ContactRepository - Protocol for contact persistence operations
/// Contacts are encrypted and backed up to server, decrypted locally

protocol ContactRepository {

    // MARK: - CRUD Operations

    /// Save a contact to the local store
    /// - Parameter contact: The contact to save
    /// - Throws: StorageError if save fails
    func save(_ contact: Contact) async throws

    /// Save multiple contacts in a batch
    /// - Parameter contacts: Array of contacts to save
    /// - Throws: StorageError if save fails
    func saveAll(_ contacts: [Contact]) async throws

    /// Fetch a contact by WhisperID
    /// - Parameter whisperId: The contact's WhisperID
    /// - Returns: The contact if found, nil otherwise
    func fetch(whisperId: WhisperID) async throws -> Contact?

    /// Fetch all contacts
    /// - Returns: Array of all contacts
    func fetchAll() async throws -> [Contact]

    /// Fetch contacts sorted for display
    /// - Returns: Array of contacts sorted by pin status, last message, then name
    func fetchSortedForDisplay() async throws -> [Contact]

    /// Update a contact
    /// - Parameter contact: The contact with updated fields
    /// - Throws: StorageError if update fails
    func update(_ contact: Contact) async throws

    /// Delete a contact
    /// - Parameter whisperId: The contact's WhisperID
    /// - Throws: StorageError if delete fails
    func delete(whisperId: WhisperID) async throws

    /// Delete all contacts
    /// - Throws: StorageError if delete fails
    func deleteAll() async throws

    // MARK: - Filtering

    /// Fetch blocked contacts
    /// - Returns: Array of blocked contacts
    func fetchBlocked() async throws -> [Contact]

    /// Fetch pinned contacts
    /// - Returns: Array of pinned contacts
    func fetchPinned() async throws -> [Contact]

    /// Fetch muted contacts
    /// - Returns: Array of muted contacts
    func fetchMuted() async throws -> [Contact]

    /// Fetch verified contacts
    /// - Returns: Array of verified contacts
    func fetchVerified() async throws -> [Contact]

    // MARK: - Status Updates

    /// Block a contact
    /// - Parameter whisperId: The contact's WhisperID
    func block(whisperId: WhisperID) async throws

    /// Unblock a contact
    /// - Parameter whisperId: The contact's WhisperID
    func unblock(whisperId: WhisperID) async throws

    /// Pin a contact
    /// - Parameter whisperId: The contact's WhisperID
    func pin(whisperId: WhisperID) async throws

    /// Unpin a contact
    /// - Parameter whisperId: The contact's WhisperID
    func unpin(whisperId: WhisperID) async throws

    /// Mute a contact
    /// - Parameter whisperId: The contact's WhisperID
    func mute(whisperId: WhisperID) async throws

    /// Unmute a contact
    /// - Parameter whisperId: The contact's WhisperID
    func unmute(whisperId: WhisperID) async throws

    /// Update verification status
    /// - Parameters:
    ///   - whisperId: The contact's WhisperID
    ///   - status: The new verification status
    func updateVerificationStatus(
        whisperId: WhisperID,
        status: VerificationStatus
    ) async throws

    /// Update display name
    /// - Parameters:
    ///   - whisperId: The contact's WhisperID
    ///   - displayName: The new display name
    func updateDisplayName(
        whisperId: WhisperID,
        displayName: String?
    ) async throws

    /// Update last message timestamp
    /// - Parameters:
    ///   - whisperId: The contact's WhisperID
    ///   - timestamp: The timestamp of the last message
    func updateLastMessageAt(
        whisperId: WhisperID,
        timestamp: Date
    ) async throws

    // MARK: - Search

    /// Search contacts by name or WhisperID
    /// - Parameter query: Search query string
    /// - Returns: Array of matching contacts
    func search(query: String) async throws -> [Contact]

    // MARK: - Existence Check

    /// Check if a contact exists
    /// - Parameter whisperId: The contact's WhisperID
    /// - Returns: true if contact exists, false otherwise
    func exists(whisperId: WhisperID) async throws -> Bool

    /// Check if a WhisperID is blocked
    /// - Parameter whisperId: The WhisperID to check
    /// - Returns: true if blocked, false otherwise
    func isBlocked(whisperId: WhisperID) async throws -> Bool

    // MARK: - Statistics

    /// Get total contact count
    /// - Returns: Number of contacts
    func count() async throws -> Int

    // MARK: - Backup & Sync

    /// Export all contacts for encrypted backup
    /// - Returns: Array of contacts ready for encryption
    func exportForBackup() async throws -> [Contact]

    /// Import contacts from encrypted backup
    /// - Parameter contacts: Array of decrypted contacts
    /// - Parameter merge: If true, merge with existing; if false, replace all
    func importFromBackup(_ contacts: [Contact], merge: Bool) async throws

    // MARK: - Reactive Streams

    /// Publisher for contact list changes
    /// - Returns: Publisher that emits updated contact list
    func contactsPublisher() -> AnyPublisher<[Contact], Never>

    /// Publisher for a specific contact's changes
    /// - Parameter whisperId: The contact's WhisperID
    /// - Returns: Publisher that emits contact updates
    func contactPublisher(whisperId: WhisperID) -> AnyPublisher<Contact?, Never>
}

// MARK: - Default Implementations

extension ContactRepository {

    /// Convenience method to add a new contact
    func add(
        whisperId: WhisperID,
        encPublicKey: String,
        signPublicKey: String,
        displayName: String? = nil
    ) async throws {
        let contact = Contact(
            whisperId: whisperId,
            encPublicKey: encPublicKey,
            signPublicKey: signPublicKey,
            displayName: displayName
        )
        try await save(contact)
    }
}
