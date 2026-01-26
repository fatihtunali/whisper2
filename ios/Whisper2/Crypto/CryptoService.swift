import Foundation
import CryptoKit  // Still needed for SHA256 in CanonicalSigning

/// Main crypto service - single authority for all cryptographic operations
/// Thread-safe singleton
/// Uses TweetNaCl for Box/SecretBox/Sign operations (server-compatible)

@MainActor
final class CryptoService: ObservableObject {

    static let shared = CryptoService()

    // MARK: - Published State

    @Published private(set) var isInitialized = false
    @Published private(set) var whisperId: String?

    // MARK: - Keys (in-memory only, persisted to Keychain)

    private var encryptionKeyPair: NaClBox.KeyPair?
    private var signingKeyPair: Signatures.SigningKeyPair?
    private var contactsKey: Data?

    private init() {}

    // MARK: - Initialization

    /// Initialize from existing keychain data
    func initializeFromKeychain() async throws {
        guard let encPrivate = KeychainService.shared.getData(forKey: Constants.StorageKey.encPrivateKey),
              let encPublic = KeychainService.shared.getData(forKey: Constants.StorageKey.encPublicKey),
              let signPrivate = KeychainService.shared.getData(forKey: Constants.StorageKey.signPrivateKey),
              let signPublic = KeychainService.shared.getData(forKey: Constants.StorageKey.signPublicKey),
              let contactsKeyData = KeychainService.shared.getData(forKey: Constants.StorageKey.contactsKey),
              let whisperIdString = KeychainService.shared.whisperId else {
            throw AuthError.notAuthenticated
        }

        self.encryptionKeyPair = NaClBox.KeyPair(publicKey: encPublic, privateKey: encPrivate)
        self.signingKeyPair = Signatures.SigningKeyPair(publicKey: signPublic, privateKey: signPrivate)
        self.contactsKey = contactsKeyData
        self.whisperId = whisperIdString
        self.isInitialized = true

        logger.info("CryptoService initialized from keychain", category: .crypto)
    }

    /// Initialize from mnemonic (new account or recovery)
    /// NOTE: WhisperID is NOT generated here - it comes from server during registration
    /// After successful registration, call setWhisperId() with the server-provided value
    func initializeFromMnemonic(_ mnemonic: String) async throws {
        // Validate mnemonic
        guard KeyDerivation.isValidMnemonic(mnemonic) else {
            throw CryptoError.invalidMnemonic
        }

        // Derive all keys
        let derivedKeys = try KeyDerivation.deriveAllKeys(from: mnemonic)

        // Generate key pairs
        let encKP = try NaClBox.keyPairFromSeed(derivedKeys.encryptionSeed)
        let signKP = try Signatures.keyPairFromSeed(derivedKeys.signingSeed)

        // Store keys in keychain (but NOT whisperId - that comes from server)
        try KeychainService.shared.setData(encKP.privateKey, forKey: Constants.StorageKey.encPrivateKey)
        try KeychainService.shared.setData(encKP.publicKey, forKey: Constants.StorageKey.encPublicKey)
        try KeychainService.shared.setData(signKP.privateKey, forKey: Constants.StorageKey.signPrivateKey)
        try KeychainService.shared.setData(signKP.publicKey, forKey: Constants.StorageKey.signPublicKey)
        try KeychainService.shared.setData(derivedKeys.contactsKey, forKey: Constants.StorageKey.contactsKey)

        // Set in-memory
        self.encryptionKeyPair = encKP
        self.signingKeyPair = signKP
        self.contactsKey = derivedKeys.contactsKey
        // whisperId will be set later via setWhisperId() after server registration
        self.isInitialized = true

        logger.info("CryptoService initialized from mnemonic (awaiting whisperId from server)", category: .crypto)
    }

    /// Set WhisperID after receiving from server
    /// Called after successful registration or recovery
    func setWhisperId(_ whisperId: String) throws {
        guard WhisperID.isValid(whisperId) else {
            throw CryptoError.invalidWhisperId
        }
        self.whisperId = whisperId
        KeychainService.shared.whisperId = whisperId
        logger.info("WhisperID set: \(whisperId)", category: .crypto)
    }

    /// Generate new mnemonic
    func generateMnemonic() -> String {
        KeyDerivation.generateMnemonic()
    }

    // MARK: - Public Key Access

    var encryptionPublicKey: Data? {
        encryptionKeyPair?.publicKey
    }

    var encryptionPublicKeyBase64: String? {
        encryptionKeyPair?.publicKey.base64EncodedString()
    }

    var signingPublicKey: Data? {
        signingKeyPair?.publicKey
    }

    var signingPublicKeyBase64: String? {
        signingKeyPair?.publicKey.base64EncodedString()
    }

    // MARK: - Box Encryption (for messages)

    /// Encrypt message for recipient
    func boxSeal(message: Data, recipientPublicKey: Data) throws -> (nonce: Data, ciphertext: Data) {
        guard let keyPair = encryptionKeyPair else {
            throw CryptoError.invalidPrivateKey
        }
        return try NaClBox.seal(
            message: message,
            recipientPublicKey: recipientPublicKey,
            senderPrivateKey: keyPair.privateKey
        )
    }

