/**
 * Whisper2 Protocol Types
 * Following WHISPER-REBUILD.md Section 3.1
 *
 * These types define the WebSocket protocol contract.
 * Server validates all incoming payloads against these types.
 * iOS/Android clients must mirror these exactly.
 */

// =============================================================================
// PROTOCOL CONSTANTS
// =============================================================================

export const PROTOCOL_VERSION = 1;
export const CRYPTO_VERSION = 1;

/** Timestamp skew tolerance: ±10 minutes (Section 3.5) */
export const TIMESTAMP_SKEW_MS = 600_000;

// =============================================================================
// WHISPER ID FORMAT
// =============================================================================

/** Format: WSP-XXXX-XXXX-XXXX (Base32: A-Z, 2-7) */
export const WHISPER_ID_REGEX = /^WSP-[A-Z2-7]{4}-[A-Z2-7]{4}-[A-Z2-7]{4}$/;

// =============================================================================
// CANONICAL WS FRAME (Section 3.1)
// =============================================================================

export interface WsFrame<T = unknown> {
  type: string;
  requestId?: string; // uuid, optional
  payload: T;
}

// =============================================================================
// STANDARD ERROR RESPONSE (Section 3.1)
// =============================================================================

export type ErrorCode =
  | 'NOT_REGISTERED'
  | 'AUTH_FAILED'
  | 'INVALID_PAYLOAD'
  | 'INVALID_TIMESTAMP'
  | 'RATE_LIMITED'
  | 'USER_BANNED'
  | 'NOT_FOUND'
  | 'FORBIDDEN'
  | 'INTERNAL_ERROR';

export interface ErrorPayload {
  code: ErrorCode;
  message: string;
  requestId?: string;
}

// =============================================================================
// VERSIONING (Section 3.1)
// Every privileged client message payload MUST include these
// =============================================================================

export interface VersionedPayload {
  protocolVersion: number;
  cryptoVersion: number;
}

// =============================================================================
// AUTHENTICATION FLOW (Section 3.3)
// =============================================================================

// Step 1: Client → Server
export interface RegisterBeginPayload extends VersionedPayload {
  whisperId?: string; // optional: present if recovery
  deviceId: string; // UUIDv4
  platform: 'ios' | 'android';
}

// Step 2: Server → Client
export interface RegisterChallengePayload {
  challengeId: string; // uuid
  challenge: string; // base64(32 bytes)
  expiresAt: number; // timestamp ms
}

// Step 3: Client → Server
export interface RegisterProofPayload extends VersionedPayload {
  challengeId: string;
  deviceId: string;
  platform: 'ios' | 'android';
  whisperId?: string; // present if recovery
  encPublicKey: string; // base64
  signPublicKey: string; // base64
  signature: string; // base64(ed25519(sign(challengeBytes)))
  pushToken?: string;
  voipToken?: string; // iOS only
}

// Step 4: Server → Client
export interface RegisterAckPayload {
  success: boolean;
  whisperId: string;
  sessionToken: string; // opaque
  sessionExpiresAt: number; // timestamp ms
  serverTime: number; // timestamp ms
}

// =============================================================================
// SESSION MANAGEMENT (Section 3.3)
// =============================================================================

export interface SessionRefreshPayload extends VersionedPayload {
  sessionToken: string;
}

export interface SessionRefreshAckPayload {
  sessionToken: string;
  sessionExpiresAt: number;
  serverTime: number;
}

export interface LogoutPayload extends VersionedPayload {
  sessionToken: string;
}

// =============================================================================
// PING/PONG (for keepalive and time sync)
// =============================================================================

export interface PingPayload {
  timestamp: number;
}

export interface PongPayload {
  timestamp: number;
  serverTime: number;
}

// =============================================================================
// MESSAGING (Section 4)
// =============================================================================

export type MessageType = 'text' | 'image' | 'voice' | 'file' | 'system';

// Attachment pointer (Section 6.3)
export interface AttachmentPointer {
  objectKey: string; // S3 path
  contentType: string;
  ciphertextSize: number;
  fileNonce: string; // base64(24 bytes)
  fileKeyBox: {
    nonce: string; // base64(24 bytes)
    ciphertext: string; // base64
  };
}

// Send message (Section 4.1)
export interface SendMessagePayload extends VersionedPayload {
  sessionToken: string;
  messageId: string; // uuid
  from: string; // whisperId
  to: string; // whisperId
  msgType: MessageType;
  timestamp: number; // ms
  nonce: string; // base64(24 bytes)
  ciphertext: string; // base64
  sig: string; // base64
  replyTo?: string; // uuid
  reactions?: Record<string, string[]>; // emoji -> whisperId[]
  attachment?: AttachmentPointer | null;
}

// Message accepted ACK (Section 4.1)
export interface MessageAcceptedPayload {
  messageId: string;
  status: 'sent';
}

// Message received (Section 4.2)
export interface MessageReceivedPayload {
  messageId: string;
  from: string;
  to: string;
  msgType: MessageType;
  timestamp: number;
  nonce: string;
  ciphertext: string;
  sig: string;
  replyTo?: string;
  reactions?: Record<string, string[]>;
  attachment?: AttachmentPointer | null;
}

// Delivery receipt (Section 4.3)
export interface DeliveryReceiptPayload extends VersionedPayload {
  sessionToken: string;
  messageId: string;
  from: string; // recipient whisperId
  to: string; // sender whisperId
  status: 'delivered' | 'read';
  timestamp: number;
}

