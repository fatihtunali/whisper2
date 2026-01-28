/**
 * Whisper2 Call Service
 * Following WHISPER-REBUILD.md Step 7
 *
 * Handles:
 * - TURN credentials generation
 * - Call signaling (initiate, ringing, answer, ICE, end)
 * - Call state machine (Redis TTL-based)
 * - Push wake-up for incoming calls
 * - Signature verification on signaling messages
 */

import * as crypto from 'crypto';
import { query } from '../db/postgres';
import * as redis from '../db/redis';
import { RedisKeys, TTL, CallData, CallState } from '../db/redis-keys';
import { connectionManager } from './ConnectionManager';
import { pushService } from './PushService';
import { logger } from '../utils/logger';
import { verifySignature, isTimestampValid, isValidNonce } from '../utils/crypto';
import {
  ErrorCode,
  CallInitiatePayload,
  CallAnswerPayload,
  CallIceCandidatePayload,
  CallEndPayload,
  CallRingingPayload,
  CallIncomingPayload,
  TurnCredentialsPayload,
  MessageTypes,
} from '../types/protocol';

// =============================================================================
// CONFIGURATION
// =============================================================================

const TURN_URLS = (process.env.TURN_URLS || '').split(',').filter(Boolean);
const TURN_SECRET = process.env.TURN_SECRET || '';
const TURN_TTL = parseInt(process.env.TURN_TTL_SECONDS || '600', 10);

// =============================================================================
// TYPES
// =============================================================================

export interface ServiceResult<T = void> {
  success: boolean;
  error?: { code: ErrorCode; message: string };
  data?: T;
}

// =============================================================================
// CALL SERVICE
// =============================================================================

export class CallService {
  /**
   * Generate TURN credentials for a user.
   * Uses time-limited HMAC-SHA1 authentication.
   */
  async getTurnCredentials(whisperId: string): Promise<ServiceResult<TurnCredentialsPayload>> {
    if (!TURN_SECRET || TURN_URLS.length === 0) {
      return {
        success: false,
        error: { code: 'INTERNAL_ERROR', message: 'TURN not configured' },
      };
    }

    // username = <unix_timestamp + ttl>:<whisperId>
    const expiry = Math.floor(Date.now() / 1000) + TURN_TTL;
    const username = `${expiry}:${whisperId}`;

    // credential = base64(HMAC-SHA1(TURN_SECRET, username))
    const hmac = crypto.createHmac('sha1', TURN_SECRET);
    hmac.update(username);
    const credential = hmac.digest('base64');

    return {
      success: true,
      data: {
        urls: TURN_URLS,
        username,
        credential,
        ttl: TURN_TTL,
      },
    };
  }

  /**
   * Initiate a call (caller → server).
   */
  async initiateCall(
    payload: CallInitiatePayload,
    callerWhisperId: string
  ): Promise<ServiceResult<{ callId: string }>> {
    const { callId, from, to, isVideo, timestamp, nonce, ciphertext, sig } = payload;

    // 1. Validate from === session
    if (from !== callerWhisperId) {
      return {
        success: false,
        error: { code: 'FORBIDDEN', message: 'Caller does not match authenticated identity' },
      };
    }

    // 2. Validate timestamp
    if (!isTimestampValid(timestamp)) {
      return {
        success: false,
        error: { code: 'INVALID_TIMESTAMP', message: 'Timestamp outside allowed window' },
      };
    }

    // 3. Validate nonce
    if (!isValidNonce(nonce)) {
      return {
        success: false,
        error: { code: 'INVALID_PAYLOAD', message: 'Invalid nonce format' },
      };
    }

    // 4. Get caller's sign key
    const callerKeys = await this.getUserKeys(from);
    if (!callerKeys) {
      return {
        success: false,
        error: { code: 'NOT_FOUND', message: 'Caller not found' },
      };
    }

    // 5. Verify signature
    const sigValid = verifySignature(callerKeys.sign_public_key, sig, {
      messageType: 'call_initiate',
      messageId: callId,
      from,
      toOrGroupId: to,
      timestamp,
      nonce,
      ciphertext,
    });

    if (!sigValid) {
      logger.warn({ callId, from, to }, 'Call initiate signature verification failed');
      return {
        success: false,
        error: { code: 'AUTH_FAILED', message: 'Signature verification failed' },
      };
    }

    // 6. Verify callee exists and is active
    const calleeExists = await this.userExists(to);
    if (!calleeExists) {
      return {
        success: false,
        error: { code: 'NOT_FOUND', message: 'Callee not found' },
      };
    }

    // 7. Check if there's already an active call involving either party
    // (This is optional - depends on whether you want to allow multiple calls)

    // 8. Create call state in Redis
    const now = Date.now();
    const callData: CallData = {
      callId,
      caller: from,
      callee: to,
      state: 'initiating',
      isVideo,
      createdAt: now,
      lastUpdate: now,
    };

    const callKey = RedisKeys.call(callId);
    await redis.setWithTTL(callKey, JSON.stringify(callData), TTL.CALL);

    // 9. Build call_incoming payload
    const callIncoming: CallIncomingPayload = {
      callId,
      from,
      isVideo,
      timestamp,
      nonce,
      ciphertext,
      sig,
    };

    // 10. Deliver to callee
    const delivered = connectionManager.sendToUser(to, {
      type: MessageTypes.CALL_INCOMING,
      payload: callIncoming,
    });

    if (!delivered) {
      // Callee offline - store pending call and send push
      const pendingKey = RedisKeys.pendingCall(to);
      await redis.setWithTTL(pendingKey, JSON.stringify(callIncoming), TTL.PENDING_CALL);

      // Send call push with full call data for VoIP/CallKit
      await pushService.sendCallWake(to, {
        callId,
        from,
        isVideo,
        timestamp,
        nonce,
        ciphertext,
        sig,
      });

      logger.info({ callId, from, to }, 'Call initiated, callee offline - push sent');
    } else {
      logger.info({ callId, from, to }, 'Call initiated, callee online - call_incoming sent');
    }

    return {
      success: true,
      data: { callId },
    };
  }

