import Foundation
import Combine
import Network

enum WSConnectionState {
    case disconnected
    case connecting
    case connected
    case reconnecting
}

final class WebSocketService: NSObject, ObservableObject {
    static let shared = WebSocketService()

    @Published private(set) var connectionState: WSConnectionState = .disconnected

    private var webSocket: URLSessionWebSocketTask?
    private var session: URLSession?
    private var pingTimer: Timer?
    private var pongTimeoutTimer: Timer?
    private var reconnectAttempts = 0
    private let maxReconnectAttempts = 50  // Increased from 5 to 50

    // Pong monitoring
    private var lastPongTime: Date = Date()
    private let pingInterval: TimeInterval = 30  // Send ping every 30 seconds
    private let pongTimeout: TimeInterval = 60   // Close connection if no pong in 60 seconds

    // Network monitoring (for status only, not for triggering reconnects)
    private var networkMonitor: NWPathMonitor?
    private var isNetworkAvailable = true

    // Background queue for network operations (don't block main thread)
    private let networkQueue = DispatchQueue(label: "com.whisper2.websocket", qos: .userInitiated)

    private let messageSubject = PassthroughSubject<Data, Never>()
    var messagePublisher: AnyPublisher<Data, Never> {
        messageSubject.eraseToAnyPublisher()
    }

