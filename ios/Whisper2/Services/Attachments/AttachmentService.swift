import Foundation

/// Whisper2 Attachment Service
/// Handles encrypted file upload/download using presigned URLs.
/// Files are encrypted client-side before upload.

// MARK: - Attachment Models

/// Pointer to an uploaded attachment
/// Sent with messages so recipients can download
struct UploadedAttachment {
    let objectKey: String           // S3 path
    let contentType: String         // Original content type
    let ciphertextSize: Int         // Encrypted size
    let fileNonce: Data             // 24-byte nonce for file encryption
    let fileKey: Data               // 32-byte key for file encryption

    /// Create AttachmentPointer for a specific recipient.
    /// Encrypts fileKey using recipient's public key.
    /// - Parameters:
    ///   - recipientPublicKey: Recipient's X25519 public key
    ///   - senderPrivateKey: Sender's X25519 private key
    /// - Returns: AttachmentPointer ready to send
    func pointer(
        forRecipient recipientPublicKey: Data,
        senderPrivateKey: Data
    ) throws -> AttachmentPointer {
        // Encrypt fileKey for recipient using box (X25519-XSalsa20-Poly1305)
        let (keyBoxNonce, keyBoxCiphertext) = try AttachmentCrypto.shared.boxSeal(
            plaintext: fileKey,
            recipientPublicKey: recipientPublicKey,
            senderPrivateKey: senderPrivateKey
        )

        return AttachmentPointer(
            objectKey: objectKey,
            contentType: contentType,
            ciphertextSize: ciphertextSize,
            fileNonce: fileNonce.base64,
            fileKeyBox: AttachmentPointer.FileKeyBox(
                nonce: keyBoxNonce.base64,
                ciphertext: keyBoxCiphertext.base64
            )
        )
    }
}

/// Presigned upload URL response from server
struct PresignUploadResponse: Codable {
    let objectKey: String
    let uploadUrl: String
    let expiresAtMs: Int64
    let headers: [String: String]
}

/// Presigned download URL response from server
struct PresignDownloadResponse: Codable {
    let objectKey: String
    let downloadUrl: String
    let expiresAtMs: Int64
    let sizeBytes: Int
    let contentType: String
}

// MARK: - Attachment Service

/// Service for uploading and downloading encrypted attachments.
final class AttachmentService {

    static let shared = AttachmentService()

    private let keychain = KeychainService.shared
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    private init() {
        encoder.outputFormatting = .sortedKeys
    }

    // MARK: - Upload

    /// Upload an encrypted attachment.
    /// - Parameters:
    ///   - data: Raw file data to upload
    ///   - contentType: MIME type of the file
    /// - Returns: UploadedAttachment with keys for creating pointers
    func uploadAttachment(data: Data, contentType: String) async throws -> UploadedAttachment {
        logger.info("Starting attachment upload (\(data.count) bytes, \(contentType))", category: .messaging)

        // 1. Validate size
        guard data.count > 0 && data.count <= Constants.Limits.maxAttachmentSize else {
            throw AttachmentServiceError.sizeLimitExceeded
        }

        // 2. Get session token
        guard let sessionToken = keychain.sessionToken else {
            throw AuthError.notAuthenticated
        }

        // 3. Generate random file key (32 bytes)
        let fileKey = try AttachmentCrypto.shared.randomBytes(Constants.Crypto.keyLength)

        // 4. Encrypt file with secretbox
        let (fileNonce, ciphertext) = try AttachmentCrypto.shared.secretboxSeal(
            plaintext: data,
            key: fileKey
        )

        // 5. Request presigned upload URL
        let presignResponse = try await requestPresignUpload(
            contentType: contentType,
            sizeBytes: ciphertext.count,
            sessionToken: sessionToken
        )

        // 6. Upload encrypted data to presigned URL
        try await uploadToPresignedUrl(
            url: presignResponse.uploadUrl,
            data: ciphertext,
            headers: presignResponse.headers
        )

        logger.info("Attachment uploaded: \(presignResponse.objectKey)", category: .messaging)

        return UploadedAttachment(
            objectKey: presignResponse.objectKey,
            contentType: contentType,
            ciphertextSize: ciphertext.count,
            fileNonce: fileNonce,
            fileKey: fileKey
        )
    }

    // MARK: - Download

