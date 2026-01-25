import Foundation

/// Time utilities for Whisper2
/// Consistent timestamp handling

enum Time {

    /// Current Unix timestamp in milliseconds
    static var nowMs: Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }

    /// Current Unix timestamp in seconds
    static var nowSec: Int64 {
        Int64(Date().timeIntervalSince1970)
    }

    /// Convert milliseconds to Date
    static func dateFromMs(_ ms: Int64) -> Date {
        Date(timeIntervalSince1970: TimeInterval(ms) / 1000)
    }

    /// Convert seconds to Date
    static func dateFromSec(_ sec: Int64) -> Date {
        Date(timeIntervalSince1970: TimeInterval(sec))
    }

    /// Format date for display (relative)
    static func relativeString(from date: Date) -> String {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return formatter.localizedString(for: date, relativeTo: Date())
    }

    /// Format date for display (absolute)
    static func absoluteString(from date: Date, style: DateFormatter.Style = .medium) -> String {
        let formatter = DateFormatter()
        formatter.dateStyle = style
        formatter.timeStyle = style
        return formatter.string(from: date)
    }

    /// Format time only
    static func timeString(from date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateStyle = .none
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }

    /// Format date for message grouping (e.g., "Today", "Yesterday", "Monday")
    static func messageDateString(from date: Date) -> String {
        let calendar = Calendar.current

        if calendar.isDateInToday(date) {
            return "Today"
        } else if calendar.isDateInYesterday(date) {
            return "Yesterday"
        } else if let daysAgo = calendar.dateComponents([.day], from: date, to: Date()).day,
                  daysAgo < 7 {
            let formatter = DateFormatter()
            formatter.dateFormat = "EEEE" // Day name
            return formatter.string(from: date)
        } else {
            let formatter = DateFormatter()
            formatter.dateStyle = .medium
            formatter.timeStyle = .none
            return formatter.string(from: date)
        }
    }

    /// Check if timestamp is expired (given TTL in seconds)
    static func isExpired(timestamp: Int64, ttlSeconds: Int64) -> Bool {
        let expiryMs = timestamp + (ttlSeconds * 1000)
        return nowMs > expiryMs
    }

    /// ISO 8601 string
    static func isoString(from date: Date) -> String {
        ISO8601DateFormatter().string(from: date)
    }

    /// Parse ISO 8601 string
    static func dateFromISO(_ string: String) -> Date? {
        ISO8601DateFormatter().date(from: string)
    }
}

// MARK: - Date Extension
extension Date {
    var millisecondsSince1970: Int64 {
        Int64(timeIntervalSince1970 * 1000)
    }

    var secondsSince1970: Int64 {
        Int64(timeIntervalSince1970)
    }

    var relativeString: String {
        Time.relativeString(from: self)
    }

    var timeString: String {
        Time.timeString(from: self)
    }

    var messageDateString: String {
        Time.messageDateString(from: self)
    }
}
