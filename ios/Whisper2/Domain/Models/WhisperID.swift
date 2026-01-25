import Foundation

/// WhisperID - Unique identifier for Whisper2 users
/// Format: WSP-XXXX-XXXX-XXXX (Base32: A-Z, 2-7)
/// MUST match server implementation in crypto.ts
///
/// The server generates WhisperIDs during registration.
/// Client does NOT generate WhisperIDs locally - they are received from server.

struct WhisperID: Hashable, Codable, CustomStringConvertible {

    // MARK: - Constants

    private static let prefix = "WSP-"
    private static let base32Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private static let pattern = "^WSP-[A-Z2-7]{4}-[A-Z2-7]{4}-[A-Z2-7]{4}$"

    // MARK: - Properties

    /// The raw string value of the WhisperID
    let rawValue: String

    // MARK: - Initialization

    /// Initialize with a raw WhisperID string
    /// - Parameter rawValue: The full WhisperID string (e.g., "WSP-ABCD-EFGH-IJKL")
    /// - Throws: WhisperIDError if validation fails
    init(_ rawValue: String) throws {
        guard Self.isValid(rawValue) else {
            throw WhisperIDError.invalidFormat
        }
        self.rawValue = rawValue.uppercased()
    }

    /// Initialize without validation (for internal use when receiving from server)
    /// - Parameter trusted: The WhisperID string from server
    init(trusted: String) {
        self.rawValue = trusted.uppercased()
    }

    // MARK: - Validation

    /// Validates a WhisperID string
    /// Format: WSP-XXXX-XXXX-XXXX where X is Base32 (A-Z, 2-7)
    /// - Parameter value: The string to validate
    /// - Returns: true if valid, false otherwise
    static func isValid(_ value: String) -> Bool {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: []) else {
            return false
        }
        let range = NSRange(value.startIndex..<value.endIndex, in: value)
        return regex.firstMatch(in: value.uppercased(), options: [], range: range) != nil
    }

    // MARK: - Display

    /// Returns formatted WhisperID for display
    /// Example: "WSP-ABCD-EFGH-IJKL"
    var displayString: String {
        rawValue
    }

    /// Returns compact form without dashes
    /// Example: "WSPABCDEFGHIJKL"
    var compact: String {
        rawValue.replacingOccurrences(of: "-", with: "")
    }

    // MARK: - Codable

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        let value = try container.decode(String.self)
        guard Self.isValid(value) else {
            throw WhisperIDError.invalidFormat
        }
        self.rawValue = value.uppercased()
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
        // Case-insensitive comparison
        lhs.rawValue.uppercased() == rhs.rawValue.uppercased()
    }

    func hash(into hasher: inout Hasher) {
        hasher.combine(rawValue.uppercased())
    }
}

// MARK: - WhisperID Errors

enum WhisperIDError: WhisperError {
    case invalidFormat

    var code: String {
        switch self {
        case .invalidFormat: return "WHISPERID_INVALID_FORMAT"
        }
    }

    var message: String {
        switch self {
        case .invalidFormat:
            return "Invalid WhisperID format. Expected: WSP-XXXX-XXXX-XXXX (A-Z, 2-7)"
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
