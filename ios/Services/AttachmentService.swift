import Foundation
import UIKit
import Combine
import UniformTypeIdentifiers

/// Service for handling file attachments (upload/download with encryption)
final class AttachmentService: ObservableObject {
    static let shared = AttachmentService()

    @Published private(set) var uploadProgress: [String: Double] = [:]  // messageId -> progress
    @Published private(set) var downloadProgress: [String: Double] = [:]  // objectKey -> progress

    private let crypto = CryptoService.shared
    private let auth = AuthService.shared
    private let contacts = ContactsService.shared

    // S3/Spaces configuration - these should come from server or be configured
    private let uploadBaseURL = "https://whisper2-attachments.nyc3.digitaloceanspaces.com"

    // Cache for downloaded files
    private var downloadCache: [String: URL] = [:]

    // Persistent storage directory for attachments
    private lazy var attachmentsDirectory: URL = {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let attachmentsDir = docs.appendingPathComponent("Attachments", isDirectory: true)
        try? FileManager.default.createDirectory(at: attachmentsDir, withIntermediateDirectories: true)
        return attachmentsDir
    }()

    private init() {
        loadCacheIndex()
    }

    // MARK: - Cache Persistence

    private let cacheIndexKey = "whisper2.attachments.cache"

    private func loadCacheIndex() {
        guard let data = KeychainService.shared.getData(for: cacheIndexKey),
              let index = try? JSONDecoder().decode([String: String].self, from: data) else {
            return
        }
        // Rebuild cache from stored paths
        for (objectKey, fileName) in index {
            let fileURL = attachmentsDirectory.appendingPathComponent(fileName)
            if FileManager.default.fileExists(atPath: fileURL.path) {
                downloadCache[objectKey] = fileURL
            }
        }
    }

    private func saveCacheIndex() {
        var index: [String: String] = [:]
        for (objectKey, url) in downloadCache {
            index[objectKey] = url.lastPathComponent
        }
        if let data = try? JSONEncoder().encode(index) {
            KeychainService.shared.save(data: data, for: cacheIndexKey)
        }
    }

    // MARK: - Upload

    /// Encrypt and upload a file, returning the attachment pointer
    func uploadFile(
        _ fileURL: URL,
        recipientPublicKey: Data,
        messageId: String
    ) async throws -> AttachmentPointer {
        guard let user = auth.currentUser else {
            throw NetworkError.connectionFailed
        }

        // Read file data
        let fileData = try Data(contentsOf: fileURL)

        // Generate random file key (32 bytes for XSalsa20-Poly1305)
        let fileKey = try crypto.randomBytes(32)

        // Generate nonce for file encryption
        let fileNonce = try crypto.randomBytes(24)

        // Encrypt file contents with file key
        let encryptedFile = try crypto.secretBoxSeal(
            message: fileData,
            key: fileKey
        )

        // Encrypt file key for recipient using NaCl box
        let (keyBoxCiphertext, keyBoxNonce) = try crypto.encryptMessage(
            fileKey.base64EncodedString(),
            recipientPublicKey: recipientPublicKey,
            senderPrivateKey: user.encPrivateKey
        )

        // Determine content type
        let contentType = getContentType(for: fileURL)

        // Generate object key (S3 path)
        let objectKey = "attachments/\(user.whisperId)/\(messageId)/\(UUID().uuidString)"

        // Upload to S3
        let uploadURL = URL(string: "\(uploadBaseURL)/\(objectKey)")!

        var request = URLRequest(url: uploadURL)
        request.httpMethod = "PUT"
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        request.setValue("public-read", forHTTPHeaderField: "x-amz-acl")

        // Track progress
        await MainActor.run {
            uploadProgress[messageId] = 0
        }

        // Combine nonce and ciphertext for upload
        var uploadData = Data()
        uploadData.append(encryptedFile.0)  // nonce from secretBoxSeal
        uploadData.append(encryptedFile.1)  // ciphertext

        let (_, response) = try await uploadWithProgress(request: request, data: uploadData, messageId: messageId)

        guard let httpResponse = response as? HTTPURLResponse,
              (200...299).contains(httpResponse.statusCode) else {
            throw NetworkError.serverError(code: "UPLOAD_FAILED", message: "File upload failed")
        }

        // Clear progress
        await MainActor.run {
            uploadProgress.removeValue(forKey: messageId)
        }

        // Create file key box
        let fileKeyBox = FileKeyBox(
            nonce: keyBoxNonce.base64EncodedString(),
            ciphertext: keyBoxCiphertext.base64EncodedString()
        )

        // Return attachment pointer
        return AttachmentPointer(
            objectKey: objectKey,
            contentType: contentType,
            ciphertextSize: uploadData.count,
            fileNonce: fileNonce.base64EncodedString(),
            fileKeyBox: fileKeyBox
        )
    }

