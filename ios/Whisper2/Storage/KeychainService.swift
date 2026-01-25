import Foundation
import Security

/// Whisper2 Keychain Service
/// Secure wrapper for storing sensitive data in iOS Keychain
/// All items use kSecAttrAccessibleAfterFirstUnlock for background access

final class KeychainService {
    static let shared = KeychainService()

    private let service = "com.whisper2.app"
    private let accessGroup: String? = nil // Use app's default access group

    private init() {}

    // MARK: - Public Interface

    // MARK: Encryption Keys

    var encPrivateKey: Data? {
        get { getData(forKey: Constants.StorageKey.encPrivateKey) }
        set { setData(newValue, forKey: Constants.StorageKey.encPrivateKey) }
    }

    var encPublicKey: Data? {
        get { getData(forKey: Constants.StorageKey.encPublicKey) }
        set { setData(newValue, forKey: Constants.StorageKey.encPublicKey) }
    }

    // MARK: Signing Keys

    var signPrivateKey: Data? {
        get { getData(forKey: Constants.StorageKey.signPrivateKey) }
        set { setData(newValue, forKey: Constants.StorageKey.signPrivateKey) }
    }

    var signPublicKey: Data? {
        get { getData(forKey: Constants.StorageKey.signPublicKey) }
        set { setData(newValue, forKey: Constants.StorageKey.signPublicKey) }
    }

    // MARK: Session

    var sessionToken: String? {
        get { getString(forKey: Constants.StorageKey.sessionToken) }
        set { setString(newValue, forKey: Constants.StorageKey.sessionToken) }
    }

    var sessionExpiry: Date? {
        get { getDate(forKey: Constants.StorageKey.sessionExpiry) }
        set { setDate(newValue, forKey: Constants.StorageKey.sessionExpiry) }
    }

    var isSessionValid: Bool {
        guard let expiry = sessionExpiry, sessionToken != nil else { return false }
        return expiry > Date()
    }

    // MARK: Identity

    var deviceId: String? {
        get { getString(forKey: Constants.StorageKey.deviceId) }
        set { setString(newValue, forKey: Constants.StorageKey.deviceId) }
    }

    var whisperId: String? {
        get { getString(forKey: Constants.StorageKey.whisperId) }
        set { setString(newValue, forKey: Constants.StorageKey.whisperId) }
    }

    // MARK: Contacts Key (for contacts backup encryption)

    var contactsKey: Data? {
        get { getData(forKey: Constants.StorageKey.contactsKey) }
        set { setData(newValue, forKey: Constants.StorageKey.contactsKey) }
    }

    // MARK: Push Tokens

    var pushToken: String? {
        get { getString(forKey: Constants.StorageKey.pushToken) }
        set { setString(newValue, forKey: Constants.StorageKey.pushToken) }
    }

    var voipToken: String? {
        get { getString(forKey: Constants.StorageKey.voipToken) }
        set { setString(newValue, forKey: Constants.StorageKey.voipToken) }
    }

    // MARK: - Key Management

    /// Store all key material after registration
    func storeKeys(
        encPrivateKey: Data,
        encPublicKey: Data,
        signPrivateKey: Data,
        signPublicKey: Data,
        contactsKey: Data
    ) throws {
        // Store in order, rolling back on failure
        var stored: [String] = []

        do {
            try setDataThrowing(encPrivateKey, forKey: Constants.StorageKey.encPrivateKey)
            stored.append(Constants.StorageKey.encPrivateKey)

            try setDataThrowing(encPublicKey, forKey: Constants.StorageKey.encPublicKey)
            stored.append(Constants.StorageKey.encPublicKey)

            try setDataThrowing(signPrivateKey, forKey: Constants.StorageKey.signPrivateKey)
            stored.append(Constants.StorageKey.signPrivateKey)

            try setDataThrowing(signPublicKey, forKey: Constants.StorageKey.signPublicKey)
            stored.append(Constants.StorageKey.signPublicKey)

            try setDataThrowing(contactsKey, forKey: Constants.StorageKey.contactsKey)
            stored.append(Constants.StorageKey.contactsKey)

            logger.info("Stored all cryptographic keys in keychain", category: .storage)
        } catch {
            // Rollback stored keys on failure
            for key in stored {
                delete(forKey: key)
            }
            logger.error(error, message: "Failed to store keys, rolled back", category: .storage)
            throw error
        }
    }

    /// Store session after authentication
    func storeSession(token: String, expiry: Date) throws {
        try setStringThrowing(token, forKey: Constants.StorageKey.sessionToken)
        try setDateThrowing(expiry, forKey: Constants.StorageKey.sessionExpiry)
        logger.info("Session stored, expires: \(expiry)", category: .storage)
    }

