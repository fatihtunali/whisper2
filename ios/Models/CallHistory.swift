import Foundation

/// Call history record
struct CallRecord: Codable, Identifiable {
    let id: String
    let peerId: String
    let peerName: String?
    let isVideo: Bool
    let isOutgoing: Bool
    let startTime: Date
    var endTime: Date?
    var duration: TimeInterval?
    let outcome: CallOutcome

    enum CallOutcome: String, Codable {
        case completed   // Call connected and ended normally
        case missed      // Incoming call not answered
        case declined    // Incoming call declined
        case noAnswer    // Outgoing call not answered
        case failed      // Call failed to connect
        case cancelled   // Outgoing call cancelled before connect
    }

    init(
        id: String = UUID().uuidString,
        peerId: String,
        peerName: String?,
        isVideo: Bool,
        isOutgoing: Bool,
        startTime: Date = Date(),
        endTime: Date? = nil,
        duration: TimeInterval? = nil,
        outcome: CallOutcome
    ) {
        self.id = id
        self.peerId = peerId
        self.peerName = peerName
        self.isVideo = isVideo
        self.isOutgoing = isOutgoing
        self.startTime = startTime
        self.endTime = endTime
        self.duration = duration
        self.outcome = outcome
    }

    var formattedDuration: String {
        guard let duration = duration else { return "" }
        let minutes = Int(duration) / 60
        let seconds = Int(duration) % 60
        return String(format: "%d:%02d", minutes, seconds)
    }

    var formattedTime: String {
        let formatter = DateFormatter()
        let calendar = Calendar.current

        if calendar.isDateInToday(startTime) {
            formatter.dateFormat = "HH:mm"
        } else if calendar.isDateInYesterday(startTime) {
            return "Yesterday"
        } else {
            formatter.dateFormat = "dd/MM/yy"
        }

        return formatter.string(from: startTime)
    }
}
