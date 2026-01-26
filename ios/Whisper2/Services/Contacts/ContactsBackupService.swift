import Foundation

/// Whisper2 Contacts Backup Service
/// Handles encrypted backup/restore of contacts to server.
/// Zero-knowledge: server never sees plaintext contact data.

// MARK: - Contact Backup Models

/// A contact entry for backup
struct BackupContact: Codable, Equatable {
    let whisperId: String
    var displayName: String?
    var flags: UInt32    // Bitmask for future extensibility (blocked, favorite, etc.)

    init(whisperId: String, displayName: String? = nil, flags: UInt32 = 0) {
        self.whisperId = whisperId
        self.displayName = displayName
        self.flags = flags
    }
}

/// Contact flags bitmask
struct ContactFlags: OptionSet {
    let rawValue: UInt32

    static let none = ContactFlags([])
    static let blocked = ContactFlags(rawValue: 1 << 0)
    static let favorite = ContactFlags(rawValue: 1 << 1)
    static let verified = ContactFlags(rawValue: 1 << 2)
    static let muted = ContactFlags(rawValue: 1 << 3)
}

/// Backup metadata from server
struct ContactsBackupMetadata: Codable {
    let sizeBytes: Int
    let updatedAt: Int64
}

/// Full backup response from server
struct ContactsBackupResponse: Codable {
    let nonce: String
    let ciphertext: String
    let sizeBytes: Int
    let updatedAt: Int64
}

/// Backup save response from server
struct ContactsBackupSaveResponse: Codable {
    let success: Bool
    let created: Bool
    let sizeBytes: Int
    let updatedAt: Int64
}

// MARK: - Contacts Backup Service

/// Service for backing up and restoring contacts.
/// Contacts are encrypted client-side with contactsKey before upload.
final class ContactsBackupService {

    static let shared = ContactsBackupService()

    private let keychain = KeychainService.shared
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    /// Debounce interval for auto-backup (seconds)
    private let autoBackupDebounce: TimeInterval = 5.0
    private var autoBackupTask: Task<Void, Never>?
    private var pendingContacts: [BackupContact]?

    private init() {
        encoder.outputFormatting = .sortedKeys
    }

    // MARK: - Local Contacts Storage Key

    private let localContactsKey = "whisper2.contacts.local"

    // MARK: - Local Contacts Operations

    /// Get all locally stored contacts
    func getLocalContacts() -> [BackupContact] {
        guard let data = UserDefaults.standard.data(forKey: localContactsKey),
              let contacts = try? decoder.decode([BackupContact].self, from: data) else {
            return []
        }
        return contacts
    }

    /// Save contacts locally
    func saveLocalContacts(_ contacts: [BackupContact]) {
        if let data = try? encoder.encode(contacts) {
            UserDefaults.standard.set(data, forKey: localContactsKey)
        }
    }

    /// Add a contact locally and schedule backup
    func addContact(_ contact: BackupContact) {
        var contacts = getLocalContacts()
        if !contacts.contains(where: { $0.whisperId == contact.whisperId }) {
            contacts.append(contact)
            saveLocalContacts(contacts)
            scheduleAutoBackup(contacts)
        }
    }

    /// Remove a contact locally and schedule backup
    func removeContact(_ whisperId: String) {
        var contacts = getLocalContacts()
        contacts.removeAll { $0.whisperId == whisperId }
        saveLocalContacts(contacts)
        scheduleAutoBackup(contacts)
    }

    // MARK: - File Export/Import

    /// Create an encrypted backup file for local export.
    /// - Returns: Encrypted backup data that can be saved to a file
    /// - Throws: CryptoError if encryption fails
    func createBackup() async throws -> Data {
        logger.info("Creating local backup file", category: .storage)

        // Get contacts key
        guard let contactsKey = keychain.contactsKey else {
            throw CryptoError.keyDerivationFailed
        }

        // Get local contacts
        let contacts = getLocalContacts()

        // Serialize contacts to JSON
        let contactsData = try encoder.encode(contacts)

        // Encrypt with secretbox
        let (nonce, ciphertext) = try ContactsBackupCrypto.shared.secretboxSeal(
            plaintext: contactsData,
            key: contactsKey
        )

        // Create backup file format: [24 byte nonce][ciphertext]
        var backupData = Data()
        backupData.append(nonce)
        backupData.append(ciphertext)

        logger.info("Created backup file: \(contacts.count) contacts, \(backupData.count) bytes", category: .storage)

        return backupData
    }

