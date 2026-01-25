import Foundation

/// Whisper2 HTTP API Client
/// Handles authenticated HTTP requests with JSON encoding/decoding

// MARK: - API Client

actor APIClient {

    // MARK: - Properties

    private let baseURL: URL
    private let session: URLSession
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder

    /// Session token provider (injected to avoid circular dependency)
    private var sessionTokenProvider: (() async -> String?)?

    // MARK: - Singleton

    static let shared = APIClient()

    // MARK: - Init

    init(
        baseURL: URL = Constants.Server.httpBaseURL,
        sessionConfiguration: URLSessionConfiguration = .default
    ) {
        self.baseURL = baseURL

        // Configure session
        sessionConfiguration.timeoutIntervalForRequest = Constants.Timeout.httpRequest
        sessionConfiguration.timeoutIntervalForResource = Constants.Timeout.httpRequest * 2
        self.session = URLSession(configuration: sessionConfiguration)

        // Configure encoder/decoder
        self.encoder = JSONEncoder()
        self.encoder.keyEncodingStrategy = .useDefaultKeys

        self.decoder = JSONDecoder()
        self.decoder.keyDecodingStrategy = .useDefaultKeys
    }

    // MARK: - Session Token Provider

    /// Set the session token provider (called by AuthService)
    func setSessionTokenProvider(_ provider: @escaping () async -> String?) {
        self.sessionTokenProvider = provider
    }

    // MARK: - Request Methods

    /// Execute a typed endpoint request
    func request<E: Endpoint>(
        _ endpoint: E,
        body: E.RequestBody? = nil
    ) async throws -> E.ResponseBody {
        guard let url = endpoint.buildURL(baseURL: baseURL) else {
            throw NetworkError.invalidURL
        }

        var request = URLRequest(url: url)
        request.httpMethod = endpoint.method.rawValue
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        // Add auth header if required
        if endpoint.requiresAuth {
            guard let token = await sessionTokenProvider?() else {
                throw AuthError.notAuthenticated
            }
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        // Encode body if present and not EmptyRequest
        if let body = body, !(body is EmptyRequest) {
            request.httpBody = try encoder.encode(body)
        }

        return try await execute(request)
    }

    /// Execute a GET request
    func get<R: Decodable>(
        path: String,
        queryItems: [URLQueryItem]? = nil,
        authenticated: Bool = true
    ) async throws -> R {
        var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: true)!
        components.path = path
        components.queryItems = queryItems

        guard let url = components.url else {
            throw NetworkError.invalidURL
        }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        if authenticated {
            guard let token = await sessionTokenProvider?() else {
                throw AuthError.notAuthenticated
            }
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        return try await execute(request)
    }

    /// Execute a POST request
    func post<T: Encodable, R: Decodable>(
        path: String,
        body: T,
        authenticated: Bool = true
    ) async throws -> R {
        var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: true)!
        components.path = path

        guard let url = components.url else {
            throw NetworkError.invalidURL
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = try encoder.encode(body)

        if authenticated {
            guard let token = await sessionTokenProvider?() else {
                throw AuthError.notAuthenticated
            }
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        return try await execute(request)
    }

    /// Execute a PUT request
    func put<T: Encodable, R: Decodable>(
        path: String,
        body: T,
        authenticated: Bool = true
    ) async throws -> R {
        var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: true)!
        components.path = path

        guard let url = components.url else {
            throw NetworkError.invalidURL
        }

        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = try encoder.encode(body)

        if authenticated {
            guard let token = await sessionTokenProvider?() else {
                throw AuthError.notAuthenticated
            }
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        return try await execute(request)
    }

    /// Execute a DELETE request
    func delete<R: Decodable>(
        path: String,
        authenticated: Bool = true
    ) async throws -> R {
        var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: true)!
        components.path = path

        guard let url = components.url else {
            throw NetworkError.invalidURL
        }

        var request = URLRequest(url: url)
        request.httpMethod = "DELETE"
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        if authenticated {
            guard let token = await sessionTokenProvider?() else {
                throw AuthError.notAuthenticated
            }
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        return try await execute(request)
    }

    // MARK: - Raw Data Methods

    /// Upload data to a presigned URL (for attachments)
    func uploadToPresignedURL(
        _ url: URL,
        data: Data,
        headers: [String: String]
    ) async throws {
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.httpBody = data

        for (key, value) in headers {
            request.setValue(value, forHTTPHeaderField: key)
        }

        let (_, response) = try await session.data(for: request)

        guard let httpResponse = response as? HTTPURLResponse else {
            throw NetworkError.invalidResponse
        }

        guard (200...299).contains(httpResponse.statusCode) else {
            throw NetworkError.httpError(statusCode: httpResponse.statusCode, message: "Upload failed")
        }

        logger.debug("Upload successful: \(data.count) bytes", category: .network)
    }

    /// Download data from a presigned URL (for attachments)
    func downloadFromPresignedURL(_ url: URL) async throws -> Data {
        var request = URLRequest(url: url)
        request.httpMethod = "GET"

        let (data, response) = try await session.data(for: request)

        guard let httpResponse = response as? HTTPURLResponse else {
            throw NetworkError.invalidResponse
        }

        guard (200...299).contains(httpResponse.statusCode) else {
            throw NetworkError.httpError(statusCode: httpResponse.statusCode, message: "Download failed")
        }

        logger.debug("Download successful: \(data.count) bytes", category: .network)
        return data
    }

    // MARK: - Private

    private func execute<R: Decodable>(_ request: URLRequest) async throws -> R {
        let logMethod = request.httpMethod ?? "?"
        let logPath = request.url?.path ?? "?"

        logger.debug("HTTP \(logMethod) \(logPath)", category: .network)

        do {
            let (data, response) = try await session.data(for: request)

            guard let httpResponse = response as? HTTPURLResponse else {
                throw NetworkError.invalidResponse
            }

            let statusCode = httpResponse.statusCode

            // Check for error response
            if !(200...299).contains(statusCode) {
                return try handleErrorResponse(data: data, statusCode: statusCode)
            }

            // Handle empty response
            if R.self == EmptyResponse.self {
                return EmptyResponse() as! R
            }

            // Decode successful response
            do {
                let result = try decoder.decode(R.self, from: data)
                logger.debug("HTTP \(logMethod) \(logPath) -> \(statusCode)", category: .network)
                return result
            } catch {
                logger.error("Decoding failed: \(error.localizedDescription)", category: .network)
                throw NetworkError.decodingFailed
            }

        } catch let error as NetworkError {
            throw error
        } catch let error as AuthError {
            throw error
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            logger.error("HTTP request failed: \(error.localizedDescription)", category: .network)

            if (error as NSError).code == NSURLErrorTimedOut {
                throw NetworkError.timeout
            }

            throw NetworkError.connectionFailed
        }
    }

    private func handleErrorResponse<R: Decodable>(data: Data, statusCode: Int) throws -> R {
        // Try to decode error response
        if let errorResponse = try? decoder.decode(HTTPErrorResponse.self, from: data) {
            logger.warning("HTTP Error \(statusCode): [\(errorResponse.error)] \(errorResponse.message)", category: .network)

            switch statusCode {
            case 401:
                throw AuthError.notAuthenticated
            case 403:
                if errorResponse.error == "USER_BANNED" {
                    throw NetworkError.serverError(code: errorResponse.error, message: errorResponse.message)
                }
                throw AuthError.notAuthenticated
            case 404:
                throw NetworkError.serverError(code: errorResponse.error, message: errorResponse.message)
            case 429:
                throw NetworkError.serverError(code: "RATE_LIMITED", message: errorResponse.message)
            default:
                throw NetworkError.serverError(code: errorResponse.error, message: errorResponse.message)
            }
        }

        // Generic HTTP error
        throw NetworkError.httpError(statusCode: statusCode, message: nil)
    }
}

// MARK: - Convenience Methods

extension APIClient {

    /// Lookup user's public keys
    func lookupKeys(whisperId: String) async throws -> KeyLookupResponse {
        let endpoint = KeyLookupEndpoint(whisperId: whisperId)
        return try await request(endpoint, body: nil)
    }

    /// Upload contacts backup
    func uploadContactsBackup(nonce: String, ciphertext: String) async throws -> ContactsBackupUploadResponse {
        let endpoint = ContactsBackupUploadEndpoint()
        let body = ContactsBackupRequest(nonce: nonce, ciphertext: ciphertext)
        return try await request(endpoint, body: body)
    }

    /// Download contacts backup
    func downloadContactsBackup() async throws -> ContactsBackupDownloadResponse {
        let endpoint = ContactsBackupDownloadEndpoint()
        return try await request(endpoint, body: nil)
    }

    /// Delete contacts backup
    func deleteContactsBackup() async throws -> ContactsBackupDeleteResponse {
        let endpoint = ContactsBackupDeleteEndpoint()
        return try await request(endpoint, body: nil)
    }

    /// Get presigned upload URL for attachment
    func presignUpload(contentType: String, sizeBytes: Int) async throws -> AttachmentPresignUploadResponse {
        let endpoint = AttachmentPresignUploadEndpoint()
        let body = AttachmentPresignUploadRequest(contentType: contentType, sizeBytes: sizeBytes)
        return try await request(endpoint, body: body)
    }

    /// Get presigned download URL for attachment
    func presignDownload(objectKey: String) async throws -> AttachmentPresignDownloadResponse {
        let endpoint = AttachmentPresignDownloadEndpoint()
        let body = AttachmentPresignDownloadRequest(objectKey: objectKey)
        return try await request(endpoint, body: body)
    }

    /// Check server health
    func checkHealth() async throws -> HealthResponse {
        let endpoint = HealthEndpoint()
        return try await request(endpoint, body: nil)
    }

    /// Check server readiness
    func checkReady() async throws -> ReadyResponse {
        let endpoint = ReadyEndpoint()
        return try await request(endpoint, body: nil)
    }
}

// MARK: - Retry Support

extension APIClient {

    /// Execute request with retry policy
    func requestWithRetry<E: Endpoint>(
        _ endpoint: E,
        body: E.RequestBody? = nil,
        retryPolicy: RetryPolicy = .http
    ) async throws -> E.ResponseBody {
        let executor = RetryExecutor(
            policy: retryPolicy,
            shouldRetryError: { error in
                // Don't retry auth errors
                if error is AuthError { return false }
                // Don't retry 4xx errors (except rate limiting)
                if case NetworkError.httpError(let code, _) = error,
                   (400..<500).contains(code) && code != 429 {
                    return false
                }
                return true
            },
            operation: { [self] in
                try await self.request(endpoint, body: body)
            }
        )
        return try await executor.execute()
    }
}
