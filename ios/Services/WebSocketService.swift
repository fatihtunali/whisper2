import Foundation
import Combine

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
    }
    
    func connect() {
        guard connectionState == .disconnected else { return }
        connectionState = .connecting
        
        let config = URLSessionConfiguration.default
        session = URLSession(configuration: config, delegate: self, delegateQueue: .main)
        
        guard let url = URL(string: Constants.wsURL) else {
            connectionState = .disconnected
            return
        }
        
        webSocket = session?.webSocketTask(with: url)
        webSocket?.resume()
        startReceiving()
        startPingTimer()
    }
    
    func disconnect() {
        stopPingTimer()
        webSocket?.cancel(with: .normalClosure, reason: nil)
        webSocket = nil
        session?.invalidateAndCancel()
        session = nil
        connectionState = .disconnected
        reconnectAttempts = 0
    }
    
    private func reconnect() {
        guard reconnectAttempts < maxReconnectAttempts else {
            connectionState = .disconnected
            return
        }
        
        connectionState = .reconnecting
        reconnectAttempts += 1
        
        let delay = min(Double(reconnectAttempts) * 2.0, 30.0)
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
            self?.webSocket = nil
            self?.session?.invalidateAndCancel()
            self?.session = nil
            self?.connectionState = .disconnected
            self?.connect()
        }
    }
    
    func send<T: Codable>(_ frame: WsFrame<T>) async throws {
        let data = try encoder.encode(frame)
        guard let string = String(data: data, encoding: .utf8) else {
            throw NetworkError.invalidResponse
        }
        try await webSocket?.send(.string(string))
    }
    
    private func startReceiving() {
        webSocket?.receive { [weak self] result in
            switch result {
            case .success(let message):
                switch message {
                case .string(let text):
                    if let data = text.data(using: .utf8) {
                        self?.messageSubject.send(data)
                    }
                case .data(let data):
                    self?.messageSubject.send(data)
                @unknown default:
                    break
                }
                self?.startReceiving()
                
            case .failure:
                self?.handleDisconnect()
            }
        }
    }
    
    private func handleDisconnect() {
        stopPingTimer()
        if connectionState == .connected {
            reconnect()
        }
    }
    
    private func startPingTimer() {
        pingTimer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
            self?.sendPing()
        }
    }
    
    private func stopPingTimer() {
        pingTimer?.invalidate()
        pingTimer = nil
    }
    
    private func sendPing() {
        let payload = PingPayload(timestamp: Int64(Date().timeIntervalSince1970 * 1000))
        let frame = WsFrame(type: Constants.MessageType.ping, payload: payload)
        Task { try? await send(frame) }
    }
}

extension WebSocketService: URLSessionWebSocketDelegate {
    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol protocol: String?) {
        connectionState = .connected
        reconnectAttempts = 0
    }
    
    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        handleDisconnect()
    }
    
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if error != nil { handleDisconnect() }
    }
}
