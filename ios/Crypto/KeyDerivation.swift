import Foundation
import CommonCrypto
import CryptoKit

/// Key derivation matching server test-vectors.ts
/// BIP39 -> PBKDF2-HMAC-SHA512 -> HKDF-SHA256
enum KeyDerivation {
    
    // MARK: - Generate Mnemonic
    
    static func generateMnemonic() throws -> String {
        var randomBytes = [UInt8](repeating: 0, count: 16)
        let status = SecRandomCopyBytes(kSecRandomDefault, 16, &randomBytes)
        guard status == errSecSuccess else {
            throw CryptoError.keyDerivationFailed
        }
        return mnemonicFromEntropy(Data(randomBytes))
    }
    
    private static func mnemonicFromEntropy(_ entropy: Data) -> String {
        let hash = SHA256.hash(data: entropy)
        let hashByte = Array(hash)[0]
        
        var bits = entropy.map { byte -> String in
            String(byte, radix: 2).leftPadded(toLength: 8, with: "0")
        }.joined()
        
        let checksumBits = String(hashByte, radix: 2).leftPadded(toLength: 8, with: "0")
        bits += String(checksumBits.prefix(entropy.count / 4))
        
        var words: [String] = []
        for i in stride(from: 0, to: bits.count, by: 11) {
            let start = bits.index(bits.startIndex, offsetBy: i)
            let end = bits.index(start, offsetBy: min(11, bits.count - i))
            let indexBits = String(bits[start..<end])
            if let index = Int(indexBits, radix: 2), index < BIP39WordList.english.count {
                words.append(BIP39WordList.english[index])
            }
        }
        
        return words.joined(separator: " ")
    }
    
    // MARK: - Validate Mnemonic
    
    static func isValidMnemonic(_ mnemonic: String) -> Bool {
        let words = normalizeMnemonic(mnemonic).split(separator: " ").map(String.init)
        guard words.count == 12 || words.count == 24 else { return false }
        return words.allSatisfy { BIP39WordList.english.contains($0) }
    }
    
    private static func normalizeMnemonic(_ mnemonic: String) -> String {
        mnemonic
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .components(separatedBy: .whitespaces)
            .filter { !$0.isEmpty }
            .joined(separator: " ")
            .lowercased()
            .decomposedStringWithCompatibilityMapping  // NFKD
    }
    
    // MARK: - BIP39 Seed (PBKDF2-HMAC-SHA512)
    
    /// Matches server: PBKDF2-HMAC-SHA512, salt="mnemonic", iterations=2048
    static func seedFromMnemonic(_ mnemonic: String, passphrase: String = "") throws -> Data {
        let normalizedMnemonic = normalizeMnemonic(mnemonic)
        let password = normalizedMnemonic.data(using: .utf8)!
        let salt = ("mnemonic" + passphrase).data(using: .utf8)!
        
        var derivedKey = [UInt8](repeating: 0, count: 64)
        let result = derivedKey.withUnsafeMutableBytes { derivedKeyBytes in
            password.withUnsafeBytes { passwordBytes in
                salt.withUnsafeBytes { saltBytes in
                    CCKeyDerivationPBKDF(
                        CCPBKDFAlgorithm(kCCPBKDF2),
                        passwordBytes.baseAddress?.assumingMemoryBound(to: Int8.self),
                        password.count,
                        saltBytes.baseAddress?.assumingMemoryBound(to: UInt8.self),
                        salt.count,
                        CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA512),
                        2048,  // iterations from server
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
    
    // MARK: - HKDF (matches server test-vectors.ts)
    
    /// HKDF-SHA256 with salt="whisper"
    static func deriveKey(from seed: Data, info: String, length: Int = 32) -> Data {
        let salt = Constants.hkdfSalt.data(using: .utf8)!
        let infoData = info.data(using: .utf8)!
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
        let encSeed: Data      // 32 bytes for X25519
        let signSeed: Data     // 32 bytes for Ed25519
        let contactsKey: Data  // 32 bytes
    }
    
    static func deriveKeys(from mnemonic: String) throws -> DerivedKeys {
        let seed = try seedFromMnemonic(mnemonic)  // 64 bytes
        
        return DerivedKeys(
            encSeed: deriveKey(from: seed, info: Constants.encryptionDomain),
            signSeed: deriveKey(from: seed, info: Constants.signingDomain),
            contactsKey: deriveKey(from: seed, info: Constants.contactsDomain)
        )
    }
}

// MARK: - String Extension

private extension String {
    func leftPadded(toLength length: Int, with character: Character) -> String {
        if count >= length { return self }
        return String(repeating: character, count: length - count) + self
    }
}
