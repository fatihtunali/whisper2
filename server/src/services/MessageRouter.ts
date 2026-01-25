/**
 * Whisper2 Message Router
 * Following WHISPER-REBUILD.md Sections 4, 5
 *
 * Responsibilities:
 * - Validate send_message (session, from, timestamp, signature)
 * - Route to online recipient or pending queue
 * - Handle delivery receipts
 * - Manage message deduplication
 */

import { query } from '../db/postgres';
import * as redis from '../db/redis';
import { RedisKeys, TTL } from '../db/redis-keys';
import { connectionManager } from './ConnectionManager';
import { logger } from '../utils/logger';
import {
  verifySignature,
  isTimestampValid,
  isValidNonce,
} from '../utils/crypto';
import {
  SendMessagePayload,
  DeliveryReceiptPayload,
  FetchPendingPayload,
  MessageReceivedPayload,
  MessageAcceptedPayload,
  MessageDeliveredPayload,
  PendingMessagesPayload,
  ErrorCode,
  TIMESTAMP_SKEW_MS,
} from '../types/protocol';

// =============================================================================
// TYPES
// =============================================================================

export interface RouteResult {
  success: boolean;
  error?: {
    code: ErrorCode;
    message: string;
  };
  data?: MessageAcceptedPayload;
}

export interface ReceiptResult {
  success: boolean;
  error?: {
    code: ErrorCode;
    message: string;
  };
  senderNotification?: MessageDeliveredPayload;
  senderWhisperId?: string;
}

export interface FetchResult {
  success: boolean;
  error?: {
    code: ErrorCode;
    message: string;
  };
  data?: PendingMessagesPayload;
}

// =============================================================================
// CONSTANTS
// =============================================================================

const PENDING_LIMIT_DEFAULT = 50;
const DEDUP_TTL_SECONDS = 7 * 24 * 60 * 60; // 7 days

// Payload size limits (before base64 expansion memory spikes)
const MAX_CIPHERTEXT_B64_LEN = 100_000; // ~75KB decoded
const MAX_OBJECT_KEY_LEN = 255;
const MAX_CONTENT_TYPE_LEN = 100;

// Allowed content types for attachments
const ALLOWED_CONTENT_TYPES = new Set([
  'image/jpeg',
  'image/png',
  'image/gif',
  'image/webp',
  'image/heic',
  'video/mp4',
  'video/quicktime',
  'audio/aac',
  'audio/m4a',
  'audio/mpeg',
  'audio/ogg',
  'application/pdf',
  'application/octet-stream',
]);

// Object key allowed characters (prevent path traversal)
const OBJECT_KEY_PATTERN = /^[a-zA-Z0-9_\-./]+$/;

// =============================================================================
// MESSAGE ROUTER SERVICE
// =============================================================================