    private func uploadWithProgress(request: URLRequest, data: Data, messageId: String) async throws -> (Data?, URLResponse) {
        return try await withCheckedThrowingContinuation { continuation in
            let task = URLSession.shared.uploadTask(with: request, from: data) { data, response, error in
                if let error = error {
                    continuation.resume(throwing: error)
                } else if let response = response {
                    continuation.resume(returning: (data, response))
                } else {
                    continuation.resume(throwing: NetworkError.connectionFailed)
                }
            }

            // Observe progress
            let observation = task.progress.observe(\.fractionCompleted) { progress, _ in
                Task { @MainActor in
                    self.uploadProgress[messageId] = progress.fractionCompleted
                }
            }

            // Store observation to prevent deallocation
            objc_setAssociatedObject(task, "progressObservation", observation, .OBJC_ASSOCIATION_RETAIN)

            task.resume()
        }
    }

    // MARK: - Download

    /// Download and decrypt an attachment
    func downloadFile(
        _ attachment: AttachmentPointer,
        senderPublicKey: Data
    ) async throws -> URL {
        guard let user = auth.currentUser else {
            throw NetworkError.connectionFailed
        }

        // Check cache
        if let cachedURL = downloadCache[attachment.objectKey],
           FileManager.default.fileExists(atPath: cachedURL.path) {
            return cachedURL
        }

        // Download encrypted file
        let downloadURL = URL(string: "\(uploadBaseURL)/\(attachment.objectKey)")!

        await MainActor.run {
            downloadProgress[attachment.objectKey] = 0
        }

        let (data, response) = try await downloadWithProgress(url: downloadURL, objectKey: attachment.objectKey)

        guard let httpResponse = response as? HTTPURLResponse,
              (200...299).contains(httpResponse.statusCode),
              let encryptedData = data else {
            throw NetworkError.serverError(code: "DOWNLOAD_FAILED", message: "File download failed")
        }

        // Decrypt file key box
        guard let keyBoxNonce = Data(base64Encoded: attachment.fileKeyBox.nonce),
              let keyBoxCiphertext = Data(base64Encoded: attachment.fileKeyBox.ciphertext) else {
            throw CryptoError.decryptionFailed
        }

        let fileKeyBase64 = try crypto.decryptMessage(
            ciphertext: keyBoxCiphertext,
            nonce: keyBoxNonce,
            senderPublicKey: senderPublicKey,
            recipientPrivateKey: user.encPrivateKey
        )

        guard let fileKey = Data(base64Encoded: fileKeyBase64) else {
            throw CryptoError.decryptionFailed
        }

        // Decrypt file
        guard encryptedData.count > 24 else {
            throw CryptoError.decryptionFailed
        }

        let nonce = encryptedData.prefix(24)
        let ciphertext = encryptedData.dropFirst(24)

        let decryptedData = try crypto.secretBoxOpen(
            ciphertext: Data(ciphertext),
            nonce: Data(nonce),
            key: fileKey
        )

        // Save to persistent attachments directory
        let fileExtension = getFileExtension(for: attachment.contentType)
        let fileName = "\(UUID().uuidString).\(fileExtension)"
        let fileURL = attachmentsDirectory.appendingPathComponent(fileName)

        try decryptedData.write(to: fileURL)

        // Cache the URL and persist the index
        downloadCache[attachment.objectKey] = fileURL
        saveCacheIndex()

        // Clear progress
        await MainActor.run {
            downloadProgress.removeValue(forKey: attachment.objectKey)
        }

        return fileURL
    }

