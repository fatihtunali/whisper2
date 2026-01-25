import Foundation

/// WhisperID - Unique identifier for Whisper2 users
/// Format: WH2-{16 hex characters}
/// Example: WH2-a1b2c3d4e5f67890

struct WhisperID: Hashable, Codable, CustomStringConvertible {

    // MARK: - Properties

    /// The raw string value of the WhisperID
    let rawValue: String

    /// The 16-character hex portion (without prefix)
    var hexPart: String {
        String(rawValue.dropFirst(Constants.Crypto.whisperIdPrefix.count))
    }

    // MARK: - Initialization

    /// Initialize with a raw WhisperID string
    /// - Parameter rawValue: The full WhisperID string (e.g., "WH2-a1b2c3d4e5f67890")
    /// - Throws: WhisperIDError if validation fails
    init(_ rawValue: String) throws {
        guard Self.isValid(rawValue) else {
            throw WhisperIDError.invalidFormat
        }
        self.rawValue = rawValue
    }

    /// Initialize from hex part only (adds WH2- prefix)
    /// - Parameter hexPart: The 16-character hex string
    /// - Throws: WhisperIDError if validation fails
    init(hexPart: String) throws {
        let fullId = Constants.Crypto.whisperIdPrefix + hexPart
        try self.init(fullId)
    }

    /// Create a WhisperID from a public key
    /// - Parameter publicKey: The public key bytes (32 bytes)
    /// - Returns: A WhisperID derived from the first 8 bytes of the public key
    static func fromPublicKey(_ publicKey: Data) throws -> WhisperID {
        guard publicKey.count >= 8 else {
            throw WhisperIDError.invalidPublicKey
        }

        // Take first 8 bytes and convert to hex (16 chars)
        let hexPart = publicKey.prefix(8).map { String(format: "%02x", $0) }.joined()
        return try WhisperID(hexPart: hexPart)
    }

    // MARK: - Validation

    /// Validates a WhisperID string
    /// - Parameter value: The string to validate
    /// - Returns: true if valid, false otherwise
    static func isValid(_ value: String) -> Bool {
        // Must start with WH2-
        guard value.hasPrefix(Constants.Crypto.whisperIdPrefix) else {
            return false
        }

        // Extract hex part
        let hexPart = String(value.dropFirst(Constants.Crypto.whisperIdPrefix.count))

        // Must be exactly 16 hex characters
        guard hexPart.count == 16 else {
            return false
        }

        // Must be valid hex
        let hexSet = CharacterSet(charactersIn: "0123456789abcdefABCDEF")
        guard hexPart.unicodeScalars.allSatisfy({ hexSet.contains($0) }) else {
            return false
        }

        return true
    }

    // MARK: - Codable

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        let value = try container.decode(String.self)
        try self.init(value)
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(rawValue)
    }

    // MARK: - CustomStringConvertible

    var description: String {
        rawValue
    }

    // MARK: - Equatable & Hashable

    static func == (lhs: WhisperID, rhs: WhisperID) -> Bool {
        // Case-insensitive comparison for the hex part
        lhs.rawValue.lowercased() == rhs.rawValue.lowercased()
    }

    func hash(into hasher: inout Hasher) {
        hasher.combine(rawValue.lowercased())
    }
}

// MARK: - WhisperID Errors

enum WhisperIDError: WhisperError {
    case invalidFormat
    case invalidPublicKey

    var code: String {
        switch self {
        case .invalidFormat: return "WHISPERID_INVALID_FORMAT"
        case .invalidPublicKey: return "WHISPERID_INVALID_PUBLIC_KEY"
        }
    }

    var message: String {
        switch self {
        case .invalidFormat:
            return "Invalid WhisperID format. Expected: WH2-{16 hex characters}"
        case .invalidPublicKey:
            return "Invalid public key for WhisperID generation"
        }
    }
}

// MARK: - String Extension

extension String {
    /// Attempts to convert this string to a WhisperID
    var asWhisperID: WhisperID? {
        try? WhisperID(self)
    }
}
