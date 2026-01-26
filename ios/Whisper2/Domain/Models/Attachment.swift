import Foundation

/// Attachment - Represents an encrypted file attachment
/// Files are encrypted with a random key, uploaded to storage,
/// and the key is included in the encrypted message

struct Attachment: Codable, Equatable {

    // MARK: - Properties

    /// Storage object key (path in DigitalOcean Spaces)
    let objectKey: String

    /// MIME content type (e.g., "image/jpeg", "video/mp4")
    let contentType: String

    /// File size in bytes
    let size: Int64

    /// Nonce used for file encryption (base64)
    let fileNonce: String

    /// Encrypted file key (encrypted with message key, base64)
    let fileKeyBox: String

    /// Original filename
    var filename: String?

    /// Duration in seconds (for audio/video)
    var duration: Double?

    /// Width in pixels (for images/videos)
    var width: Int?

    /// Height in pixels (for images/videos)
    var height: Int?

    /// Thumbnail data (base64, for images/videos)
    var thumbnailData: String?

    /// Local file URL (for caching)
    var localUrl: URL?

    /// Download progress (0.0 - 1.0)
    var downloadProgress: Double?

    /// Upload progress (0.0 - 1.0)
    var uploadProgress: Double?

    /// Current transfer state
    var transferState: AttachmentTransferState

    // MARK: - Computed Properties

    /// The attachment type based on content type
    var type: AttachmentType {
        AttachmentType.from(contentType: contentType)
    }

    /// Human-readable file size
    var formattedSize: String {
        ByteCountFormatter.string(fromByteCount: size, countStyle: .file)
    }

    /// Duration formatted as mm:ss
    var formattedDuration: String? {
        guard let duration = duration else { return nil }
        let minutes = Int(duration) / 60
        let seconds = Int(duration) % 60
        return String(format: "%d:%02d", minutes, seconds)
    }

    /// Whether the file has been downloaded locally
    var isDownloaded: Bool {
        guard let url = localUrl else { return false }
        return FileManager.default.fileExists(atPath: url.path)
    }

    /// Whether the file is currently being transferred
    var isTransferring: Bool {
        transferState == .downloading || transferState == .uploading
    }

    /// Full URL to the file in storage
    var remoteUrl: URL? {
        // Construct URL from base storage URL + object key
        let baseUrl = "\(Constants.Server.baseURL)/files"
        return URL(string: "\(baseUrl)/\(objectKey)")
    }

    // MARK: - Initialization

    /// Create an attachment for upload
    /// - Parameters:
    ///   - localUrl: Local file URL
    ///   - contentType: MIME type
    ///   - filename: Original filename
    ///   - fileNonce: Encryption nonce (base64)
    ///   - fileKeyBox: Encrypted file key (base64)
    static func forUpload(
        localUrl: URL,
        contentType: String,
        filename: String,
        fileNonce: String,
        fileKeyBox: String
    ) throws -> Attachment {
        // Get file size
        let attributes = try FileManager.default.attributesOfItem(atPath: localUrl.path)
        let size = attributes[.size] as? Int64 ?? 0

        guard size <= Constants.Limits.maxAttachmentSize else {
            throw AttachmentError.fileTooLarge
        }

        // Generate object key
        let objectKey = "attachments/\(UUID().uuidString)/\(filename)"

        return Attachment(
            objectKey: objectKey,
            contentType: contentType,
            size: size,
            fileNonce: fileNonce,
            fileKeyBox: fileKeyBox,
            filename: filename,
            duration: nil,
            width: nil,
            height: nil,
            thumbnailData: nil,
            localUrl: localUrl,
            downloadProgress: nil,
            uploadProgress: 0.0,
            transferState: .pending
        )
    }

    /// Create an attachment from received message
    /// - Parameters:
    ///   - objectKey: Storage object key
    ///   - contentType: MIME type
    ///   - size: File size in bytes
    ///   - fileNonce: Encryption nonce (base64)
    ///   - fileKeyBox: Encrypted file key (base64)
    ///   - filename: Original filename
    ///   - metadata: Additional metadata (duration, dimensions, thumbnail)
    static func fromReceived(
        objectKey: String,
        contentType: String,
        size: Int64,
        fileNonce: String,
        fileKeyBox: String,
        filename: String?,
        metadata: AttachmentMetadata? = nil
    ) -> Attachment {
        Attachment(
            objectKey: objectKey,
            contentType: contentType,
            size: size,
            fileNonce: fileNonce,
            fileKeyBox: fileKeyBox,
            filename: filename,
            duration: metadata?.duration,
            width: metadata?.width,
            height: metadata?.height,
            thumbnailData: metadata?.thumbnailData,
            localUrl: nil,
            downloadProgress: nil,
            uploadProgress: nil,
            transferState: .pending
        )
    }

