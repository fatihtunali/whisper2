import Foundation
import CryptoKit

// MARK: - Data Extensions

extension Data {
    var base64String: String {
        base64EncodedString()
    }
    
    var hexString: String {
        map { String(format: "%02x", $0) }.joined()
    }
    
    init?(hexString: String) {
        let len = hexString.count / 2
        var data = Data(capacity: len)
        var index = hexString.startIndex
        for _ in 0..<len {
            let nextIndex = hexString.index(index, offsetBy: 2)
            guard let byte = UInt8(hexString[index..<nextIndex], radix: 16) else {
                return nil
            }
            data.append(byte)
            index = nextIndex
        }
        self = data
    }
    
    var sha256: Data {
        Data(SHA256.hash(data: self))
    }
}

// MARK: - String Extensions

extension String {
    var base64Data: Data? {
        Data(base64Encoded: self)
    }
    
    var normalizedMnemonic: String {
        self.decomposedStringWithCompatibilityMapping
            .lowercased()
            .components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.isEmpty }
            .joined(separator: " ")
    }
    
    var isValidWhisperId: Bool {
        let regex = try? NSRegularExpression(pattern: Constants.whisperIdRegex)
        let range = NSRange(location: 0, length: utf16.count)
        return regex?.firstMatch(in: self, range: range) != nil
    }
    
    func isValidBase64(expectedBytes: Int? = nil) -> Bool {
        guard !isEmpty, count % 4 == 0 else { return false }
        guard let data = Data(base64Encoded: self) else { return false }
        if let expected = expectedBytes {
            return data.count == expected
        }
        return true
    }
}

// MARK: - Date Extensions

extension Date {
    var timestampMs: Int64 {
        Int64(timeIntervalSince1970 * 1000)
    }
    
    init(timestampMs: Int64) {
        self.init(timeIntervalSince1970: Double(timestampMs) / 1000)
    }
    
    var chatTimeString: String {
        let formatter = DateFormatter()
        let calendar = Calendar.current
        
        if calendar.isDateInToday(self) {
            formatter.dateFormat = "HH:mm"
        } else if calendar.isDateInYesterday(self) {
            return "Yesterday"
        } else if calendar.isDate(self, equalTo: Date(), toGranularity: .weekOfYear) {
            formatter.dateFormat = "EEEE"
        } else {
            formatter.dateFormat = "dd/MM/yy"
        }
        return formatter.string(from: self)
    }
    
    var messageTimeString: String {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm"
        return formatter.string(from: self)
    }
}

// MARK: - Int64 Extensions

extension Int64 {
    var toDate: Date {
        Date(timestampMs: self)
    }
    
    var isValidTimestamp: Bool {
        guard self > 0 else { return false }
        let diff = abs(self - Date().timestampMs)
        return diff <= Constants.timestampSkewMs
    }
}

// MARK: - UUID

extension UUID {
    static var newString: String {
        UUID().uuidString.lowercased()
    }
}
