import Foundation
import CryptoKit

/// Key derivation using BIP39 mnemonic and HKDF
/// Matches server implementation in crypto.ts

enum KeyDerivation {

    // MARK: - BIP39 Word List (first 100 words for demo - full list needed)
    private static let wordList: [String] = [
        "abandon", "ability", "able", "about", "above", "absent", "absorb", "abstract",
        "absurd", "abuse", "access", "accident", "account", "accuse", "achieve", "acid",
        "acoustic", "acquire", "across", "act", "action", "actor", "actress", "actual",
        "adapt", "add", "addict", "address", "adjust", "admit", "adult", "advance",
        "advice", "aerobic", "affair", "afford", "afraid", "again", "age", "agent",
        "agree", "ahead", "aim", "air", "airport", "aisle", "alarm", "album",
        "alcohol", "alert", "alien", "all", "alley", "allow", "almost", "alone",
        "alpha", "already", "also", "alter", "always", "amateur", "amazing", "among",
        "amount", "amused", "analyst", "anchor", "ancient", "anger", "angle", "angry",
        "animal", "ankle", "announce", "annual", "another", "answer", "antenna", "antique",
        "anxiety", "any", "apart", "apology", "appear", "apple", "approve", "april",
        "arch", "arctic", "area", "arena", "argue", "arm", "armed", "armor",
        "army", "around", "arrange", "arrest"
        // Full BIP39 word list (2048 words) should be loaded from resource
    ]

    // MARK: - Generate Mnemonic

    /// Generate a new 24-word mnemonic phrase
    static func generateMnemonic() -> String {
        var randomBytes = [UInt8](repeating: 0, count: 32)
        _ = SecRandomCopyBytes(kSecRandomDefault, 32, &randomBytes)
        return mnemonicFromEntropy(Data(randomBytes))
    }

    /// Convert entropy to mnemonic words
    private static func mnemonicFromEntropy(_ entropy: Data) -> String {
        // SHA256 checksum
        let hash = SHA256.hash(data: entropy)
        let hashByte = hash.first!

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

    // MARK: - Mnemonic to Seed

    /// Convert mnemonic to 64-byte seed using PBKDF2
    static func seedFromMnemonic(_ mnemonic: String, passphrase: String = "") throws -> Data {
        let mnemonicData = mnemonic.decomposedStringWithCompatibilityMapping.data(using: .utf8)!
        let salt = ("mnemonic" + passphrase).decomposedStringWithCompatibilityMapping.data(using: .utf8)!

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
    static func deriveKey(from seed: Data, info: String, length: Int = 32) -> Data {
        let infoData = info.data(using: .utf8)!
        let key = SymmetricKey(data: seed.prefix(32))

        // HKDF expand
        let hkdf = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: key,
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

    // MARK: - WhisperID Generation

    /// Generate WhisperID from signing public key
    /// Format: WH2-{first 16 chars of hex(sha256(signPublicKey))}
    static func whisperIdFromSigningKey(_ publicKey: Data) -> String {
        let hash = SHA256.hash(data: publicKey)
        let hex = hash.prefix(8).map { String(format: "%02x", $0) }.joined()
        return "\(Constants.Crypto.whisperIdPrefix)\(hex)"
    }

    // MARK: - Validation

    /// Validate mnemonic phrase
    static func isValidMnemonic(_ mnemonic: String) -> Bool {
        let words = mnemonic.lowercased().split(separator: " ").map(String.init)
        guard words.count == 12 || words.count == 24 else { return false }
        return words.allSatisfy { wordList.contains($0) }
    }
}

// MARK: - CommonCrypto Import
import CommonCrypto
