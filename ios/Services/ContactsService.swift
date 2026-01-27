import Foundation
import Combine

/// Service for managing contacts with persistent storage
final class ContactsService: ObservableObject {
    static let shared = ContactsService()
    
    @Published private(set) var contacts: [String: Contact] = [:] // whisperId -> Contact
    @Published private(set) var blockedUsers: [String: BlockedUser] = [:] // whisperId -> BlockedUser
    @Published private(set) var messageRequests: [String: MessageRequest] = [:] // senderId -> MessageRequest
    @Published private(set) var pendingMessages: [String: [MessageReceivedPayload]] = [:] // senderId -> messages
    
    private let keychain = KeychainService.shared
    private let ws = WebSocketService.shared
    private var cancellables = Set<AnyCancellable>()
    
    private let contactsStorageKey = "whisper2.contacts.data"
    private let blockedStorageKey = "whisper2.blocked.data"
    private let requestsStorageKey = "whisper2.requests.data"
    private let pendingMsgsStorageKey = "whisper2.pending.msgs.data"
    
    private init() {
        loadFromStorage()
        setupMessageHandler()
    }
    
    // MARK: - Public API - Contacts
    
    func getContact(whisperId: String) -> Contact? {
        return contacts[whisperId]
    }
    
    func getPublicKey(for whisperId: String) -> Data? {
        return contacts[whisperId]?.encPublicKey
    }
    
    func addContact(_ contact: Contact) {
        contacts[contact.whisperId] = contact
        saveContactsToStorage()
    }
    
    func addContact(whisperId: String, encPublicKey: Data, nickname: String? = nil) {
        let contact = Contact(
            whisperId: whisperId,
            encPublicKey: encPublicKey,
            nickname: nickname
        )
        contacts[whisperId] = contact
        saveContactsToStorage()
    }
    
    func updateContactPublicKey(whisperId: String, encPublicKey: Data) {
        if var contact = contacts[whisperId] {
            if contact.encPublicKey == Data(repeating: 0, count: 32) {
                contact = Contact(
                    id: contact.id,
                    whisperId: contact.whisperId,
                    encPublicKey: encPublicKey,
                    nickname: contact.nickname,
                    avatarUrl: contact.avatarUrl,
                    isBlocked: contact.isBlocked,
                    addedAt: contact.addedAt,
                    lastSeen: contact.lastSeen,
                    isOnline: contact.isOnline
                )
                contacts[whisperId] = contact
                saveContactsToStorage()
            }
        } else {
            let contact = Contact(
                whisperId: whisperId,
                encPublicKey: encPublicKey,
                nickname: nil
            )
            contacts[whisperId] = contact
            saveContactsToStorage()
        }
    }
    
    func deleteContact(whisperId: String) {
        contacts.removeValue(forKey: whisperId)
        saveContactsToStorage()
    }
    
    func getAllContacts() -> [Contact] {
        return Array(contacts.values).sorted { $0.displayName < $1.displayName }
    }
    
    func hasContact(whisperId: String) -> Bool {
        return contacts[whisperId] != nil
    }
    
    func hasValidPublicKey(for whisperId: String) -> Bool {
        guard let contact = contacts[whisperId] else { return false }
        return contact.encPublicKey != Data(repeating: 0, count: 32)
    }

    func updateNickname(for whisperId: String, nickname: String?) {
        guard var contact = contacts[whisperId] else { return }
        contact = Contact(
            id: contact.id,
            whisperId: contact.whisperId,
            encPublicKey: contact.encPublicKey,
            nickname: nickname,
            avatarUrl: contact.avatarUrl,
            isBlocked: contact.isBlocked,
            addedAt: contact.addedAt,
            lastSeen: contact.lastSeen,
            isOnline: contact.isOnline
        )
        objectWillChange.send()
        contacts[whisperId] = contact
        saveContactsToStorage()
    }