    /// Download and decrypt an attachment.
    /// - Parameters:
    ///   - pointer: Attachment pointer from received message
    ///   - recipientPrivateKey: Recipient's X25519 private key for decrypting fileKey
    ///   - senderPublicKey: Sender's X25519 public key for decrypting fileKey
    /// - Returns: Decrypted file data
    func downloadAttachment(
        pointer: AttachmentPointer,
        recipientPrivateKey: Data,
        senderPublicKey: Data
    ) async throws -> Data {
        logger.info("Starting attachment download: \(pointer.objectKey)", category: .messaging)

        // 1. Get session token
        guard let sessionToken = keychain.sessionToken else {
            throw AuthError.notAuthenticated
        }

        // 2. Decrypt fileKey from fileKeyBox
        let keyBoxNonce = try pointer.fileKeyBox.nonce.base64Decoded()
        let keyBoxCiphertext = try pointer.fileKeyBox.ciphertext.base64Decoded()

        let fileKey = try AttachmentCrypto.shared.boxOpen(
            ciphertext: keyBoxCiphertext,
            nonce: keyBoxNonce,
            senderPublicKey: senderPublicKey,
            recipientPrivateKey: recipientPrivateKey
        )

        // 3. Request presigned download URL
        let presignResponse = try await requestPresignDownload(
            objectKey: pointer.objectKey,
            sessionToken: sessionToken
        )

        // 4. Download encrypted data
        let ciphertext = try await downloadFromPresignedUrl(url: presignResponse.downloadUrl)

        // 5. Decrypt file
        let fileNonce = try pointer.fileNonce.base64Decoded()
        let plaintext = try AttachmentCrypto.shared.secretboxOpen(
            ciphertext: ciphertext,
            nonce: fileNonce,
            key: fileKey
        )

        logger.info("Attachment downloaded and decrypted (\(plaintext.count) bytes)", category: .messaging)

        return plaintext
    }

    // MARK: - Private Helpers

    /// Request presigned upload URL from server.
    private func requestPresignUpload(
        contentType: String,
        sizeBytes: Int,
        sessionToken: String
    ) async throws -> PresignUploadResponse {
        let body: [String: Any] = [
            "contentType": contentType,
            "sizeBytes": sizeBytes
        ]

        let response = try await httpRequest(
            method: "POST",
            path: "/attachments/presign/upload",
            body: body,
            sessionToken: sessionToken
        )

        return try decoder.decode(PresignUploadResponse.self, from: response)
    }

    /// Request presigned download URL from server.
    private func requestPresignDownload(
        objectKey: String,
        sessionToken: String
    ) async throws -> PresignDownloadResponse {
        let body: [String: String] = [
            "objectKey": objectKey
        ]

        let response = try await httpRequest(
            method: "POST",
            path: "/attachments/presign/download",
            body: body,
            sessionToken: sessionToken
        )

        return try decoder.decode(PresignDownloadResponse.self, from: response)
    }

    /// Upload data to presigned URL.
    private func uploadToPresignedUrl(
        url: String,
        data: Data,
        headers: [String: String]
    ) async throws {
        guard let uploadUrl = URL(string: url) else {
            throw NetworkError.invalidURL
        }

        var request = URLRequest(url: uploadUrl)
        request.httpMethod = "PUT"
        request.timeoutInterval = 300 // 5 minutes for large files
        request.httpBody = data

        // Add headers from presign response
        for (key, value) in headers {
            request.setValue(value, forHTTPHeaderField: key)
        }

        let (_, response) = try await URLSession.shared.data(for: request)

        guard let httpResponse = response as? HTTPURLResponse,
              (200...299).contains(httpResponse.statusCode) else {
            throw AttachmentServiceError.uploadFailed
        }
    }

    /// Download data from presigned URL.
    private func downloadFromPresignedUrl(url: String) async throws -> Data {
        guard let downloadUrl = URL(string: url) else {
            throw NetworkError.invalidURL
        }

        var request = URLRequest(url: downloadUrl)
        request.httpMethod = "GET"
        request.timeoutInterval = 300 // 5 minutes for large files

        let (data, response) = try await URLSession.shared.data(for: request)

        guard let httpResponse = response as? HTTPURLResponse,
              (200...299).contains(httpResponse.statusCode) else {
            throw AttachmentServiceError.downloadFailed
        }

        return data
    }

