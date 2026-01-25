import Foundation

/// Call State - Represents the current state of a voice/video call

enum CallState: Equatable {
    /// No active call
    case idle

    /// Outgoing call being initiated (waiting for server)
    case outgoingInitiating(callId: String, remoteId: WhisperID)

    /// Incoming call ringing
    case incomingRinging(callInfo: CallInfo)

    /// Call is connecting (WebRTC handshake in progress)
    case connecting(callInfo: CallInfo)

    /// Call is active and connected
    case connected(callInfo: CallInfo)

    /// Call has ended
    case ended(callInfo: CallInfo, reason: CallEndReason)

    // MARK: - Computed Properties

    /// Whether there is an active or pending call
    var isActive: Bool {
        switch self {
        case .idle, .ended:
            return false
        default:
            return true
        }
    }

    /// Whether the call is currently ringing (incoming)
    var isRinging: Bool {
        if case .incomingRinging = self {
            return true
        }
        return false
    }

    /// Whether the call is connected
    var isConnected: Bool {
        if case .connected = self {
            return true
        }
        return false
    }

    /// Get the call info if available
    var callInfo: CallInfo? {
        switch self {
        case .idle:
            return nil
        case .outgoingInitiating:
            return nil
        case .incomingRinging(let info):
            return info
        case .connecting(let info):
            return info
        case .connected(let info):
            return info
        case .ended(let info, _):
            return info
        }
    }

    /// Get the call ID if available
    var callId: String? {
        switch self {
        case .idle:
            return nil
        case .outgoingInitiating(let id, _):
            return id
        case .incomingRinging(let info):
            return info.callId
        case .connecting(let info):
            return info.callId
        case .connected(let info):
            return info.callId
        case .ended(let info, _):
            return info.callId
        }
    }

    /// Get the remote party's WhisperID
    var remotePartyId: WhisperID? {
        switch self {
        case .idle:
            return nil
        case .outgoingInitiating(_, let remoteId):
            return remoteId
        case .incomingRinging(let info):
            return info.remotePartyId
        case .connecting(let info):
            return info.remotePartyId
        case .connected(let info):
            return info.remotePartyId
        case .ended(let info, _):
            return info.remotePartyId
        }
    }

    /// Display text for UI
    var displayText: String {
        switch self {
        case .idle:
            return ""
        case .outgoingInitiating:
            return "Calling..."
        case .incomingRinging:
            return "Incoming Call"
        case .connecting:
            return "Connecting..."
        case .connected(let info):
            return info.formattedDuration ?? "Connected"
        case .ended(_, let reason):
            return reason.displayText
        }
    }
}

// MARK: - Call Info

struct CallInfo: Codable, Equatable {

    /// Unique call identifier
    let callId: String

    /// WhisperID of the caller
    let callerId: WhisperID

    /// WhisperID of the callee
    let calleeId: WhisperID

    /// Whether this is a video call
    let isVideo: Bool

    /// When the call was initiated
    let initiatedAt: Date

    /// When the call was answered
    var answeredAt: Date?

    /// When the call ended
    var endedAt: Date?

    /// Whether we are the caller
    let isOutgoing: Bool

    // MARK: - Computed Properties

    /// The remote party's WhisperID (the other person in the call)
    var remotePartyId: WhisperID {
        isOutgoing ? calleeId : callerId
    }

    /// Call duration in seconds (only valid for answered calls)
    var duration: TimeInterval? {
        guard let start = answeredAt else { return nil }
        let end = endedAt ?? Date()
        return end.timeIntervalSince(start)
    }

    /// Formatted call duration (e.g., "5:23")
    var formattedDuration: String? {
        guard let duration = duration else { return nil }
        let minutes = Int(duration) / 60
        let seconds = Int(duration) % 60
        return String(format: "%d:%02d", minutes, seconds)
    }

    /// Whether the call was answered
    var wasAnswered: Bool {
        answeredAt != nil
    }

    // MARK: - Initialization