export class MessageRouter {
  /**
   * Handle send_message from sender.
   *
   * Validates:
   * 1. from === session.whisperId
   * 2. Timestamp within ±10 minutes
   * 3. Signature using sender's signPublicKey from DB
   * 4. Idempotency on messageId
   */
  async routeMessage(
    payload: SendMessagePayload,
    sessionWhisperId: string
  ): Promise<RouteResult> {
    const { messageId, from, to, timestamp, nonce, ciphertext, sig } = payload;

    // 1. Validate from === session.whisperId
    if (from !== sessionWhisperId) {
      logger.warn({ from, sessionWhisperId }, 'Sender mismatch');
      return {
        success: false,
        error: {
          code: 'FORBIDDEN',
          message: 'Sender does not match authenticated identity',
        },
      };
    }

    // 2. Validate timestamp within ±10 minutes
    if (!isTimestampValid(timestamp)) {
      logger.warn({ timestamp, serverTime: Date.now() }, 'Invalid timestamp');
      return {
        success: false,
        error: {
          code: 'INVALID_TIMESTAMP',
          message: `Timestamp outside allowed window (±${TIMESTAMP_SKEW_MS / 60000} minutes)`,
        },
      };
    }

    // 3. Validate nonce format (must be exactly 24 bytes base64)
    if (!isValidNonce(nonce)) {
      logger.warn({ messageId }, 'Invalid nonce format (must be 24 bytes)');
      return {
        success: false,
        error: {
          code: 'INVALID_PAYLOAD',
          message: 'Invalid nonce format (must be 24 bytes)',
        },
      };
    }

    // 3a. Validate ciphertext size (prevent memory spikes)
    if (ciphertext.length > MAX_CIPHERTEXT_B64_LEN) {
      logger.warn({ messageId, length: ciphertext.length }, 'Ciphertext too large');
      return {
        success: false,
        error: {
          code: 'INVALID_PAYLOAD',
          message: `Ciphertext exceeds maximum size (${MAX_CIPHERTEXT_B64_LEN} base64 chars)`,
        },
      };
    }

    // 3b. Validate attachment if present
    if (payload.attachment) {
      const att = payload.attachment;

      // Validate objectKey: non-empty, safe characters, no path traversal
      if (!att.objectKey || att.objectKey.length === 0 || att.objectKey.length > MAX_OBJECT_KEY_LEN) {
        logger.warn({ messageId }, 'Invalid attachment objectKey length');
        return {
          success: false,
          error: {
            code: 'INVALID_PAYLOAD',
            message: 'Invalid attachment objectKey',
          },
        };
      }
      if (!OBJECT_KEY_PATTERN.test(att.objectKey)) {
        logger.warn({ messageId, objectKey: att.objectKey }, 'Invalid attachment objectKey characters');
        return {
          success: false,
          error: {
            code: 'INVALID_PAYLOAD',
            message: 'Invalid attachment objectKey format',
          },
        };
      }
      if (att.objectKey.includes('..')) {
        logger.warn({ messageId }, 'Path traversal attempt in objectKey');
        return {
          success: false,
          error: {
            code: 'INVALID_PAYLOAD',
            message: 'Invalid attachment objectKey',
          },
        };
      }

      // Validate contentType
      if (!att.contentType || att.contentType.length > MAX_CONTENT_TYPE_LEN) {
        logger.warn({ messageId }, 'Invalid attachment contentType');
        return {
          success: false,
          error: {
            code: 'INVALID_PAYLOAD',
            message: 'Invalid attachment contentType',
          },
        };
      }
      if (!ALLOWED_CONTENT_TYPES.has(att.contentType)) {
        logger.warn({ messageId, contentType: att.contentType }, 'Disallowed attachment contentType');
        return {
          success: false,
          error: {
            code: 'INVALID_PAYLOAD',
            message: 'Unsupported attachment contentType',
          },
        };
      }

      // Validate ciphertextSize is positive
      if (!Number.isFinite(att.ciphertextSize) || att.ciphertextSize <= 0) {
        logger.warn({ messageId }, 'Invalid attachment ciphertextSize');
        return {
          success: false,
          error: {
            code: 'INVALID_PAYLOAD',
            message: 'Invalid attachment ciphertextSize',
          },
        };
      }

      // Validate attachment nonces (must be exactly 24 bytes)
      if (!isValidNonce(att.fileNonce)) {
        logger.warn({ messageId }, 'Invalid attachment fileNonce (must be 24 bytes)');
        return {
          success: false,
          error: {
            code: 'INVALID_PAYLOAD',
            message: 'Invalid attachment fileNonce (must be 24 bytes)',
          },
        };
      }
      if (!att.fileKeyBox || !isValidNonce(att.fileKeyBox.nonce)) {
        logger.warn({ messageId }, 'Invalid attachment fileKeyBox.nonce (must be 24 bytes)');
        return {
          success: false,
          error: {
            code: 'INVALID_PAYLOAD',
            message: 'Invalid attachment fileKeyBox.nonce (must be 24 bytes)',
          },
        };
      }
    }

    // 4. Get sender's signPublicKey from DB
    const senderKeys = await this.getUserKeys(from);
    if (!senderKeys) {
      logger.warn({ from }, 'Sender not found in DB');
      return {
        success: false,
        error: {
          code: 'NOT_FOUND',
          message: 'Sender not found',
        },
      };
    }

    // 5. Verify signature
    const sigValid = verifySignature(senderKeys.sign_public_key, sig, {
      messageType: 'send_message',
      messageId,
      from,
      toOrGroupId: to,
      timestamp,
      nonce,
      ciphertext,
    });

    if (!sigValid) {
      logger.warn({ messageId, from }, 'Signature verification failed');
      return {
        success: false,
        error: {
          code: 'AUTH_FAILED',
          message: 'Signature verification failed',
        },
      };
    }

    // 6. Check recipient exists
    const recipientExists = await this.userExists(to);
    if (!recipientExists) {
      logger.warn({ to }, 'Recipient not found');
      return {
        success: false,
        error: {
          code: 'NOT_FOUND',
          message: 'Recipient not found',
        },
      };
    }

    // 7. Check idempotency (dedup)
    const isDuplicate = await this.checkDuplicate(from, messageId);
    if (isDuplicate) {
      logger.info({ messageId, from }, 'Duplicate message, returning ACK');
      return {
        success: true,
        data: { messageId, status: 'sent' },
      };
    }

    // 8. Mark message as processed (dedup)
    await this.markProcessed(from, messageId);

    // 9. Build message_received payload for recipient
    // Omit optional fields when not present (cleaner cross-platform decoding)
    const messageReceived: MessageReceivedPayload = {
      messageId,
      from,
      to,
      msgType: payload.msgType,
      timestamp,
      nonce,
      ciphertext,
      sig,
    };

    // Only include optional fields when present (not null/undefined)
    if (payload.replyTo) {
      messageReceived.replyTo = payload.replyTo;
    }
    if (payload.reactions && Object.keys(payload.reactions).length > 0) {
      messageReceived.reactions = payload.reactions;
    }
    if (payload.attachment) {
      messageReceived.attachment = payload.attachment;
    }

    // 10. Try to route to online recipient
    const delivered = connectionManager.sendToUser(to, {
      type: 'message_received',
      payload: messageReceived,
    });

    if (delivered) {
      logger.info({ messageId, from, to }, 'Message delivered online');
    } else {
      // 11. Recipient offline - store in pending queue
      await this.storePending(to, messageReceived);
      logger.info({ messageId, from, to }, 'Message stored in pending queue');

      // TODO: Trigger push notification (Step 3/4)
      // await pushService.sendWakeUp(to, { messageId, from });
    }

    return {
      success: true,
      data: { messageId, status: 'sent' },
    };
  }