  /**
   * Handle call ringing (callee → server → caller).
   */
  async handleRinging(
    payload: CallRingingPayload,
    calleeWhisperId: string
  ): Promise<ServiceResult> {
    const { callId, from, to, timestamp, nonce, ciphertext, sig } = payload;

    // 1. Validate from === session (callee)
    if (from !== calleeWhisperId) {
      return {
        success: false,
        error: { code: 'FORBIDDEN', message: 'Sender does not match authenticated identity' },
      };
    }

    // 2. Validate timestamp
    if (!isTimestampValid(timestamp)) {
      return {
        success: false,
        error: { code: 'INVALID_TIMESTAMP', message: 'Timestamp outside allowed window' },
      };
    }

    // 3. Get and validate call state
    const callData = await this.getCall(callId);
    if (!callData) {
      return {
        success: false,
        error: { code: 'NOT_FOUND', message: 'Call not found' },
      };
    }

    // 4. Verify parties
    if (callData.callee !== from || callData.caller !== to) {
      return {
        success: false,
        error: { code: 'FORBIDDEN', message: 'Not authorized for this call' },
      };
    }

    // 5. Update call state
    callData.state = 'ringing';
    callData.lastUpdate = Date.now();
    await this.updateCall(callData);

    // 6. Forward to caller
    connectionManager.sendToUser(to, {
      type: MessageTypes.CALL_RINGING,
      payload: { callId, from },
    });

    logger.info({ callId, from, to }, 'Call ringing');

    return { success: true };
  }

