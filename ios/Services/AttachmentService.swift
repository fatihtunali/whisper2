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

    // Server API base URL for presigned URLs
    private let serverBaseURL = Constants.baseURL

    // Cache for downloaded files
    private var downloadCache: [String: URL] = [:]

    // Cache for outgoing files (so sender can view their own sent attachments)
    private var outgoingCache: [String: URL] = [:]

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

    // MARK: - Presigned URL Response Types

    private struct PresignUploadResponse: Codable {
        let objectKey: String
        let uploadUrl: String
        let expiresAtMs: Int64
        let headers: [String: String]
    }

    private struct PresignDownloadResponse: Codable {
        let objectKey: String
        let downloadUrl: String
        let expiresAtMs: Int64
        let sizeBytes: Int
        let contentType: String
    }

    // MARK: - Cache Persistence

    private let cacheIndexKey = "whisper2.attachments.cache"
    private let outgoingCacheIndexKey = "whisper2.attachments.outgoing.cache"

    private func loadCacheIndex() {
        // Load download cache
        if let data = KeychainService.shared.getData(for: cacheIndexKey),
           let index = try? JSONDecoder().decode([String: String].self, from: data) {
            // Rebuild cache from stored paths
            for (objectKey, fileName) in index {
                let fileURL = attachmentsDirectory.appendingPathComponent(fileName)
                if FileManager.default.fileExists(atPath: fileURL.path) {
                    downloadCache[objectKey] = fileURL
                }
            }
        }

        // Load outgoing cache
        if let data = KeychainService.shared.getData(for: outgoingCacheIndexKey),
           let index = try? JSONDecoder().decode([String: String].self, from: data) {
            for (objectKey, fileName) in index {
                let fileURL = attachmentsDirectory.appendingPathComponent(fileName)
                if FileManager.default.fileExists(atPath: fileURL.path) {
                    outgoingCache[objectKey] = fileURL
                }
            }
        }
    }

    private func saveCacheIndex() {
        // Save download cache
        var index: [String: String] = [:]
        for (objectKey, url) in downloadCache {
            index[objectKey] = url.lastPathComponent
        }
        if let data = try? JSONEncoder().encode(index) {
            KeychainService.shared.save(data: data, for: cacheIndexKey)
        }

        // Save outgoing cache
        var outgoingIndex: [String: String] = [:]
        for (objectKey, url) in outgoingCache {
            outgoingIndex[objectKey] = url.lastPathComponent
        }
        if let data = try? JSONEncoder().encode(outgoingIndex) {
            KeychainService.shared.save(data: data, for: outgoingCacheIndexKey)
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
            print("[AttachmentService] ERROR: No current user - not authenticated")
            throw NetworkError.connectionFailed
        }

        guard let sessionToken = auth.sessionToken, !sessionToken.isEmpty else {
            print("[AttachmentService] ERROR: No session token available")
            throw NetworkError.connectionFailed
        }

        print("[AttachmentService] Starting upload with session token: \(sessionToken.prefix(20))...")

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

        // Combine nonce and ciphertext for upload
        var uploadData = Data()
        uploadData.append(encryptedFile.0)  // nonce from secretBoxSeal
        uploadData.append(encryptedFile.1)  // ciphertext

        // Encrypt file key for recipient using NaCl box
        let (keyBoxCiphertext, keyBoxNonce) = try crypto.encryptMessage(
            fileKey.base64EncodedString(),
            recipientPublicKey: recipientPublicKey,
            senderPrivateKey: user.encPrivateKey
        )

        // Determine content type
        let contentType = getContentType(for: fileURL)
        print("[AttachmentService] Uploading file: \(fileURL.lastPathComponent), contentType: \(contentType), size: \(uploadData.count)")

        // 1. Request presigned upload URL from server
        let presignURL = URL(string: "\(serverBaseURL)/attachments/presign/upload")!
        var presignRequest = URLRequest(url: presignURL)
        presignRequest.httpMethod = "POST"
        presignRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
        presignRequest.setValue("Bearer \(sessionToken)", forHTTPHeaderField: "Authorization")

        let presignBody: [String: Any] = [
            "contentType": contentType,
            "sizeBytes": uploadData.count
        ]
        presignRequest.httpBody = try JSONSerialization.data(withJSONObject: presignBody)

        let (presignData, presignResponse) = try await URLSession.shared.data(for: presignRequest)

        guard let httpPresignResponse = presignResponse as? HTTPURLResponse else {
            print("[AttachmentService] ERROR: No HTTP response from presign request")
            throw NetworkError.serverError(code: "NO_RESPONSE", message: "No response from server")
        }

        let statusCode = httpPresignResponse.statusCode
        print("[AttachmentService] Presign response status: \(statusCode)")

        guard (200...299).contains(statusCode) else {
            let errorMsg = String(data: presignData, encoding: .utf8) ?? "Unknown error"
            print("[AttachmentService] Presign upload failed (HTTP \(statusCode)): \(errorMsg)")

            if statusCode == 401 {
                throw NetworkError.serverError(code: "UNAUTHORIZED", message: "Session expired - please re-login")
            } else if statusCode == 403 {
                throw NetworkError.serverError(code: "FORBIDDEN", message: "Not authorized to upload")
            }
            throw NetworkError.serverError(code: "PRESIGN_FAILED", message: "Failed to get upload URL (HTTP \(statusCode))")
        }

        let presignResult = try JSONDecoder().decode(PresignUploadResponse.self, from: presignData)
        print("[AttachmentService] Got presigned URL for objectKey: \(presignResult.objectKey)")

        // 2. Upload to the presigned URL
        guard let uploadURL = URL(string: presignResult.uploadUrl) else {
            throw NetworkError.serverError(code: "INVALID_URL", message: "Invalid upload URL")
        }

        var uploadRequest = URLRequest(url: uploadURL)
        uploadRequest.httpMethod = "PUT"

        // Apply headers from server response
        for (key, value) in presignResult.headers {
            uploadRequest.setValue(value, forHTTPHeaderField: key)
        }

        // Track progress
        await MainActor.run {
            uploadProgress[messageId] = 0
        }

        let (_, uploadResponse) = try await uploadWithProgress(request: uploadRequest, data: uploadData, messageId: messageId)

        guard let httpUploadResponse = uploadResponse as? HTTPURLResponse,
              (200...299).contains(httpUploadResponse.statusCode) else {
            throw NetworkError.serverError(code: "UPLOAD_FAILED", message: "File upload failed")
        }

        // Clear progress
        await MainActor.run {
            uploadProgress.removeValue(forKey: messageId)
        }

        print("[AttachmentService] Upload successful for objectKey: \(presignResult.objectKey)")

        // Create file key box
        let fileKeyBox = FileKeyBox(
            nonce: keyBoxNonce.base64EncodedString(),
            ciphertext: keyBoxCiphertext.base64EncodedString()
        )

        // Return attachment pointer with server-provided objectKey
        return AttachmentPointer(
            objectKey: presignResult.objectKey,
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

        guard let sessionToken = auth.sessionToken else {
            throw NetworkError.connectionFailed
        }

        // Check cache
        if let cachedURL = downloadCache[attachment.objectKey],
           FileManager.default.fileExists(atPath: cachedURL.path) {
            return cachedURL
        }

        // 1. Request presigned download URL from server
        let presignURL = URL(string: "\(serverBaseURL)/attachments/presign/download")!
        var presignRequest = URLRequest(url: presignURL)
        presignRequest.httpMethod = "POST"
        presignRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
        presignRequest.setValue("Bearer \(sessionToken)", forHTTPHeaderField: "Authorization")

        let presignBody: [String: Any] = [
            "objectKey": attachment.objectKey
        ]
        presignRequest.httpBody = try JSONSerialization.data(withJSONObject: presignBody)

        let (presignData, presignResponse) = try await URLSession.shared.data(for: presignRequest)

        guard let httpPresignResponse = presignResponse as? HTTPURLResponse,
              (200...299).contains(httpPresignResponse.statusCode) else {
            let errorMsg = String(data: presignData, encoding: .utf8) ?? "Unknown error"
            print("[AttachmentService] Presign download failed: \(errorMsg)")
            throw NetworkError.serverError(code: "PRESIGN_FAILED", message: "Failed to get download URL")
        }

        let presignResult = try JSONDecoder().decode(PresignDownloadResponse.self, from: presignData)
        print("[AttachmentService] Got presigned download URL for objectKey: \(presignResult.objectKey)")

        // 2. Download from the presigned URL
        guard let downloadURL = URL(string: presignResult.downloadUrl) else {
            throw NetworkError.serverError(code: "INVALID_URL", message: "Invalid download URL")
        }

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

    // MARK: - Outgoing File Cache

    /// Cache an outgoing file so the sender can view it later
    /// Call this BEFORE uploading and deleting the original file
    func cacheOutgoingFile(_ fileURL: URL, forObjectKey objectKey: String) throws {
        // Read file data
        let fileData = try Data(contentsOf: fileURL)

        // Determine file extension
        let ext = fileURL.pathExtension.isEmpty ? "bin" : fileURL.pathExtension
        let fileName = "\(UUID().uuidString).\(ext)"
        let cachedURL = attachmentsDirectory.appendingPathComponent(fileName)

        // Write to cache
        try fileData.write(to: cachedURL)

        // Store in cache dictionary
        outgoingCache[objectKey] = cachedURL
        saveCacheIndex()

        print("[AttachmentService] Cached outgoing file: \(objectKey) -> \(cachedURL.lastPathComponent)")
    }

    /// Get the cached URL for an outgoing attachment
    func getOutgoingFileURL(forObjectKey objectKey: String) -> URL? {
        if let url = outgoingCache[objectKey],
           FileManager.default.fileExists(atPath: url.path) {
            return url
        }
        return nil
    }

    /// Check if an outgoing file is cached
    func hasOutgoingFileCache(forObjectKey objectKey: String) -> Bool {
        return getOutgoingFileURL(forObjectKey: objectKey) != nil
    }

    // MARK: - Helpers

    private func getContentType(for url: URL) -> String {
        let ext = url.pathExtension.lowercased()

        // Use explicit mappings for known types to ensure server compatibility
        switch ext {
        // Images
        case "jpg", "jpeg":
            return "image/jpeg"
        case "png":
            return "image/png"
        case "gif":
            return "image/gif"
        case "webp":
            return "image/webp"
        case "heic":
            return "image/heic"
        case "heif":
            return "image/heif"
        case "bmp":
            return "image/bmp"
        case "tiff", "tif":
            return "image/tiff"
        case "svg":
            return "image/svg+xml"

        // Videos - iOS
        case "mp4":
            return "video/mp4"
        case "mov":
            return "video/quicktime"
        case "m4v":
            return "video/x-m4v"

        // Videos - Android & cross-platform
        case "3gp":
            return "video/3gpp"
        case "3g2":
            return "video/3gpp2"
        case "webm":
            return "video/webm"
        case "mpeg", "mpg":
            return "video/mpeg"
        case "avi":
            return "video/x-msvideo"
        case "mkv":
            return "video/x-matroska"

        // Audio - iOS
        case "m4a":
            return "audio/m4a"
        case "aac":
            return "audio/aac"
        case "caf":
            return "audio/x-caf"
        case "aiff", "aif":
            return "audio/aiff"

        // Audio - cross-platform
        case "mp3":
            return "audio/mpeg"
        case "ogg", "oga":
            return "audio/ogg"
        case "wav":
            return "audio/wav"
        case "flac":
            return "audio/flac"
        case "amr":
            return "audio/amr"

        // Documents
        case "pdf":
            return "application/pdf"
        case "doc":
            return "application/msword"
        case "docx":
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        case "xls":
            return "application/vnd.ms-excel"
        case "xlsx":
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        case "ppt":
            return "application/vnd.ms-powerpoint"
        case "pptx":
            return "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        case "txt":
            return "text/plain"
        case "csv":
            return "text/csv"
        case "zip":
            return "application/zip"
        case "json":
            return "application/json"

        default:
            // Fallback to UTType
            if let utType = UTType(filenameExtension: ext) {
                return utType.preferredMIMEType ?? "application/octet-stream"
            }
            return "application/octet-stream"
        }
    }

    private func getFileExtension(for contentType: String) -> String {
        if let utType = UTType(mimeType: contentType),
           let ext = utType.preferredFilenameExtension {
            return ext
        }

        // Fallback mappings for all supported types
        switch contentType {
        // Images
        case "image/jpeg": return "jpg"
        case "image/png": return "png"
        case "image/gif": return "gif"
        case "image/webp": return "webp"
        case "image/heic": return "heic"
        case "image/heif": return "heif"
        case "image/bmp": return "bmp"
        case "image/tiff": return "tiff"
        case "image/svg+xml": return "svg"

        // Videos
        case "video/mp4": return "mp4"
        case "video/quicktime", "video/mov": return "mov"
        case "video/x-m4v", "video/m4v": return "m4v"
        case "video/3gpp": return "3gp"
        case "video/3gpp2": return "3g2"
        case "video/webm": return "webm"
        case "video/mpeg": return "mpeg"
        case "video/x-msvideo", "video/avi": return "avi"
        case "video/x-matroska": return "mkv"
        case "video/ogg": return "ogv"

        // Audio
        case "audio/m4a", "audio/x-m4a", "audio/mp4": return "m4a"
        case "audio/aac": return "aac"
        case "audio/mpeg", "audio/mp3": return "mp3"
        case "audio/ogg": return "ogg"
        case "audio/wav", "audio/x-wav": return "wav"
        case "audio/flac", "audio/x-flac": return "flac"
        case "audio/amr": return "amr"
        case "audio/x-caf": return "caf"
        case "audio/aiff", "audio/x-aiff": return "aiff"
        case "audio/webm": return "weba"
        case "audio/3gpp": return "3gp"

        // Documents
        case "application/pdf": return "pdf"
        case "application/msword": return "doc"
        case "application/vnd.openxmlformats-officedocument.wordprocessingml.document": return "docx"
        case "application/vnd.ms-excel": return "xls"
        case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": return "xlsx"
        case "application/vnd.ms-powerpoint": return "ppt"
        case "application/vnd.openxmlformats-officedocument.presentationml.presentation": return "pptx"
        case "text/plain": return "txt"
        case "text/csv": return "csv"
        case "application/zip", "application/x-zip-compressed": return "zip"
        case "application/json": return "json"

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

    /// Clear outgoing file cache
    func clearOutgoingCache() {
        for url in outgoingCache.values {
            try? FileManager.default.removeItem(at: url)
        }
        outgoingCache.removeAll()
        KeychainService.shared.delete(key: outgoingCacheIndexKey)
    }

    /// Clear all attachments (for wipe data)
    func clearAllAttachments() {
        clearCache()
        clearOutgoingCache()
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