    func blockContact(whisperId: String) {
        guard var contact = contacts[whisperId] else { return }
        contact = Contact(
            id: contact.id,
            whisperId: contact.whisperId,
            encPublicKey: contact.encPublicKey,
            nickname: contact.nickname,
            avatarUrl: contact.avatarUrl,
            isBlocked: true,
            addedAt: contact.addedAt,
            lastSeen: contact.lastSeen,
            isOnline: contact.isOnline
        )
        contacts[whisperId] = contact
        saveContactsToStorage()
    }

    func unblockContact(whisperId: String) {
        guard var contact = contacts[whisperId] else { return }
        contact = Contact(
            id: contact.id,
            whisperId: contact.whisperId,
            encPublicKey: contact.encPublicKey,
            nickname: contact.nickname,
            avatarUrl: contact.avatarUrl,
            isBlocked: false,
            addedAt: contact.addedAt,
            lastSeen: contact.lastSeen,
            isOnline: contact.isOnline
        )
        contacts[whisperId] = contact
        saveContactsToStorage()
    }

    // MARK: - Public API - Blocked Users
    
    func isBlocked(whisperId: String) -> Bool {
        return blockedUsers[whisperId] != nil
    }
    
    func blockUser(whisperId: String, reason: String? = nil) {
        let blocked = BlockedUser(whisperId: whisperId, reason: reason)
        blockedUsers[whisperId] = blocked
        
        // Remove from contacts if exists
        contacts.removeValue(forKey: whisperId)
        
        // Remove any pending requests
        messageRequests.removeValue(forKey: whisperId)
        pendingMessages.removeValue(forKey: whisperId)
        
        saveBlockedToStorage()
        saveContactsToStorage()
        saveRequestsToStorage()
    }
    
    func unblockUser(whisperId: String) {
        blockedUsers.removeValue(forKey: whisperId)
        saveBlockedToStorage()
    }
    
    func getBlockedUsers() -> [BlockedUser] {
        return Array(blockedUsers.values).sorted { $0.blockedAt > $1.blockedAt }
    }
    
    // MARK: - Public API - Message Requests
    
    func hasMessageRequest(from senderId: String) -> Bool {
        return messageRequests[senderId] != nil
    }
    
    func getMessageRequests() -> [MessageRequest] {
        return Array(messageRequests.values)
            .filter { $0.status == .pending }
            .sorted { $0.lastReceivedAt > $1.lastReceivedAt }
    }
    
    func getPendingRequestCount() -> Int {
        return messageRequests.values.filter { $0.status == .pending }.count
    }
    
    /// Add or update a message request from unknown sender
    func addMessageRequest(senderId: String, messageId: String, payload: MessageReceivedPayload) {
        // Don't create request if blocked
        guard !isBlocked(whisperId: senderId) else { return }

        // Don't create request if already a contact
        guard !hasContact(whisperId: senderId) else { return }

        // Extract sender's public key from payload (if available)
        var senderPublicKey: Data? = nil
        if let keyB64 = payload.senderEncPublicKey,
           let keyData = Data(base64Encoded: keyB64) {
            senderPublicKey = keyData
        }

        // Store the pending message
        if pendingMessages[senderId] == nil {
            pendingMessages[senderId] = []
        }
        pendingMessages[senderId]?.append(payload)

        // Create or update request
        if var request = messageRequests[senderId] {
            // Update existing request - keep the public key if we have it
            let publicKey = senderPublicKey ?? request.senderEncPublicKey
            request = MessageRequest(
                id: request.id,
                senderId: request.senderId,
                firstMessageId: request.firstMessageId,
                firstMessagePreview: request.firstMessagePreview,
                messageCount: request.messageCount + 1,
                firstReceivedAt: request.firstReceivedAt,
                lastReceivedAt: Date(),
                status: request.status,
                senderEncPublicKey: publicKey
            )
            messageRequests[senderId] = request
        } else {
            // Create new request with sender's public key
            let request = MessageRequest(
                senderId: senderId,
                firstMessageId: messageId,
                firstMessagePreview: "[Encrypted message]",
                messageCount: 1,
                senderEncPublicKey: senderPublicKey
            )
            messageRequests[senderId] = request
        }

        saveRequestsToStorage()
        savePendingMessagesToStorage()
    }
    
