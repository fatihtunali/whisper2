import Foundation

/// Whisper2 Error Types
/// Domain-specific errors with localized descriptions

// MARK: - Base Error Protocol
protocol WhisperError: LocalizedError {
    var code: String { get }
    var message: String { get }
}

extension WhisperError {
    var errorDescription: String? { message }
}

// MARK: - Crypto Errors
enum CryptoError: WhisperError {
    case invalidSeed
    case invalidMnemonic
    case keyDerivationFailed
    case encryptionFailed
    case decryptionFailed
    case signatureFailed
    case signatureVerificationFailed
    case invalidPublicKey
    case invalidPrivateKey
    case invalidNonce
    case invalidBase64
    case invalidKeyLength

    var code: String {
        switch self {
        case .invalidSeed: return "CRYPTO_INVALID_SEED"
        case .invalidMnemonic: return "CRYPTO_INVALID_MNEMONIC"
        case .keyDerivationFailed: return "CRYPTO_KEY_DERIVATION_FAILED"
        case .encryptionFailed: return "CRYPTO_ENCRYPTION_FAILED"
        case .decryptionFailed: return "CRYPTO_DECRYPTION_FAILED"
        case .signatureFailed: return "CRYPTO_SIGNATURE_FAILED"
        case .signatureVerificationFailed: return "CRYPTO_SIGNATURE_VERIFICATION_FAILED"
        case .invalidPublicKey: return "CRYPTO_INVALID_PUBLIC_KEY"
        case .invalidPrivateKey: return "CRYPTO_INVALID_PRIVATE_KEY"
        case .invalidNonce: return "CRYPTO_INVALID_NONCE"
        case .invalidBase64: return "CRYPTO_INVALID_BASE64"
        case .invalidKeyLength: return "CRYPTO_INVALID_KEY_LENGTH"
        }
    }

    var message: String {
        switch self {
        case .invalidSeed: return "Invalid seed data"
        case .invalidMnemonic: return "Invalid mnemonic phrase"
        case .keyDerivationFailed: return "Failed to derive keys"
        case .encryptionFailed: return "Encryption failed"
        case .decryptionFailed: return "Decryption failed"
        case .signatureFailed: return "Failed to create signature"
        case .signatureVerificationFailed: return "Signature verification failed"
        case .invalidPublicKey: return "Invalid public key"
        case .invalidPrivateKey: return "Invalid private key"
        case .invalidNonce: return "Invalid nonce"
        case .invalidBase64: return "Invalid base64 encoding"
        case .invalidKeyLength: return "Invalid key length"
        }
    }
}

// MARK: - Network Errors
enum NetworkError: WhisperError {
    case connectionFailed
    case connectionClosed
    case timeout
    case invalidURL
    case invalidResponse
    case httpError(statusCode: Int, message: String?)
    case encodingFailed
    case decodingFailed
    case serverError(code: String, message: String)

    var code: String {
        switch self {
        case .connectionFailed: return "NET_CONNECTION_FAILED"
        case .connectionClosed: return "NET_CONNECTION_CLOSED"
        case .timeout: return "NET_TIMEOUT"
        case .invalidURL: return "NET_INVALID_URL"
        case .invalidResponse: return "NET_INVALID_RESPONSE"
        case .httpError(let code, _): return "NET_HTTP_\(code)"
        case .encodingFailed: return "NET_ENCODING_FAILED"
        case .decodingFailed: return "NET_DECODING_FAILED"
        case .serverError(let code, _): return code
        }
    }

    var message: String {
        switch self {
        case .connectionFailed: return "Failed to connect to server"
        case .connectionClosed: return "Connection closed"
        case .timeout: return "Request timed out"
        case .invalidURL: return "Invalid URL"
        case .invalidResponse: return "Invalid server response"
        case .httpError(let code, let msg): return msg ?? "HTTP error \(code)"
        case .encodingFailed: return "Failed to encode request"
        case .decodingFailed: return "Failed to decode response"
        case .serverError(_, let msg): return msg
        }
    }
}

// MARK: - Auth Errors
enum AuthError: WhisperError {
    case notAuthenticated
    case sessionExpired
    case invalidChallenge
    case invalidCredentials
    case deviceKicked
    case registrationFailed(reason: String)

    var code: String {
        switch self {
        case .notAuthenticated: return "AUTH_NOT_AUTHENTICATED"
        case .sessionExpired: return "AUTH_SESSION_EXPIRED"
        case .invalidChallenge: return "AUTH_INVALID_CHALLENGE"
        case .invalidCredentials: return "AUTH_INVALID_CREDENTIALS"
        case .deviceKicked: return "AUTH_DEVICE_KICKED"
        case .registrationFailed: return "AUTH_REGISTRATION_FAILED"
        }
    }