  /**
   * Handle delivery_receipt from recipient.
   *
   * Validates:
   * 1. from === session.whisperId (recipient sending receipt)
   * 2. Timestamp within window
   */
  async handleReceipt(
    payload: DeliveryReceiptPayload,
    sessionWhisperId: string
  ): Promise<ReceiptResult> {
    const { messageId, from, to, status, timestamp } = payload;

    // 1. Validate from === session.whisperId
    if (from !== sessionWhisperId) {
      return {
        success: false,
        error: {
          code: 'FORBIDDEN',
          message: 'Receipt sender does not match authenticated identity',
        },
      };
    }

    // 2. Validate timestamp
    if (!isTimestampValid(timestamp)) {
      return {
        success: false,
        error: {
          code: 'INVALID_TIMESTAMP',
          message: 'Timestamp outside allowed window',
        },
      };
    }

    // 3. Remove from pending queue (if status is 'delivered')
    if (status === 'delivered') {
      await this.removePending(from, messageId);
    }

    // 4. Notify original sender
    const notification: MessageDeliveredPayload = {
      messageId,
      status,
      timestamp,
    };

    logger.info({ messageId, from, to, status }, 'Delivery receipt processed');

    return {
      success: true,
      senderNotification: notification,
      senderWhisperId: to, // 'to' in receipt is the original sender
    };
  }

