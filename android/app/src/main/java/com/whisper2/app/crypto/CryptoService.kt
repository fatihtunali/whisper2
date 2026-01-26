package com.whisper2.app.crypto

import android.util.Base64
import com.whisper2.app.core.AuthException
import com.whisper2.app.core.Constants
import com.whisper2.app.core.CryptoException
import com.whisper2.app.domain.model.WhisperID
import com.whisper2.app.storage.SecureStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Main crypto service - single authority for all cryptographic operations
 * Thread-safe singleton (provided via CryptoModule)
 * Uses LazySodium for Box/SecretBox/Sign operations (server-compatible)
 */
class CryptoService(
    private val secureStorage: SecureStorage
) {
    // MARK: - State

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _whisperId = MutableStateFlow<String?>(null)
    val whisperId: StateFlow<String?> = _whisperId.asStateFlow()

    // MARK: - Keys (in-memory only, persisted to secure storage)

    private var encryptionKeyPair: NaClBox.KeyPair? = null
    private var signingKeyPair: Signatures.SigningKeyPair? = null
    private var contactsKey: ByteArray? = null

    // MARK: - Initialization

    /**
     * Initialize from existing secure storage data
     */
    suspend fun initializeFromStorage() {
        val encPrivate = secureStorage.getBytes(Constants.StorageKey.ENC_PRIVATE_KEY)
        val encPublic = secureStorage.getBytes(Constants.StorageKey.ENC_PUBLIC_KEY)
        val signPrivate = secureStorage.getBytes(Constants.StorageKey.SIGN_PRIVATE_KEY)
        val signPublic = secureStorage.getBytes(Constants.StorageKey.SIGN_PUBLIC_KEY)
        val contactsKeyData = secureStorage.getBytes(Constants.StorageKey.CONTACTS_KEY)
        val whisperIdString = secureStorage.getString(Constants.StorageKey.WHISPER_ID)

        if (encPrivate == null || encPublic == null ||
            signPrivate == null || signPublic == null ||
            contactsKeyData == null || whisperIdString == null
        ) {
            throw AuthException.NotAuthenticated()
        }

        this.encryptionKeyPair = NaClBox.KeyPair(encPublic, encPrivate)
        this.signingKeyPair = Signatures.SigningKeyPair(signPublic, signPrivate)
        this.contactsKey = contactsKeyData
        this._whisperId.value = whisperIdString
        this._isInitialized.value = true
    }

    /**
     * Initialize from mnemonic (new account or recovery)
     * NOTE: WhisperID is NOT generated here - it comes from server during registration
     * After successful registration, call setWhisperId() with the server-provided value
     */
    suspend fun initializeFromMnemonic(mnemonic: String) {
        // Validate mnemonic
        if (!KeyDerivation.isValidMnemonic(mnemonic)) {
            throw CryptoException.InvalidMnemonic()
        }

        // Derive all keys
        val derivedKeys = KeyDerivation.deriveAllKeys(mnemonic)

        // Generate key pairs
        val encKP = NaClBox.keyPairFromSeed(derivedKeys.encSeed)
        val signKP = Signatures.keyPairFromSeed(derivedKeys.signSeed)

        // Store keys in secure storage (but NOT whisperId - that comes from server)
        secureStorage.setBytes(Constants.StorageKey.ENC_PRIVATE_KEY, encKP.privateKey)
        secureStorage.setBytes(Constants.StorageKey.ENC_PUBLIC_KEY, encKP.publicKey)
        secureStorage.setBytes(Constants.StorageKey.SIGN_PRIVATE_KEY, signKP.privateKey)
        secureStorage.setBytes(Constants.StorageKey.SIGN_PUBLIC_KEY, signKP.publicKey)
        secureStorage.setBytes(Constants.StorageKey.CONTACTS_KEY, derivedKeys.contactsKey)

        // Set in-memory
        this.encryptionKeyPair = encKP
        this.signingKeyPair = signKP
        this.contactsKey = derivedKeys.contactsKey
        // whisperId will be set later via setWhisperId() after server registration
        this._isInitialized.value = true
    }

    /**
     * Set WhisperID after receiving from server
     * Called after successful registration or recovery
     */
    suspend fun setWhisperId(whisperId: String) {
        if (!WhisperID.isValid(whisperId)) {
            throw CryptoException.InvalidWhisperId()
        }
        this._whisperId.value = whisperId
        secureStorage.setString(Constants.StorageKey.WHISPER_ID, whisperId)
    }

    /**
     * Generate new mnemonic
     */
    fun generateMnemonic(): String {
        return KeyDerivation.generateMnemonic()
    }

    // MARK: - Public Key Access

    val encryptionPublicKey: ByteArray?
        get() = encryptionKeyPair?.publicKey

    val encryptionPublicKeyBase64: String?
        get() = encryptionKeyPair?.publicKey?.let {
            Base64.encodeToString(it, Base64.NO_WRAP)
        }

    val signingPublicKey: ByteArray?
        get() = signingKeyPair?.publicKey

    val signingPublicKeyBase64: String?
        get() = signingKeyPair?.publicKey?.let {
            Base64.encodeToString(it, Base64.NO_WRAP)
        }

    // MARK: - Box Encryption (for messages)

    /**
     * Encrypt message for recipient
     */
    fun boxSeal(message: ByteArray, recipientPublicKey: ByteArray): Pair<ByteArray, ByteArray> {
        val keyPair = encryptionKeyPair ?: throw CryptoException.InvalidPrivateKey()
        return NaClBox.seal(message, recipientPublicKey, keyPair.privateKey)
    }

    /**
     * Decrypt message from sender
     */
    fun boxOpen(ciphertext: ByteArray, nonce: ByteArray, senderPublicKey: ByteArray): ByteArray {
        val keyPair = encryptionKeyPair ?: throw CryptoException.InvalidPrivateKey()
        return NaClBox.open(ciphertext, nonce, senderPublicKey, keyPair.privateKey)
    }

    // MARK: - SecretBox Encryption (for attachments/backups)

    /**
     * Encrypt with contacts key (for backup)
     */
    fun secretBoxSealWithContactsKey(message: ByteArray): Pair<ByteArray, ByteArray> {
        val key = contactsKey ?: throw CryptoException.InvalidPrivateKey()
        return NaClSecretBox.seal(message, key)
    }

    /**
     * Decrypt with contacts key (for backup)
     */
    fun secretBoxOpenWithContactsKey(ciphertext: ByteArray, nonce: ByteArray): ByteArray {
        val key = contactsKey ?: throw CryptoException.InvalidPrivateKey()
        return NaClSecretBox.open(ciphertext, nonce, key)
    }

    /**
     * Encrypt with random key (for attachments)
     */
    fun secretBoxSeal(message: ByteArray, key: ByteArray): Pair<ByteArray, ByteArray> {
        return NaClSecretBox.seal(message, key)
    }

    /**
     * Decrypt with provided key
     */
    fun secretBoxOpen(ciphertext: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        return NaClSecretBox.open(ciphertext, nonce, key)
    }

    /**
     * Generate random file key
     */
    fun generateFileKey(): ByteArray {
        return NaClSecretBox.generateKey()
    }

    // MARK: - Signing

    /**
     * Sign data
     */
    fun sign(message: ByteArray): ByteArray {
        val keyPair = signingKeyPair ?: throw CryptoException.InvalidPrivateKey()
        return Signatures.sign(message, keyPair.privateKey)
    }

    /**
     * Sign and return base64
     */
    fun signBase64(message: ByteArray): String {
        return Base64.encodeToString(sign(message), Base64.NO_WRAP)
    }

    /**
     * Verify signature
     */
    fun verify(signature: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean {
        return Signatures.verify(signature, message, publicKey)
    }

    // MARK: - Canonical Signing

    /**
     * Sign message canonically
     */
    fun signCanonical(
        messageType: String,
        messageId: String,
        to: String,
        timestamp: Long,
        nonce: ByteArray,
        ciphertext: ByteArray
    ): String {
        val keyPair = signingKeyPair ?: throw CryptoException.InvalidPrivateKey()
        val wid = _whisperId.value ?: throw CryptoException.InvalidPrivateKey()

        return CanonicalSigning.signCanonicalBase64(
            messageType = messageType,
            messageId = messageId,
            from = wid,
            to = to,
            timestamp = timestamp,
            nonce = nonce,
            ciphertext = ciphertext,
            privateKey = keyPair.privateKey
        )
    }

    /**
     * Verify canonical signature
     */
    fun verifyCanonical(
        signatureB64: String,
        messageType: String,
        messageId: String,
        from: String,
        to: String,
        timestamp: Long,
        nonceB64: String,
        ciphertextB64: String,
        senderPublicKey: ByteArray
    ): Boolean {
        return CanonicalSigning.verifyCanonicalBase64(
            signatureB64 = signatureB64,
            messageType = messageType,
            messageId = messageId,
            from = from,
            to = to,
            timestamp = timestamp,
            nonceB64 = nonceB64,
            ciphertextB64 = ciphertextB64,
            publicKey = senderPublicKey
        )
    }

    // MARK: - Challenge Signing (auth)

    /**
     * Sign registration challenge
     */
    fun signChallenge(challengeB64: String): String {
        val keyPair = signingKeyPair ?: throw CryptoException.InvalidPrivateKey()
        return CanonicalSigning.signChallengeBase64(challengeB64, keyPair.privateKey)
    }

    // MARK: - Cleanup

    /**
     * Clear all keys from memory and secure storage
     */
    suspend fun clear() {
        encryptionKeyPair = null
        signingKeyPair = null
        contactsKey = null
        _whisperId.value = null
        _isInitialized.value = false

        // Clear from secure storage
        secureStorage.remove(Constants.StorageKey.ENC_PRIVATE_KEY)
        secureStorage.remove(Constants.StorageKey.ENC_PUBLIC_KEY)
        secureStorage.remove(Constants.StorageKey.SIGN_PRIVATE_KEY)
        secureStorage.remove(Constants.StorageKey.SIGN_PUBLIC_KEY)
        secureStorage.remove(Constants.StorageKey.CONTACTS_KEY)
        secureStorage.remove(Constants.StorageKey.WHISPER_ID)
    }
}
