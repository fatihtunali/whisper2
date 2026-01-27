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
import { sendFcmMessage, isFirebaseReady } from './firebase';
import * as apn from 'apn';

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
// APNS CONFIGURATION
// =============================================================================

const APNS_KEY_ID = process.env.APNS_KEY_ID || '';
const APNS_TEAM_ID = process.env.APNS_TEAM_ID || '';
const APNS_KEY_PATH = process.env.APNS_KEY_PATH || '';
const APNS_BUNDLE_ID = process.env.APNS_BUNDLE_ID || '';
const APNS_PRODUCTION = process.env.APNS_PRODUCTION === 'true';

// APNs providers (initialized lazily)
let apnsProvider: apn.Provider | null = null;
let voipProvider: apn.Provider | null = null;

function getApnsProvider(): apn.Provider | null {
  if (apnsProvider) return apnsProvider;

  if (!APNS_KEY_ID || !APNS_TEAM_ID || !APNS_KEY_PATH || !APNS_BUNDLE_ID) {
    logger.warn('APNs not configured: missing environment variables');
    return null;
  }

  try {
    apnsProvider = new apn.Provider({
      token: {
        key: APNS_KEY_PATH,
        keyId: APNS_KEY_ID,
        teamId: APNS_TEAM_ID,
      },
      production: APNS_PRODUCTION,
    });
    logger.info('APNs provider initialized');
    return apnsProvider;
  } catch (error) {
    logger.error({ error }, 'Failed to initialize APNs provider');
    return null;
  }
}