    /// Create an outgoing call
    static func outgoing(
        callId: String,
        callerId: WhisperID,
        calleeId: WhisperID,
        isVideo: Bool
    ) -> CallInfo {
        CallInfo(
            callId: callId,
            callerId: callerId,
            calleeId: calleeId,
            isVideo: isVideo,
            initiatedAt: Date(),
            answeredAt: nil,
            endedAt: nil,
            isOutgoing: true
        )
    }

    /// Create an incoming call
    static func incoming(
        callId: String,
        callerId: WhisperID,
        calleeId: WhisperID,
        isVideo: Bool
    ) -> CallInfo {
        CallInfo(
            callId: callId,
            callerId: callerId,
            calleeId: calleeId,
            isVideo: isVideo,
            initiatedAt: Date(),
            answeredAt: nil,
            endedAt: nil,
            isOutgoing: false
        )
    }

    // MARK: - Full Initializer

    init(
        callId: String,
        callerId: WhisperID,
        calleeId: WhisperID,
        isVideo: Bool,
        initiatedAt: Date,
        answeredAt: Date?,
        endedAt: Date?,
        isOutgoing: Bool
    ) {
        self.callId = callId
        self.callerId = callerId
        self.calleeId = calleeId
        self.isVideo = isVideo
        self.initiatedAt = initiatedAt
        self.answeredAt = answeredAt
        self.endedAt = endedAt
        self.isOutgoing = isOutgoing
    }

    // MARK: - Mutating

    /// Mark the call as answered
    mutating func markAnswered() {
        self.answeredAt = Date()
    }

    /// Mark the call as ended
    mutating func markEnded() {
        self.endedAt = Date()
    }
}

// MARK: - Call End Reason

enum CallEndReason: String, Codable, Equatable {
    /// Call ended normally by one of the parties
    case hungUp

    /// Call was declined by the callee
    case declined

    /// Call timed out (no answer)
    case noAnswer

    /// Call was cancelled by the caller before answer
    case cancelled

    /// Callee was busy on another call
    case busy

    /// Network or connection failure
    case failed

    /// Remote party is unavailable/offline
    case unavailable

    var displayText: String {
        switch self {
        case .hungUp: return "Call Ended"
        case .declined: return "Call Declined"
        case .noAnswer: return "No Answer"
        case .cancelled: return "Call Cancelled"
        case .busy: return "Line Busy"
        case .failed: return "Call Failed"
        case .unavailable: return "User Unavailable"
        }
    }

    var icon: String {
        switch self {
        case .hungUp: return "phone.down"
        case .declined: return "phone.down.circle"
        case .noAnswer: return "phone.badge.waveform"
        case .cancelled: return "xmark.circle"
        case .busy: return "phone.badge.waveform.fill"
        case .failed: return "exclamationmark.triangle"
        case .unavailable: return "person.slash"
        }
    }
}

// MARK: - TURN Credentials

struct TurnCredentials: Codable {

    /// TURN server URLs
    let urls: [String]

    /// Username for authentication
    let username: String

    /// Credential/password for authentication
    let credential: String

    /// When the credentials expire
    let expiresAt: Date

    /// Whether credentials are still valid
    var isValid: Bool {
        expiresAt > Date()
    }
}

// MARK: - Call History Entry

struct CallHistoryEntry: Identifiable, Codable {

    /// Unique identifier
    let id: String

    /// Call information
    let callInfo: CallInfo

    /// How the call ended
    let endReason: CallEndReason

    /// Whether this was a missed call (incoming, not answered by us)
    var isMissed: Bool {
        !callInfo.isOutgoing && !callInfo.wasAnswered
    }

    /// Create from call info and end reason
    init(callInfo: CallInfo, endReason: CallEndReason) {
        self.id = callInfo.callId
        self.callInfo = callInfo
        self.endReason = endReason
    }
}

// MARK: - WebRTC Session Description

struct RTCSessionDescription: Codable {
    let type: RTCSessionType
    let sdp: String
}

enum RTCSessionType: String, Codable {
    case offer
    case answer
}

// MARK: - ICE Candidate

struct RTCIceCandidate: Codable {
    let candidate: String
    let sdpMid: String?
    let sdpMLineIndex: Int32
}
