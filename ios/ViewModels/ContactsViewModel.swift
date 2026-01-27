import Foundation
import Combine

/// View model for contacts
@MainActor
final class ContactsViewModel: ObservableObject {
    @Published var contacts: [Contact] = []
    @Published var isLoading = false
    @Published var error: String?
    @Published var showAddContactSheet = false
    
    private let contactsService = ContactsService.shared
    private var cancellables = Set<AnyCancellable>()
    
    init() {
        setupBindings()
        loadContacts()
    }
    
    private func setupBindings() {
        // Subscribe to contacts changes
        contactsService.$contacts
            .receive(on: DispatchQueue.main)
            .map { Array($0.values).sorted { $0.displayName < $1.displayName } }
            .sink { [weak self] contacts in
                self?.contacts = contacts
            }
            .store(in: &cancellables)
    }
    
    func loadContacts() {
        contacts = contactsService.getAllContacts()
    }
    
    /// Add contact with WhisperID and public key (from QR code)
    func addContact(whisperId: String, encPublicKey: Data, nickname: String?) {
        isLoading = true
        error = nil
        
        // Validate Whisper ID format
        guard whisperId.isValidWhisperId else {
            error = "Invalid Whisper ID format. Expected: WSP-XXXX-XXXX-XXXX"
            isLoading = false
            return
        }
        
        // Validate public key
        guard encPublicKey.count == 32 else {
            error = "Invalid public key"
            isLoading = false
            return
        }
        
        // Check if already exists
        if contactsService.hasContact(whisperId: whisperId) {
            // Update existing contact with new public key if provided
            contactsService.updateContactPublicKey(whisperId: whisperId, encPublicKey: encPublicKey)
            if let nick = nickname {
                updateNickname(for: whisperId, nickname: nick)
            }
            isLoading = false
            return
        }
        
        // Add new contact
        contactsService.addContact(
            whisperId: whisperId,
            encPublicKey: encPublicKey,
            nickname: nickname
        )
        
        isLoading = false
    }
    
    /// Add contact with just WhisperID (public key to be added later via QR)
    func addContactWithoutKey(whisperId: String, nickname: String?) {
        isLoading = true
        error = nil
        
        // Validate Whisper ID format
        guard whisperId.isValidWhisperId else {
            error = "Invalid Whisper ID format. Expected: WSP-XXXX-XXXX-XXXX"
            isLoading = false
            return
        }
        
        // Check if already exists
        if contactsService.hasContact(whisperId: whisperId) {
            error = "Contact already exists"
            isLoading = false
            return
        }
        
        // Add contact with placeholder key (needs to scan QR to get real key)
        // This allows adding the contact but messaging won't work until key is added
        contactsService.addContact(
            whisperId: whisperId,
            encPublicKey: Data(repeating: 0, count: 32), // Placeholder
            nickname: nickname
        )
        
        error = "Contact added. Scan their QR code to enable messaging."
        isLoading = false
    }
    
    func deleteContacts(at offsets: IndexSet) {
        let contactsToDelete = offsets.map { contacts[$0] }
        for contact in contactsToDelete {
            contactsService.deleteContact(whisperId: contact.whisperId)
        }
    }
    
    func deleteContact(_ contact: Contact) {
        contactsService.deleteContact(whisperId: contact.whisperId)
    }
    
    func updateNickname(for whisperId: String, nickname: String?) {
        if let contact = contactsService.getContact(whisperId: whisperId) {
            let updated = Contact(
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
            contactsService.addContact(updated)
        }
    }
    
    func blockContact(_ whisperId: String) {
        if let contact = contactsService.getContact(whisperId: whisperId) {
            let updated = Contact(
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
            contactsService.addContact(updated)
        }
    }
    
    func unblockContact(_ whisperId: String) {
        if let contact = contactsService.getContact(whisperId: whisperId) {
            let updated = Contact(
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
            contactsService.addContact(updated)
        }
    }
    
    /// Check if contact has a valid public key (can send messages)
    func canMessage(whisperId: String) -> Bool {
        return contactsService.hasValidPublicKey(for: whisperId)
    }
    
    /// Get public key for a contact
    func getPublicKey(for whisperId: String) -> Data? {
        return contactsService.getPublicKey(for: whisperId)
    }
}