    /// Accept a message request - adds sender to contacts
    func acceptMessageRequest(senderId: String, publicKey: Data) {
        guard var request = messageRequests[senderId] else { return }
        
        // Add to contacts with their public key
        addContact(whisperId: senderId, encPublicKey: publicKey, nickname: nil)
        
        // Update request status
        request = MessageRequest(
            id: request.id,
            senderId: request.senderId,
            firstMessageId: request.firstMessageId,
            firstMessagePreview: request.firstMessagePreview,
            messageCount: request.messageCount,
            firstReceivedAt: request.firstReceivedAt,
            lastReceivedAt: request.lastReceivedAt,
            status: .accepted
        )
        messageRequests[senderId] = request
        
        saveRequestsToStorage()
        
        // Notify that pending messages should be processed
        NotificationCenter.default.post(
            name: NSNotification.Name("ProcessPendingMessages"),
            object: nil,
            userInfo: ["senderId": senderId]
        )
    }
    
    /// Get pending messages for a sender (after accepting request)
    func getPendingMessages(for senderId: String) -> [MessageReceivedPayload] {
        return pendingMessages[senderId] ?? []
    }
    
    /// Clear pending messages after processing
    func clearPendingMessages(for senderId: String) {
        pendingMessages.removeValue(forKey: senderId)
        savePendingMessagesToStorage()
    }
    
    /// Decline/Block a message request
    func declineMessageRequest(senderId: String, block: Bool = false) {
        if block {
            blockUser(whisperId: senderId, reason: "Declined message request")
        } else {
            // Just remove the request without blocking
            messageRequests.removeValue(forKey: senderId)
            pendingMessages.removeValue(forKey: senderId)
            saveRequestsToStorage()
            savePendingMessagesToStorage()
        }
    }
    
    // MARK: - Persistence
    
    private func loadFromStorage() {
        // Load contacts
        if let data = keychain.getData(forKey: contactsStorageKey),
           let decoded = try? JSONDecoder().decode([Contact].self, from: data) {
            contacts = Dictionary(uniqueKeysWithValues: decoded.map { ($0.whisperId, $0) })
        }
        
        // Load blocked users
        if let data = keychain.getData(forKey: blockedStorageKey),
           let decoded = try? JSONDecoder().decode([BlockedUser].self, from: data) {
            blockedUsers = Dictionary(uniqueKeysWithValues: decoded.map { ($0.whisperId, $0) })
        }
        
        // Load message requests
        if let data = keychain.getData(forKey: requestsStorageKey),
           let decoded = try? JSONDecoder().decode([MessageRequest].self, from: data) {
            messageRequests = Dictionary(uniqueKeysWithValues: decoded.map { ($0.senderId, $0) })
        }
        
        // Load pending messages
        if let data = keychain.getData(forKey: pendingMsgsStorageKey),
           let decoded = try? JSONDecoder().decode([String: [MessageReceivedPayload]].self, from: data) {
            pendingMessages = decoded
        }
    }
    
    private func saveContactsToStorage() {
        let contactsArray = Array(contacts.values)
        if let data = try? JSONEncoder().encode(contactsArray) {
            keychain.setData(data, forKey: contactsStorageKey)
        }
    }
    
    private func saveBlockedToStorage() {
        let blockedArray = Array(blockedUsers.values)
        if let data = try? JSONEncoder().encode(blockedArray) {
            keychain.setData(data, forKey: blockedStorageKey)
        }
    }
    
    private func saveRequestsToStorage() {
        let requestsArray = Array(messageRequests.values)
        if let data = try? JSONEncoder().encode(requestsArray) {
            keychain.setData(data, forKey: requestsStorageKey)
        }
    }
    