    var message: String {
        switch self {
        case .notAuthenticated: return "Not authenticated"
        case .sessionExpired: return "Session expired"
        case .invalidChallenge: return "Invalid challenge"
        case .invalidCredentials: return "Invalid credentials"
        case .deviceKicked: return "Another device logged in"
        case .registrationFailed(let reason): return "Registration failed: \(reason)"
        }
    }
}

// MARK: - Messaging Errors
enum MessagingError: WhisperError {
    case recipientNotFound
    case encryptionFailed
    case decryptionFailed
    case invalidSignature
    case messageNotFound
    case sendFailed(reason: String)

    var code: String {
        switch self {
        case .recipientNotFound: return "MSG_RECIPIENT_NOT_FOUND"
        case .encryptionFailed: return "MSG_ENCRYPTION_FAILED"
        case .decryptionFailed: return "MSG_DECRYPTION_FAILED"
        case .invalidSignature: return "MSG_INVALID_SIGNATURE"
        case .messageNotFound: return "MSG_NOT_FOUND"
        case .sendFailed: return "MSG_SEND_FAILED"
        }
    }

    var message: String {
        switch self {
        case .recipientNotFound: return "Recipient not found"
        case .encryptionFailed: return "Failed to encrypt message"
        case .decryptionFailed: return "Failed to decrypt message"
        case .invalidSignature: return "Invalid message signature"
        case .messageNotFound: return "Message not found"
        case .sendFailed(let reason): return "Failed to send: \(reason)"
        }
    }
}

// MARK: - Storage Errors
enum StorageError: WhisperError {
    case saveFailed
    case loadFailed
    case deleteFailed
    case migrationFailed
    case keychainError(status: OSStatus)

    var code: String {
        switch self {
        case .saveFailed: return "STORAGE_SAVE_FAILED"
        case .loadFailed: return "STORAGE_LOAD_FAILED"
        case .deleteFailed: return "STORAGE_DELETE_FAILED"
        case .migrationFailed: return "STORAGE_MIGRATION_FAILED"
        case .keychainError(let status): return "STORAGE_KEYCHAIN_\(status)"
        }
    }

    var message: String {
        switch self {
        case .saveFailed: return "Failed to save data"
        case .loadFailed: return "Failed to load data"
        case .deleteFailed: return "Failed to delete data"
        case .migrationFailed: return "Database migration failed"
        case .keychainError(let status): return "Keychain error: \(status)"
        }
    }
}

// MARK: - Call Errors
enum CallError: WhisperError {
    case notInCall
    case alreadyInCall
    case callNotFound
    case notCallParty
    case webRTCFailed(reason: String)
    case turnCredentialsFailed

    var code: String {
        switch self {
        case .notInCall: return "CALL_NOT_IN_CALL"
        case .alreadyInCall: return "CALL_ALREADY_IN_CALL"
        case .callNotFound: return "CALL_NOT_FOUND"
        case .notCallParty: return "CALL_NOT_PARTY"
        case .webRTCFailed: return "CALL_WEBRTC_FAILED"
        case .turnCredentialsFailed: return "CALL_TURN_FAILED"
        }
    }

    var message: String {
        switch self {
        case .notInCall: return "Not in a call"
        case .alreadyInCall: return "Already in a call"
        case .callNotFound: return "Call not found"
        case .notCallParty: return "Not a party to this call"
        case .webRTCFailed(let reason): return "WebRTC error: \(reason)"
        case .turnCredentialsFailed: return "Failed to get TURN credentials"
        }
    }
}

// MARK: - Group Errors
enum GroupError: WhisperError {
    case groupNotFound
    case notGroupMember
    case notGroupOwner
    case memberLimitExceeded

    var code: String {
        switch self {
        case .groupNotFound: return "GROUP_NOT_FOUND"
        case .notGroupMember: return "GROUP_NOT_MEMBER"
        case .notGroupOwner: return "GROUP_NOT_OWNER"
        case .memberLimitExceeded: return "GROUP_MEMBER_LIMIT"
        }
    }

    var message: String {
        switch self {
        case .groupNotFound: return "Group not found"
        case .notGroupMember: return "Not a member of this group"
        case .notGroupOwner: return "Not the group owner"
        case .memberLimitExceeded: return "Group member limit exceeded"
        }
    }
}
