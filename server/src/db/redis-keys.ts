/**
 * Whisper2 Redis Keyspace Definitions
 * Following WHISPER-REBUILD.md Section 12.2
 *
 * Redis is used for volatile/TTL data:
 * - Sessions
 * - Challenges
 * - Presence
 * - Pending messages
 * - Rate limits
 * - Call state
 */

// =============================================================================
// TTL CONSTANTS (in seconds)
// =============================================================================

export const TTL = {
  /** Session expires after 7 days (Section 3.3) */
  SESSION: 7 * 24 * 60 * 60, // 604800 seconds

  /** Challenge expires after 60 seconds (Section 3.3) */
  CHALLENGE: 60,

  /** Pending messages expire after 72 hours (Section 5.1) */
  PENDING: 72 * 60 * 60, // 259200 seconds

  /** Presence TTL - 5 minutes for cleanup on disconnect */
  PRESENCE: 5 * 60, // 300 seconds

  /** Call state TTL - 5 minutes */
  CALL: 5 * 60, // 300 seconds

  /** Rate limit window - 1 minute */
  RATE_LIMIT: 60,
} as const;

// =============================================================================
// KEY BUILDERS
// =============================================================================

export const RedisKeys = {
  /**
   * Session key: stores session data
   * Value: JSON { whisperId, deviceId, platform }
   * TTL: 7 days
   */
  session: (token: string): string => `session:${token}`,

  /**
   * Challenge key: stores challenge bytes for auth
   * Value: base64 encoded challenge bytes (32 bytes)
   * TTL: 60 seconds
   * Single-use: deleted after verification
   */
  challenge: (challengeId: string): string => `challenge:${challengeId}`,

  /**
   * Presence key: tracks online status
   * Value: JSON { connId, lastSeen }
   * TTL: 5 minutes (refreshed on activity)
   */
  presence: (whisperId: string): string => `presence:${whisperId}`,

  /**
   * Pending messages key: stores messages for offline users
   * Value: Redis List of message payloads
   * TTL: 72 hours
   */
  pending: (whisperId: string): string => `pending:${whisperId}`,

  /**
   * Rate limit key: tracks request counts
   * Value: integer count
   * TTL: 1 minute (sliding window)
   */
  rateLimit: (whisperId: string, action: string): string =>
    `ratelimit:${whisperId}:${action}`,

  /**
   * Rate limit by IP (for unauthenticated requests)
   */
  rateLimitIp: (ip: string, action: string): string =>
    `ratelimit:ip:${ip}:${action}`,

  /**
   * Call state key: tracks active call signaling
   * Value: JSON { callId, initiator, recipient, state, createdAt }
   * TTL: 5 minutes
   */
  call: (callId: string): string => `call:${callId}`,

  /**
   * Active session lookup: maps whisperId to current sessionToken
   * Used for single-active-device enforcement
   * Value: sessionToken
   * TTL: 7 days (same as session)
   */
  activeSession: (whisperId: string): string => `active_session:${whisperId}`,

  /**
   * Connection mapping: maps connId to whisperId
   * Used for routing messages to WebSocket connections
   * Value: whisperId
   * No TTL: removed on disconnect
   */
  connection: (connId: string): string => `conn:${connId}`,

  /**
   * Reverse connection mapping: whisperId to connId
   * For looking up connection by user
   * Value: connId
   * No TTL: removed on disconnect
   */
  userConnection: (whisperId: string): string => `user_conn:${whisperId}`,
} as const;

// =============================================================================
// SESSION DATA TYPE
// =============================================================================

export interface SessionData {
  whisperId: string;
  deviceId: string;
  platform: 'ios' | 'android';
  createdAt: number; // timestamp ms
  expiresAt: number; // timestamp ms
}

// =============================================================================
// PRESENCE DATA TYPE
// =============================================================================

export interface PresenceData {
  connId: string;
  lastSeen: number; // timestamp ms
  platform: 'ios' | 'android';
}

// =============================================================================
// CALL STATE TYPE
// =============================================================================

export type CallState = 'initiating' | 'ringing' | 'answered' | 'ended';

export interface CallData {
  callId: string;
  initiator: string; // whisperId
  recipient: string; // whisperId
  state: CallState;
  isVideo: boolean;
  createdAt: number; // timestamp ms
}
