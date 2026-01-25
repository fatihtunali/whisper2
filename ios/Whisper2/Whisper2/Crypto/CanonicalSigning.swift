import Foundation
import CryptoKit

/// Canonical message signing
/// MUST match server implementation exactly for signature verification

enum CanonicalSigning {

    // MARK: - Canonical Format Version

    static let version = "v1"

    // MARK: - Build Canonical String

    /// Build canonical string for direct message signing
    /// Format: v1\nmessageType\nmessageId\nfrom\nto\ntimestamp\nnonceB64\nciphertextB64\n
    static func buildCanonicalString(
        messageType: String,
        messageId: String,
        from: String,
        to: String,
        timestamp: Int64,
        nonceB64: String,
        ciphertextB64: String
    ) -> String {
        return [
            version,
            messageType,
            messageId,
            from,
            to,
            String(timestamp),
            nonceB64,
            ciphertextB64,
            "" // Trailing newline
        ].joined(separator: "\n")
    }

    /// Build canonical string for group message signing
    static func buildGroupCanonicalString(
        messageType: String,
        messageId: String,
        from: String,
        groupId: String,
        timestamp: Int64,
        nonceB64: String,
        ciphertextB64: String
    ) -> String {
        return buildCanonicalString(
            messageType: messageType,
            messageId: messageId,
            from: from,
            to: groupId, // Use groupId as "to" field
            timestamp: timestamp,
            nonceB64: nonceB64,
            ciphertextB64: ciphertextB64
        )
    }

    // MARK: - Sign Canonical

    /// Sign a message with canonical format
    static func signCanonical(
        messageType: String,
        messageId: String,
        from: String,
        to: String,
        timestamp: Int64,
        nonce: Data,
        ciphertext: Data,
        privateKey: Data
    ) throws -> Data {
        let canonical = buildCanonicalString(
            messageType: messageType,
            messageId: messageId,
            from: from,
            to: to,
            timestamp: timestamp,
            nonceB64: nonce.base64EncodedString(),
            ciphertextB64: ciphertext.base64EncodedString()
        )

        guard let canonicalData = canonical.data(using: .utf8) else {
            throw CryptoError.signatureFailed
        }

        return try Signatures.sign(message: canonicalData, privateKey: privateKey)
    }

    /// Sign and return base64-encoded signature
    static func signCanonicalBase64(
        messageType: String,
        messageId: String,
        from: String,
        to: String,
        timestamp: Int64,
        nonce: Data,
        ciphertext: Data,
        privateKey: Data
    ) throws -> String {
        let signature = try signCanonical(
            messageType: messageType,
            messageId: messageId,
            from: from,
            to: to,
            timestamp: timestamp,
            nonce: nonce,
            ciphertext: ciphertext,
            privateKey: privateKey
        )
        return signature.base64EncodedString()
    }

    // MARK: - Verify Canonical

    /// Verify a canonically signed message
    static func verifyCanonical(
        signature: Data,
        messageType: String,
        messageId: String,
        from: String,
        to: String,
        timestamp: Int64,
        nonce: Data,
        ciphertext: Data,
        publicKey: Data
    ) -> Bool {
        let canonical = buildCanonicalString(
            messageType: messageType,
            messageId: messageId,
            from: from,
            to: to,
            timestamp: timestamp,
            nonceB64: nonce.base64EncodedString(),
            ciphertextB64: ciphertext.base64EncodedString()
        )

        guard let canonicalData = canonical.data(using: .utf8) else {
            return false
        }

        return Signatures.verify(signature: signature, message: canonicalData, publicKey: publicKey)
    }

    /// Verify base64-encoded signature
    static func verifyCanonicalBase64(
        signatureB64: String,
        messageType: String,
        messageId: String,
        from: String,
        to: String,
        timestamp: Int64,
        nonceB64: String,
        ciphertextB64: String,
        publicKey: Data
    ) -> Bool {
        guard let signature = Data(base64Encoded: signatureB64),
              let nonce = Data(base64Encoded: nonceB64),
              let ciphertext = Data(base64Encoded: ciphertextB64) else {
            return false
        }

        return verifyCanonical(
            signature: signature,
            messageType: messageType,
            messageId: messageId,
            from: from,
            to: to,
            timestamp: timestamp,
            nonce: nonce,
            ciphertext: ciphertext,
            publicKey: publicKey
        )
    }

    // MARK: - Challenge Signing (for auth)

    /// Sign a registration challenge
    static func signChallenge(_ challengeBytes: Data, privateKey: Data) throws -> Data {
        return try Signatures.sign(message: challengeBytes, privateKey: privateKey)
    }

    /// Sign base64-encoded challenge
    static func signChallengeBase64(_ challengeB64: String, privateKey: Data) throws -> String {
        guard let challengeData = Data(base64Encoded: challengeB64) else {
            throw CryptoError.invalidBase64
        }
        let signature = try signChallenge(challengeData, privateKey: privateKey)
        return signature.base64EncodedString()
    }
}
