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
    private var reconnectAttempts = 0
    private let maxReconnectAttempts = 5

    // Pong monitoring
    private var lastPongTime: Date = Date()
    private let pingInterval: TimeInterval = 30  // Send ping every 30 seconds

    // Network monitoring (for status only, not for triggering reconnects)
    private var networkMonitor: NWPathMonitor?
    private var isNetworkAvailable = true

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

    // MARK: - Network Monitoring (status only)

    private func setupNetworkMonitoring() {
        networkMonitor = NWPathMonitor()
        networkMonitor?.pathUpdateHandler = { [weak self] path in
            self?.isNetworkAvailable = (path.status == .satisfied)
            DispatchQueue.main.async {
                if path.status == .satisfied {
                    print("[WebSocket] Network available")
                } else {
                    print("[WebSocket] Network unavailable")
                }
            }
        }
        networkMonitor?.start(queue: DispatchQueue.global(qos: .utility))
    }

    // MARK: - Connection Management

    func connect() {
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
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 60
        session = URLSession(configuration: config, delegate: self, delegateQueue: .main)

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
        print("[WebSocket] Disconnecting...")
        stopPingTimer()
        webSocket?.cancel(with: .normalClosure, reason: nil)
        webSocket = nil
        session?.invalidateAndCancel()
        session = nil
        connectionState = .disconnected
        reconnectAttempts = 0
    }

    /// Check if connected and trigger reconnect if needed (called by AuthService only)
    func ensureConnected() {
        if connectionState == .disconnected {
            reconnectAttempts = 0
            connect()
        }
    }

    private func reconnect() {
        guard reconnectAttempts < maxReconnectAttempts else {
            print("[WebSocket] Max reconnect attempts reached")
            connectionState = .disconnected
            return
        }

        guard isNetworkAvailable else {
            print("[WebSocket] Network not available, waiting...")
            connectionState = .disconnected
            return
        }

        connectionState = .reconnecting
        reconnectAttempts += 1

        // Simple backoff: 2s, 4s, 6s, 8s, 10s
        let delay = Double(reconnectAttempts) * 2.0
        print("[WebSocket] Reconnecting in \(delay)s (attempt \(reconnectAttempts)/\(maxReconnectAttempts))...")

        DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
            guard let self = self, self.connectionState == .reconnecting else { return }
            self.webSocket = nil
            self.session?.invalidateAndCancel()
            self.session = nil
            self.connectionState = .disconnected
            self.connect()
        }
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
            return
        }

        // Forward to subscribers
        messageSubject.send(data)
    }

    private func handleDisconnect() {
        print("[WebSocket] Connection lost")
        stopPingTimer()

        // Only reconnect if we were actually connected
        if connectionState == .connected {
            reconnect()
        }
    }

    // MARK: - Ping Timer

    private func startPingTimer() {
        stopPingTimer()
        lastPongTime = Date()
        pingTimer = Timer.scheduledTimer(withTimeInterval: pingInterval, repeats: true) { [weak self] _ in
            self?.sendPing()
        }
    }

    private func stopPingTimer() {
        pingTimer?.invalidate()
        pingTimer = nil
    }

    private func sendPing() {
        guard connectionState == .connected else { return }
        let payload = PingPayload(timestamp: Int64(Date().timeIntervalSince1970 * 1000))
        let frame = WsFrame(type: Constants.MessageType.ping, payload: payload)
        Task { try? await send(frame) }
    }

    deinit {
        networkMonitor?.cancel()
    }
}

// MARK: - URLSessionWebSocketDelegate

extension WebSocketService: URLSessionWebSocketDelegate {
    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol protocol: String?) {
        print("[WebSocket] Connected")
        connectionState = .connected
        reconnectAttempts = 0
        startPingTimer()
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
