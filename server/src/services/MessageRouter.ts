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

import * as redis from '../db/redis';
import { RedisKeys, TTL } from '../db/redis-keys';
import { connectionManager } from './ConnectionManager';
import { logger } from '../utils/logger';
import {
  verifySignature,
  isTimestampValid,
} from '../utils/crypto';
import { pushService } from './PushService';
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
import { attachmentService } from './AttachmentService';
import { userExists, getUserKeys } from '../db/UserRepository';
import {
  validateAttachment,
  isDuplicateMessage,
  markMessageProcessed,
} from '../utils/validation';

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
const MAX_CIPHERTEXT_B64_LEN = 100_000; // ~75KB decoded

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

    // 3b. Validate attachment if present (using shared validation)
    if (payload.attachment) {
      const attValidation = validateAttachment(payload.attachment);
      if (!attValidation.valid) {
        logger.warn({ messageId, error: attValidation.error }, 'Attachment validation failed');
        return {
          success: false,
          error: {
            code: 'INVALID_PAYLOAD',
            message: attValidation.error!,
          },
        };
      }
    }

    // 4. Get sender's signPublicKey from DB
    const senderKeys = await getUserKeys(from);
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
    const recipientExists = await userExists(to);
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
    const isDuplicate = await isDuplicateMessage(from, messageId);
    if (isDuplicate) {
      logger.info({ messageId, from }, 'Duplicate message, returning ACK');
      return {
        success: true,
        data: { messageId, status: 'sent' },
      };
    }

    // 8. Mark message as processed (dedup)
    await markMessageProcessed(from, messageId);

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

    // Include sender's encryption public key (for message requests from unknown senders)
    messageReceived.senderEncPublicKey = senderKeys.enc_public_key;

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

      // 12. Trigger push notification for offline messages
      // Use minimal coalescing (2 seconds) to prevent app crash from rapid notifications
      await pushService.sendWake(to, 'message');
    }

    // 12. Grant attachment access to recipient (Step 4)
    if (payload.attachment) {
      const granted = await attachmentService.grantAccess(payload.attachment.objectKey, to);
      if (!granted) {
        logger.warn(
          { messageId, objectKey: payload.attachment.objectKey, to },
          'Failed to grant attachment access (attachment may not exist)'
        );
      }
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
