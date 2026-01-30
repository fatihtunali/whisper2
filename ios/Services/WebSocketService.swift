import Foundation
import Combine
import UIKit
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
    private let maxReconnectAttempts = 10

    // Heartbeat monitoring
    private var lastPongTime: Date = Date()
    private var heartbeatTimer: Timer?
    private let pingInterval: TimeInterval = 15  // Send ping every 15 seconds
    private let pongTimeout: TimeInterval = 10   // Expect pong within 10 seconds

    // Network monitoring
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
        setupAppLifecycleObservers()
        setupNetworkMonitoring()
    }

    // MARK: - App Lifecycle

    private func setupAppLifecycleObservers() {
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(appDidEnterBackground),
            name: UIApplication.didEnterBackgroundNotification,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(appWillEnterForeground),
            name: UIApplication.willEnterForegroundNotification,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(appDidBecomeActive),
            name: UIApplication.didBecomeActiveNotification,
            object: nil
        )
    }

    @objc private func appDidEnterBackground() {
        print("[WebSocket] App entered background - connection may be suspended by iOS")
        // Don't disconnect - let iOS handle it, but note that it may drop
    }

    @objc private func appWillEnterForeground() {
        print("[WebSocket] App will enter foreground - checking connection")
        checkConnectionHealth()
    }

    @objc private func appDidBecomeActive() {
        print("[WebSocket] App became active - ensuring connection")
        // Give a small delay for network to stabilize
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
            self?.ensureConnected()
        }
    }

    // MARK: - Network Monitoring

    private func setupNetworkMonitoring() {
        networkMonitor = NWPathMonitor()
        networkMonitor?.pathUpdateHandler = { [weak self] path in
            let wasAvailable = self?.isNetworkAvailable ?? true
            self?.isNetworkAvailable = (path.status == .satisfied)

            DispatchQueue.main.async {
                if path.status == .satisfied {
                    print("[WebSocket] Network became available (interface: \(path.availableInterfaces.first?.type ?? .other))")
                    if !wasAvailable {
                        // Network just came back - reconnect
                        self?.ensureConnected()
                    }
                } else {
                    print("[WebSocket] Network became unavailable")
                }
            }
        }
        networkMonitor?.start(queue: DispatchQueue.global(qos: .utility))
    }

    // MARK: - Connection Management

    func connect() {
        guard connectionState == .disconnected || connectionState == .reconnecting else {
            print("[WebSocket] Already connecting or connected, state: \(connectionState)")
            return
        }

        guard isNetworkAvailable else {
            print("[WebSocket] Network not available, will connect when available")
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
        stopTimers()
        webSocket?.cancel(with: .normalClosure, reason: nil)
        webSocket = nil
        session?.invalidateAndCancel()
        session = nil
        connectionState = .disconnected
        reconnectAttempts = 0
    }

    /// Ensure we're connected - reconnect if not
    func ensureConnected() {
        switch connectionState {
        case .connected:
            // Verify connection is actually alive
            checkConnectionHealth()
        case .disconnected:
            reconnectAttempts = 0
            connect()
        case .connecting, .reconnecting:
            // Already trying to connect
            break
        }
    }

    private func reconnect() {
        guard reconnectAttempts < maxReconnectAttempts else {
            print("[WebSocket] Max reconnect attempts (\(maxReconnectAttempts)) reached, giving up")
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

        // Exponential backoff: 1s, 2s, 4s, 8s, 16s, max 30s
        let delay = min(pow(2.0, Double(reconnectAttempts - 1)), 30.0)
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

    // MARK: - Health Check

    private func checkConnectionHealth() {
        guard connectionState == .connected else { return }

        // Check if we've received a pong recently
        let timeSinceLastPong = Date().timeIntervalSince(lastPongTime)
        if timeSinceLastPong > pingInterval + pongTimeout {
            print("[WebSocket] Connection appears dead (no pong for \(Int(timeSinceLastPong))s), reconnecting...")
            handleDisconnect()
            return
        }

        // Send a ping to verify connection
        sendPing()
    }

    // MARK: - Send

    func send<T: Codable>(_ frame: WsFrame<T>) async throws {
        guard connectionState == .connected else {
            print("[WebSocket] Cannot send - not connected (state: \(connectionState))")
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
        stopTimers()

        if connectionState == .connected || connectionState == .connecting {
            reconnect()
        }
    }

    // MARK: - Ping/Pong

    private func startTimers() {
        stopTimers()

        // Ping timer - send ping every 15 seconds
        pingTimer = Timer.scheduledTimer(withTimeInterval: pingInterval, repeats: true) { [weak self] _ in
            self?.sendPing()
        }

        // Heartbeat timer - check connection health every 20 seconds
        heartbeatTimer = Timer.scheduledTimer(withTimeInterval: 20, repeats: true) { [weak self] _ in
            self?.checkConnectionHealth()
        }

        // Initialize last pong time
        lastPongTime = Date()
    }

    private func stopTimers() {
        pingTimer?.invalidate()
        pingTimer = nil
        heartbeatTimer?.invalidate()
        heartbeatTimer = nil
    }

    private func sendPing() {
        guard connectionState == .connected else { return }

        let payload = PingPayload(timestamp: Int64(Date().timeIntervalSince1970 * 1000))
        let frame = WsFrame(type: Constants.MessageType.ping, payload: payload)
        Task {
            do {
                try await send(frame)
            } catch {
                print("[WebSocket] Failed to send ping: \(error)")
            }
        }
    }

    deinit {
        networkMonitor?.cancel()
        NotificationCenter.default.removeObserver(self)
    }
}

// MARK: - URLSessionWebSocketDelegate

extension WebSocketService: URLSessionWebSocketDelegate {
    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol protocol: String?) {
        print("[WebSocket] Connected successfully")
        connectionState = .connected
        reconnectAttempts = 0
        lastPongTime = Date()
        startTimers()
    }

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        let reasonString = reason.flatMap { String(data: $0, encoding: .utf8) } ?? "unknown"
        print("[WebSocket] Closed with code: \(closeCode.rawValue), reason: \(reasonString)")
        handleDisconnect()
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error = error {
            print("[WebSocket] Task completed with error: \(error.localizedDescription)")
            handleDisconnect()
        }
    }
}