    /// Clear session (logout)
    func clearSession() {
        delete(forKey: Constants.StorageKey.sessionToken)
        delete(forKey: Constants.StorageKey.sessionExpiry)
        logger.info("Session cleared", category: .storage)
    }

    /// Clear all stored data (full reset)
    func clearAll() {
        let keys = [
            Constants.StorageKey.encPrivateKey,
            Constants.StorageKey.encPublicKey,
            Constants.StorageKey.signPrivateKey,
            Constants.StorageKey.signPublicKey,
            Constants.StorageKey.sessionToken,
            Constants.StorageKey.sessionExpiry,
            Constants.StorageKey.deviceId,
            Constants.StorageKey.whisperId,
            Constants.StorageKey.contactsKey,
            Constants.StorageKey.pushToken,
            Constants.StorageKey.voipToken
        ]

        for key in keys {
            delete(forKey: key)
        }
        logger.info("Cleared all keychain data", category: .storage)
    }

    /// Check if user is registered (has keys)
    var isRegistered: Bool {
        encPrivateKey != nil &&
        encPublicKey != nil &&
        signPrivateKey != nil &&
        signPublicKey != nil &&
        whisperId != nil
    }

    // MARK: - Generic Accessors

    /// Generic get for any key (Data)
    func getData(forKey key: String) -> Data? {
        var query = baseQuery(forKey: key)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)

        if status == errSecSuccess {
            return result as? Data
        } else if status != errSecItemNotFound {
            logger.debug("Keychain read failed for '\(key)': \(status)", category: .storage)
        }

        return nil
    }

    /// Generic set for any key (Data)
    func setData(_ data: Data?, forKey key: String) {
        if let data = data {
            try? setDataThrowing(data, forKey: key)
        } else {
            delete(forKey: key)
        }
    }

    /// Delete item for key
    func delete(forKey key: String) {
        let query = baseQuery(forKey: key)
        let status = SecItemDelete(query as CFDictionary)

        if status != errSecSuccess && status != errSecItemNotFound {
            logger.debug("Keychain delete failed for '\(key)': \(status)", category: .storage)
        }
    }

    // MARK: - Private Helpers

    private func baseQuery(forKey key: String) -> [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock
        ]

        if let group = accessGroup {
            query[kSecAttrAccessGroup as String] = group
        }

        return query
    }

    private func setDataThrowing(_ data: Data, forKey key: String) throws {
        // First, try to delete existing item
        delete(forKey: key)

        // Now add the new item
        var query = baseQuery(forKey: key)
        query[kSecValueData as String] = data

        let status = SecItemAdd(query as CFDictionary, nil)

        if status != errSecSuccess {
            logger.error("Keychain write failed for '\(key)': \(status)", category: .storage)
            throw StorageError.keychainError(status: status)
        }
    }

    // MARK: - String Helpers

    private func getString(forKey key: String) -> String? {
        guard let data = getData(forKey: key) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private func setString(_ string: String?, forKey key: String) {
        guard let string = string else {
            delete(forKey: key)
            return
        }
        guard let data = string.data(using: .utf8) else { return }
        setData(data, forKey: key)
    }

    private func setStringThrowing(_ string: String, forKey key: String) throws {
        guard let data = string.data(using: .utf8) else {
            throw StorageError.saveFailed
        }
        try setDataThrowing(data, forKey: key)
    }

    // MARK: - Date Helpers

    private func getDate(forKey key: String) -> Date? {
        guard let data = getData(forKey: key) else { return nil }
        return try? JSONDecoder().decode(Date.self, from: data)
    }

    private func setDate(_ date: Date?, forKey key: String) {
        guard let date = date else {
            delete(forKey: key)
            return
        }
        guard let data = try? JSONEncoder().encode(date) else { return }
        setData(data, forKey: key)
    }

    private func setDateThrowing(_ date: Date, forKey key: String) throws {
        guard let data = try? JSONEncoder().encode(date) else {
            throw StorageError.saveFailed
        }
        try setDataThrowing(data, forKey: key)
    }
}

// MARK: - Keychain Convenience Extensions

extension KeychainService {
    /// Retrieve encryption key pair
    func getEncryptionKeyPair() -> (privateKey: Data, publicKey: Data)? {
        guard let privateKey = encPrivateKey,
              let publicKey = encPublicKey else {
            return nil
        }
        return (privateKey, publicKey)
    }

    /// Retrieve signing key pair
    func getSigningKeyPair() -> (privateKey: Data, publicKey: Data)? {
        guard let privateKey = signPrivateKey,
              let publicKey = signPublicKey else {
            return nil
        }
        return (privateKey, publicKey)
    }

    /// Get session info if valid
    func getValidSession() -> (token: String, expiry: Date)? {
        guard let token = sessionToken,
              let expiry = sessionExpiry,
              expiry > Date() else {
            return nil
        }
        return (token, expiry)
    }
}
