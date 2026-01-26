import Foundation
import TweetNacl

/// NaCl SecretBox (XSalsa20-Poly1305) implementation
/// Symmetric authenticated encryption for attachments and backups
/// Uses TweetNaCl for server-compatible crypto

enum NaClSecretBox {

    // MARK: - Constants

    /// SecretBox overhead (Poly1305 tag)
    static let boxOverhead = 16

    /// Nonce length for XSalsa20
    static let nonceLength = 24

    /// Key length
    static let keyLength = 32

    // MARK: - Key Generation

    /// Generate random 32-byte symmetric key
    static func generateKey() -> Data {
        var keyBytes = [UInt8](repeating: 0, count: keyLength)
        _ = SecRandomCopyBytes(kSecRandomDefault, keyLength, &keyBytes)
        return Data(keyBytes)
    }

    // MARK: - Nonce Generation

    /// Generate random 24-byte nonce
    static func generateNonce() -> Data {
        var nonceBytes = [UInt8](repeating: 0, count: nonceLength)
        _ = SecRandomCopyBytes(kSecRandomDefault, nonceLength, &nonceBytes)
        return Data(nonceBytes)
    }

    // MARK: - Encryption

    /// Encrypt data with symmetric key
    /// - Parameters:
    ///   - message: Plaintext to encrypt
    ///   - key: 32-byte symmetric key
    /// - Returns: Tuple of (nonce, ciphertext) where ciphertext includes Poly1305 tag
    static func seal(message: Data, key: Data) throws -> (nonce: Data, ciphertext: Data) {
        guard key.count == keyLength else {
            throw CryptoError.invalidKeyLength
        }

        let nonce = generateNonce()
        let ciphertext = try seal(message: message, nonce: nonce, key: key)

        return (nonce: nonce, ciphertext: ciphertext)
    }

    /// Encrypt data with symmetric key and provided nonce
    /// - Parameters:
    ///   - message: Plaintext to encrypt
    ///   - nonce: 24-byte nonce (must be unique per message)
    ///   - key: 32-byte symmetric key
    /// - Returns: Ciphertext with Poly1305 tag appended
    static func seal(message: Data, nonce: Data, key: Data) throws -> Data {
        guard key.count == keyLength else {
            throw CryptoError.invalidKeyLength
        }
        guard nonce.count == nonceLength else {
            throw CryptoError.invalidNonce
        }

        do {
            let ciphertext = try NaclSecretBox.secretBox(
                message: message,
                nonce: nonce,
                key: key
            )
            return ciphertext
        } catch {
            throw CryptoError.encryptionFailed
        }
    }

    // MARK: - Decryption

    /// Decrypt data with symmetric key
    /// - Parameters:
    ///   - ciphertext: Encrypted data with Poly1305 tag
    ///   - nonce: 24-byte nonce used for encryption
    ///   - key: 32-byte symmetric key
    /// - Returns: Decrypted plaintext
    static func open(ciphertext: Data, nonce: Data, key: Data) throws -> Data {
        guard key.count == keyLength else {
            throw CryptoError.invalidKeyLength
        }
        guard nonce.count == nonceLength else {
            throw CryptoError.invalidNonce
        }
        guard ciphertext.count >= boxOverhead else {
            throw CryptoError.decryptionFailed
        }

        do {
            let plaintext = try NaclSecretBox.open(
                box: ciphertext,
                nonce: nonce,
                key: key
            )
            return plaintext
        } catch {
            throw CryptoError.decryptionFailed
        }
    }

    // MARK: - Convenience

    /// Encrypt and prepend nonce to ciphertext
    static func sealCombined(message: Data, key: Data) throws -> Data {
        let (nonce, ciphertext) = try seal(message: message, key: key)
        return nonce + ciphertext
    }

    /// Decrypt combined nonce + ciphertext
    static func openCombined(combined: Data, key: Data) throws -> Data {
        guard combined.count > nonceLength + boxOverhead else {
            throw CryptoError.decryptionFailed
        }

        let nonce = combined.prefix(nonceLength)
        let ciphertext = combined.suffix(from: nonceLength)

        return try open(ciphertext: ciphertext, nonce: nonce, key: key)
    }
}
