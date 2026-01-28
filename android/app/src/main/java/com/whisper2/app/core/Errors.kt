package com.whisper2.app.core

sealed class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)
class InvalidMnemonicException(message: String = "Invalid mnemonic") : CryptoException(message)
class KeyDerivationException(message: String, cause: Throwable? = null) : CryptoException(message, cause)
class EncryptionException(message: String, cause: Throwable? = null) : CryptoException(message, cause)
class DecryptionException(message: String = "Decryption failed") : CryptoException(message)
class SignatureVerificationException(message: String = "Invalid signature") : CryptoException(message)

sealed class ProtocolException(message: String) : Exception(message)
class TimestampValidationException(message: String = "Invalid timestamp") : ProtocolException(message)
class AuthenticationException(message: String = "Auth failed") : ProtocolException(message)
class SessionExpiredException(message: String = "Session expired") : ProtocolException(message)
class UserNotFoundException(id: String) : ProtocolException("User not found: $id")

sealed class NetworkException(message: String, cause: Throwable? = null) : Exception(message, cause)
class WebSocketConnectionException(message: String, cause: Throwable? = null) : NetworkException(message, cause)
class WebSocketDisconnectedException(message: String = "Disconnected") : NetworkException(message)

sealed class AttachmentException(message: String) : Exception(message)
class AttachmentTooLargeException(size: Long) : AttachmentException("Too large: $size")
class AttachmentUploadException(message: String) : AttachmentException(message)

sealed class CallException(message: String) : Exception(message)
class CallInitiationException(message: String) : CallException(message)
class WebRtcException(message: String) : CallException(message)
