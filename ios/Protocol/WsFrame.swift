import Foundation

/// Generic WebSocket frame wrapper matching server protocol
struct WsFrame<T: Codable>: Codable {
    let type: String
    let requestId: String?
    let payload: T
    
    init(type: String, payload: T, requestId: String? = nil) {
        self.type = type
        self.requestId = requestId
        self.payload = payload
    }
}

/// Frame with unknown payload for initial parsing
struct RawWsFrame: Codable {
    let type: String
    let requestId: String?
    let payload: AnyCodable?
}

/// Type-erased codable wrapper
struct AnyCodable: Codable {
    let value: Any
    
    init(_ value: Any) {
        self.value = value
    }
    
    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        
        if container.decodeNil() {
            self.value = NSNull()
        } else if let bool = try? container.decode(Bool.self) {
            self.value = bool
        } else if let int = try? container.decode(Int.self) {
            self.value = int
        } else if let int64 = try? container.decode(Int64.self) {
            self.value = int64
        } else if let double = try? container.decode(Double.self) {
            self.value = double
        } else if let string = try? container.decode(String.self) {
            self.value = string
        } else if let array = try? container.decode([AnyCodable].self) {
            self.value = array.map { $0.value }
        } else if let dict = try? container.decode([String: AnyCodable].self) {
            self.value = dict.mapValues { $0.value }
        } else {
            throw DecodingError.dataCorruptedError(in: container, debugDescription: "Cannot decode value")
        }
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        
        switch value {
        case is NSNull:
            try container.encodeNil()
        case let bool as Bool:
            try container.encode(bool)
        case let int as Int:
            try container.encode(int)
        case let int64 as Int64:
            try container.encode(int64)
        case let double as Double:
            try container.encode(double)
        case let string as String:
            try container.encode(string)
        case let array as [Any]:
            try container.encode(array.map { AnyCodable($0) })
        case let dict as [String: Any]:
            try container.encode(dict.mapValues { AnyCodable($0) })
        default:
            throw EncodingError.invalidValue(value, EncodingError.Context(codingPath: [], debugDescription: "Cannot encode value"))
        }
    }
}

// MARK: - Frame Builder

enum FrameBuilder {
    private static let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return encoder
    }()
    
    private static let decoder = JSONDecoder()
    
    /// Encode a frame to JSON data
    static func encode<T: Codable>(_ frame: WsFrame<T>) throws -> Data {
        try encoder.encode(frame)
    }
    
    /// Encode a frame to JSON string
    static func encodeToString<T: Codable>(_ frame: WsFrame<T>) throws -> String {
        let data = try encode(frame)
        guard let string = String(data: data, encoding: .utf8) else {
            throw NSError(domain: "FrameBuilder", code: 1, userInfo: [NSLocalizedDescriptionKey: "Failed to encode frame to string"])
        }
        return string
    }
    
    /// Decode raw frame to get type
    static func decodeRaw(from data: Data) throws -> RawWsFrame {
        try decoder.decode(RawWsFrame.self, from: data)
    }
    
    /// Decode frame with specific payload type
    static func decode<T: Codable>(_ type: T.Type, from data: Data) throws -> WsFrame<T> {
        try decoder.decode(WsFrame<T>.self, from: data)
    }
    
    /// Create a frame
    static func frame<T: Codable>(type: String, payload: T, requestId: String? = nil) -> WsFrame<T> {
        WsFrame(type: type, payload: payload, requestId: requestId)
    }
}
