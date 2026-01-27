import Foundation

// MARK: - Crypto Errors
enum CryptoError: LocalizedError {
    case invalidSeed
    case invalidMnemonic
    case keyDerivationFailed
    case encryptionFailed
    case decryptionFailed
    case signatureFailed
    case invalidPublicKey
    case invalidPrivateKey
    case invalidNonce
    case invalidBase64
    case invalidWhisperId
    case invalidMessage
    case randomGenerationFailed

    var errorDescription: String? {
        switch self {
        case .invalidSeed: return "Invalid seed data"
        case .invalidMnemonic: return "Invalid mnemonic phrase"
        case .keyDerivationFailed: return "Failed to derive keys"
        case .encryptionFailed: return "Encryption failed"
        case .decryptionFailed: return "Decryption failed"
        case .signatureFailed: return "Failed to create signature"
        case .invalidPublicKey: return "Invalid public key"
        case .invalidPrivateKey: return "Invalid private key"
        case .invalidNonce: return "Invalid nonce"
        case .invalidBase64: return "Invalid base64 encoding"
        case .invalidWhisperId: return "Invalid WhisperID format"
        case .invalidMessage: return "Invalid message data"
        case .randomGenerationFailed: return "Failed to generate random bytes"
        }
    }
}

// MARK: - Auth Errors
enum AuthError: LocalizedError {
    case notAuthenticated
    case notAuthorized
    case sessionExpired
    case invalidChallenge
    case invalidCredentials
    case registrationFailed(String)

    var errorDescription: String? {
        switch self {
        case .notAuthenticated: return "Not authenticated"
        case .notAuthorized: return "Not authorized to perform this action"
        case .sessionExpired: return "Session expired"
        case .invalidChallenge: return "Invalid challenge"
        case .invalidCredentials: return "Invalid credentials"
        case .registrationFailed(let reason): return "Registration failed: \(reason)"
        }
    }
}

// MARK: - Network Errors
enum NetworkError: LocalizedError {
    case connectionFailed
    case connectionClosed
    case timeout
    case invalidResponse
    case serverError(code: String, message: String)
    
    var errorDescription: String? {
        switch self {
        case .connectionFailed: return "Failed to connect to server"
        case .connectionClosed: return "Connection closed"
        case .timeout: return "Request timed out"
        case .invalidResponse: return "Invalid server response"
        case .serverError(_, let msg): return msg
        }
    }
}

// MARK: - Storage Errors
enum StorageError: LocalizedError {
    case saveFailed
    case loadFailed
    case keychainError(OSStatus)
    
    var errorDescription: String? {
        switch self {
        case .saveFailed: return "Failed to save data"
        case .loadFailed: return "Failed to load data"
        case .keychainError(let status): return "Keychain error: \(status)"
        }
    }
}