    /// Restore contacts from an encrypted backup file.
    /// - Parameter data: Encrypted backup data
    /// - Returns: Number of contacts restored
    /// - Throws: CryptoError if decryption fails
    func restoreBackup(_ data: Data) async throws -> Int {
        logger.info("Restoring from backup file (\(data.count) bytes)", category: .storage)

        // Get contacts key
        guard let contactsKey = keychain.contactsKey else {
            throw CryptoError.keyDerivationFailed
        }

        // Parse backup file format: [24 byte nonce][ciphertext]
        guard data.count > 24 else {
            throw CryptoError.decryptionFailed
        }

        let nonce = data.prefix(24)
        let ciphertext = data.dropFirst(24)

        // Decrypt with secretbox
        let plaintext = try ContactsBackupCrypto.shared.secretboxOpen(
            ciphertext: Data(ciphertext),
            nonce: nonce,
            key: contactsKey
        )

        // Deserialize JSON
        let contacts = try decoder.decode([BackupContact].self, from: plaintext)

        // Merge with existing contacts (don't overwrite, add new ones)
        var existingContacts = getLocalContacts()
        var addedCount = 0

        for contact in contacts {
            if !existingContacts.contains(where: { $0.whisperId == contact.whisperId }) {
                existingContacts.append(contact)
                addedCount += 1
            }
        }

        saveLocalContacts(existingContacts)

        logger.info("Restored \(addedCount) new contacts from backup", category: .storage)

        return addedCount
    }

    // MARK: - Server Backup API

    /// Backup contacts to server.
    /// - Parameter contacts: Array of contacts to backup
    /// - Throws: NetworkError or CryptoError
    /// - Returns: Backup metadata
    @discardableResult
    func backupContacts(_ contacts: [BackupContact]) async throws -> ContactsBackupMetadata {
        logger.info("Starting contacts backup (\(contacts.count) contacts)", category: .storage)

        // 1. Get session token
        guard let sessionToken = keychain.sessionToken else {
            throw AuthError.notAuthenticated
        }

        // 2. Get contacts key
        guard let contactsKey = keychain.contactsKey else {
            throw CryptoError.keyDerivationFailed
        }

        // 3. Serialize contacts to JSON
        let contactsData = try encoder.encode(contacts)

        // 4. Encrypt with secretbox
        let (nonce, ciphertext) = try ContactsBackupCrypto.shared.secretboxSeal(
            plaintext: contactsData,
            key: contactsKey
        )

        // 5. Build request body
        let requestBody: [String: String] = [
            "nonce": nonce.base64,
            "ciphertext": ciphertext.base64
        ]

        // 6. PUT to server
        let response = try await httpRequest(
            method: "PUT",
            path: "/backup/contacts",
            body: requestBody,
            sessionToken: sessionToken
        )

        // 7. Parse response
        let saveResponse = try decoder.decode(ContactsBackupSaveResponse.self, from: response)

        guard saveResponse.success else {
            throw NetworkError.serverError(code: "BACKUP_FAILED", message: "Server rejected backup")
        }

        logger.info("Contacts backup successful (\(saveResponse.sizeBytes) bytes)", category: .storage)

        return ContactsBackupMetadata(
            sizeBytes: saveResponse.sizeBytes,
            updatedAt: saveResponse.updatedAt
        )
    }

    /// Restore contacts from server backup.
    /// - Returns: Array of restored contacts, or nil if no backup exists
    /// - Throws: NetworkError or CryptoError
    func restoreContacts() async throws -> [BackupContact]? {
        logger.info("Starting contacts restore", category: .storage)

        // 1. Get session token
        guard let sessionToken = keychain.sessionToken else {
            throw AuthError.notAuthenticated
        }

        // 2. Get contacts key
        guard let contactsKey = keychain.contactsKey else {
            throw CryptoError.keyDerivationFailed
        }

        // 3. GET from server
        let response: Data
        do {
            response = try await httpRequest(
                method: "GET",
                path: "/backup/contacts",
                body: nil as [String: String]?,
                sessionToken: sessionToken
            )
        } catch NetworkError.httpError(statusCode: 404, _) {
            // No backup exists
            logger.info("No contacts backup found on server", category: .storage)
            return nil
        }

        // 4. Parse response
        let backupResponse = try decoder.decode(ContactsBackupResponse.self, from: response)

        // 5. Decode base64
        let nonce = try backupResponse.nonce.base64Decoded()
        let ciphertext = try backupResponse.ciphertext.base64Decoded()

        // 6. Decrypt with secretbox
        let plaintext = try ContactsBackupCrypto.shared.secretboxOpen(
            ciphertext: ciphertext,
            nonce: nonce,
            key: contactsKey
        )

        // 7. Deserialize JSON
        let contacts = try decoder.decode([BackupContact].self, from: plaintext)

        logger.info("Contacts restored (\(contacts.count) contacts)", category: .storage)

        return contacts
    }