  /**
   * Handle call answer (callee → server → caller).
   */
  async handleAnswer(
    payload: CallAnswerPayload,
    calleeWhisperId: string
  ): Promise<ServiceResult> {
    const { callId, from, to, timestamp, nonce, ciphertext, sig } = payload;

    // 1. Validate from === session (callee)
    if (from !== calleeWhisperId) {
      return {
        success: false,
        error: { code: 'FORBIDDEN', message: 'Sender does not match authenticated identity' },
      };
    }

    // 2. Validate timestamp
    if (!isTimestampValid(timestamp)) {
      return {
        success: false,
        error: { code: 'INVALID_TIMESTAMP', message: 'Timestamp outside allowed window' },
      };
    }

    // 3. Validate nonce
    if (!isValidNonce(nonce)) {
      return {
        success: false,
        error: { code: 'INVALID_PAYLOAD', message: 'Invalid nonce format' },
      };
    }

    // 4. Get callee's sign key and verify signature
    const calleeKeys = await this.getUserKeys(from);
    if (!calleeKeys) {
      return {
        success: false,
        error: { code: 'NOT_FOUND', message: 'Callee not found' },
      };
    }

    const sigValid = verifySignature(calleeKeys.sign_public_key, sig, {
      messageType: 'call_answer',
      messageId: callId,
      from,
      toOrGroupId: to,
      timestamp,
      nonce,
      ciphertext,
    });

    if (!sigValid) {
      logger.warn({ callId, from, to }, 'Call answer signature verification failed');
      return {
        success: false,
        error: { code: 'AUTH_FAILED', message: 'Signature verification failed' },
      };
    }

    // 5. Get and validate call state
    const callData = await this.getCall(callId);
    if (!callData) {
      return {
        success: false,
        error: { code: 'NOT_FOUND', message: 'Call not found' },
      };
    }

    // 6. Verify parties
    if (callData.callee !== from || callData.caller !== to) {
      return {
        success: false,
        error: { code: 'FORBIDDEN', message: 'Not authorized for this call' },
      };
    }

    // 7. State must be initiating or ringing
    if (callData.state !== 'initiating' && callData.state !== 'ringing') {
      return {
        success: false,
        error: { code: 'FORBIDDEN', message: `Cannot answer call in state: ${callData.state}` },
      };
    }

    // 8. Update call state to connected
    callData.state = 'connected';
    callData.lastUpdate = Date.now();
    await this.updateCall(callData);

    // 9. Forward answer to caller
    connectionManager.sendToUser(to, {
      type: MessageTypes.CALL_ANSWER,
      payload: { callId, from, timestamp, nonce, ciphertext, sig },
    });

    logger.info({ callId, from, to }, 'Call answered');

    return { success: true };
  }

  /**
   * Handle ICE candidate (either direction).
   */
  async handleIceCandidate(
    payload: CallIceCandidatePayload,
    senderWhisperId: string
  ): Promise<ServiceResult> {
    const { callId, from, to, timestamp, nonce, ciphertext, sig } = payload;

    // 1. Validate from === session
    if (from !== senderWhisperId) {
      return {
        success: false,
        error: { code: 'FORBIDDEN', message: 'Sender does not match authenticated identity' },
      };
    }

    // 2. Validate timestamp
    if (!isTimestampValid(timestamp)) {
      return {
        success: false,
        error: { code: 'INVALID_TIMESTAMP', message: 'Timestamp outside allowed window' },
      };
    }

    // 3. Validate nonce
    if (!isValidNonce(nonce)) {
      return {
        success: false,
        error: { code: 'INVALID_PAYLOAD', message: 'Invalid nonce format' },
      };
    }

    // 4. Get sender's sign key and verify signature
    const senderKeys = await this.getUserKeys(from);
    if (!senderKeys) {
      return {
        success: false,
        error: { code: 'NOT_FOUND', message: 'Sender not found' },
      };
    }

    const sigValid = verifySignature(senderKeys.sign_public_key, sig, {
      messageType: 'call_ice_candidate',
      messageId: callId,
      from,
      toOrGroupId: to,
      timestamp,
      nonce,
      ciphertext,
    });

    if (!sigValid) {
      logger.warn({ callId, from, to }, 'ICE candidate signature verification failed');
      return {
        success: false,
        error: { code: 'AUTH_FAILED', message: 'Signature verification failed' },
      };
    }

    // 5. Get and validate call state
    const callData = await this.getCall(callId);
    if (!callData) {
      return {
        success: false,
        error: { code: 'NOT_FOUND', message: 'Call not found' },
      };
    }

    // 6. Verify sender is a party to this call
    if (callData.caller !== from && callData.callee !== from) {
      return {
        success: false,
        error: { code: 'FORBIDDEN', message: 'Not authorized for this call' },
      };
    }

    // 7. Verify recipient is the other party
    const expectedRecipient = callData.caller === from ? callData.callee : callData.caller;
    if (to !== expectedRecipient) {
      return {
        success: false,
        error: { code: 'FORBIDDEN', message: 'Invalid recipient' },
      };
    }

    // 8. Renew TTL
    callData.lastUpdate = Date.now();
    await this.updateCall(callData);

    // 9. Forward to other party
    connectionManager.sendToUser(to, {
      type: MessageTypes.CALL_ICE_CANDIDATE,
      payload: { callId, from, timestamp, nonce, ciphertext, sig },
    });

    logger.debug({ callId, from, to }, 'ICE candidate forwarded');

    return { success: true };
  }

