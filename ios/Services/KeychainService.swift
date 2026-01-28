import Foundation
import Security

/// Keychain service for secure storage
final class KeychainService {
    static let shared = KeychainService()
    
    private let service = Constants.keychainService
    
    private init() {}
    
    // MARK: - Data Operations
    
    func getData(forKey key: String) -> Data? {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        
        guard status == errSecSuccess else { return nil }
        return result as? Data
    }
    
    func setData(_ data: Data?, forKey key: String) {
        guard let data = data else {
            delete(forKey: key)
            return
        }
        try? setDataThrowing(data, forKey: key)
    }

    /// Thread-safe keychain write using atomic update-or-insert pattern
    /// Prevents race conditions when multiple threads try to write the same key
    func setDataThrowing(_ data: Data, forKey key: String) throws {
        // First, try to update existing item (atomic operation)
        let updateQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key
        ]

        let updateAttributes: [String: Any] = [
            kSecValueData as String: data
        ]

        var status = SecItemUpdate(updateQuery as CFDictionary, updateAttributes as CFDictionary)

        // If item doesn't exist, add it
        if status == errSecItemNotFound {
            let addQuery: [String: Any] = [
                kSecClass as String: kSecClassGenericPassword,
                kSecAttrService as String: service,
                kSecAttrAccount as String: key,
                kSecValueData as String: data,
                kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock
            ]

            status = SecItemAdd(addQuery as CFDictionary, nil)
        }

        guard status == errSecSuccess else {
            throw StorageError.keychainError(status)
        }
    }
    
    func delete(forKey key: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key
        ]
        SecItemDelete(query as CFDictionary)
    }
    
    // MARK: - String Operations
    
    func getString(forKey key: String) -> String? {
        guard let data = getData(forKey: key) else { return nil }
        return String(data: data, encoding: .utf8)
    }
    
    func setString(_ string: String?, forKey key: String) {
        guard let string = string,
              let data = string.data(using: .utf8) else {
            delete(forKey: key)
            return
        }
        setData(data, forKey: key)
    }
    
    // MARK: - Convenience Properties
    
    var whisperId: String? {
        get { getString(forKey: Constants.StorageKey.whisperId) }
        set { setString(newValue, forKey: Constants.StorageKey.whisperId) }
    }
    
    var sessionToken: String? {
        get { getString(forKey: Constants.StorageKey.sessionToken) }
        set { setString(newValue, forKey: Constants.StorageKey.sessionToken) }
    }
    
    var deviceId: String? {
        get { getString(forKey: Constants.StorageKey.deviceId) }
        set { setString(newValue, forKey: Constants.StorageKey.deviceId) }
    }
    
    var mnemonic: String? {
        get { getString(forKey: Constants.StorageKey.mnemonic) }
        set { setString(newValue, forKey: Constants.StorageKey.mnemonic) }
    }
    
    func getOrCreateDeviceId() -> String {
        if let existing = deviceId {
            return existing
        }
        let newId = UUID().uuidString.lowercased()
        deviceId = newId
        return newId
    }
    
    var isRegistered: Bool {
        getData(forKey: Constants.StorageKey.encPrivateKey) != nil &&
        getData(forKey: Constants.StorageKey.signPrivateKey) != nil &&
        whisperId != nil
    }
    
    // MARK: - Store Keys
    
    func storeKeys(
        encPrivateKey: Data,
        encPublicKey: Data,
        signPrivateKey: Data,
        signPublicKey: Data,
        contactsKey: Data
    ) throws {
        try setDataThrowing(encPrivateKey, forKey: Constants.StorageKey.encPrivateKey)
        try setDataThrowing(encPublicKey, forKey: Constants.StorageKey.encPublicKey)
        try setDataThrowing(signPrivateKey, forKey: Constants.StorageKey.signPrivateKey)
        try setDataThrowing(signPublicKey, forKey: Constants.StorageKey.signPublicKey)
        try setDataThrowing(contactsKey, forKey: Constants.StorageKey.contactsKey)
    }
    
    // MARK: - Aliases for convenience

    /// Alias for getData(forKey:)
    func getData(for key: String) -> Data? {
        return getData(forKey: key)
    }

    /// Alias for setData
    func save(data: Data, for key: String) {
        setData(data, forKey: key)
    }

    /// Alias for delete(forKey:)
    func delete(key: String) {
        delete(forKey: key)
    }

    // MARK: - Clear

    func clearAll() {
        delete(forKey: Constants.StorageKey.encPrivateKey)
        delete(forKey: Constants.StorageKey.encPublicKey)
        delete(forKey: Constants.StorageKey.signPrivateKey)
        delete(forKey: Constants.StorageKey.signPublicKey)
        delete(forKey: Constants.StorageKey.contactsKey)
        delete(forKey: Constants.StorageKey.sessionToken)
        delete(forKey: Constants.StorageKey.whisperId)
        delete(forKey: Constants.StorageKey.mnemonic)
        delete(forKey: Constants.StorageKey.deviceId)
    }

    /// Clear all data - wipes everything from keychain for this app
    func clearAllData() {
        // Delete all items for this service
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service
        ]
        SecItemDelete(query as CFDictionary)
    }
}
