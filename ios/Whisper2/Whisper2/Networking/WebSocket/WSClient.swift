import Foundation

/// Whisper2 WebSocket Client
/// Handles WebSocket connection using URLSessionWebSocketTask

// MARK: - WSClient Delegate

protocol WSClientDelegate: AnyObject {
    /// Called when connection state changes
    func wsClient(_ client: WSClient, didChangeState state: WSConnectionState)

    /// Called when a message is received
    func wsClient(_ client: WSClient, didReceiveMessage data: Data)

    /// Called when an error occurs
    func wsClient(_ client: WSClient, didEncounterError error: Error)

    /// Called when session is invalidated (forced logout)
    func wsClientDidReceiveForceLogout(_ client: WSClient, reason: String)
}

// MARK: - WSClient

/// WebSocket client using URLSessionWebSocketTask
/// Features:
/// - Ping/pong keepalive
/// - Auto-reconnect with exponential backoff
/// - Connection state management
actor WSClient {

    // MARK: - Properties

    private let url: URL
    private let sessionConfiguration: URLSessionConfiguration
    private var session: URLSession?
    private var webSocketTask: URLSessionWebSocketTask?
    private var pingTask: Task<Void, Never>?
    private var receiveTask: Task<Void, Never>?

    private let reconnectPolicy: WSReconnectPolicy
    private var shouldReconnect: Bool = true
    private var isManualDisconnect: Bool = false

    private weak var delegate: WSClientDelegate?

    // MARK: - State

    private(set) var connectionState: WSConnectionState = .disconnected {
        didSet {
            guard oldValue != connectionState else { return }
            notifyStateChange(connectionState)
        }
    }

    /// Server time offset (server - local) in milliseconds
    private(set) var serverTimeOffset: Int64 = 0

    /// Last pong received timestamp
    private var lastPongAt: Date?

    // MARK: - Init

    init(
        url: URL = Constants.Server.webSocketURL,
        sessionConfiguration: URLSessionConfiguration = .default,
        reconnectPolicy: WSReconnectPolicy? = nil
    ) {
        self.url = url
        self.sessionConfiguration = sessionConfiguration
        self.reconnectPolicy = reconnectPolicy ?? WSReconnectPolicy()
    }

    // MARK: - Delegate

    func setDelegate(_ delegate: WSClientDelegate?) {
        self.delegate = delegate
    }

    // MARK: - Connection

    /// Connect to the WebSocket server
    func connect() async {
        guard connectionState != .connected, !connectionState.isConnecting else {
            logger.debug("Already connected or connecting", category: .network)
            return
        }

        isManualDisconnect = false
        shouldReconnect = true
        await reconnectPolicy.beginConnecting()
        connectionState = .connecting

        await performConnect()
    }

    /// Disconnect from the WebSocket server
    func disconnect() async {
        isManualDisconnect = true
        shouldReconnect = false

        await stopPingPong()
        await stopReceiving()
        await reconnectPolicy.didDisconnectManually()

        webSocketTask?.cancel(with: .normalClosure, reason: nil)
        webSocketTask = nil
        session?.invalidateAndCancel()
        session = nil

        connectionState = .disconnected
        logger.info("WebSocket disconnected (manual)", category: .network)
    }

    // MARK: - Sending

    /// Send a message to the server
    func send(_ data: Data) async throws {
        guard connectionState == .connected, let task = webSocketTask else {
            throw NetworkError.connectionClosed
        }

        do {
            try await task.send(.data(data))
            logger.debug("Sent \(data.count) bytes", category: .network)
        } catch {
            logger.error("Send failed: \(error.localizedDescription)", category: .network)
            throw NetworkError.connectionClosed
        }
    }

    /// Send a string message to the server
    func send(_ string: String) async throws {
        guard let data = string.data(using: .utf8) else {
            throw NetworkError.encodingFailed
        }
        try await send(data)
    }

    /// Send a typed message frame
    func send<T: Encodable>(type: String, payload: T, requestId: String? = nil) async throws {
        let frame = WSFrame(type: type, payload: payload, requestId: requestId)
        let encoder = JSONEncoder()
        encoder.keyEncodingStrategy = .useDefaultKeys
        let data = try encoder.encode(frame)
        try await send(data)
    }

    // MARK: - Server Time

    /// Get adjusted current time (using server offset)
    var adjustedCurrentTime: Int64 {
        Int64(Date().timeIntervalSince1970 * 1000) + serverTimeOffset
    }

    /// Update server time offset from pong
    func updateServerTime(serverTime: Int64) {
        let localTime = Int64(Date().timeIntervalSince1970 * 1000)
        serverTimeOffset = serverTime - localTime
        logger.debug("Server time offset: \(serverTimeOffset)ms", category: .network)
    }

    // MARK: - Private - Connection

    private func performConnect() async {
        // Create session if needed
        if session == nil {
            let delegateQueue = OperationQueue()
            delegateQueue.name = "com.whisper2.websocket"
            delegateQueue.maxConcurrentOperationCount = 1
            session = URLSession(configuration: sessionConfiguration, delegate: nil, delegateQueue: delegateQueue)
        }

        guard let session = session else { return }

        // Create WebSocket task
        webSocketTask = session.webSocketTask(with: url)
        webSocketTask?.resume()

        logger.info("Connecting to \(url.absoluteString)", category: .network)

        // Start receiving messages
        await startReceiving()

        // Wait briefly to confirm connection
        do {
            try await Task.sleep(nanoseconds: 100_000_000) // 100ms

            // Send initial ping to verify connection
            try await sendPing()

            // Connection successful
            await reconnectPolicy.didConnect()
            connectionState = .connected
            logger.info("WebSocket connected", category: .network)

            // Start ping/pong keepalive
            await startPingPong()
        } catch {
            logger.error("Connection failed: \(error.localizedDescription)", category: .network)
            await handleDisconnection()
        }
    }

    private func handleDisconnection() async {
        await stopPingPong()
        await stopReceiving()

        webSocketTask?.cancel()
        webSocketTask = nil

        guard shouldReconnect && !isManualDisconnect else {
            connectionState = .disconnected
            return
        }

        if let delay = await reconnectPolicy.didDisconnect() {
            if case .reconnecting(let attempt) = await reconnectPolicy.state {
                connectionState = .reconnecting(attempt: attempt)
            }

            logger.info("Reconnecting in \(String(format: "%.2f", delay))s", category: .network)

            await reconnectPolicy.scheduleReconnect { [weak self] in
                await self?.performConnect()
            }
        } else {
            logger.warning("Max reconnection attempts reached", category: .network)
            connectionState = .disconnected
            notifyError(NetworkError.connectionFailed)
        }
    }

    // MARK: - Private - Receiving

    private func startReceiving() async {
        receiveTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self = self else { break }

                do {
                    let message = try await self.receiveMessage()

                    switch message {
                    case .data(let data):
                        await self.handleReceivedData(data)
                    case .string(let string):
                        if let data = string.data(using: .utf8) {
                            await self.handleReceivedData(data)
                        }
                    @unknown default:
                        break
                    }
                } catch {
                    if !Task.isCancelled {
                        logger.error("Receive error: \(error.localizedDescription)", category: .network)
                        await self.handleDisconnection()
                    }
                    break
                }
            }
        }
    }

    private func receiveMessage() async throws -> URLSessionWebSocketTask.Message {
        guard let task = webSocketTask else {
            throw NetworkError.connectionClosed
        }
        return try await task.receive()
    }

    private func stopReceiving() async {
        receiveTask?.cancel()
        receiveTask = nil
    }

    private func handleReceivedData(_ data: Data) async {
        logger.debug("Received \(data.count) bytes", category: .network)

        // Check for pong response
        if let pong = try? JSONDecoder().decode(WSFrame<PongPayload>.self, from: data),
           pong.type == Constants.MessageType.pong {
            lastPongAt = Date()
            updateServerTime(serverTime: pong.payload.serverTime)
            return
        }

        // Check for force logout
        if let forceLogout = try? JSONDecoder().decode(WSFrame<ForceLogoutPayload>.self, from: data),
           forceLogout.type == Constants.MessageType.forceLogout {
            shouldReconnect = false
            await disconnect()
            notifyForceLogout(reason: forceLogout.payload.reason)
            return
        }

        // Forward to delegate
        notifyMessage(data)
    }

    // MARK: - Private - Ping/Pong

    private func startPingPong() async {
        await stopPingPong()

        pingTask = Task { [weak self] in
            while !Task.isCancelled {
                do {
                    try await Task.sleep(nanoseconds: UInt64(Constants.Timeout.pingInterval * 1_000_000_000))

                    guard !Task.isCancelled, let self = self else { break }

                    // Check if we received a pong recently
                    if let lastPong = await self.lastPongAt,
                       Date().timeIntervalSince(lastPong) > Constants.Timeout.pingInterval + Constants.Timeout.pongTimeout {
                        logger.warning("Pong timeout, reconnecting", category: .network)
                        await self.handleDisconnection()
                        break
                    }

                    do {
                        try await self.sendPing()
                    } catch {
                        if !Task.isCancelled {
                            logger.error("Ping failed: \(error.localizedDescription)", category: .network)
                            await self.handleDisconnection()
                        }
                        break
                    }
                } catch {
                    break // Task cancelled
                }
            }
        }
    }

    private func stopPingPong() async {
        pingTask?.cancel()
        pingTask = nil
    }

    private func sendPing() async throws {
        let ping = WSFrame(type: Constants.MessageType.ping, payload: PingPayload())
        let data = try JSONEncoder().encode(ping)
        try await send(data)
        logger.debug("Ping sent", category: .network)
    }

    // MARK: - Private - Notifications

    private nonisolated func notifyStateChange(_ state: WSConnectionState) {
        Task { @MainActor [weak self] in
            guard let self = self else { return }
            let delegate = await self.delegate
            delegate?.wsClient(self, didChangeState: state)
        }
    }

    private nonisolated func notifyMessage(_ data: Data) {
        Task { @MainActor [weak self] in
            guard let self = self else { return }
            let delegate = await self.delegate
            delegate?.wsClient(self, didReceiveMessage: data)
        }
    }

    private nonisolated func notifyError(_ error: Error) {
        Task { @MainActor [weak self] in
            guard let self = self else { return }
            let delegate = await self.delegate
            delegate?.wsClient(self, didEncounterError: error)
        }
    }

    private nonisolated func notifyForceLogout(reason: String) {
        Task { @MainActor [weak self] in
            guard let self = self else { return }
            let delegate = await self.delegate
            delegate?.wsClientDidReceiveForceLogout(self, reason: reason)
        }
    }
}

// MARK: - Connection Info Extension

extension WSClient {
    /// Get current connection state (for external access)
    var state: WSConnectionState {
        get async { connectionState }
    }

    /// Check if currently connected
    var isConnected: Bool {
        get async { connectionState.isConnected }
    }
}