  /**
   * Handle call end (either direction).
   */
  async handleEnd(
    payload: CallEndPayload,
    senderWhisperId: string
  ): Promise<ServiceResult> {
    const { callId, from, to, timestamp, nonce, ciphertext, sig, reason } = payload;

    // 1. Validate from === session
    if (from !== senderWhisperId) {
      return {
        success: false,
        error: { code: 'FORBIDDEN', message: 'Sender does not match authenticated identity' },
      };
    }

    // 2. Validate timestamp
    if (!isTimestampValid(timestamp)) {
      return {
        success: false,
        error: { code: 'INVALID_TIMESTAMP', message: 'Timestamp outside allowed window' },
      };
    }

    // 3. Get and validate call state
    const callData = await this.getCall(callId);
    if (!callData) {
      // Call may have already been cleaned up - still try to forward to recipient
      logger.info({ callId, from, to, reason }, 'Call end for unknown call (may be already ended)');
      // Forward to the intended recipient anyway (best effort)
      connectionManager.sendToUser(to, {
        type: MessageTypes.CALL_END,
        payload: { callId, from, reason },
      });
      return { success: true };
    }

    // 4. Verify sender is a party to this call
    if (callData.caller !== from && callData.callee !== from) {
      return {
        success: false,
        error: { code: 'FORBIDDEN', message: 'Not authorized for this call' },
      };
    }

    // 5. Forward to other party
    const otherParty = callData.caller === from ? callData.callee : callData.caller;
    connectionManager.sendToUser(otherParty, {
      type: MessageTypes.CALL_END,
      payload: { callId, from, reason },
    });

    // 6. Delete call state
    await this.deleteCall(callId);

    // 7. Clear any pending call for callee
    const pendingKey = RedisKeys.pendingCall(callData.callee);
    await redis.del(pendingKey);

    logger.info({ callId, from, to, reason }, 'Call ended');

    return { success: true };
  }

  /**
   * End call due to timeout (called by cleanup job).
   */
  async endCallTimeout(callId: string): Promise<void> {
    const callData = await this.getCall(callId);
    if (!callData) {
      return;
    }

    // Notify both parties
    const timeoutPayload = {
      callId,
      from: 'system',
      reason: 'timeout',
    };

    connectionManager.sendToUser(callData.caller, {
      type: MessageTypes.CALL_END,
      payload: timeoutPayload,
    });

    connectionManager.sendToUser(callData.callee, {
      type: MessageTypes.CALL_END,
      payload: timeoutPayload,
    });

    // Delete call state
    await this.deleteCall(callId);

    logger.info({ callId, caller: callData.caller, callee: callData.callee }, 'Call ended due to timeout');
  }

  /**
   * Get pending call for a user (when reconnecting).
   */
  async getPendingCall(whisperId: string): Promise<CallIncomingPayload | null> {
    const pendingKey = RedisKeys.pendingCall(whisperId);
    const data = await redis.get(pendingKey);
    if (!data) {
      return null;
    }

    try {
      return JSON.parse(data);
    } catch {
      return null;
    }
  }

  /**
   * Clear pending call (after delivery or rejection).
   */
  async clearPendingCall(whisperId: string): Promise<void> {
    const pendingKey = RedisKeys.pendingCall(whisperId);
    await redis.del(pendingKey);
  }

  // ===========================================================================
  // PRIVATE HELPERS
  // ===========================================================================

  private async userExists(whisperId: string): Promise<boolean> {
    const result = await query(
      `SELECT 1 FROM users WHERE whisper_id = $1 AND status = 'active'`,
      [whisperId]
    );
    return (result.rowCount ?? 0) > 0;
  }

  private async getUserKeys(
    whisperId: string
  ): Promise<{ enc_public_key: string; sign_public_key: string } | null> {
    const result = await query<{
      enc_public_key: string;
      sign_public_key: string;
    }>(
      `SELECT enc_public_key, sign_public_key FROM users WHERE whisper_id = $1 AND status = 'active'`,
      [whisperId]
    );
    return result.rows[0] || null;
  }

  private async getCall(callId: string): Promise<CallData | null> {
    const callKey = RedisKeys.call(callId);
    const data = await redis.get(callKey);
    if (!data) {
      return null;
    }

    try {
      return JSON.parse(data);
    } catch {
      return null;
    }
  }

  private async updateCall(callData: CallData): Promise<void> {
    const callKey = RedisKeys.call(callData.callId);
    await redis.setWithTTL(callKey, JSON.stringify(callData), TTL.CALL);
  }

  private async deleteCall(callId: string): Promise<void> {
    const callKey = RedisKeys.call(callId);
    await redis.del(callKey);
  }
}

export const callService = new CallService();
