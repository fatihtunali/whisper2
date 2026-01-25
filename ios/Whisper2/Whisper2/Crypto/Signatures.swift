import Foundation
import CryptoKit

/// Ed25519 digital signatures
/// Matches server implementation

enum Signatures {

    // MARK: - Key Generation

    struct SigningKeyPair {
        let publicKey: Data
        let privateKey: Data
    }

    /// Generate Ed25519 key pair from seed
    static func keyPairFromSeed(_ seed: Data) throws -> SigningKeyPair {
        guard seed.count >= 32 else {
            throw CryptoError.invalidSeed
        }

        let privateKey = try Curve25519.Signing.PrivateKey(rawRepresentation: seed.prefix(32))
        let publicKey = privateKey.publicKey

        return SigningKeyPair(
            publicKey: publicKey.rawRepresentation,
            privateKey: privateKey.rawRepresentation
        )
    }

    /// Generate random signing key pair
    static func generateKeyPair() -> SigningKeyPair {
        let privateKey = Curve25519.Signing.PrivateKey()
        return SigningKeyPair(
            publicKey: privateKey.publicKey.rawRepresentation,
            privateKey: privateKey.rawRepresentation
        )
    }

    // MARK: - Signing

    /// Sign message with private key
    /// Returns 64-byte signature
    static func sign(message: Data, privateKey: Data) throws -> Data {
        guard privateKey.count == 32 else {
            throw CryptoError.invalidPrivateKey
        }

        let signingKey = try Curve25519.Signing.PrivateKey(rawRepresentation: privateKey)
        let signature = try signingKey.signature(for: message)

        return signature
    }

    /// Sign message bytes and return base64-encoded signature
    static func signBase64(message: Data, privateKey: Data) throws -> String {
        let signature = try sign(message: message, privateKey: privateKey)
        return signature.base64EncodedString()
    }

    // MARK: - Verification

    /// Verify signature against message using public key
    static func verify(signature: Data, message: Data, publicKey: Data) -> Bool {
        guard publicKey.count == 32, signature.count == 64 else {
            return false
        }

        do {
            let verifyKey = try Curve25519.Signing.PublicKey(rawRepresentation: publicKey)
            return verifyKey.isValidSignature(signature, for: message)
        } catch {
            return false
        }
    }

    /// Verify base64-encoded signature
    static func verifyBase64(signature: String, message: Data, publicKey: Data) -> Bool {
        guard let sigData = Data(base64Encoded: signature) else {
            return false
        }
        return verify(signature: sigData, message: message, publicKey: publicKey)
    }

    // MARK: - Convenience

    /// Sign string message
    static func sign(message: String, privateKey: Data) throws -> Data {
        guard let messageData = message.data(using: .utf8) else {
            throw CryptoError.signatureFailed
        }
        return try sign(message: messageData, privateKey: privateKey)
    }

    /// Verify signature for string message
    static func verify(signature: Data, message: String, publicKey: Data) -> Bool {
        guard let messageData = message.data(using: .utf8) else {
            return false
        }
        return verify(signature: signature, message: messageData, publicKey: publicKey)
    }
}