    /// Delete contacts backup from server.
    /// - Throws: NetworkError if deletion fails
    func deleteBackup() async throws {
        logger.info("Deleting contacts backup", category: .storage)

        guard let sessionToken = keychain.sessionToken else {
            throw AuthError.notAuthenticated
        }

        _ = try await httpRequest(
            method: "DELETE",
            path: "/backup/contacts",
            body: nil as [String: String]?,
            sessionToken: sessionToken
        )

        logger.info("Contacts backup deleted", category: .storage)
    }

    /// Schedule auto-backup with debouncing.
    /// Call this when contacts change to trigger backup after a delay.
    /// - Parameter contacts: Current contacts array
    func scheduleAutoBackup(_ contacts: [BackupContact]) {
        // Cancel any pending backup
        autoBackupTask?.cancel()
        pendingContacts = contacts

        // Schedule new backup with debounce
        autoBackupTask = Task { [weak self] in
            do {
                try await Task.sleep(nanoseconds: UInt64(self?.autoBackupDebounce ?? 5.0) * 1_000_000_000)

                guard !Task.isCancelled else { return }
                guard let contacts = self?.pendingContacts else { return }

                try await self?.backupContacts(contacts)
                self?.pendingContacts = nil
            } catch {
                if !Task.isCancelled {
                    logger.error(error, message: "Auto-backup failed", category: .storage)
                }
            }
        }
    }

    /// Cancel any pending auto-backup.
    func cancelPendingBackup() {
        autoBackupTask?.cancel()
        autoBackupTask = nil
        pendingContacts = nil
    }

    // MARK: - Private Helpers

    /// Make authenticated HTTP request to server.
    private func httpRequest<T: Encodable>(
        method: String,
        path: String,
        body: T?,
        sessionToken: String
    ) async throws -> Data {
        // Build URL
        guard let url = URL(string: "\(Constants.Server.httpBaseURL)\(path)") else {
            throw NetworkError.invalidURL
        }

        // Build request
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = Constants.Timeout.httpRequest
        request.setValue("Bearer \(sessionToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        // Add body if present
        if let body = body {
            request.httpBody = try encoder.encode(body)
        }

        // Execute request
        let (data, response) = try await URLSession.shared.data(for: request)

        // Check response
        guard let httpResponse = response as? HTTPURLResponse else {
            throw NetworkError.invalidResponse
        }

        // Handle errors
        switch httpResponse.statusCode {
        case 200, 201:
            return data
        case 401:
            throw AuthError.sessionExpired
        case 404:
            throw NetworkError.httpError(statusCode: 404, message: "Not found")
        case 429:
            let errorInfo = try? decoder.decode(ServerErrorResponse.self, from: data)
            throw NetworkError.httpError(statusCode: 429, message: errorInfo?.message ?? "Rate limited")
        default:
            let errorInfo = try? decoder.decode(ServerErrorResponse.self, from: data)
            throw NetworkError.httpError(
                statusCode: httpResponse.statusCode,
                message: errorInfo?.message ?? "Request failed"
            )
        }
    }
}

// MARK: - Server Error Response

private struct ServerErrorResponse: Codable {
    let error: String
    let message: String
}

// MARK: - Contacts Backup Crypto

/// Crypto operations for contacts backup using NaClSecretBox
/// Uses TweetNaCl for server-compatible crypto
final class ContactsBackupCrypto {
    static let shared = ContactsBackupCrypto()

    private init() {}

    /// Encrypt data using NaCl secretbox (XSalsa20-Poly1305).
    /// - Parameters:
    ///   - plaintext: Data to encrypt
    ///   - key: 32-byte secret key
    /// - Returns: Tuple of (nonce, ciphertext)
    func secretboxSeal(plaintext: Data, key: Data) throws -> (nonce: Data, ciphertext: Data) {
        return try NaClSecretBox.seal(message: plaintext, key: key)
    }

    /// Decrypt data using NaCl secretbox.
    /// - Parameters:
    ///   - ciphertext: Encrypted data
    ///   - nonce: 24-byte nonce used for encryption
    ///   - key: 32-byte secret key
    /// - Returns: Decrypted plaintext
    func secretboxOpen(ciphertext: Data, nonce: Data, key: Data) throws -> Data {
        return try NaClSecretBox.open(ciphertext: ciphertext, nonce: nonce, key: key)
    }

    /// Generate random bytes.
    /// - Parameter count: Number of bytes to generate
    /// - Returns: Random data
    func randomBytes(_ count: Int) throws -> Data {
        var data = Data(count: count)
        let result = data.withUnsafeMutableBytes { ptr in
            SecRandomCopyBytes(kSecRandomDefault, count, ptr.baseAddress!)
        }
        guard result == errSecSuccess else {
            throw CryptoError.keyDerivationFailed
        }
        return data
    }
}
