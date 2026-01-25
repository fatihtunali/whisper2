import Foundation

/// Strict Base64 encoding/decoding utilities
/// Matches server implementation

enum Base64 {

    /// Encode data to base64 string (standard, no padding option)
    static func encode(_ data: Data) -> String {
        data.base64EncodedString()
    }

    /// Decode base64 string to data (strict)
    /// Rejects invalid characters and incorrect padding
    static func decode(_ string: String) throws -> Data {
        // Validate characters (standard base64 alphabet + padding)
        let validChars = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=")
        guard string.unicodeScalars.allSatisfy({ validChars.contains($0) }) else {
            throw CryptoError.invalidBase64
        }

        // Attempt decode
        guard let data = Data(base64Encoded: string) else {
            throw CryptoError.invalidBase64
        }

        return data
    }

    /// Decode base64 string, returning nil on failure
    static func decodeOrNil(_ string: String) -> Data? {
        try? decode(string)
    }

    /// URL-safe base64 encode
    static func encodeURLSafe(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    /// URL-safe base64 decode
    static func decodeURLSafe(_ string: String) throws -> Data {
        var base64 = string
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")

        // Add padding if needed
        let paddingLength = (4 - base64.count % 4) % 4
        base64 += String(repeating: "=", count: paddingLength)

        guard let data = Data(base64Encoded: base64) else {
            throw CryptoError.invalidBase64
        }

        return data
    }

    /// Validate base64 string without decoding
    static func isValid(_ string: String) -> Bool {
        let validChars = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=")
        guard string.unicodeScalars.allSatisfy({ validChars.contains($0) }) else {
            return false
        }
        return Data(base64Encoded: string) != nil
    }

    /// Check expected decoded length
    static func expectedDecodedLength(_ base64String: String) -> Int {
        let padding = base64String.suffix(2).filter { $0 == "=" }.count
        return (base64String.count * 3 / 4) - padding
    }
}

// MARK: - Data Extension
extension Data {
    var base64: String {
        Base64.encode(self)
    }

    var base64URLSafe: String {
        Base64.encodeURLSafe(self)
    }
}

// MARK: - String Extension
extension String {
    func base64Decoded() throws -> Data {
        try Base64.decode(self)
    }

    func base64URLSafeDecoded() throws -> Data {
        try Base64.decodeURLSafe(self)
    }
}
