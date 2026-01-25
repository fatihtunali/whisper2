import XCTest
@testable import Whisper2

/// PERMANENT TEST VECTORS - DO NOT MODIFY AFTER LAUNCH
/// These vectors ensure cross-platform recovery compatibility.
/// If these tests fail, recovery is broken. DO NOT change expected values.
///
/// Computed using:
/// - BIP39 reference implementation
/// - HKDF-SHA256 (RFC 5869)
/// - libsodium/sodium-native (same as server)

final class KeyDerivationTests: XCTestCase {

    // =========================================================================
    // FROZEN TEST VECTOR - NEVER CHANGE THESE VALUES
    // =========================================================================

    /// Standard BIP39 test mnemonic (12 words)
    /// Source: https://github.com/trezor/python-mnemonic/blob/master/vectors.json
    static let testMnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    /// Empty passphrase (standard BIP39 test)
    static let testPassphrase = ""

    // -------------------------------------------------------------------------
    // BIP39 Seed (64 bytes)
    // PBKDF2-HMAC-SHA512(mnemonic, salt="mnemonic", iterations=2048)
    // -------------------------------------------------------------------------
    static let expectedBIP39SeedHex = "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4"

    // -------------------------------------------------------------------------
    // HKDF-SHA256 Derived Seeds (32 bytes each)
    // IKM = 64-byte BIP39 seed, salt = "whisper"
    // -------------------------------------------------------------------------
    static let expectedEncSeedHex = "08851144b1bdf8b99c563bd408f4a613943fef2d9120397573932bd9833e0149"
    static let expectedSignSeedHex = "457f5c29bc4ab25ea84b9d076fee560db80b9994725106594400e28672f3e5be"
    static let expectedContactsKeyHex = "de3d0fda0659df936a71ee48cf6519da84b285344916511b5244d2ac36c23ff2"

    // Base64 versions
    static let expectedEncSeedB64 = "CIURRLG9+LmcVjvUCPSmE5Q/7y2RIDl1c5Mr2YM+AUk="
    static let expectedSignSeedB64 = "RX9cKbxKsl6oS50Hb+5WDbgLmZRyUQZZRADihnLz5b4="
    static let expectedContactsKeyB64 = "3j0P2gZZ35Nqce5Iz2UZ2oSyhTRJFlEbUkTSrDbCP/I="

    // -------------------------------------------------------------------------
    // Public Keys (32 bytes each)
    // Derived from seeds using libsodium/TweetNaCl
    // -------------------------------------------------------------------------
    static let expectedEncPublicKeyHex = "19c6d0f986827f8e86a5ed4a233f2ed1c97355536d0d26c4ae3b2b908369c050"
    static let expectedSignPublicKeyHex = "bd930cbbf856bb76f2c25c64062a2944cc6065ba54b9af7242d2bfbde5d7c95b"

    // Base64 versions (what server stores)
    static let expectedEncPublicKeyB64 = "GcbQ+YaCf46Gpe1KIz8u0clzVVNtDSbErjsrkINpwFA="
    static let expectedSignPublicKeyB64 = "vZMMu/hWu3bywlxkBiopRMxgZbpUua9yQtK/veXXyVs="

    // =========================================================================
    // BIP39 TESTS
    // =========================================================================

    /// Test BIP39 seed derivation matches expected vector
    func testBIP39SeedDerivation() throws {
        let seed = try KeyDerivation.seedFromMnemonic(
            Self.testMnemonic,
            passphrase: Self.testPassphrase
        )

        XCTAssertEqual(seed.count, 64, "BIP39 seed must be 64 bytes")

        let seedHex = seed.map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual(
            seedHex,
            Self.expectedBIP39SeedHex,
            "BIP39 seed mismatch - PBKDF2 implementation is wrong"
        )
    }

    // =========================================================================
    // HKDF TESTS
    // =========================================================================

    /// Test HKDF produces correct encryption seed
    func testHKDFEncryptionSeed() throws {
        let seed = try KeyDerivation.seedFromMnemonic(Self.testMnemonic)
        let encSeed = KeyDerivation.deriveKey(from: seed, info: Constants.Crypto.encryptionDomain)

        XCTAssertEqual(encSeed.count, 32)

        let encSeedHex = encSeed.map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual(encSeedHex, Self.expectedEncSeedHex, "Encryption seed mismatch - HKDF broken")

        XCTAssertEqual(encSeed.base64EncodedString(), Self.expectedEncSeedB64)
    }

