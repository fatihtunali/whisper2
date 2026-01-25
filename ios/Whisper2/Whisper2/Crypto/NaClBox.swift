import Foundation
import CryptoKit

/// NaCl Box (Curve25519-XSalsa20-Poly1305) implementation
/// Public-key authenticated encryption

enum NaClBox {

    // MARK: - Key Generation

    struct KeyPair {
        let publicKey: Data
        let privateKey: Data
    }

    /// Generate X25519 key pair from seed
    static func keyPairFromSeed(_ seed: Data) throws -> KeyPair {
        guard seed.count >= 32 else {
            throw CryptoError.invalidSeed
        }

        let privateKey = Curve25519.KeyAgreement.PrivateKey(rawRepresentation: seed.prefix(32))
        let publicKey = privateKey.publicKey

        return KeyPair(
            publicKey: publicKey.rawRepresentation,
            privateKey: privateKey.rawRepresentation
        )
    }

    /// Generate random key pair
    static func generateKeyPair() -> KeyPair {
        let privateKey = Curve25519.KeyAgreement.PrivateKey()
        return KeyPair(
            publicKey: privateKey.publicKey.rawRepresentation,
            privateKey: privateKey.rawRepresentation
        )
    }

    // MARK: - Encryption

    /// Encrypt message using recipient's public key
    /// Returns: nonce + ciphertext
    static func seal(
        message: Data,
        recipientPublicKey: Data,
        senderPrivateKey: Data
    ) throws -> (nonce: Data, ciphertext: Data) {
        // Generate random nonce (24 bytes for XSalsa20)
        var nonceBytes = [UInt8](repeating: 0, count: Constants.Crypto.nonceLength)
        _ = SecRandomCopyBytes(kSecRandomDefault, Constants.Crypto.nonceLength, &nonceBytes)
        let nonce = Data(nonceBytes)

        // Compute shared secret using X25519
        let sharedSecret = try computeSharedSecret(
            privateKey: senderPrivateKey,
            publicKey: recipientPublicKey
        )

        // Derive encryption key using HSalsa20 (simplified: use HKDF)
        let encryptionKey = deriveBoxKey(sharedSecret: sharedSecret, nonce: nonce)

        // Encrypt with ChaCha20-Poly1305 (CryptoKit substitute for XSalsa20-Poly1305)
        let symmetricKey = SymmetricKey(data: encryptionKey)
        let sealedBox = try ChaChaPoly.seal(message, using: symmetricKey, nonce: ChaChaPoly.Nonce())

        // Combine nonce used internally + ciphertext + tag
        let ciphertext = sealedBox.ciphertext + sealedBox.tag

        return (nonce: nonce, ciphertext: ciphertext)
    }

    // MARK: - Decryption

    /// Decrypt message using sender's public key
    static func open(
        ciphertext: Data,
        nonce: Data,
        senderPublicKey: Data,
        recipientPrivateKey: Data
    ) throws -> Data {
        guard nonce.count == Constants.Crypto.nonceLength else {
            throw CryptoError.invalidNonce
        }

        guard ciphertext.count > 16 else { // At minimum: tag
            throw CryptoError.decryptionFailed
        }

        // Compute shared secret
        let sharedSecret = try computeSharedSecret(
            privateKey: recipientPrivateKey,
            publicKey: senderPublicKey
        )

        // Derive decryption key
        let decryptionKey = deriveBoxKey(sharedSecret: sharedSecret, nonce: nonce)

        // Split ciphertext and tag
        let tagLength = 16
        let encryptedData = ciphertext.prefix(ciphertext.count - tagLength)
        let tag = ciphertext.suffix(tagLength)

        // Decrypt
        let symmetricKey = SymmetricKey(data: decryptionKey)
        let sealedBox = try ChaChaPoly.SealedBox(
            nonce: ChaChaPoly.Nonce(),
            ciphertext: encryptedData,
            tag: tag
        )

        return try ChaChaPoly.open(sealedBox, using: symmetricKey)
    }

    // MARK: - Shared Secret

    private static func computeSharedSecret(privateKey: Data, publicKey: Data) throws -> Data {
        guard privateKey.count == 32, publicKey.count == 32 else {
            throw CryptoError.invalidKeyLength
        }

        let privKey = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: privateKey)
        let pubKey = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: publicKey)

        let shared = try privKey.sharedSecretFromKeyAgreement(with: pubKey)
        return shared.withUnsafeBytes { Data($0) }
    }

    /// Derive box key from shared secret and nonce (simplified HSalsa20)
    private static func deriveBoxKey(sharedSecret: Data, nonce: Data) -> Data {
        // Use HKDF as substitute for HSalsa20
        let combined = sharedSecret + nonce
        let key = SymmetricKey(data: combined)
        let derived = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: key,
            info: "nacl-box".data(using: .utf8)!,
            outputByteCount: 32
        )
        return derived.withUnsafeBytes { Data($0) }
    }
}