    // MARK: - Full Initializer

    init(
        objectKey: String,
        contentType: String,
        size: Int64,
        fileNonce: String,
        fileKeyBox: String,
        filename: String?,
        duration: Double?,
        width: Int?,
        height: Int?,
        thumbnailData: String?,
        localUrl: URL?,
        downloadProgress: Double?,
        uploadProgress: Double?,
        transferState: AttachmentTransferState
    ) {
        self.objectKey = objectKey
        self.contentType = contentType
        self.size = size
        self.fileNonce = fileNonce
        self.fileKeyBox = fileKeyBox
        self.filename = filename
        self.duration = duration
        self.width = width
        self.height = height
        self.thumbnailData = thumbnailData
        self.localUrl = localUrl
        self.downloadProgress = downloadProgress
        self.uploadProgress = uploadProgress
        self.transferState = transferState
    }

    // MARK: - Mutating Methods

    /// Update download progress
    mutating func updateDownloadProgress(_ progress: Double) {
        self.downloadProgress = progress
        self.transferState = progress < 1.0 ? .downloading : .completed
    }

    /// Update upload progress
    mutating func updateUploadProgress(_ progress: Double) {
        self.uploadProgress = progress
        self.transferState = progress < 1.0 ? .uploading : .completed
    }

    /// Set local URL after download
    mutating func setLocalUrl(_ url: URL) {
        self.localUrl = url
        self.transferState = .completed
        self.downloadProgress = 1.0
    }

    /// Mark as failed
    mutating func markFailed() {
        self.transferState = .failed
    }
}

// MARK: - Attachment Type

enum AttachmentType: String, Codable {
    case image
    case video
    case audio
    case document
    case other

    static func from(contentType: String) -> AttachmentType {
        let type = contentType.lowercased()

        if type.hasPrefix("image/") {
            return .image
        } else if type.hasPrefix("video/") {
            return .video
        } else if type.hasPrefix("audio/") {
            return .audio
        } else if type.hasPrefix("application/pdf") ||
                    type.hasPrefix("application/msword") ||
                    type.hasPrefix("application/vnd.") ||
                    type.hasPrefix("text/") {
            return .document
        }

        return .other
    }

    var icon: String {
        switch self {
        case .image: return "photo"
        case .video: return "video"
        case .audio: return "waveform"
        case .document: return "doc"
        case .other: return "paperclip"
        }
    }
}

// MARK: - Attachment Transfer State

enum AttachmentTransferState: String, Codable {
    case pending
    case uploading
    case downloading
    case completed
    case failed
}

// MARK: - Attachment Metadata

struct AttachmentMetadata: Codable {
    var duration: Double?
    var width: Int?
    var height: Int?
    var thumbnailData: String?
}

// MARK: - Attachment Errors

enum AttachmentError: WhisperError {
    case fileTooLarge
    case fileNotFound
    case uploadFailed(reason: String)
    case downloadFailed(reason: String)
    case decryptionFailed
    case invalidContentType

    var code: String {
        switch self {
        case .fileTooLarge: return "ATTACHMENT_TOO_LARGE"
        case .fileNotFound: return "ATTACHMENT_NOT_FOUND"
        case .uploadFailed: return "ATTACHMENT_UPLOAD_FAILED"
        case .downloadFailed: return "ATTACHMENT_DOWNLOAD_FAILED"
        case .decryptionFailed: return "ATTACHMENT_DECRYPTION_FAILED"
        case .invalidContentType: return "ATTACHMENT_INVALID_TYPE"
        }
    }

    var message: String {
        switch self {
        case .fileTooLarge:
            return "File exceeds maximum size of \(ByteCountFormatter.string(fromByteCount: Int64(Constants.Limits.maxAttachmentSize), countStyle: .file))"
        case .fileNotFound:
            return "File not found"
        case .uploadFailed(let reason):
            return "Upload failed: \(reason)"
        case .downloadFailed(let reason):
            return "Download failed: \(reason)"
        case .decryptionFailed:
            return "Failed to decrypt attachment"
        case .invalidContentType:
            return "Invalid file type"
        }
    }
}
