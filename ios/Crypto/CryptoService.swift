import Foundation
import CryptoKit
import Security
import TweetNacl

// MARK: - NaCl Constants (matching TweetNacl internal constants)
private let kBoxNonceLength = 24
private let kBoxKeyLength = 32
private let kSecretBoxKeyLength = 32
private let kSecretBoxNonceLength = 24

// MARK: - Random Bytes Helper
private func secureRandomBytes(count: Int) throws -> Data {
    var bytes = [UInt8](repeating: 0, count: count)
    let status = SecRandomCopyBytes(kSecRandomDefault, count, &bytes)
    guard status == errSecSuccess else {
        throw CryptoError.randomGenerationFailed
    }
    return Data(bytes)
}

/// Main crypto service matching server crypto.ts
final class CryptoService {
    static let shared = CryptoService()

    private init() {}

    // MARK: - Random Bytes

    /// Generate secure random bytes
    func randomBytes(_ count: Int) throws -> Data {
        return try secureRandomBytes(count: count)
    }

    // MARK: - Key Pair Types

    struct KeyPair {
        let publicKey: Data
        let privateKey: Data
    }

    // MARK: - Key Generation from Seed

    /// Generate X25519 key pair from 32-byte seed (for encryption)
    func generateEncryptionKeyPair(from seed: Data) throws -> KeyPair {
        guard seed.count == 32 else { throw CryptoError.invalidSeed }

        // TweetNaCl: box keypair from seed
        let keyPair = try NaclBox.keyPair(fromSecretKey: seed)
        return KeyPair(publicKey: keyPair.publicKey, privateKey: keyPair.secretKey)
    }

    /// Generate Ed25519 key pair from 32-byte seed (for signing)
    func generateSigningKeyPair(from seed: Data) throws -> KeyPair {
        guard seed.count == 32 else { throw CryptoError.invalidSeed }

        // TweetNaCl: sign keypair from seed
        let keyPair = try NaclSign.KeyPair.keyPair(fromSeed: seed)
        return KeyPair(publicKey: keyPair.publicKey, privateKey: keyPair.secretKey)
    }

    // MARK: - Challenge Signing (matches server verifyChallengeSignature)

    /// Sign challenge: Ed25519_Sign(SHA256(challengeBytes), privateKey)
    func signChallenge(_ challengeBytes: Data, privateKey: Data) throws -> Data {
        // Server expects: Sign(SHA256(challenge))
        let hash = SHA256.hash(data: challengeBytes)
        let hashData = Data(hash)
        return try sign(message: hashData, privateKey: privateKey)
    }

    // MARK: - Ed25519 Signing

    func sign(message: Data, privateKey: Data) throws -> Data {
        // TweetNaCl sign.detached returns just the signature (64 bytes)
        return try NaclSign.signDetached(message: message, secretKey: privateKey)
    }

    func verify(signature: Data, message: Data, publicKey: Data) -> Bool {
        do {
            return try NaclSign.signDetachedVerify(message: message, sig: signature, publicKey: publicKey)
        } catch {
            return false
        }
    }

    // MARK: - Box Encryption (X25519 + XSalsa20-Poly1305)

    func boxSeal(message: Data, recipientPublicKey: Data, senderPrivateKey: Data) throws -> (nonce: Data, ciphertext: Data) {
        guard recipientPublicKey.count == kBoxKeyLength else { throw CryptoError.invalidPublicKey }
        guard senderPrivateKey.count == kBoxKeyLength else { throw CryptoError.invalidPrivateKey }

        let nonce = try secureRandomBytes(count: kBoxNonceLength)
        let ciphertext = try NaclBox.box(
            message: message,
            nonce: nonce,
            publicKey: recipientPublicKey,
            secretKey: senderPrivateKey
        )

        return (nonce, ciphertext)
    }

    func boxOpen(ciphertext: Data, nonce: Data, senderPublicKey: Data, recipientPrivateKey: Data) throws -> Data {
        guard senderPublicKey.count == kBoxKeyLength else { throw CryptoError.invalidPublicKey }
        guard recipientPrivateKey.count == kBoxKeyLength else { throw CryptoError.invalidPrivateKey }
        guard nonce.count == kBoxNonceLength else { throw CryptoError.invalidNonce }

        return try NaclBox.open(
            message: ciphertext,
            nonce: nonce,
            publicKey: senderPublicKey,
            secretKey: recipientPrivateKey
        )
    }

    // MARK: - SecretBox (Symmetric Encryption)

    func secretBoxSeal(message: Data, key: Data) throws -> (nonce: Data, ciphertext: Data) {
        guard key.count == kSecretBoxKeyLength else { throw CryptoError.invalidPrivateKey }

        let nonce = try secureRandomBytes(count: kSecretBoxNonceLength)
        let ciphertext = try NaclSecretBox.secretBox(message: message, nonce: nonce, key: key)

        return (nonce, ciphertext)
    }

    func secretBoxOpen(ciphertext: Data, nonce: Data, key: Data) throws -> Data {
        return try NaclSecretBox.open(box: ciphertext, nonce: nonce, key: key)
    }

    // MARK: - Message Encryption/Decryption Helpers

    /// Encrypt a message string for a recipient
    func encryptMessage(_ content: String, recipientPublicKey: Data, senderPrivateKey: Data) throws -> (ciphertext: Data, nonce: Data) {
        guard let messageData = content.data(using: .utf8) else {
            throw CryptoError.invalidMessage
        }
        let (nonce, ciphertext) = try boxSeal(
            message: messageData,
            recipientPublicKey: recipientPublicKey,
            senderPrivateKey: senderPrivateKey
        )
        return (ciphertext, nonce)
    }

    /// Decrypt a message from a sender
    func decryptMessage(ciphertext: Data, nonce: Data, senderPublicKey: Data, recipientPrivateKey: Data) throws -> String {
        let plaintext = try boxOpen(
            ciphertext: ciphertext,
            nonce: nonce,
            senderPublicKey: senderPublicKey,
            recipientPrivateKey: recipientPrivateKey
        )
        guard let message = String(data: plaintext, encoding: .utf8) else {
            throw CryptoError.decryptionFailed
        }
        return message
    }

    // MARK: - Canonical Message Signing (matches server buildCanonicalBytes)

    /// Build canonical bytes for message signing
    /// Format from server crypto.ts:
    /// v1\n + messageType\n + messageId\n + from\n + to\n + timestamp\n + nonceB64\n + ciphertextB64\n
    func buildCanonicalBytes(
        messageType: String,
        messageId: String,
        from: String,
        to: String,
        timestamp: Int64,
        nonceB64: String,
        ciphertextB64: String
    ) -> Data {
        let canonical = "v1\n\(messageType)\n\(messageId)\n\(from)\n\(to)\n\(timestamp)\n\(nonceB64)\n\(ciphertextB64)\n"
        return canonical.data(using: .utf8)!
    }

    /// Sign message canonically: Ed25519_Sign(SHA256(canonicalBytes), privateKey)
    func signMessage(
        messageType: String,
        messageId: String,
        from: String,
        to: String,
        timestamp: Int64,
        nonce: Data,
        ciphertext: Data,
        privateKey: Data
    ) throws -> Data {
        let canonicalBytes = buildCanonicalBytes(
            messageType: messageType,
            messageId: messageId,
            from: from,
            to: to,
            timestamp: timestamp,
            nonceB64: nonce.base64EncodedString(),
            ciphertextB64: ciphertext.base64EncodedString()
        )

        let hash = SHA256.hash(data: canonicalBytes)
        let hashData = Data(hash)
        return try sign(message: hashData, privateKey: privateKey)
    }
}
