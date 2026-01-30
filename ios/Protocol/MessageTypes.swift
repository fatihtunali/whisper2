import Foundation

/// Message type constants matching server protocol.ts
enum MessageTypes {
    // Auth
    static let registerBegin = "register_begin"
    static let registerChallenge = "register_challenge"
    static let registerProof = "register_proof"
    static let registerAck = "register_ack"
    static let sessionRefresh = "session_refresh"
    static let sessionRefreshAck = "session_refresh_ack"
    static let logout = "logout"

    // Messaging
    static let sendMessage = "send_message"
    static let messageAccepted = "message_accepted"
    static let messageReceived = "message_received"
    static let deliveryReceipt = "delivery_receipt"
    static let messageDelivered = "message_delivered"
    static let fetchPending = "fetch_pending"
    static let pendingMessages = "pending_messages"

    // Groups
    static let groupCreate = "group_create"
    static let groupEvent = "group_event"
    static let groupUpdate = "group_update"
    static let groupSendMessage = "group_send_message"

    // Calls
    static let getTurnCredentials = "get_turn_credentials"
    static let turnCredentials = "turn_credentials"
    static let callInitiate = "call_initiate"
    static let callIncoming = "call_incoming"
    static let callAnswer = "call_answer"
    static let callIceCandidate = "call_ice_candidate"
    static let callEnd = "call_end"
    static let callRinging = "call_ringing"

    // Push tokens
    static let updateTokens = "update_tokens"

    // Presence
    static let presenceUpdate = "presence_update"
    static let typing = "typing"
    static let typingNotification = "typing_notification"

    // Ping/Pong
    static let ping = "ping"
    static let pong = "pong"

    // Error
    static let error = "error"
}

/// Message content types - must match server schema msgType enum
enum MessageContentType: String, Codable {
    case text
    case image
    case voice
    case audio    // For audio messages (distinct from voice notes)
    case video    // For video messages
    case file
    case location // For location sharing
    case system
}

/// Delivery status for delivery receipts - server only accepts 'delivered' or 'read'
/// Note: 'sent' status comes from message_accepted, not delivery_receipt
enum DeliveryStatus: String, Codable {
    case delivered
    case read
}

/// Call end reasons
enum CallEndReason: String, Codable, Equatable {
    case ended      // Normal call end (either party hangs up)
    case declined   // Callee rejected the call
    case busy       // Callee is busy
    case timeout    // Call wasn't answered in time
    case failed     // Connection/technical failure
    case cancelled  // Caller cancelled before callee answered
}

/// Presence status
enum PresenceStatus: String, Codable {
    case online
    case offline
}