// Message delivered notification
export interface MessageDeliveredPayload {
  messageId: string;
  status: 'delivered' | 'read';
  timestamp: number;
}

// =============================================================================
// PENDING MESSAGES (Section 5)
// =============================================================================

export interface FetchPendingPayload extends VersionedPayload {
  sessionToken: string;
  cursor?: string;
  limit?: number; // default 50
}

export interface PendingMessagesPayload {
  messages: MessageReceivedPayload[];
  nextCursor?: string;
}

// =============================================================================
// GROUPS (Section 7)
// =============================================================================

export interface GroupCreatePayload extends VersionedPayload {
  sessionToken: string;
  title: string;
  memberIds: string[]; // whisperId[]
}

export interface GroupCreateAckPayload {
  groupId: string;
  title: string;
  memberIds: string[];
  createdAt: number;
}

export interface GroupUpdatePayload extends VersionedPayload {
  sessionToken: string;
  groupId: string;
  addMembers?: string[];
  removeMembers?: string[];
  title?: string;
}

export interface GroupSendMessagePayload extends VersionedPayload {
  sessionToken: string;
  groupId: string;
  messageId: string;
  from: string;
  to: string; // individual member
  msgType: MessageType;
  timestamp: number;
  nonce: string;
  ciphertext: string;
  sig: string;
  attachment?: AttachmentPointer | null;
}

// =============================================================================
// PUSH TOKEN UPDATE (Section 8)
// =============================================================================

export interface UpdateTokensPayload extends VersionedPayload {
  sessionToken: string;
  pushToken?: string;
  voipToken?: string; // iOS only
}

// =============================================================================
// CALLS (Section 9)
// =============================================================================

export interface CallInitiatePayload extends VersionedPayload {
  sessionToken: string;
  callId: string;
  from: string;
  to: string;
  isVideo: boolean;
  timestamp: number;
  nonce: string;
  ciphertext: string; // encrypted SDP offer
  sig: string;
}

export interface CallAnswerPayload extends VersionedPayload {
  sessionToken: string;
  callId: string;
  from: string;
  to: string;
  timestamp: number;
  nonce: string;
  ciphertext: string; // encrypted SDP answer
  sig: string;
}

export interface CallIceCandidatePayload extends VersionedPayload {
  sessionToken: string;
  callId: string;
  from: string;
  to: string;
  timestamp: number;
  nonce: string;
  ciphertext: string; // encrypted ICE candidate
  sig: string;
}

export interface CallEndPayload extends VersionedPayload {
  sessionToken: string;
  callId: string;
  from: string;
  to: string;
  timestamp: number;
  nonce: string;
  ciphertext: string;
  sig: string;
  reason: 'ended' | 'declined' | 'busy' | 'timeout' | 'failed';
}

export interface CallRingingPayload {
  callId: string;
  from: string;
  to: string;
}

// =============================================================================
// PRESENCE & TYPING (Section 10)
// =============================================================================

export interface PresenceUpdatePayload {
  whisperId: string;
  status: 'online' | 'offline';
  lastSeen?: number;
}

export interface TypingPayload extends VersionedPayload {
  sessionToken: string;
  to: string; // peer whisperId
  isTyping: boolean;
}

export interface TypingNotificationPayload {
  from: string;
  isTyping: boolean;
}

// =============================================================================
// MESSAGE TYPES ENUM (for canonical signing)
// =============================================================================

export const SignedMessageTypes = [
  'send_message',
  'group_send_message',
  'call_initiate',
  'call_answer',
  'call_ice_candidate',
  'call_end',
] as const;

export type SignedMessageType = (typeof SignedMessageTypes)[number];

// =============================================================================
// ALL MESSAGE TYPES
// =============================================================================

export const MessageTypes = {
  // Auth
  REGISTER_BEGIN: 'register_begin',
  REGISTER_CHALLENGE: 'register_challenge',
  REGISTER_PROOF: 'register_proof',
  REGISTER_ACK: 'register_ack',
  SESSION_REFRESH: 'session_refresh',
  SESSION_REFRESH_ACK: 'session_refresh_ack',
  LOGOUT: 'logout',

  // Messaging
  SEND_MESSAGE: 'send_message',
  MESSAGE_ACCEPTED: 'message_accepted',
  MESSAGE_RECEIVED: 'message_received',
  DELIVERY_RECEIPT: 'delivery_receipt',
  MESSAGE_DELIVERED: 'message_delivered',
  FETCH_PENDING: 'fetch_pending',
  PENDING_MESSAGES: 'pending_messages',

  // Groups
  GROUP_CREATE: 'group_create',
  GROUP_CREATE_ACK: 'group_create_ack',
  GROUP_UPDATE: 'group_update',
  GROUP_SEND_MESSAGE: 'group_send_message',

  // Calls
  CALL_INITIATE: 'call_initiate',
  CALL_ANSWER: 'call_answer',
  CALL_ICE_CANDIDATE: 'call_ice_candidate',
  CALL_END: 'call_end',
  CALL_RINGING: 'call_ringing',

  // Push tokens
  UPDATE_TOKENS: 'update_tokens',

  // Presence
  PRESENCE_UPDATE: 'presence_update',
  TYPING: 'typing',
  TYPING_NOTIFICATION: 'typing_notification',

  // Ping/Pong
  PING: 'ping',
  PONG: 'pong',

  // Error
  ERROR: 'error',
} as const;
