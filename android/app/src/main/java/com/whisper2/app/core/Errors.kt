package com.whisper2.app.core

/**
 * Whisper2 Error Types
 * Domain-specific exceptions with error codes
 */

// MARK: - Base Error
sealed class WhisperException(
    val code: String,
    override val message: String,
    cause: Throwable? = null
) : Exception(message, cause)

// MARK: - Crypto Errors
sealed class CryptoException(code: String, message: String, cause: Throwable? = null) :
    WhisperException(code, message, cause) {

    class InvalidSeed(cause: Throwable? = null) :
        CryptoException("CRYPTO_INVALID_SEED", "Invalid seed data", cause)

    class InvalidMnemonic(cause: Throwable? = null) :
        CryptoException("CRYPTO_INVALID_MNEMONIC", "Invalid mnemonic phrase", cause)

    class KeyDerivationFailed(cause: Throwable? = null) :
        CryptoException("CRYPTO_KEY_DERIVATION_FAILED", "Failed to derive keys", cause)

    class EncryptionFailed(cause: Throwable? = null) :
        CryptoException("CRYPTO_ENCRYPTION_FAILED", "Encryption failed", cause)

    class DecryptionFailed(cause: Throwable? = null) :
        CryptoException("CRYPTO_DECRYPTION_FAILED", "Decryption failed", cause)

    class SignatureFailed(cause: Throwable? = null) :
        CryptoException("CRYPTO_SIGNATURE_FAILED", "Failed to create signature", cause)

    class SignatureVerificationFailed(cause: Throwable? = null) :
        CryptoException("CRYPTO_SIGNATURE_VERIFICATION_FAILED", "Signature verification failed", cause)

    class InvalidPublicKey(cause: Throwable? = null) :
        CryptoException("CRYPTO_INVALID_PUBLIC_KEY", "Invalid public key", cause)

    class InvalidPrivateKey(cause: Throwable? = null) :
        CryptoException("CRYPTO_INVALID_PRIVATE_KEY", "Invalid private key", cause)

    class InvalidNonce(cause: Throwable? = null) :
        CryptoException("CRYPTO_INVALID_NONCE", "Invalid nonce", cause)

    class InvalidBase64(cause: Throwable? = null) :
        CryptoException("CRYPTO_INVALID_BASE64", "Invalid base64 encoding", cause)

    class InvalidKeyLength(cause: Throwable? = null) :
        CryptoException("CRYPTO_INVALID_KEY_LENGTH", "Invalid key length", cause)

    class InvalidWhisperId(cause: Throwable? = null) :
        CryptoException("CRYPTO_INVALID_WHISPER_ID", "Invalid WhisperID format", cause)
}

// MARK: - Network Errors
sealed class NetworkException(code: String, message: String, cause: Throwable? = null) :
    WhisperException(code, message, cause) {

    class ConnectionFailed(cause: Throwable? = null) :
        NetworkException("NET_CONNECTION_FAILED", "Failed to connect to server", cause)

    class ConnectionClosed(cause: Throwable? = null) :
        NetworkException("NET_CONNECTION_CLOSED", "Connection closed", cause)

    class Timeout(cause: Throwable? = null) :
        NetworkException("NET_TIMEOUT", "Request timed out", cause)

    class InvalidUrl(cause: Throwable? = null) :
        NetworkException("NET_INVALID_URL", "Invalid URL", cause)

    class InvalidResponse(cause: Throwable? = null) :
        NetworkException("NET_INVALID_RESPONSE", "Invalid server response", cause)

    class HttpError(val statusCode: Int, val errorMessage: String?, cause: Throwable? = null) :
        NetworkException("NET_HTTP_$statusCode", errorMessage ?: "HTTP error $statusCode", cause)

    class EncodingFailed(cause: Throwable? = null) :
        NetworkException("NET_ENCODING_FAILED", "Failed to encode request", cause)

    class DecodingFailed(cause: Throwable? = null) :
        NetworkException("NET_DECODING_FAILED", "Failed to decode response", cause)

    class ServerError(errorCode: String, errorMessage: String, cause: Throwable? = null) :
        NetworkException(errorCode, errorMessage, cause)
}

