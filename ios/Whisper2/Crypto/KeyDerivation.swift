import Foundation
import CryptoKit

/// Key derivation using BIP39 mnemonic and HKDF
/// Matches server implementation in crypto.ts

enum KeyDerivation {

    // MARK: - BIP39 Word List
    /// Full 2048-word BIP39 English word list
    /// Loaded from BIP39WordList.swift
    private static var wordList: [String] { BIP39WordList.english }

    // MARK: - Generate Mnemonic

    /// Generate a new 12-word mnemonic phrase
    /// 128 bits of entropy = 12 words (standard for most wallets)
    static func generateMnemonic() -> String {
        // 16 bytes = 128 bits entropy → 12 words
        var randomBytes = [UInt8](repeating: 0, count: 16)
        _ = SecRandomCopyBytes(kSecRandomDefault, 16, &randomBytes)
        return mnemonicFromEntropy(Data(randomBytes))
    }

    /// Convert entropy to mnemonic words
    private static func mnemonicFromEntropy(_ entropy: Data) -> String {
        // SHA256 checksum
        let hash = SHA256.hash(data: entropy)
        let hashData = Data(hash)
        guard let hashByte = hashData.first else {
            return "" // Should never happen with valid entropy
        }

        // Combine entropy + checksum bits
        var bits = entropy.map { byte -> String in
            String(byte, radix: 2).padding(toLength: 8, withPad: "0", startingAt: 0)
        }.joined()

        // Add checksum (first entropy.count/4 bits)
        let checksumBits = String(hashByte, radix: 2).padding(toLength: 8, withPad: "0", startingAt: 0)
        bits += String(checksumBits.prefix(entropy.count / 4))

        // Split into 11-bit groups for word indices
        var words: [String] = []
        for i in stride(from: 0, to: bits.count, by: 11) {
            let start = bits.index(bits.startIndex, offsetBy: i)
            let end = bits.index(start, offsetBy: min(11, bits.count - i))
            let indexBits = String(bits[start..<end])
            if let index = Int(indexBits, radix: 2), index < wordList.count {
                words.append(wordList[index])
            }
        }

        return words.joined(separator: " ")
    }

    // MARK: - Mnemonic Normalization

    /// Normalize mnemonic for BIP39 compatibility
    /// - Trim leading/trailing whitespace
    /// - Collapse multiple spaces to single space
    /// - Apply NFKD Unicode normalization
    /// This prevents "same words, different bytes" issues (e.g., Turkish keyboard)
    private static func normalizeMnemonic(_ mnemonic: String) -> String {
        let trimmed = mnemonic.trimmingCharacters(in: .whitespacesAndNewlines)
        let collapsed = trimmed.components(separatedBy: .whitespaces)
            .filter { !$0.isEmpty }
            .joined(separator: " ")
        return collapsed.decomposedStringWithCompatibilityMapping  // NFKD
    }

    /// Normalize passphrase for BIP39 compatibility
    private static func normalizePassphrase(_ passphrase: String) -> String {
        return passphrase.decomposedStringWithCompatibilityMapping  // NFKD (no space collapse for passphrase)
    }

    // MARK: - Mnemonic to Seed

    /// Convert mnemonic to 64-byte seed using PBKDF2
    /// BIP39: PBKDF2-HMAC-SHA512, salt = "mnemonic" + passphrase, 2048 iterations
    static func seedFromMnemonic(_ mnemonic: String, passphrase: String = "") throws -> Data {
        let normalizedMnemonic = normalizeMnemonic(mnemonic)
        let normalizedPassphrase = normalizePassphrase(passphrase)

        let mnemonicData = normalizedMnemonic.data(using: .utf8)!
        let salt = ("mnemonic" + normalizedPassphrase).data(using: .utf8)!

        // PBKDF2-HMAC-SHA512 with 2048 iterations
        var derivedKey = [UInt8](repeating: 0, count: 64)
        let result = derivedKey.withUnsafeMutableBytes { derivedKeyBytes in
            mnemonicData.withUnsafeBytes { passwordBytes in
                salt.withUnsafeBytes { saltBytes in
                    CCKeyDerivationPBKDF(
                        CCPBKDFAlgorithm(kCCPBKDF2),
                        passwordBytes.baseAddress?.assumingMemoryBound(to: Int8.self),
                        mnemonicData.count,
                        saltBytes.baseAddress?.assumingMemoryBound(to: UInt8.self),
                        salt.count,
                        CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA512),
                        2048,
                        derivedKeyBytes.baseAddress?.assumingMemoryBound(to: UInt8.self),
                        64
                    )
                }
            }
        }

        guard result == kCCSuccess else {
            throw CryptoError.keyDerivationFailed
        }

        return Data(derivedKey)
    }

    // MARK: - HKDF Key Derivation

    /// Derive domain-specific key using HKDF-SHA256
    /// CRITICAL: Uses full 64-byte BIP39 seed + "whisper" salt for cross-platform recovery
    /// - Parameters:
    ///   - seed: Full 64-byte BIP39 seed (NOT truncated)
    ///   - info: Domain string ("whisper/enc", "whisper/sign", "whisper/contacts")
    ///   - length: Output key length (default 32 bytes)
    static func deriveKey(from seed: Data, info: String, length: Int = 32) -> Data {
        precondition(seed.count == Constants.Crypto.bip39SeedLength,
                     "BIP39 seed must be \(Constants.Crypto.bip39SeedLength) bytes")

        let salt = Constants.Crypto.hkdfSalt.data(using: .utf8)!
        let infoData = info.data(using: .utf8)!

        // Use FULL 64-byte seed as input key material
        let ikm = SymmetricKey(data: seed)

        let hkdf = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: ikm,
            salt: salt,
            info: infoData,
            outputByteCount: length
        )

        return hkdf.withUnsafeBytes { Data($0) }
    }

    // MARK: - Derive All Keys

    struct DerivedKeys {
        let encryptionSeed: Data  // For X25519
        let signingSeed: Data     // For Ed25519
        let contactsKey: Data     // For contacts backup
    }

    /// Derive all keys from mnemonic
    static func deriveAllKeys(from mnemonic: String) throws -> DerivedKeys {
        let seed = try seedFromMnemonic(mnemonic)

        return DerivedKeys(
            encryptionSeed: deriveKey(from: seed, info: Constants.Crypto.encryptionDomain),
            signingSeed: deriveKey(from: seed, info: Constants.Crypto.signingDomain),
            contactsKey: deriveKey(from: seed, info: Constants.Crypto.contactsDomain)
        )
    }

    // MARK: - WhisperID
    //
    // NOTE: WhisperIDs are generated SERVER-SIDE, not client-side!
    // Server generates random WSP-XXXX-XXXX-XXXX during registration
    // Client receives WhisperID in register_ack and stores it
    // Do NOT generate WhisperIDs locally - they come from server

    // MARK: - Validation

    /// Validate mnemonic phrase
    /// Uses normalized input to match seedFromMnemonic behavior
    static func isValidMnemonic(_ mnemonic: String) -> Bool {
        let normalized = normalizeMnemonic(mnemonic)
        let words = normalized.lowercased().split(separator: " ").map(String.init)
        guard words.count == 12 || words.count == 24 else { return false }
        return words.allSatisfy { wordList.contains($0) }
    }
}

// MARK: - CommonCrypto Import
import CommonCrypto