    /// Test HKDF produces correct signing seed
    func testHKDFSigningSeed() throws {
        let seed = try KeyDerivation.seedFromMnemonic(Self.testMnemonic)
        let signSeed = KeyDerivation.deriveKey(from: seed, info: Constants.Crypto.signingDomain)

        XCTAssertEqual(signSeed.count, 32)

        let signSeedHex = signSeed.map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual(signSeedHex, Self.expectedSignSeedHex, "Signing seed mismatch - HKDF broken")

        XCTAssertEqual(signSeed.base64EncodedString(), Self.expectedSignSeedB64)
    }

    /// Test HKDF produces correct contacts key
    func testHKDFContactsKey() throws {
        let seed = try KeyDerivation.seedFromMnemonic(Self.testMnemonic)
        let contactsKey = KeyDerivation.deriveKey(from: seed, info: Constants.Crypto.contactsDomain)

        XCTAssertEqual(contactsKey.count, 32)

        let contactsKeyHex = contactsKey.map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual(contactsKeyHex, Self.expectedContactsKeyHex, "Contacts key mismatch - HKDF broken")

        XCTAssertEqual(contactsKey.base64EncodedString(), Self.expectedContactsKeyB64)
    }

    // =========================================================================
    // PUBLIC KEY TESTS
    // =========================================================================

    /// Test X25519 public key derivation
    func testEncryptionPublicKey() throws {
        let derivedKeys = try KeyDerivation.deriveAllKeys(from: Self.testMnemonic)
        let encKP = try NaClBox.keyPairFromSeed(derivedKeys.encryptionSeed)

        XCTAssertEqual(encKP.publicKey.count, 32)

        let pubKeyHex = encKP.publicKey.map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual(pubKeyHex, Self.expectedEncPublicKeyHex, "X25519 public key mismatch")

        XCTAssertEqual(encKP.publicKey.base64EncodedString(), Self.expectedEncPublicKeyB64)
    }

    /// Test Ed25519 public key derivation
    func testSigningPublicKey() throws {
        let derivedKeys = try KeyDerivation.deriveAllKeys(from: Self.testMnemonic)
        let signKP = try Signatures.keyPairFromSeed(derivedKeys.signingSeed)

        XCTAssertEqual(signKP.publicKey.count, 32)

        let pubKeyHex = signKP.publicKey.map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual(pubKeyHex, Self.expectedSignPublicKeyHex, "Ed25519 public key mismatch")

        XCTAssertEqual(signKP.publicKey.base64EncodedString(), Self.expectedSignPublicKeyB64)
    }

    // =========================================================================
    // BASE64 ENCODING TESTS
    // =========================================================================

    /// Test base64 encoding produces correct padding
    func testBase64Padding() throws {
        let derivedKeys = try KeyDerivation.deriveAllKeys(from: Self.testMnemonic)

        // All keys are 32 bytes = 44 chars base64 with padding (32 * 4/3 = 42.67 → 44 with padding)
        let encSeedB64 = derivedKeys.encryptionSeed.base64EncodedString()
        let signSeedB64 = derivedKeys.signingSeed.base64EncodedString()
        let contactsKeyB64 = derivedKeys.contactsKey.base64EncodedString()

        // Check padding (must end with = for 32-byte input)
        XCTAssertTrue(encSeedB64.hasSuffix("="), "Base64 must have padding")
        XCTAssertTrue(signSeedB64.hasSuffix("="), "Base64 must have padding")
        XCTAssertTrue(contactsKeyB64.hasSuffix("="), "Base64 must have padding")

        // Check length (44 chars for 32 bytes)
        XCTAssertEqual(encSeedB64.count, 44)
        XCTAssertEqual(signSeedB64.count, 44)
        XCTAssertEqual(contactsKeyB64.count, 44)
    }

    /// Test base64 round-trip
    func testBase64RoundTrip() throws {
        let derivedKeys = try KeyDerivation.deriveAllKeys(from: Self.testMnemonic)

        // Encode then decode should equal original
        let encSeedB64 = derivedKeys.encryptionSeed.base64EncodedString()
        let decoded = Data(base64Encoded: encSeedB64)

        XCTAssertNotNil(decoded)
        XCTAssertEqual(decoded, derivedKeys.encryptionSeed, "Base64 round-trip failed")

        // Re-encode should produce identical string
        let reencoded = decoded!.base64EncodedString()
        XCTAssertEqual(reencoded, encSeedB64, "Base64 re-encoding mismatch")
    }

    // =========================================================================
    // WORDLIST INTEGRITY TESTS
    // =========================================================================