    private func savePendingMessagesToStorage() {
        if let data = try? JSONEncoder().encode(pendingMessages) {
            keychain.setData(data, forKey: pendingMsgsStorageKey)
        }
    }
    
    // MARK: - WebSocket Handler
    
    private func setupMessageHandler() {
        ws.messagePublisher
            .sink { [weak self] data in
                self?.handleMessage(data)
            }
            .store(in: &cancellables)
    }
    
    private func handleMessage(_ data: Data) {
        guard let raw = try? JSONDecoder().decode(RawWsFrame.self, from: data) else { return }
        
        if raw.type == Constants.MessageType.presenceUpdate {
            handlePresenceUpdate(data)
        }
    }
    
    private func handlePresenceUpdate(_ data: Data) {
        guard let frame = try? JSONDecoder().decode(WsFrame<PresenceUpdatePayload>.self, from: data) else { return }
        
        let payload = frame.payload
        
        DispatchQueue.main.async { [weak self] in
            if var contact = self?.contacts[payload.whisperId] {
                contact = Contact(
                    id: contact.id,
                    whisperId: contact.whisperId,
                    encPublicKey: contact.encPublicKey,
                    nickname: contact.nickname,
                    avatarUrl: contact.avatarUrl,
                    isBlocked: contact.isBlocked,
                    addedAt: contact.addedAt,
                    lastSeen: payload.lastSeen != nil ? Date(timeIntervalSince1970: Double(payload.lastSeen!) / 1000) : contact.lastSeen,
                    isOnline: payload.status == "online"
                )
                self?.contacts[payload.whisperId] = contact
            }
        }
    }
    
    // MARK: - Encrypted Backup
    
    func exportEncryptedBackup() throws -> Data {
        guard let contactsKey = keychain.getData(forKey: Constants.StorageKey.contactsKey) else {
            throw StorageError.loadFailed
        }
        
        let contactsArray = Array(contacts.values)
        let plaintext = try JSONEncoder().encode(contactsArray)
        
        let (nonce, ciphertext) = try CryptoService.shared.secretBoxSeal(message: plaintext, key: contactsKey)
        
        var backup = Data()
        backup.append(nonce)
        backup.append(ciphertext)
        return backup
    }
    
    func importEncryptedBackup(_ backup: Data) throws {
        guard let contactsKey = keychain.getData(forKey: Constants.StorageKey.contactsKey) else {
            throw StorageError.loadFailed
        }
        
        guard backup.count > 24 else {
            throw CryptoError.decryptionFailed
        }
        
        let nonce = backup.prefix(24)
        let ciphertext = backup.dropFirst(24)
        
        let plaintext = try CryptoService.shared.secretBoxOpen(ciphertext: Data(ciphertext), nonce: Data(nonce), key: contactsKey)
        
        let importedContacts = try JSONDecoder().decode([Contact].self, from: plaintext)
        
        for contact in importedContacts {
            if let existing = contacts[contact.whisperId] {
                let merged = Contact(
                    id: existing.id,
                    whisperId: contact.whisperId,
                    encPublicKey: contact.encPublicKey,
                    nickname: contact.nickname ?? existing.nickname,
                    avatarUrl: contact.avatarUrl ?? existing.avatarUrl,
                    isBlocked: existing.isBlocked,
                    addedAt: existing.addedAt,
                    lastSeen: existing.lastSeen,
                    isOnline: existing.isOnline
                )
                contacts[contact.whisperId] = merged
            } else {
                contacts[contact.whisperId] = contact
            }
        }

        saveContactsToStorage()
    }

    // MARK: - Clear All Data

    /// Clear all contacts data (for wipe data feature)
    func clearAllData() {
        contacts.removeAll()
        blockedUsers.removeAll()
        messageRequests.removeAll()
        pendingMessages.removeAll()
        keychain.delete(key: contactsStorageKey)
        keychain.delete(key: blockedStorageKey)
        keychain.delete(key: requestsStorageKey)
        keychain.delete(key: pendingMsgsStorageKey)
    }
}
