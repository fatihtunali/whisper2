package com.whisper2.app.conformance

import com.whisper2.app.crypto.KeyDerivation
import com.whisper2.app.crypto.NaClBox
import com.whisper2.app.crypto.Signatures

/**
 * Test Identity
 *
 * Represents a test user with derived keys from mnemonic.
 * Used for conformance testing.
 */
class TestIdentity(
    val name: String,
    val mnemonic: String,
    val deviceId: String
) {
    // Derived seeds
    private val derivedKeys: KeyDerivation.DerivedKeys by lazy {
        KeyDerivation.deriveAll(mnemonic, "")
    }

    // X25519 encryption key pair (derived from encSeed)
    private val encKeyPair: NaClBox.KeyPair by lazy {
        NaClBox.keyPairFromSeed(derivedKeys.encSeed)
    }

    // Ed25519 signing key pair (derived from signSeed)
    private val signKeyPair: Signatures.SigningKeyPair by lazy {
        Signatures.keyPairFromSeed(derivedKeys.signSeed)
    }

    val encPublicKey: ByteArray get() = encKeyPair.publicKey
    val encPrivateKey: ByteArray get() = encKeyPair.privateKey
    val signPublicKey: ByteArray get() = signKeyPair.publicKey
    val signPrivateKey: ByteArray get() = signKeyPair.privateKey
    val contactsKey: ByteArray get() = derivedKeys.contactsKey

    // Registration state (set after register)
    var whisperId: String? = null
        internal set
    var sessionToken: String? = null
        internal set

    val isRegistered: Boolean get() = whisperId != null && sessionToken != null

    fun requireRegistered(): TestIdentity {
        require(isRegistered) { "Identity $name not registered yet" }
        return this
    }

    override fun toString(): String {
        val status = if (isRegistered) "registered" else "unregistered"
        val id = whisperId?.let { ConformanceLogger.maskPii(it) } ?: "none"
        return "TestIdentity($name, whisperId=$id, $status)"
    }

    companion object {
        /**
         * Create Identity A from BuildConfig
         */
        fun createA(): TestIdentity {
            ConformanceConfig.requireValid()
            return TestIdentity(
                name = "UserA",
                mnemonic = ConformanceConfig.TEST_MNEMONIC_1,
                deviceId = ConformanceConfig.DEVICE_ID_A
            )
        }

        /**
         * Create Identity B from BuildConfig
         */
        fun createB(): TestIdentity {
            ConformanceConfig.requireValid()
            return TestIdentity(
                name = "UserB",
                mnemonic = ConformanceConfig.TEST_MNEMONIC_2,
                deviceId = ConformanceConfig.DEVICE_ID_B
            )
        }
    }
}
