/**
 * Whisper2 Push Service
 * Following WHISPER-REBUILD.md Step 6
 *
 * Push Discipline (Wake-up Only, No Leaks):
 * - Push is wake-up only (no content, no metadata)
 * - Push only when pending transitions 0→1
 * - Coalescing prevents notification spam
 * - Invalid tokens get cleaned up automatically
 */

import { query } from '../db/postgres';
import * as redis from '../db/redis';
import { RedisKeys, TTL } from '../db/redis-keys';
import { connectionManager } from './ConnectionManager';
import { logger } from '../utils/logger';

// =============================================================================
// TYPES
// =============================================================================

export type PushReason = 'message' | 'call' | 'system';

/**
 * Minimal push payload (frozen per spec).
 * Contains ONLY these fields - never any content or metadata.
 */
export interface PushPayload {
  type: 'wake';
  reason: PushReason;
  whisperId: string;
  hint?: string; // optional small string
}

/**
 * Device info for push sending.
 */
export interface DeviceInfo {
  deviceId: string;
  platform: 'ios' | 'android';
  pushToken: string | null;
  voipToken: string | null;
}

/**
 * Push send result.
 */
export interface PushResult {
  sent: boolean;
  skipped: boolean;
  reason?: string;
  provider?: 'apns' | 'fcm' | 'voip';
  ticketId?: string;
}

// =============================================================================
// FORBIDDEN FIELDS (NEVER include in push)
// =============================================================================

const FORBIDDEN_FIELDS = [
  'ciphertext',
  'nonce',
  'messageId',
  'senderId',
  'groupId',
  'objectKey',
  'attachment',
  'content',
  'plaintext',
] as const;

// =============================================================================
// PUSH SERVICE
// =============================================================================

export class PushService {
  /**
   * Send a wake-up push notification.
   *
   * Rules:
   * 1. Never send if recipient is online (WS connected)
   * 2. Never send if suppress key exists (coalescing)
   * 3. Never include forbidden fields
   * 4. Log only allowed fields
   */
  async sendWake(
    whisperId: string,
    reason: PushReason,
    hint?: string
  ): Promise<PushResult> {
    // 1. Check if user is online - skip push if connected via WS
    if (connectionManager.isUserConnected(whisperId)) {
      logger.debug({ whisperId, reason }, 'Push skipped: user is online');
      return { sent: false, skipped: true, reason: 'user_online' };
    }

    // 2. Check suppress key (coalescing)
    const suppressKey = RedisKeys.pushSuppress(whisperId, reason);
    const isSuppressed = await redis.exists(suppressKey);
    if (isSuppressed) {
      logger.debug({ whisperId, reason }, 'Push skipped: suppressed (coalescing)');
      return { sent: false, skipped: true, reason: 'suppressed' };
    }

    // 3. Get device tokens
    const device = await this.getActiveDevice(whisperId);
    if (!device || !device.pushToken) {
      logger.debug({ whisperId, reason }, 'Push skipped: no push token');
      return { sent: false, skipped: true, reason: 'no_token' };
    }

    // 4. Build minimal payload (NEVER include forbidden fields)
    const payload: PushPayload = {
      type: 'wake',
      reason,
      whisperId,
    };
    if (hint) {
      payload.hint = hint;
    }

    // 5. Validate payload doesn't contain forbidden fields
    this.validatePayload(payload);

    // 6. Set suppress key BEFORE sending (prevents race conditions)
    await redis.setWithTTL(suppressKey, '1', TTL.PUSH_SUPPRESS);

    // 7. Send push based on platform and reason
    let result: PushResult;
    if (device.platform === 'ios') {
      result = await this.sendApns(device, payload, reason);
    } else {
      result = await this.sendFcm(device, payload, reason);
    }

    // 8. Log push (only allowed fields)
    logger.info(
      {
        whisperId,
        reason,
        provider: result.provider,
        sent: result.sent,
        ticketId: result.ticketId,
      },
      'Push notification processed'
    );

    return result;
  }

  /**
   * Send wake-up push for a call (uses VoIP push on iOS if available).
   */
  async sendCallWake(whisperId: string): Promise<PushResult> {
    // Check if user is online
    if (connectionManager.isUserConnected(whisperId)) {
      logger.debug({ whisperId }, 'Call push skipped: user is online');
      return { sent: false, skipped: true, reason: 'user_online' };
    }

    // Check suppress key
    const suppressKey = RedisKeys.pushSuppress(whisperId, 'call');
    const isSuppressed = await redis.exists(suppressKey);
    if (isSuppressed) {
      logger.debug({ whisperId }, 'Call push skipped: suppressed');
      return { sent: false, skipped: true, reason: 'suppressed' };
    }

    const device = await this.getActiveDevice(whisperId);
    if (!device) {
      return { sent: false, skipped: true, reason: 'no_device' };
    }

    // Build payload
    const payload: PushPayload = {
      type: 'wake',
      reason: 'call',
      whisperId,
    };

    // Set suppress key
    await redis.setWithTTL(suppressKey, '1', TTL.PUSH_SUPPRESS);

    // iOS: prefer VoIP push for calls
    if (device.platform === 'ios' && device.voipToken) {
      return this.sendVoip(device, payload);
    }

    // Fallback to regular push
    if (device.platform === 'ios') {
      return this.sendApns(device, payload, 'call');
    } else {
      return this.sendFcm(device, payload, 'call');
    }
  }

  /**
   * Check if push should be sent (pending queue transition 0→1).
   *
   * Returns true if pending count was 0 before this message.
   */
  async shouldSendPush(whisperId: string): Promise<boolean> {
    // Get current pending count
    const pendingKey = RedisKeys.pending(whisperId);
    const count = await redis.listLength(pendingKey);

    // If count is 1, this message just transitioned 0→1
    // (message was already added before this check)
    return count === 1;
  }