function getVoipProvider(): apn.Provider | null {
  if (voipProvider) return voipProvider;

  if (!APNS_KEY_ID || !APNS_TEAM_ID || !APNS_KEY_PATH || !APNS_BUNDLE_ID) {
    logger.warn('VoIP push not configured: missing environment variables');
    return null;
  }

  try {
    voipProvider = new apn.Provider({
      token: {
        key: APNS_KEY_PATH,
        keyId: APNS_KEY_ID,
        teamId: APNS_TEAM_ID,
      },
      production: APNS_PRODUCTION,
    });
    logger.info('VoIP provider initialized');
    return voipProvider;
  } catch (error) {
    logger.error({ error }, 'Failed to initialize VoIP provider');
    return null;
  }
}

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
   */
  private async sendApns(
    device: DeviceInfo,
    payload: PushPayload,
    reason: PushReason
  ): Promise<PushResult> {
    if (!device.pushToken) {
      return { sent: false, skipped: true, reason: 'no_token' };
    }

    const provider = getApnsProvider();
    if (!provider) {
      logger.warn({ deviceId: device.deviceId }, 'APNs provider not available');
      return { sent: false, skipped: true, reason: 'apns_not_configured' };
    }

    try {
      const notification = new apn.Notification();
      notification.topic = APNS_BUNDLE_ID;
      notification.expiry = Math.floor(Date.now() / 1000) + 60; // 1 minute expiry

      if (reason === 'call') {
        // High priority for calls
        notification.priority = 10;
        notification.pushType = 'alert';
        notification.alert = {
          title: 'Incoming Call',
          body: 'You have an incoming call',
        };
        notification.sound = 'default';
      } else {
        // Background push for messages
        notification.priority = 5;
        notification.pushType = 'background';
        notification.contentAvailable = true;
      }

      notification.payload = {
        type: payload.type,
        reason: payload.reason,
        whisperId: payload.whisperId,
      };

      const result = await provider.send(notification, device.pushToken);

      if (result.failed.length > 0) {
        const failure = result.failed[0];
        logger.warn(
          { deviceId: device.deviceId, error: failure.response },
          'APNs push failed'
        );

        // Handle invalid token
        if (failure.response?.reason === 'BadDeviceToken' ||
            failure.response?.reason === 'Unregistered') {
          await this.handleInvalidToken(payload.whisperId, device.deviceId, 'push');
        }

        return { sent: false, skipped: false, reason: failure.response?.reason || 'apns_error' };
      }

      logger.info(
        { deviceId: device.deviceId, reason },
        'APNs push sent successfully'
      );

      return {
        sent: true,
        skipped: false,
        provider: 'apns',
        ticketId: `apns_${Date.now()}`,
      };
    } catch (error) {
      logger.error({ error, deviceId: device.deviceId }, 'APNs push error');
      return { sent: false, skipped: false, reason: 'apns_exception' };
    }
  }

  /**
   * Send VoIP push (iOS CallKit).
   */
  private async sendVoip(
    device: DeviceInfo,
    payload: PushPayload
  ): Promise<PushResult> {
    if (!device.voipToken) {
      return { sent: false, skipped: true, reason: 'no_voip_token' };
    }

    const provider = getVoipProvider();
    if (!provider) {
      logger.warn({ deviceId: device.deviceId }, 'VoIP provider not available');
      return { sent: false, skipped: true, reason: 'voip_not_configured' };
    }

    try {
      const notification = new apn.Notification();
      // VoIP push uses .voip suffix on bundle ID
      notification.topic = `${APNS_BUNDLE_ID}.voip`;
      notification.pushType = 'voip';
      notification.priority = 10; // VoIP must be high priority
      notification.expiry = Math.floor(Date.now() / 1000) + 30; // 30 second expiry for calls

      notification.payload = {
        type: payload.type,
        reason: payload.reason,
        whisperId: payload.whisperId,
        aps: {
          'content-available': 1,
        },
      };

      const result = await provider.send(notification, device.voipToken);

      if (result.failed.length > 0) {
        const failure = result.failed[0];
        logger.warn(
          { deviceId: device.deviceId, error: failure.response },
          'VoIP push failed'
        );

        // Handle invalid token
        if (failure.response?.reason === 'BadDeviceToken' ||
            failure.response?.reason === 'Unregistered') {
          await this.handleInvalidToken(payload.whisperId, device.deviceId, 'voip');
        }

        return { sent: false, skipped: false, reason: failure.response?.reason || 'voip_error' };
      }

      logger.info(
        { deviceId: device.deviceId },
        'VoIP push sent successfully'
      );

      return {
        sent: true,
        skipped: false,
        provider: 'voip',
        ticketId: `voip_${Date.now()}`,
      };
    } catch (error) {
      logger.error({ error, deviceId: device.deviceId }, 'VoIP push error');
      return { sent: false, skipped: false, reason: 'voip_exception' };
    }
  }

  /**
   * Send FCM push (Android).
   * Uses firebase-admin SDK for real push delivery.
   */
  private async sendFcm(
    device: DeviceInfo,
    payload: PushPayload,
    reason: PushReason
  ): Promise<PushResult> {
    if (!device.pushToken) {
      return { sent: false, skipped: true, reason: 'no_token' };
    }

    // Check if Firebase is configured
    if (!isFirebaseReady()) {
      logger.debug(
        { deviceId: device.deviceId, platform: 'android', reason },
        'FCM push skipped: Firebase not configured'
      );
      return { sent: false, skipped: true, reason: 'firebase_not_configured' };
    }

    // Determine channel and priority based on reason
    const channelId = reason === 'call' ? 'whisper2_calls' : 'whisper2_messages';
    const priority = reason === 'call' ? 'high' : 'normal';

    // Build data payload (wake-only, no content)
    const data: Record<string, string> = {
      type: payload.type,
      reason: payload.reason,
      whisperId: payload.whisperId,
    };
    if (payload.hint) {
      data.hint = payload.hint;
    }

    // Send via Firebase
    const result = await sendFcmMessage(device.pushToken, data, {
      priority,
      channelId,
      ttl: reason === 'call' ? 30 : 60, // Calls need faster delivery
    });

    if (result.success) {
      logger.debug(
        { deviceId: device.deviceId, platform: 'android', reason, messageId: result.messageId },
        'FCM push sent'
      );
      return {
        sent: true,
        skipped: false,
        provider: 'fcm',
        ticketId: result.messageId,
      };
    }

    // Handle invalid token
    if (result.shouldInvalidateToken) {
      logger.warn(
        { deviceId: device.deviceId, whisperId: payload.whisperId },
        'FCM token invalid, clearing from database'
      );
      await this.handleInvalidToken(payload.whisperId, device.deviceId, 'push');
    }

    return {
      sent: false,
      skipped: false,
      reason: result.error || 'fcm_error',
      provider: 'fcm',
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
