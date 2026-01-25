import Foundation
import os.log

/// Whisper2 Logger
/// Centralized logging with privacy-aware output

final class Logger {
    static let shared = Logger()

    private let subsystem = "com.whisper2.app"

    // Category loggers
    private lazy var authLogger = OSLog(subsystem: subsystem, category: "Auth")
    private lazy var cryptoLogger = OSLog(subsystem: subsystem, category: "Crypto")
    private lazy var networkLogger = OSLog(subsystem: subsystem, category: "Network")
    private lazy var messagingLogger = OSLog(subsystem: subsystem, category: "Messaging")
    private lazy var callLogger = OSLog(subsystem: subsystem, category: "Calls")
    private lazy var storageLogger = OSLog(subsystem: subsystem, category: "Storage")
    private lazy var uiLogger = OSLog(subsystem: subsystem, category: "UI")
    private lazy var generalLogger = OSLog(subsystem: subsystem, category: "General")

    private init() {}

    enum Category {
        case auth
        case crypto
        case network
        case messaging
        case calls
        case storage
        case ui
        case general
    }

    enum Level {
        case debug
        case info
        case warning
        case error
        case fault

        var osLogType: OSLogType {
            switch self {
            case .debug: return .debug
            case .info: return .info
            case .warning: return .default
            case .error: return .error
            case .fault: return .fault
            }
        }

        var emoji: String {
            switch self {
            case .debug: return "🔍"
            case .info: return "ℹ️"
            case .warning: return "⚠️"
            case .error: return "❌"
            case .fault: return "💥"
            }
        }
    }

    private func logger(for category: Category) -> OSLog {
        switch category {
        case .auth: return authLogger
        case .crypto: return cryptoLogger
        case .network: return networkLogger
        case .messaging: return messagingLogger
        case .calls: return callLogger
        case .storage: return storageLogger
        case .ui: return uiLogger
        case .general: return generalLogger
        }
    }

    func log(
        _ message: String,
        level: Level = .info,
        category: Category = .general,
        file: String = #file,
        function: String = #function,
        line: Int = #line
    ) {
        let fileName = URL(fileURLWithPath: file).lastPathComponent
        let logMessage = "\(level.emoji) [\(fileName):\(line)] \(function) - \(message)"

        os_log("%{public}@", log: logger(for: category), type: level.osLogType, logMessage)

        #if DEBUG
        print(logMessage)
        #endif
    }

    // Convenience methods
    func debug(_ message: String, category: Category = .general, file: String = #file, function: String = #function, line: Int = #line) {
        log(message, level: .debug, category: category, file: file, function: function, line: line)
    }

    func info(_ message: String, category: Category = .general, file: String = #file, function: String = #function, line: Int = #line) {
        log(message, level: .info, category: category, file: file, function: function, line: line)
    }

    func warning(_ message: String, category: Category = .general, file: String = #file, function: String = #function, line: Int = #line) {
        log(message, level: .warning, category: category, file: file, function: function, line: line)
    }

    func error(_ message: String, category: Category = .general, file: String = #file, function: String = #function, line: Int = #line) {
        log(message, level: .error, category: category, file: file, function: function, line: line)
    }

    func fault(_ message: String, category: Category = .general, file: String = #file, function: String = #function, line: Int = #line) {
        log(message, level: .fault, category: category, file: file, function: function, line: line)
    }

    // Error logging with Error object
    func error(_ error: Error, message: String? = nil, category: Category = .general, file: String = #file, function: String = #function, line: Int = #line) {
        let errorMessage = message.map { "\($0): \(error.localizedDescription)" } ?? error.localizedDescription
        log(errorMessage, level: .error, category: category, file: file, function: function, line: line)
    }
}

// MARK: - Global convenience
let logger = Logger.shared