  /**
   * Check pending count before storing (call this BEFORE storing).
   */
  async getPendingCountBefore(whisperId: string): Promise<number> {
    const pendingKey = RedisKeys.pending(whisperId);
    return await redis.listLength(pendingKey);
  }

  /**
   * Handle invalid token response from provider.
   * Clears the token in the database.
   */
  async handleInvalidToken(
    whisperId: string,
    deviceId: string,
    tokenType: 'push' | 'voip'
  ): Promise<void> {
    const column = tokenType === 'voip' ? 'voip_token' : 'push_token';

    await query(
      `UPDATE devices SET ${column} = NULL, updated_at = NOW()
       WHERE whisper_id = $1 AND device_id = $2`,
      [whisperId, deviceId]
    );

    logger.warn(
      { whisperId, deviceId, tokenType },
      'Push token invalidated and cleared'
    );
  }

  /**
   * Get the active device for a user (single-active-device policy).
   */
  private async getActiveDevice(whisperId: string): Promise<DeviceInfo | null> {
    const result = await query<{
      device_id: string;
      platform: string;
      push_token: string | null;
      voip_token: string | null;
    }>(
      `SELECT device_id, platform, push_token, voip_token
       FROM devices
       WHERE whisper_id = $1
       ORDER BY updated_at DESC
       LIMIT 1`,
      [whisperId]
    );

    if (result.rows.length === 0) {
      return null;
    }

    const row = result.rows[0];
    return {
      deviceId: row.device_id,
      platform: row.platform as 'ios' | 'android',
      pushToken: row.push_token,
      voipToken: row.voip_token,
    };
  }

  /**
   * Validate payload contains no forbidden fields.
   */
  private validatePayload(payload: PushPayload): void {
    const payloadStr = JSON.stringify(payload);
    for (const field of FORBIDDEN_FIELDS) {
      if (payloadStr.includes(`"${field}"`)) {
        throw new Error(`SECURITY: Push payload contains forbidden field: ${field}`);
      }
    }
  }

  /**
   * Send APNs push (iOS).
   * NOTE: This is a stub - in production, integrate with Apple Push Notification Service.
   */
  private async sendApns(
    device: DeviceInfo,
    payload: PushPayload,
    reason: PushReason
  ): Promise<PushResult> {
    if (!device.pushToken) {
      return { sent: false, skipped: true, reason: 'no_token' };
    }

    // TODO: Integrate with actual APNs provider (e.g., node-apn, @parse/node-apn)
    // For now, this is a stub that simulates success

    // In production:
    // - Use APNs HTTP/2 provider
    // - Send to device.pushToken
    // - Handle "BadDeviceToken" response → call handleInvalidToken()
    // - Use "alert" or "background" based on reason

    logger.debug(
      { deviceId: device.deviceId, platform: 'ios', reason },
      'APNs push simulated (stub)'
    );

    // Simulate success
    return {
      sent: true,
      skipped: false,
      provider: 'apns',
      ticketId: `apns_${Date.now()}`,
    };
  }

  /**
   * Send VoIP push (iOS CallKit).
   * NOTE: This is a stub - in production, integrate with Apple VoIP Push.
   */
  private async sendVoip(
    device: DeviceInfo,
    payload: PushPayload
  ): Promise<PushResult> {
    if (!device.voipToken) {
      return { sent: false, skipped: true, reason: 'no_voip_token' };
    }

    // TODO: Integrate with APNs VoIP push
    // - Use PushKit topic
    // - Handle "BadDeviceToken" response

    logger.debug(
      { deviceId: device.deviceId, platform: 'ios' },
      'VoIP push simulated (stub)'
    );

    return {
      sent: true,
      skipped: false,
      provider: 'voip',
      ticketId: `voip_${Date.now()}`,
    };
  }

  /**
   * Send FCM push (Android).
   * NOTE: This is a stub - in production, integrate with Firebase Cloud Messaging.
   */
  private async sendFcm(
    device: DeviceInfo,
    payload: PushPayload,
    reason: PushReason
  ): Promise<PushResult> {
    if (!device.pushToken) {
      return { sent: false, skipped: true, reason: 'no_token' };
    }

    // TODO: Integrate with actual FCM (firebase-admin SDK)
    // For now, this is a stub that simulates success

    // In production:
    // - Use firebase-admin
    // - Send to device.pushToken
    // - Handle "NOT_REGISTERED" / "INVALID_ARGUMENT" → call handleInvalidToken()
    // - Use "calls" channel for calls (high priority)
    // - Use "messages" channel for messages (default priority)

    const channel = reason === 'call' ? 'calls' : 'messages';

    logger.debug(
      { deviceId: device.deviceId, platform: 'android', reason, channel },
      'FCM push simulated (stub)'
    );

    return {
      sent: true,
      skipped: false,
      provider: 'fcm',
      ticketId: `fcm_${Date.now()}`,
    };
  }

  /**
   * Clear suppress key (for testing).
   */
  async clearSuppress(whisperId: string, reason: PushReason): Promise<void> {
    const suppressKey = RedisKeys.pushSuppress(whisperId, reason);
    await redis.del(suppressKey);
  }

  /**
   * Check if suppress key exists (for testing).
   */
  async isSuppressed(whisperId: string, reason: PushReason): Promise<boolean> {
    const suppressKey = RedisKeys.pushSuppress(whisperId, reason);
    return await redis.exists(suppressKey);
  }
}

export const pushService = new PushService();
