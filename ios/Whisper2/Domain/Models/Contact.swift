import Foundation

/// Contact - Represents a known user contact
/// Stores their public keys for end-to-end encrypted communication

struct Contact: Identifiable, Codable, Equatable, Hashable {

    // MARK: - Properties

    /// Unique identifier (same as whisperId for convenience)
    var id: String { whisperId.rawValue }

    /// The contact's WhisperID
    let whisperId: WhisperID

    /// Contact's encryption public key (X25519, base64 encoded)
    let encPublicKey: String

    /// Contact's signing public key (Ed25519, base64 encoded)
    let signPublicKey: String

    /// Display name set by the user
    var displayName: String?

    /// When the contact was added
    let addedAt: Date

    /// Whether the contact is blocked
    var isBlocked: Bool

    /// Whether the contact is pinned to top
    var isPinned: Bool

    /// Whether notifications are muted for this contact
    var isMuted: Bool

    /// Optional notes about the contact
    var notes: String?

    /// Last message timestamp (for sorting)
    var lastMessageAt: Date?

    /// Verification status
    var verificationStatus: VerificationStatus

    // MARK: - Computed Properties

    /// Returns display name or shortened WhisperID
    var name: String {
        displayName ?? whisperId.rawValue
    }

    /// Returns the encryption public key as Data
    var encPublicKeyData: Data? {
        Data(base64Encoded: encPublicKey)
    }

    /// Returns the signing public key as Data
    var signPublicKeyData: Data? {
        Data(base64Encoded: signPublicKey)
    }

    // MARK: - Initialization

    /// Create a new contact
    /// - Parameters:
    ///   - whisperId: The contact's WhisperID
    ///   - encPublicKey: Base64-encoded encryption public key
    ///   - signPublicKey: Base64-encoded signing public key
    ///   - displayName: Optional display name
    init(
        whisperId: WhisperID,
        encPublicKey: String,
        signPublicKey: String,
        displayName: String? = nil
    ) {
        self.whisperId = whisperId
        self.encPublicKey = encPublicKey
        self.signPublicKey = signPublicKey
        self.displayName = displayName
        self.addedAt = Date()
        self.isBlocked = false
        self.isPinned = false
        self.isMuted = false
        self.verificationStatus = .unverified
    }

    /// Create from server response
    /// - Parameters:
    ///   - whisperId: The contact's WhisperID
    ///   - encPublicKey: Base64-encoded encryption public key
    ///   - signPublicKey: Base64-encoded signing public key
    ///   - displayName: Optional display name
    ///   - addedAt: When the contact was added
    ///   - isBlocked: Block status
    ///   - isPinned: Pin status
    ///   - isMuted: Mute status
    ///   - notes: Optional notes
    ///   - lastMessageAt: Last message timestamp
    ///   - verificationStatus: Verification status
    init(
        whisperId: WhisperID,
        encPublicKey: String,
        signPublicKey: String,
        displayName: String?,
        addedAt: Date,
        isBlocked: Bool,
        isPinned: Bool,
        isMuted: Bool,
        notes: String?,
        lastMessageAt: Date?,
        verificationStatus: VerificationStatus
    ) {
        self.whisperId = whisperId
        self.encPublicKey = encPublicKey
        self.signPublicKey = signPublicKey
        self.displayName = displayName
        self.addedAt = addedAt
        self.isBlocked = isBlocked
        self.isPinned = isPinned
        self.isMuted = isMuted
        self.notes = notes
        self.lastMessageAt = lastMessageAt
        self.verificationStatus = verificationStatus
    }

    // MARK: - Hashable

    func hash(into hasher: inout Hasher) {
        hasher.combine(whisperId)
    }

    static func == (lhs: Contact, rhs: Contact) -> Bool {
        lhs.whisperId == rhs.whisperId
    }
}

// MARK: - Verification Status

enum VerificationStatus: String, Codable {
    /// Not yet verified
    case unverified

    /// Verified via QR code scan
    case verifiedQR

    /// Verified via key comparison
    case verifiedKey

    /// Verification pending
    case pending

    var isVerified: Bool {
        switch self {
        case .verifiedQR, .verifiedKey:
            return true
        case .unverified, .pending:
            return false
        }
    }

    var displayText: String {
        switch self {
        case .unverified: return "Not Verified"
        case .verifiedQR: return "Verified (QR)"
        case .verifiedKey: return "Verified (Key)"
        case .pending: return "Pending"
        }
    }
}

// MARK: - Contact Sorting

extension Contact {

    /// Sort contacts by pin status, then by last message or name
    static func sortedForDisplay(_ contacts: [Contact]) -> [Contact] {
        contacts.sorted { lhs, rhs in
            // Pinned contacts first
            if lhs.isPinned != rhs.isPinned {
                return lhs.isPinned
            }

            // Then by last message time (most recent first)
            if let lhsTime = lhs.lastMessageAt, let rhsTime = rhs.lastMessageAt {
                return lhsTime > rhsTime
            }

            // Contacts with messages before those without
            if lhs.lastMessageAt != nil && rhs.lastMessageAt == nil {
                return true
            }
            if lhs.lastMessageAt == nil && rhs.lastMessageAt != nil {
                return false
            }

            // Finally by name
            return lhs.name.localizedCaseInsensitiveCompare(rhs.name) == .orderedAscending
        }
    }
}
