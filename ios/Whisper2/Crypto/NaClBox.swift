import Foundation
import TweetNaClx

/// NaCl Box (Curve25519-XSalsa20-Poly1305) implementation
/// Public-key authenticated encryption
/// Uses TweetNaCl for server-compatible crypto

enum NaClBox {

    // MARK: - Constants

    /// Box overhead (Poly1305 tag)
    static let boxOverhead = 16

    /// Nonce length for XSalsa20
    static let nonceLength = 24

    /// Public/Secret key length
    static let keyLength = 32

    // MARK: - Key Types

    struct KeyPair {
        let publicKey: Data
        let privateKey: Data
    }

    // MARK: - Key Generation

    /// Generate X25519 key pair from 32-byte seed
    static func keyPairFromSeed(_ seed: Data) throws -> KeyPair {
        guard seed.count >= keyLength else {
            throw CryptoError.invalidSeed
        }

        // TweetNaCl box keypair from seed
        let seedBytes = [UInt8](seed.prefix(keyLength))
        guard let keyPair = TweetNaCl.keyPair(fromSeed: seedBytes) else {
            throw CryptoError.keyDerivationFailed
        }

        return KeyPair(
            publicKey: Data(keyPair.publicKey),
            privateKey: Data(keyPair.secretKey)
        )
    }

    /// Generate random X25519 key pair
    static func generateKeyPair() throws -> KeyPair {
        guard let keyPair = TweetNaCl.keyPair() else {
            throw CryptoError.keyDerivationFailed
        }

        return KeyPair(
            publicKey: Data(keyPair.publicKey),
            privateKey: Data(keyPair.secretKey)
        )
    }

    // MARK: - Nonce Generation

    /// Generate random 24-byte nonce
    static func generateNonce() -> Data {
        var nonceBytes = [UInt8](repeating: 0, count: nonceLength)
        _ = SecRandomCopyBytes(kSecRandomDefault, nonceLength, &nonceBytes)
        return Data(nonceBytes)
    }

    // MARK: - Encryption

    /// Encrypt message using recipient's public key and sender's private key
    /// - Parameters:
    ///   - message: Plaintext to encrypt
    ///   - recipientPublicKey: Recipient's X25519 public key (32 bytes)
    ///   - senderPrivateKey: Sender's X25519 private key (32 bytes)
    /// - Returns: Tuple of (nonce, ciphertext) where ciphertext includes Poly1305 tag
    static func seal(
        message: Data,
        recipientPublicKey: Data,
        senderPrivateKey: Data
    ) throws -> (nonce: Data, ciphertext: Data) {
        guard recipientPublicKey.count == keyLength else {
            throw CryptoError.invalidPublicKey
        }
        guard senderPrivateKey.count == keyLength else {
            throw CryptoError.invalidPrivateKey
        }

        // Generate random nonce
        let nonce = generateNonce()

        // Encrypt using TweetNaCl box
        let ciphertext = try sealWithNonce(
            message: message,
            nonce: nonce,
            recipientPublicKey: recipientPublicKey,
            senderPrivateKey: senderPrivateKey
        )

        return (nonce: nonce, ciphertext: ciphertext)
    }

    /// Encrypt with provided nonce
    static func sealWithNonce(
        message: Data,
        nonce: Data,
        recipientPublicKey: Data,
        senderPrivateKey: Data
    ) throws -> Data {
        guard nonce.count == nonceLength else {
            throw CryptoError.invalidNonce
        }
        guard recipientPublicKey.count == keyLength else {
            throw CryptoError.invalidPublicKey
        }
        guard senderPrivateKey.count == keyLength else {
            throw CryptoError.invalidPrivateKey
        }

        let messageBytes = [UInt8](message)
        let nonceBytes = [UInt8](nonce)
        let pubKeyBytes = [UInt8](recipientPublicKey)
        let secKeyBytes = [UInt8](senderPrivateKey)

        guard let ciphertext = TweetNaCl.box(
            message: messageBytes,
            nonce: nonceBytes,
            publicKey: pubKeyBytes,
            secretKey: secKeyBytes
        ) else {
            throw CryptoError.encryptionFailed
        }

        return Data(ciphertext)
    }

    // MARK: - Decryption

    /// Decrypt message using sender's public key and recipient's private key
    /// - Parameters:
    ///   - ciphertext: Encrypted data with Poly1305 tag
    ///   - nonce: 24-byte nonce used for encryption
    ///   - senderPublicKey: Sender's X25519 public key (32 bytes)
    ///   - recipientPrivateKey: Recipient's X25519 private key (32 bytes)
    /// - Returns: Decrypted plaintext
    static func open(
        ciphertext: Data,
        nonce: Data,
        senderPublicKey: Data,
        recipientPrivateKey: Data
    ) throws -> Data {
        guard nonce.count == nonceLength else {
            throw CryptoError.invalidNonce
        }
        guard senderPublicKey.count == keyLength else {
            throw CryptoError.invalidPublicKey
        }
        guard recipientPrivateKey.count == keyLength else {
            throw CryptoError.invalidPrivateKey
        }
        guard ciphertext.count >= boxOverhead else {
            throw CryptoError.decryptionFailed
        }

        let ciphertextBytes = [UInt8](ciphertext)
        let nonceBytes = [UInt8](nonce)
        let pubKeyBytes = [UInt8](senderPublicKey)
        let secKeyBytes = [UInt8](recipientPrivateKey)

        guard let plaintext = TweetNaCl.open(
            box: ciphertextBytes,
            nonce: nonceBytes,
            publicKey: pubKeyBytes,
            secretKey: secKeyBytes
        ) else {
            throw CryptoError.decryptionFailed
        }

        return Data(plaintext)
    }

    // MARK: - Convenience

    /// Encrypt and prepend nonce to ciphertext
    static func sealCombined(
        message: Data,
        recipientPublicKey: Data,
        senderPrivateKey: Data
    ) throws -> Data {
        let (nonce, ciphertext) = try seal(
            message: message,
            recipientPublicKey: recipientPublicKey,
            senderPrivateKey: senderPrivateKey
        )
        return nonce + ciphertext
    }

    /// Decrypt combined nonce + ciphertext
    static func openCombined(
        combined: Data,
        senderPublicKey: Data,
        recipientPrivateKey: Data
    ) throws -> Data {
        guard combined.count > nonceLength + boxOverhead else {
            throw CryptoError.decryptionFailed
        }

        let nonce = combined.prefix(nonceLength)
        let ciphertext = combined.suffix(from: nonceLength)

        return try open(
            ciphertext: ciphertext,
            nonce: nonce,
            senderPublicKey: senderPublicKey,
            recipientPrivateKey: recipientPrivateKey
        )
    }
}
