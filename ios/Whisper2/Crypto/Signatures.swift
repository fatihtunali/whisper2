import Foundation
import TweetNaClx

/// Ed25519 digital signatures
/// Uses TweetNaCl for server-compatible crypto (libsodium compatible)

enum Signatures {

    // MARK: - Constants

    /// Public key length
    static let publicKeyLength = 32

    /// Secret key length (includes seed + public key for TweetNaCl)
    static let secretKeyLength = 64

    /// Seed length
    static let seedLength = 32

    /// Signature length
    static let signatureLength = 64

    // MARK: - Key Types

    struct SigningKeyPair {
        let publicKey: Data   // 32 bytes
        let privateKey: Data  // 64 bytes (seed + public key)
    }

    // MARK: - Key Generation

    /// Generate Ed25519 key pair from 32-byte seed
    static func keyPairFromSeed(_ seed: Data) throws -> SigningKeyPair {
        guard seed.count >= seedLength else {
            throw CryptoError.invalidSeed
        }

        let seedBytes = [UInt8](seed.prefix(seedLength))
        guard let keyPair = TweetNaCl.signKeyPair(fromSeed: seedBytes) else {
            throw CryptoError.keyDerivationFailed
        }

        return SigningKeyPair(
            publicKey: Data(keyPair.publicKey),
            privateKey: Data(keyPair.secretKey)
        )
    }

    /// Generate random Ed25519 signing key pair
    static func generateKeyPair() throws -> SigningKeyPair {
        guard let keyPair = TweetNaCl.signKeyPair() else {
            throw CryptoError.keyDerivationFailed
        }

        return SigningKeyPair(
            publicKey: Data(keyPair.publicKey),
            privateKey: Data(keyPair.secretKey)
        )
    }

    // MARK: - Signing

    /// Sign message with Ed25519 private key
    /// - Parameters:
    ///   - message: Data to sign
    ///   - privateKey: 64-byte Ed25519 secret key
    /// - Returns: 64-byte detached signature
    static func sign(message: Data, privateKey: Data) throws -> Data {
        guard privateKey.count == secretKeyLength else {
            throw CryptoError.invalidPrivateKey
        }

        let messageBytes = [UInt8](message)
        let secretKeyBytes = [UInt8](privateKey)

        guard let signature = TweetNaCl.signDetached(
            message: messageBytes,
            secretKey: secretKeyBytes
        ) else {
            throw CryptoError.signatureFailed
        }

        return Data(signature)
    }

    /// Sign message and return base64-encoded signature
    static func signBase64(message: Data, privateKey: Data) throws -> String {
        let signature = try sign(message: message, privateKey: privateKey)
        return signature.base64EncodedString()
    }

    // MARK: - Verification

    /// Verify Ed25519 signature
    /// - Parameters:
    ///   - signature: 64-byte signature
    ///   - message: Original message data
    ///   - publicKey: 32-byte Ed25519 public key
    /// - Returns: true if signature is valid
    static func verify(signature: Data, message: Data, publicKey: Data) -> Bool {
        guard publicKey.count == publicKeyLength else {
            return false
        }
        guard signature.count == signatureLength else {
            return false
        }

        let signatureBytes = [UInt8](signature)
        let messageBytes = [UInt8](message)
        let publicKeyBytes = [UInt8](publicKey)

        return TweetNaCl.signDetachedVerify(
            signature: signatureBytes,
            message: messageBytes,
            publicKey: publicKeyBytes
        )
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