    /// Decrypt message from sender
    func boxOpen(ciphertext: Data, nonce: Data, senderPublicKey: Data) throws -> Data {
        guard let keyPair = encryptionKeyPair else {
            throw CryptoError.invalidPrivateKey
        }
        return try NaClBox.open(
            ciphertext: ciphertext,
            nonce: nonce,
            senderPublicKey: senderPublicKey,
            recipientPrivateKey: keyPair.privateKey
        )
    }

    // MARK: - SecretBox Encryption (for attachments/backups)

    /// Encrypt with contacts key (for backup)
    func secretBoxSealWithContactsKey(message: Data) throws -> (nonce: Data, ciphertext: Data) {
        guard let key = contactsKey else {
            throw CryptoError.invalidPrivateKey
        }
        return try NaClSecretBox.seal(message: message, key: key)
    }

    /// Decrypt with contacts key (for backup)
    func secretBoxOpenWithContactsKey(ciphertext: Data, nonce: Data) throws -> Data {
        guard let key = contactsKey else {
            throw CryptoError.invalidPrivateKey
        }
        return try NaClSecretBox.open(ciphertext: ciphertext, nonce: nonce, key: key)
    }

    /// Encrypt with random key (for attachments)
    func secretBoxSeal(message: Data, key: Data) throws -> (nonce: Data, ciphertext: Data) {
        try NaClSecretBox.seal(message: message, key: key)
    }

    /// Decrypt with provided key
    func secretBoxOpen(ciphertext: Data, nonce: Data, key: Data) throws -> Data {
        try NaClSecretBox.open(ciphertext: ciphertext, nonce: nonce, key: key)
    }

    /// Generate random file key
    func generateFileKey() -> Data {
        NaClSecretBox.generateKey()
    }

    // MARK: - Signing

    /// Sign data
    func sign(message: Data) throws -> Data {
        guard let keyPair = signingKeyPair else {
            throw CryptoError.invalidPrivateKey
        }
        return try Signatures.sign(message: message, privateKey: keyPair.privateKey)
    }

    /// Sign and return base64
    func signBase64(message: Data) throws -> String {
        try sign(message: message).base64EncodedString()
    }

    /// Verify signature
    func verify(signature: Data, message: Data, publicKey: Data) -> Bool {
        Signatures.verify(signature: signature, message: message, publicKey: publicKey)
    }

    // MARK: - Canonical Signing

    /// Sign message canonically
    func signCanonical(
        messageType: String,
        messageId: String,
        to: String,
        timestamp: Int64,
        nonce: Data,
        ciphertext: Data
    ) throws -> String {
        guard let keyPair = signingKeyPair, let wid = whisperId else {
            throw CryptoError.invalidPrivateKey
        }

        return try CanonicalSigning.signCanonicalBase64(
            messageType: messageType,
            messageId: messageId,
            from: wid,
            to: to,
            timestamp: timestamp,
            nonce: nonce,
            ciphertext: ciphertext,
            privateKey: keyPair.privateKey
        )
    }

    /// Verify canonical signature
    func verifyCanonical(
        signatureB64: String,
        messageType: String,
        messageId: String,
        from: String,
        to: String,
        timestamp: Int64,
        nonceB64: String,
        ciphertextB64: String,
        senderPublicKey: Data
    ) -> Bool {
        CanonicalSigning.verifyCanonicalBase64(
            signatureB64: signatureB64,
            messageType: messageType,
            messageId: messageId,
            from: from,
            to: to,
            timestamp: timestamp,
            nonceB64: nonceB64,
            ciphertextB64: ciphertextB64,
            publicKey: senderPublicKey
        )
    }

    // MARK: - Challenge Signing (auth)

    /// Sign registration challenge
    func signChallenge(_ challengeB64: String) throws -> String {
        guard let keyPair = signingKeyPair else {
            throw CryptoError.invalidPrivateKey
        }
        return try CanonicalSigning.signChallengeBase64(challengeB64, privateKey: keyPair.privateKey)
    }

    // MARK: - Cleanup

    /// Clear all keys from memory and keychain
    func clear() {
        encryptionKeyPair = nil
        signingKeyPair = nil
        contactsKey = nil
        whisperId = nil
        isInitialized = false

        // Clear from keychain
        KeychainService.shared.delete(forKey: Constants.StorageKey.encPrivateKey)
        KeychainService.shared.delete(forKey: Constants.StorageKey.encPublicKey)
        KeychainService.shared.delete(forKey: Constants.StorageKey.signPrivateKey)
        KeychainService.shared.delete(forKey: Constants.StorageKey.signPublicKey)
        KeychainService.shared.delete(forKey: Constants.StorageKey.contactsKey)
        KeychainService.shared.delete(forKey: Constants.StorageKey.whisperId)

        logger.info("CryptoService cleared", category: .crypto)
    }
}