    private let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.outputFormatting = [.sortedKeys]
        return e
    }()

    private override init() {
        super.init()
        setupNetworkMonitoring()
    }

    // MARK: - Network Monitoring

    private func setupNetworkMonitoring() {
        networkMonitor = NWPathMonitor()
        networkMonitor?.pathUpdateHandler = { [weak self] path in
            guard let self = self else { return }
            let wasAvailable = self.isNetworkAvailable
            let isNowAvailable = (path.status == .satisfied)
            self.isNetworkAvailable = isNowAvailable

            DispatchQueue.main.async {
                if isNowAvailable {
                    print("[WebSocket] Network available")
                    // Trigger reconnect if network just became available and we're disconnected
                    if !wasAvailable && self.connectionState == .disconnected {
                        print("[WebSocket] Network restored - triggering reconnect")
                        self.reconnectAttempts = 0  // Reset backoff when network comes back
                        self.connect()
                    }
                } else {
                    print("[WebSocket] Network unavailable")
                }
            }
        }
        networkMonitor?.start(queue: DispatchQueue.global(qos: .utility))
    }

    // MARK: - Connection Management

    func connect() {
        // Ensure we're on main thread for @Published property access
        guard Thread.isMainThread else {
            DispatchQueue.main.async { self.connect() }
            return
        }

        // Only connect if completely disconnected
        guard connectionState == .disconnected else {
            print("[WebSocket] Already connecting or connected, state: \(connectionState)")
            return
        }

        guard isNetworkAvailable else {
            print("[WebSocket] Network not available")
            return
        }

        print("[WebSocket] Connecting to \(Constants.wsURL)...")
        connectionState = .connecting

        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 60
        config.timeoutIntervalForResource = 300
        config.waitsForConnectivity = true  // Wait for network instead of failing immediately

        // Use background queue for delegate callbacks to not block UI
        session = URLSession(configuration: config, delegate: self, delegateQueue: OperationQueue())

        guard let url = URL(string: Constants.wsURL) else {
            print("[WebSocket] Invalid URL: \(Constants.wsURL)")
            connectionState = .disconnected
            return
        }

        webSocket = session?.webSocketTask(with: url)
        webSocket?.resume()
        startReceiving()
    }

    func disconnect() {
        // Ensure we're on main thread for @Published property access
        guard Thread.isMainThread else {
            DispatchQueue.main.async { self.disconnect() }
            return
        }

        print("[WebSocket] Disconnecting...")
        stopPingTimer()
        stopPongTimeoutTimer()
        webSocket?.cancel(with: .normalClosure, reason: nil)
        webSocket = nil
        session?.invalidateAndCancel()
        session = nil
        connectionState = .disconnected
        reconnectAttempts = 0
    }

    /// Check if connected and trigger reconnect if needed (called by AuthService only)
    func ensureConnected() {
        // Ensure we're on main thread for @Published property access
        guard Thread.isMainThread else {
            DispatchQueue.main.async { self.ensureConnected() }
            return
        }

        if connectionState == .disconnected {
            reconnectAttempts = 0
            connect()
        }
    }

    /// Called when app enters foreground - force reconnect if disconnected
    func handleAppDidBecomeActive() {
        // Ensure we're on main thread for @Published property access
        guard Thread.isMainThread else {
            DispatchQueue.main.async { self.handleAppDidBecomeActive() }
            return
        }

        print("[WebSocket] App became active, checking connection...")

        // Reset reconnect attempts when coming to foreground
        reconnectAttempts = 0

        switch connectionState {
        case .disconnected:
            print("[WebSocket] Disconnected - reconnecting...")
            connect()
        case .connected:
            // Send a ping to verify connection is still alive
            print("[WebSocket] Connected - verifying with ping...")
            sendPing()
            // Restart ping timer that was stopped in background
            startPingTimer()
        case .connecting, .reconnecting:
            print("[WebSocket] Already connecting...")
        }
    }

    /// Called when app enters background
    func handleAppWillResignActive() {
        print("[WebSocket] App resigning active")
        // Don't disconnect - let iOS manage the connection
        // But stop timers as they won't fire reliably in background
        stopPingTimer()
        stopPongTimeoutTimer()
    }

    private func reconnect() {
        guard reconnectAttempts < maxReconnectAttempts else {
            print("[WebSocket] Max reconnect attempts reached, will retry on next app foreground")
            DispatchQueue.main.async {
                self.connectionState = .disconnected
            }
            return
        }

        guard isNetworkAvailable else {
            print("[WebSocket] Network not available, waiting...")
            DispatchQueue.main.async {
                self.connectionState = .disconnected
            }
            return
        }

        DispatchQueue.main.async {
            self.connectionState = .reconnecting
        }
        reconnectAttempts += 1

        // Exponential backoff with jitter: 1s, 2s, 4s, 8s... max 30s
        let baseDelay = min(pow(2.0, Double(reconnectAttempts - 1)), 30.0)
        let jitter = Double.random(in: 0...1)
        let delay = baseDelay + jitter
        print("[WebSocket] Reconnecting in \(String(format: "%.1f", delay))s (attempt \(reconnectAttempts)/\(maxReconnectAttempts))...")

        DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
            guard let self = self, self.connectionState == .reconnecting else { return }
            self.webSocket = nil
            self.session?.invalidateAndCancel()
            self.session = nil
            self.connectionState = .disconnected
            self.connect()
        }
    }

    /// Reset reconnect counter (called after successful auth)
    func resetReconnectAttempts() {
        reconnectAttempts = 0
    }

    // MARK: - Send

    func send<T: Codable>(_ frame: WsFrame<T>) async throws {
        guard connectionState == .connected else {
            throw NetworkError.connectionFailed
        }

        let data = try encoder.encode(frame)
        guard let string = String(data: data, encoding: .utf8) else {
            throw NetworkError.invalidResponse
        }
        try await webSocket?.send(.string(string))
    }

    // MARK: - Receive

    private func startReceiving() {
        webSocket?.receive { [weak self] result in
            switch result {
            case .success(let message):
                switch message {
                case .string(let text):
                    if let data = text.data(using: .utf8) {
                        self?.handleReceivedMessage(data)
                    }
                case .data(let data):
                    self?.handleReceivedMessage(data)
                @unknown default:
                    break
                }
                self?.startReceiving()

            case .failure(let error):
                print("[WebSocket] Receive error: \(error.localizedDescription)")
                self?.handleDisconnect()
            }
        }
    }

    private func handleReceivedMessage(_ data: Data) {
        // Check if it's a pong response
        if let raw = try? JSONDecoder().decode(RawWsFrame.self, from: data),
           raw.type == Constants.MessageType.pong || raw.type == "pong" {
            lastPongTime = Date()
            print("[WebSocket] Pong received")
            return
        }

        // Forward to subscribers
        messageSubject.send(data)
    }

    private func handleDisconnect() {
        print("[WebSocket] Connection lost")
        stopPingTimer()
        stopPongTimeoutTimer()

        // Only reconnect if we were actually connected
        DispatchQueue.main.async {
            if self.connectionState == .connected {
                self.reconnect()
            }
        }
    }

    // MARK: - Ping Timer

    private func startPingTimer() {
        stopPingTimer()
        stopPongTimeoutTimer()
        lastPongTime = Date()

        DispatchQueue.main.async {
            self.pingTimer = Timer.scheduledTimer(withTimeInterval: self.pingInterval, repeats: true) { [weak self] _ in
                self?.sendPing()
                self?.startPongTimeoutTimer()
            }
        }
    }

    private func stopPingTimer() {
        DispatchQueue.main.async {
            self.pingTimer?.invalidate()
            self.pingTimer = nil
        }
    }

    private func startPongTimeoutTimer() {
        stopPongTimeoutTimer()

        DispatchQueue.main.async {
            self.pongTimeoutTimer = Timer.scheduledTimer(withTimeInterval: self.pongTimeout, repeats: false) { [weak self] _ in
                self?.checkPongTimeout()
            }
        }
    }

    private func stopPongTimeoutTimer() {
        DispatchQueue.main.async {
            self.pongTimeoutTimer?.invalidate()
            self.pongTimeoutTimer = nil
        }
    }

    private func checkPongTimeout() {
        let timeSinceLastPong = Date().timeIntervalSince(lastPongTime)
        if timeSinceLastPong > pongTimeout {
            print("[WebSocket] Pong timeout (\(Int(timeSinceLastPong))s since last pong), closing connection")
            // Force close and reconnect
            webSocket?.cancel(with: .abnormalClosure, reason: "Pong timeout".data(using: .utf8))
            handleDisconnect()
        }
    }

    private func sendPing() {
        guard connectionState == .connected else { return }
        let payload = PingPayload(timestamp: Int64(Date().timeIntervalSince1970 * 1000))
        let frame = WsFrame(type: Constants.MessageType.ping, payload: payload)
        Task {
            do {
                try await send(frame)
                print("[WebSocket] Ping sent")
            } catch {
                print("[WebSocket] Failed to send ping: \(error)")
            }
        }
    }

    deinit {
        networkMonitor?.cancel()
    }
}

// MARK: - URLSessionWebSocketDelegate

extension WebSocketService: URLSessionWebSocketDelegate {
    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol protocol: String?) {
        print("[WebSocket] Connected")
        DispatchQueue.main.async {
            self.connectionState = .connected
            self.reconnectAttempts = 0
            self.startPingTimer()
        }
    }

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        let reasonString = reason.flatMap { String(data: $0, encoding: .utf8) } ?? "unknown"
        print("[WebSocket] Closed: code=\(closeCode.rawValue), reason=\(reasonString)")
        handleDisconnect()
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error = error {
            print("[WebSocket] Error: \(error.localizedDescription)")
            handleDisconnect()
        }
    }
}