// MARK: - Auth Errors
sealed class AuthException(code: String, message: String, cause: Throwable? = null) :
    WhisperException(code, message, cause) {

    class NotAuthenticated(cause: Throwable? = null) :
        AuthException("AUTH_NOT_AUTHENTICATED", "Not authenticated", cause)

    class SessionExpired(cause: Throwable? = null) :
        AuthException("AUTH_SESSION_EXPIRED", "Session expired", cause)

    class InvalidChallenge(reason: String, cause: Throwable? = null) :
        AuthException("AUTH_INVALID_CHALLENGE", "Invalid challenge: $reason", cause)

    class ChallengeExpired(cause: Throwable? = null) :
        AuthException("AUTH_CHALLENGE_EXPIRED", "Challenge expired", cause)

    class InvalidCredentials(cause: Throwable? = null) :
        AuthException("AUTH_INVALID_CREDENTIALS", "Invalid credentials", cause)

    class DeviceKicked(cause: Throwable? = null) :
        AuthException("AUTH_DEVICE_KICKED", "Another device logged in", cause)

    class Kicked(val reason: String, cause: Throwable? = null) :
        AuthException("AUTH_KICKED", "Session terminated: $reason", cause)

    class AuthFailed(reason: String, cause: Throwable? = null) :
        AuthException("AUTH_FAILED", reason, cause)

    class InvalidPayload(reason: String, cause: Throwable? = null) :
        AuthException("AUTH_INVALID_PAYLOAD", reason, cause)

    class RateLimited(reason: String, cause: Throwable? = null) :
        AuthException("AUTH_RATE_LIMITED", reason, cause)

    class InvalidResponse(reason: String, cause: Throwable? = null) :
        AuthException("AUTH_INVALID_RESPONSE", reason, cause)

    class ReplayAttempt(val challengeId: String, cause: Throwable? = null) :
        AuthException("AUTH_REPLAY_ATTEMPT", "Challenge already used: $challengeId", cause)

    class RegistrationFailed(val reason: String, cause: Throwable? = null) :
        AuthException("AUTH_REGISTRATION_FAILED", "Registration failed: $reason", cause)
}

// MARK: - Messaging Errors
sealed class MessagingException(code: String, message: String, cause: Throwable? = null) :
    WhisperException(code, message, cause) {

    class RecipientNotFound(cause: Throwable? = null) :
        MessagingException("MSG_RECIPIENT_NOT_FOUND", "Recipient not found", cause)

    class EncryptionFailed(cause: Throwable? = null) :
        MessagingException("MSG_ENCRYPTION_FAILED", "Failed to encrypt message", cause)

    class DecryptionFailed(cause: Throwable? = null) :
        MessagingException("MSG_DECRYPTION_FAILED", "Failed to decrypt message", cause)

    class InvalidSignature(cause: Throwable? = null) :
        MessagingException("MSG_INVALID_SIGNATURE", "Invalid message signature", cause)

    class MessageNotFound(cause: Throwable? = null) :
        MessagingException("MSG_NOT_FOUND", "Message not found", cause)

    class SendFailed(val reason: String, cause: Throwable? = null) :
        MessagingException("MSG_SEND_FAILED", "Failed to send: $reason", cause)
}

// MARK: - Storage Errors
sealed class StorageException(code: String, message: String, cause: Throwable? = null) :
    WhisperException(code, message, cause) {

    class SaveFailed(cause: Throwable? = null) :
        StorageException("STORAGE_SAVE_FAILED", "Failed to save data", cause)

    class LoadFailed(cause: Throwable? = null) :
        StorageException("STORAGE_LOAD_FAILED", "Failed to load data", cause)

    class DeleteFailed(cause: Throwable? = null) :
        StorageException("STORAGE_DELETE_FAILED", "Failed to delete data", cause)

    class MigrationFailed(cause: Throwable? = null) :
        StorageException("STORAGE_MIGRATION_FAILED", "Database migration failed", cause)

    class KeystoreError(val status: Int, cause: Throwable? = null) :
        StorageException("STORAGE_KEYSTORE_$status", "Keystore error: $status", cause)
}

// MARK: - Call Errors
sealed class CallException(code: String, message: String, cause: Throwable? = null) :
    WhisperException(code, message, cause) {

    class NotInCall(cause: Throwable? = null) :
        CallException("CALL_NOT_IN_CALL", "Not in a call", cause)

    class AlreadyInCall(cause: Throwable? = null) :
        CallException("CALL_ALREADY_IN_CALL", "Already in a call", cause)

    class CallNotFound(cause: Throwable? = null) :
        CallException("CALL_NOT_FOUND", "Call not found", cause)

    class NotCallParty(cause: Throwable? = null) :
        CallException("CALL_NOT_PARTY", "Not a party to this call", cause)

    class WebRtcFailed(val reason: String, cause: Throwable? = null) :
        CallException("CALL_WEBRTC_FAILED", "WebRTC error: $reason", cause)

    class TurnCredentialsFailed(cause: Throwable? = null) :
        CallException("CALL_TURN_FAILED", "Failed to get TURN credentials", cause)
}

// MARK: - Group Errors
sealed class GroupException(code: String, message: String, cause: Throwable? = null) :
    WhisperException(code, message, cause) {

    class GroupNotFound(cause: Throwable? = null) :
        GroupException("GROUP_NOT_FOUND", "Group not found", cause)

    class NotGroupMember(cause: Throwable? = null) :
        GroupException("GROUP_NOT_MEMBER", "Not a member of this group", cause)

    class NotGroupOwner(cause: Throwable? = null) :
        GroupException("GROUP_NOT_OWNER", "Not the group owner", cause)

    class MemberLimitExceeded(cause: Throwable? = null) :
        GroupException("GROUP_MEMBER_LIMIT", "Group member limit exceeded", cause)
}
