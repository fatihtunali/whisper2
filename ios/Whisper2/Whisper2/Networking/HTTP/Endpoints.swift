import Foundation

/// Whisper2 HTTP API Endpoints
/// All HTTP endpoint definitions matching server HttpAdmin.ts

// MARK: - Endpoint Protocol

protocol Endpoint {
    associatedtype RequestBody: Encodable
    associatedtype ResponseBody: Decodable

    var path: String { get }
    var method: HTTPMethod { get }
    var requiresAuth: Bool { get }
}

// MARK: - HTTP Method

enum HTTPMethod: String {
    case GET
    case POST
    case PUT
    case DELETE
}

// MARK: - Empty Request/Response

/// Marker type for endpoints with no request body
struct EmptyRequest: Encodable {}

/// Marker type for endpoints with no response body
struct EmptyResponse: Decodable {}

// MARK: - Key Lookup Endpoint

/// GET /users/{whisperId}/keys
/// Lookup a user's public keys for adding contact by ID/QR
struct KeyLookupEndpoint: Endpoint {
    typealias RequestBody = EmptyRequest
    typealias ResponseBody = KeyLookupResponse

    let whisperId: String

    var path: String { "/users/\(whisperId)/keys" }
    let method: HTTPMethod = .GET
    let requiresAuth: Bool = true
}

struct KeyLookupResponse: Decodable {
    let whisperId: String
    let encPublicKey: String   // base64
    let signPublicKey: String  // base64
    let status: String         // "active"
}

// MARK: - Contacts Backup Endpoints

/// PUT /backup/contacts
/// Upload or replace encrypted contacts backup
struct ContactsBackupUploadEndpoint: Endpoint {
    typealias RequestBody = ContactsBackupRequest
    typealias ResponseBody = ContactsBackupUploadResponse

    let path: String = "/backup/contacts"
    let method: HTTPMethod = .PUT
    let requiresAuth: Bool = true
}

struct ContactsBackupRequest: Encodable {
    let nonce: String        // base64(24 bytes)
    let ciphertext: String   // base64
}

struct ContactsBackupUploadResponse: Decodable {
    let success: Bool
    let created: Bool
    let sizeBytes: Int
    let updatedAt: Int64     // timestamp ms
}

/// GET /backup/contacts
/// Download encrypted contacts backup
struct ContactsBackupDownloadEndpoint: Endpoint {
    typealias RequestBody = EmptyRequest
    typealias ResponseBody = ContactsBackupDownloadResponse

    let path: String = "/backup/contacts"
    let method: HTTPMethod = .GET
    let requiresAuth: Bool = true
}

struct ContactsBackupDownloadResponse: Decodable {
    let nonce: String        // base64(24 bytes)
    let ciphertext: String   // base64
    let sizeBytes: Int
    let updatedAt: Int64     // timestamp ms
}

/// DELETE /backup/contacts
/// Delete contacts backup
struct ContactsBackupDeleteEndpoint: Endpoint {
    typealias RequestBody = EmptyRequest
    typealias ResponseBody = ContactsBackupDeleteResponse

    let path: String = "/backup/contacts"
    let method: HTTPMethod = .DELETE
    let requiresAuth: Bool = true
}

struct ContactsBackupDeleteResponse: Decodable {
    let success: Bool
}

// MARK: - Attachment Endpoints

/// POST /attachments/presign/upload
/// Request a presigned URL for uploading an encrypted attachment
struct AttachmentPresignUploadEndpoint: Endpoint {
    typealias RequestBody = AttachmentPresignUploadRequest
    typealias ResponseBody = AttachmentPresignUploadResponse

    let path: String = "/attachments/presign/upload"
    let method: HTTPMethod = .POST
    let requiresAuth: Bool = true
}

struct AttachmentPresignUploadRequest: Encodable {
    let contentType: String
    let sizeBytes: Int
}

struct AttachmentPresignUploadResponse: Decodable {
    let objectKey: String
    let uploadUrl: String
    let expiresAtMs: Int64
    let headers: [String: String]
}

/// POST /attachments/presign/download
/// Request a presigned URL for downloading an encrypted attachment
struct AttachmentPresignDownloadEndpoint: Endpoint {
    typealias RequestBody = AttachmentPresignDownloadRequest
    typealias ResponseBody = AttachmentPresignDownloadResponse

    let path: String = "/attachments/presign/download"
    let method: HTTPMethod = .POST
    let requiresAuth: Bool = true
}

struct AttachmentPresignDownloadRequest: Encodable {
    let objectKey: String
}

struct AttachmentPresignDownloadResponse: Decodable {
    let objectKey: String
    let downloadUrl: String
    let expiresAtMs: Int64
    let sizeBytes: Int
    let contentType: String
}

// MARK: - Health Endpoints (No Auth)

/// GET /health
/// Basic liveness check
struct HealthEndpoint: Endpoint {
    typealias RequestBody = EmptyRequest
    typealias ResponseBody = HealthResponse

    let path: String = "/health"
    let method: HTTPMethod = .GET
    let requiresAuth: Bool = false
}

struct HealthResponse: Decodable {
    let status: String       // "healthy", "degraded", "unhealthy"
    let postgres: Bool
    let redis: Bool
    let uptime: Int64
    let timestamp: Int64
}

/// GET /ready
/// Full readiness check
struct ReadyEndpoint: Endpoint {
    typealias RequestBody = EmptyRequest
    typealias ResponseBody = ReadyResponse

    let path: String = "/ready"
    let method: HTTPMethod = .GET
    let requiresAuth: Bool = false
}

struct ReadyResponse: Decodable {
    let ready: Bool
    var postgres: Bool?
    var redis: Bool?
}

// MARK: - Error Response

/// Standard HTTP error response from server
struct HTTPErrorResponse: Decodable {
    let error: String        // Error code
    let message: String
    var retryAfter: Int?     // For rate limiting
}

// MARK: - URL Builder

extension Endpoint {
    /// Build the full URL for this endpoint
    func buildURL(baseURL: URL = Constants.Server.httpBaseURL) -> URL? {
        var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: true)
        components?.path = path
        return components?.url
    }
}

// MARK: - Allowed Content Types

/// Content types allowed for attachment upload
enum AllowedContentType: String, CaseIterable {
    case jpeg = "image/jpeg"
    case png = "image/png"
    case gif = "image/gif"
    case webp = "image/webp"
    case heic = "image/heic"
    case mp4 = "video/mp4"
    case quicktime = "video/quicktime"
    case aac = "audio/aac"
    case m4a = "audio/m4a"
    case mpeg = "audio/mpeg"
    case ogg = "audio/ogg"
    case pdf = "application/pdf"
    case octetStream = "application/octet-stream"

    static func isAllowed(_ contentType: String) -> Bool {
        Self.allCases.contains { $0.rawValue == contentType }
    }
}
