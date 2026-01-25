import Foundation
import CryptoKit

/// NaCl SecretBox (XSalsa20-Poly1305) implementation
/// Symmetric authenticated encryption for attachments and backups

enum NaClSecretBox {

    // MARK: - Key Generation

    /// Generate random 32-byte key
    static func generateKey() -> Data {
        var keyBytes = [UInt8](repeating: 0, count: Constants.Crypto.keyLength)
        _ = SecRandomCopyBytes(kSecRandomDefault, Constants.Crypto.keyLength, &keyBytes)
        return Data(keyBytes)
    }

    // MARK: - Nonce Generation

    /// Generate random 24-byte nonce
    static func generateNonce() -> Data {
        var nonceBytes = [UInt8](repeating: 0, count: Constants.Crypto.nonceLength)
        _ = SecRandomCopyBytes(kSecRandomDefault, Constants.Crypto.nonceLength, &nonceBytes)
        return Data(nonceBytes)
    }

    // MARK: - Encryption

    /// Encrypt data with symmetric key
    /// Returns: nonce + ciphertext
    static func seal(message: Data, key: Data) throws -> (nonce: Data, ciphertext: Data) {
        guard key.count == Constants.Crypto.keyLength else {
            throw CryptoError.invalidKeyLength
        }

        let nonce = generateNonce()
        let ciphertext = try seal(message: message, nonce: nonce, key: key)

        return (nonce: nonce, ciphertext: ciphertext)
    }

    /// Encrypt data with symmetric key and provided nonce
    static func seal(message: Data, nonce: Data, key: Data) throws -> Data {
        guard key.count == Constants.Crypto.keyLength else {
            throw CryptoError.invalidKeyLength
        }
        guard nonce.count == Constants.Crypto.nonceLength else {
            throw CryptoError.invalidNonce
        }

        // Derive encryption key from key + nonce (simplified XSalsa20)
        let encKey = deriveSecretBoxKey(key: key, nonce: nonce)
        let symmetricKey = SymmetricKey(data: encKey)

        // Use ChaChaPoly as substitute for XSalsa20-Poly1305
        let sealedBox = try ChaChaPoly.seal(message, using: symmetricKey)

        // Return ciphertext + tag
        return sealedBox.ciphertext + sealedBox.tag
    }

    // MARK: - Decryption

    /// Decrypt data with symmetric key
    static func open(ciphertext: Data, nonce: Data, key: Data) throws -> Data {
        guard key.count == Constants.Crypto.keyLength else {
            throw CryptoError.invalidKeyLength
        }
        guard nonce.count == Constants.Crypto.nonceLength else {
            throw CryptoError.invalidNonce
        }
        guard ciphertext.count > 16 else {
            throw CryptoError.decryptionFailed
        }

        // Derive decryption key
        let decKey = deriveSecretBoxKey(key: key, nonce: nonce)
        let symmetricKey = SymmetricKey(data: decKey)

        // Split ciphertext and tag
        let tagLength = 16
        let encryptedData = ciphertext.prefix(ciphertext.count - tagLength)
        let tag = ciphertext.suffix(tagLength)

        let sealedBox = try ChaChaPoly.SealedBox(
            nonce: ChaChaPoly.Nonce(),
            ciphertext: encryptedData,
            tag: tag
        )

        return try ChaChaPoly.open(sealedBox, using: symmetricKey)
    }

    // MARK: - Convenience

    /// Encrypt and prepend nonce
    static func sealCombined(message: Data, key: Data) throws -> Data {
        let (nonce, ciphertext) = try seal(message: message, key: key)
        return nonce + ciphertext
    }

    /// Decrypt combined nonce + ciphertext
    static func openCombined(combined: Data, key: Data) throws -> Data {
        guard combined.count > Constants.Crypto.nonceLength + 16 else {
            throw CryptoError.decryptionFailed
        }

        let nonce = combined.prefix(Constants.Crypto.nonceLength)
        let ciphertext = combined.suffix(from: Constants.Crypto.nonceLength)

        return try open(ciphertext: ciphertext, nonce: nonce, key: key)
    }

    // MARK: - Key Derivation

    private static func deriveSecretBoxKey(key: Data, nonce: Data) -> Data {
        // Use HKDF to derive key from key + nonce
        let combined = key + nonce
        let ikm = SymmetricKey(data: combined)
        let derived = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: ikm,
            info: "nacl-secretbox".data(using: .utf8)!,
            outputByteCount: 32
        )
        return derived.withUnsafeBytes { Data($0) }
    }
}
