import Foundation
import TweetNacl

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

        // TweetNaCl box keypair from secret key (seed)
        let seedData = seed.prefix(keyLength)
        let keyPair = try NaclBox.keyPair(fromSecretKey: Data(seedData))

        return KeyPair(
            publicKey: keyPair.publicKey,
            privateKey: keyPair.secretKey
        )
    }

    /// Generate random X25519 key pair
    static func generateKeyPair() throws -> KeyPair {
        let keyPair = try NaclBox.keyPair()

        return KeyPair(
            publicKey: keyPair.publicKey,
            privateKey: keyPair.secretKey
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

        do {
            let ciphertext = try NaclBox.box(
                message: message,
                nonce: nonce,
                publicKey: recipientPublicKey,
                secretKey: senderPrivateKey
            )
            return ciphertext
        } catch {
            throw CryptoError.encryptionFailed
        }
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

        do {
            let plaintext = try NaclBox.open(
                message: ciphertext,
                nonce: nonce,
                publicKey: senderPublicKey,
                secretKey: recipientPrivateKey
            )
            return plaintext
        } catch {
            throw CryptoError.decryptionFailed
        }
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