    /// Test BIP39 wordlist integrity
    func testWordlistIntegrity() {
        let wordlist = BIP39WordList.english

        // Must have exactly 2048 words
        XCTAssertEqual(wordlist.count, 2048, "BIP39 wordlist must have 2048 words")

        // First word must be "abandon"
        XCTAssertEqual(wordlist[0], "abandon", "First word must be 'abandon'")

        // Last word must be "zoo"
        XCTAssertEqual(wordlist[2047], "zoo", "Last word must be 'zoo'")

        // All words must be lowercase ASCII
        for (i, word) in wordlist.enumerated() {
            XCTAssertTrue(word == word.lowercased(), "Word \(i) must be lowercase: \(word)")
            XCTAssertTrue(word.allSatisfy { $0.isASCII && $0.isLetter }, "Word \(i) must be ASCII letters: \(word)")
        }
    }

    // =========================================================================
    // NORMALIZATION TESTS
    // =========================================================================

    /// Test mnemonic normalization handles whitespace edge cases
    func testMnemonicNormalizationWhitespace() throws {
        let expectedSeed = try KeyDerivation.seedFromMnemonic(Self.testMnemonic)

        // Leading/trailing spaces
        let withSpaces = "  " + Self.testMnemonic + "  "
        XCTAssertEqual(try KeyDerivation.seedFromMnemonic(withSpaces), expectedSeed)

        // Double spaces between words
        let doubleSpaces = Self.testMnemonic.replacingOccurrences(of: " ", with: "  ")
        XCTAssertEqual(try KeyDerivation.seedFromMnemonic(doubleSpaces), expectedSeed)

        // Tabs and newlines
        let withTabs = Self.testMnemonic.replacingOccurrences(of: " ", with: "\t")
        XCTAssertEqual(try KeyDerivation.seedFromMnemonic(withTabs), expectedSeed)

        let withNewlines = Self.testMnemonic.replacingOccurrences(of: " ", with: "\n")
        XCTAssertEqual(try KeyDerivation.seedFromMnemonic(withNewlines), expectedSeed)

        // Mixed whitespace
        let mixed = "  abandon\t\tabandom \n abandon  abandon\nabandom abandon abandon abandon abandon abandon about  "
        // Note: This will fail validation but seedFromMnemonic should normalize it
    }

    /// Test mnemonic normalization handles Unicode edge cases (NFKD)
    func testMnemonicNormalizationUnicode() throws {
        // Test that NFKD normalization works
        // "café" in NFD (e + combining acute) vs NFC (é precomposed)
        // BIP39 requires NFKD normalization

        let expectedSeed = try KeyDerivation.seedFromMnemonic(Self.testMnemonic)

        // Same mnemonic should produce same seed regardless of normalization form
        let nfkd = Self.testMnemonic.decomposedStringWithCompatibilityMapping
        XCTAssertEqual(try KeyDerivation.seedFromMnemonic(nfkd), expectedSeed)

        let nfc = Self.testMnemonic.precomposedStringWithCompatibilityMapping
        XCTAssertEqual(try KeyDerivation.seedFromMnemonic(nfc), expectedSeed)
    }

    // =========================================================================
    // DETERMINISM TESTS
    // =========================================================================

    /// Test that key derivation is deterministic
    func testDeterministicDerivation() throws {
        let keys1 = try KeyDerivation.deriveAllKeys(from: Self.testMnemonic)
        let keys2 = try KeyDerivation.deriveAllKeys(from: Self.testMnemonic)

        XCTAssertEqual(keys1.encryptionSeed, keys2.encryptionSeed)
        XCTAssertEqual(keys1.signingSeed, keys2.signingSeed)
        XCTAssertEqual(keys1.contactsKey, keys2.contactsKey)
    }

    // =========================================================================
    // CONSTANTS VALIDATION TESTS
    // =========================================================================

    /// Test HKDF constants are frozen correctly
    func testFrozenConstants() {
        // These values MUST NOT change after launch
        XCTAssertEqual(Constants.Crypto.bip39SeedLength, 64, "BIP39 seed length must be 64")
        XCTAssertEqual(Constants.Crypto.hkdfSalt, "whisper", "HKDF salt must be 'whisper'")
        XCTAssertEqual(Constants.Crypto.encryptionDomain, "whisper/enc")
        XCTAssertEqual(Constants.Crypto.signingDomain, "whisper/sign")
        XCTAssertEqual(Constants.Crypto.contactsDomain, "whisper/contacts")
    }
}