    private func downloadWithProgress(url: URL, objectKey: String) async throws -> (Data?, URLResponse) {
        return try await withCheckedThrowingContinuation { continuation in
            let task = URLSession.shared.dataTask(with: url) { data, response, error in
                if let error = error {
                    continuation.resume(throwing: error)
                } else if let response = response {
                    continuation.resume(returning: (data, response))
                } else {
                    continuation.resume(throwing: NetworkError.connectionFailed)
                }
            }

            // Observe progress
            let observation = task.progress.observe(\.fractionCompleted) { progress, _ in
                Task { @MainActor in
                    self.downloadProgress[objectKey] = progress.fractionCompleted
                }
            }

            objc_setAssociatedObject(task, "progressObservation", observation, .OBJC_ASSOCIATION_RETAIN)

            task.resume()
        }
    }

    // MARK: - Helpers

    private func getContentType(for url: URL) -> String {
        if let utType = UTType(filenameExtension: url.pathExtension) {
            return utType.preferredMIMEType ?? "application/octet-stream"
        }
        return "application/octet-stream"
    }

    private func getFileExtension(for contentType: String) -> String {
        if let utType = UTType(mimeType: contentType),
           let ext = utType.preferredFilenameExtension {
            return ext
        }

        // Fallback mappings
        switch contentType {
        case "image/jpeg": return "jpg"
        case "image/png": return "png"
        case "image/gif": return "gif"
        case "video/mp4": return "mp4"
        case "video/quicktime": return "mov"
        case "audio/mpeg": return "mp3"
        case "audio/m4a": return "m4a"
        case "application/pdf": return "pdf"
        default: return "bin"
        }
    }

    /// Check if a file is an image
    func isImage(_ contentType: String) -> Bool {
        return contentType.hasPrefix("image/")
    }

    /// Check if a file is a video
    func isVideo(_ contentType: String) -> Bool {
        return contentType.hasPrefix("video/")
    }

    /// Check if a file is audio
    func isAudio(_ contentType: String) -> Bool {
        return contentType.hasPrefix("audio/")
    }

    /// Get file size in human-readable format
    func formatFileSize(_ bytes: Int) -> String {
        let formatter = ByteCountFormatter()
        formatter.countStyle = .file
        return formatter.string(fromByteCount: Int64(bytes))
    }

    /// Clear download cache
    func clearCache() {
        for url in downloadCache.values {
            try? FileManager.default.removeItem(at: url)
        }
        downloadCache.removeAll()
        KeychainService.shared.delete(key: cacheIndexKey)
    }

    /// Clear all attachments (for wipe data)
    func clearAllAttachments() {
        clearCache()
        // Also remove the entire attachments directory
        try? FileManager.default.removeItem(at: attachmentsDirectory)
        // Recreate the directory
        try? FileManager.default.createDirectory(at: attachmentsDirectory, withIntermediateDirectories: true)
    }

    // MARK: - Thumbnail Generation

    /// Generate thumbnail for image/video
    func generateThumbnail(for url: URL, maxSize: CGSize = CGSize(width: 200, height: 200)) async -> UIImage? {
        if let image = UIImage(contentsOfFile: url.path) {
            return resizeImage(image, to: maxSize)
        }

        // For video, extract first frame
        if isVideo(getContentType(for: url)) {
            return await extractVideoThumbnail(from: url, at: 0)
        }

        return nil
    }

    private func resizeImage(_ image: UIImage, to size: CGSize) -> UIImage {
        let aspectWidth = size.width / image.size.width
        let aspectHeight = size.height / image.size.height
        let aspectRatio = min(aspectWidth, aspectHeight)

        let newSize = CGSize(
            width: image.size.width * aspectRatio,
            height: image.size.height * aspectRatio
        )

        UIGraphicsBeginImageContextWithOptions(newSize, false, 0)
        image.draw(in: CGRect(origin: .zero, size: newSize))
        let resizedImage = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()

        return resizedImage ?? image
    }

    private func extractVideoThumbnail(from url: URL, at time: TimeInterval) async -> UIImage? {
        // Use AVAssetImageGenerator
        return nil // TODO: Implement video thumbnail
    }
}

// MARK: - Attachment Model

/// Local attachment model for UI
struct Attachment: Identifiable {
    let id: String
    let type: AttachmentType
    let localURL: URL?
    let remotePointer: AttachmentPointer?
    var downloadState: AttachmentDownloadState = .notStarted

    enum AttachmentType {
        case image
        case video
        case audio
        case document
    }

    enum AttachmentDownloadState {
        case notStarted
        case downloading(progress: Double)
        case downloaded(url: URL)
        case failed(error: String)
    }
}