  /**
   * Fetch pending messages for a user.
   *
   * Returns messages with paging. Does NOT delete on fetch.
   * Delete only happens on delivery_receipt.
   */
  async fetchPending(
    payload: FetchPendingPayload,
    sessionWhisperId: string
  ): Promise<FetchResult> {
    const limit = payload.limit || PENDING_LIMIT_DEFAULT;
    const cursor = payload.cursor ? parseInt(payload.cursor, 10) : 0;

    // Get pending messages from Redis list
    const pendingKey = RedisKeys.pending(sessionWhisperId);
    const messages = await redis.listRange(pendingKey, cursor, cursor + limit);

    // Parse message payloads
    const parsedMessages: MessageReceivedPayload[] = [];
    for (const msgJson of messages) {
      try {
        parsedMessages.push(JSON.parse(msgJson));
      } catch {
        logger.error({ msgJson }, 'Failed to parse pending message');
      }
    }

    // Determine next cursor
    const totalPending = await redis.listLength(pendingKey);
    const nextStart = cursor + messages.length;
    const nextCursor = nextStart < totalPending ? String(nextStart) : undefined;

    logger.info(
      { whisperId: sessionWhisperId, count: parsedMessages.length, cursor, nextCursor },
      'Fetched pending messages'
    );

    return {
      success: true,
      data: {
        messages: parsedMessages,
        nextCursor,
      },
    };
  }

  // ===========================================================================
  // PRIVATE HELPERS
  // ===========================================================================

  private async getUserKeys(
    whisperId: string
  ): Promise<{ enc_public_key: string; sign_public_key: string } | null> {
    const result = await query<{
      enc_public_key: string;
      sign_public_key: string;
    }>(
      'SELECT enc_public_key, sign_public_key FROM users WHERE whisper_id = $1 AND status = $2',
      [whisperId, 'active']
    );
    return result.rows[0] || null;
  }

  private async userExists(whisperId: string): Promise<boolean> {
    const result = await query(
      'SELECT 1 FROM users WHERE whisper_id = $1 AND status = $2',
      [whisperId, 'active']
    );
    return (result.rowCount ?? 0) > 0;
  }

  /**
   * Check if message was already processed (dedup).
   * Uses Redis SETNX pattern.
   */
  private async checkDuplicate(
    senderId: string,
    messageId: string
  ): Promise<boolean> {
    const key = `dedup:${senderId}:${messageId}`;
    const exists = await redis.exists(key);
    return exists;
  }

  /**
   * Mark message as processed for dedup.
   */
  private async markProcessed(
    senderId: string,
    messageId: string
  ): Promise<void> {
    const key = `dedup:${senderId}:${messageId}`;
    await redis.setWithTTL(key, '1', DEDUP_TTL_SECONDS);
  }

  /**
   * Store message in pending queue for offline recipient.
   */
  private async storePending(
    recipientId: string,
    message: MessageReceivedPayload
  ): Promise<void> {
    const key = RedisKeys.pending(recipientId);
    await redis.listPush(key, JSON.stringify(message));
    // Ensure TTL is set/refreshed
    await redis.expire(key, TTL.PENDING);
  }

  /**
   * Remove specific message from pending queue.
   * Called when delivery_receipt(delivered) is received.
   */
  private async removePending(
    recipientId: string,
    messageId: string
  ): Promise<void> {
    const key = RedisKeys.pending(recipientId);
    const messages = await redis.listRange(key, 0, -1);

    // Find and remove the message with matching messageId
    for (const msgJson of messages) {
      try {
        const msg = JSON.parse(msgJson) as MessageReceivedPayload;
        if (msg.messageId === messageId) {
          await redis.listRemove(key, 1, msgJson);
          logger.debug({ recipientId, messageId }, 'Removed message from pending');
          return;
        }
      } catch {
        // Skip malformed messages
      }
    }
  }
}

export const messageRouter = new MessageRouter();