    /// Make authenticated HTTP request to server.
    private func httpRequest(
        method: String,
        path: String,
        body: [String: Any],
        sessionToken: String
    ) async throws -> Data {
        guard let url = URL(string: "\(Constants.Server.httpBaseURL)\(path)") else {
            throw NetworkError.invalidURL
        }

        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = Constants.Timeout.httpRequest
        request.setValue("Bearer \(sessionToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, response) = try await URLSession.shared.data(for: request)

        guard let httpResponse = response as? HTTPURLResponse else {
            throw NetworkError.invalidResponse
        }

        switch httpResponse.statusCode {
        case 200, 201:
            return data
        case 401:
            throw AuthError.sessionExpired
        case 403:
            throw AttachmentServiceError.accessDenied
        case 404:
            throw AttachmentServiceError.notFound
        case 429:
            throw NetworkError.httpError(statusCode: 429, message: "Rate limited")
        default:
            let errorInfo = try? decoder.decode(ServerErrorResponse.self, from: data)
            throw NetworkError.httpError(
                statusCode: httpResponse.statusCode,
                message: errorInfo?.message ?? "Request failed"
            )
        }
    }
}

// MARK: - Attachment Service Errors
// Note: AttachmentError is defined in Domain/Models/Attachment.swift

enum AttachmentServiceError: WhisperError {
    case sizeLimitExceeded
    case invalidContentType
    case uploadFailed
    case downloadFailed
    case accessDenied
    case notFound
    case decryptionFailed

    var code: String {
        switch self {
        case .sizeLimitExceeded: return "ATTACHMENT_SIZE_LIMIT"
        case .invalidContentType: return "ATTACHMENT_INVALID_TYPE"
        case .uploadFailed: return "ATTACHMENT_UPLOAD_FAILED"
        case .downloadFailed: return "ATTACHMENT_DOWNLOAD_FAILED"
        case .accessDenied: return "ATTACHMENT_ACCESS_DENIED"
        case .notFound: return "ATTACHMENT_NOT_FOUND"
        case .decryptionFailed: return "ATTACHMENT_DECRYPTION_FAILED"
        }
    }

    var message: String {
        switch self {
        case .sizeLimitExceeded: return "File exceeds maximum size limit"
        case .invalidContentType: return "Content type not allowed"
        case .uploadFailed: return "Failed to upload attachment"
        case .downloadFailed: return "Failed to download attachment"
        case .accessDenied: return "Access denied to attachment"
        case .notFound: return "Attachment not found"
        case .decryptionFailed: return "Failed to decrypt attachment"
        }
    }
}

// MARK: - Server Error Response

private struct ServerErrorResponse: Codable {
    let error: String
    let message: String
}

// MARK: - Attachment Crypto

/// Crypto operations specific to attachment handling.
/// Uses NaCl secretbox (XSalsa20-Poly1305) for file encryption
/// and box (X25519-XSalsa20-Poly1305) for key encryption.
final class AttachmentCrypto {

    static let shared = AttachmentCrypto()

    private init() {}

    /// Generate random bytes.
    func randomBytes(_ count: Int) throws -> Data {
        var data = Data(count: count)
        let result = data.withUnsafeMutableBytes { ptr in
            SecRandomCopyBytes(kSecRandomDefault, count, ptr.baseAddress!)
        }
        guard result == errSecSuccess else {
            throw CryptoError.keyDerivationFailed
        }
        return data
    }

    /// Encrypt data with secretbox (symmetric).
    func secretboxSeal(plaintext: Data, key: Data) throws -> (nonce: Data, ciphertext: Data) {
        return try NaClSecretBox.seal(message: plaintext, key: key)
    }

    /// Decrypt data with secretbox (symmetric).
    func secretboxOpen(ciphertext: Data, nonce: Data, key: Data) throws -> Data {
        return try NaClSecretBox.open(ciphertext: ciphertext, nonce: nonce, key: key)
    }

    /// Encrypt data with box (asymmetric).
    func boxSeal(
        plaintext: Data,
        recipientPublicKey: Data,
        senderPrivateKey: Data
    ) throws -> (nonce: Data, ciphertext: Data) {
        return try NaClBox.seal(
            message: plaintext,
            recipientPublicKey: recipientPublicKey,
            senderPrivateKey: senderPrivateKey
        )
    }

    /// Decrypt data with box (asymmetric).
    func boxOpen(
        ciphertext: Data,
        nonce: Data,
        senderPublicKey: Data,
        recipientPrivateKey: Data
    ) throws -> Data {
        return try NaClBox.open(
            ciphertext: ciphertext,
            nonce: nonce,
            senderPublicKey: senderPublicKey,
            recipientPrivateKey: recipientPrivateKey
        )
    }
}
