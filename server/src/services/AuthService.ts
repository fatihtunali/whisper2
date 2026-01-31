/**
 * Whisper2 AuthService
 * Following WHISPER-REBUILD.md Section 3.3
 *
 * WebSocket auth only:
 * register_begin → register_challenge → register_proof → register_ack
 *
 * Identity creation:
 * - Server assigns Whisper ID on first register (client sends no whisperId)
 * - Recovery: client provides whisperId + keys must match, else reject
 *
 * Single-active-device:
 * - On successful login, invalidate old sessions
 */

import { v4 as uuidv4 } from 'uuid';
import { query, withTransaction } from '../db/postgres';
import * as redis from '../db/redis';
import { RedisKeys, TTL, SessionData } from '../db/redis-keys';
import {
  generateChallenge,
  generateSessionToken,
  generateWhisperId,
  verifyChallengeSignature,
  isValidPublicKey,
} from '../utils/crypto';
import { logger } from '../utils/logger';
import { userExistsAnyStatus } from '../db/UserRepository';
import {
  RegisterBeginPayload,
  RegisterChallengePayload,
  RegisterProofPayload,
  RegisterAckPayload,
  ErrorCode,
} from '../types/protocol';

// =============================================================================
// TYPES
// =============================================================================

export interface AuthResult {
  success: boolean;
  error?: {
    code: ErrorCode;
    message: string;
  };
  data?: RegisterAckPayload;
}

interface ChallengeData {
  challengeBytes: string; // base64
  deviceId: string;
  platform: 'ios' | 'android';
  whisperId?: string; // present if recovery attempt
  createdAt: number;
}

// =============================================================================
// AUTH SERVICE
// =============================================================================

export class AuthService {
  /**
   * Step 1: Handle register_begin
   * Client initiates registration/recovery.
   * Server generates challenge.
   */
  async handleRegisterBegin(
    payload: RegisterBeginPayload,
    clientIp: string
  ): Promise<{ challengePayload?: RegisterChallengePayload; error?: { code: ErrorCode; message: string } }> {
    const { deviceId, platform, whisperId } = payload;

    // If recovery attempt, verify user exists
    if (whisperId) {
      const userExists = await userExistsAnyStatus(whisperId);
      if (!userExists) {
        logger.warn({ whisperId }, 'Recovery attempted for non-existent user');
        return {
          error: {
            code: 'NOT_FOUND',
            message: 'Whisper ID not found',
          },
        };
      }

      // Check if user is banned
      const isBanned = await this.isUserBanned(whisperId);
      if (isBanned) {
        logger.warn({ whisperId }, 'Banned user attempted registration');
        return {
          error: {
            code: 'USER_BANNED',
            message: 'Account is banned',
          },
        };
      }
    }

    // Generate challenge
    const challengeId = uuidv4();
    const challengeBytes = generateChallenge();
    const expiresAt = Date.now() + TTL.CHALLENGE * 1000;

    // Store challenge data in Redis (single-use, 60s TTL)
    const challengeData: ChallengeData = {
      challengeBytes: challengeBytes.toString('base64'),
      deviceId,
      platform,
      whisperId,
      createdAt: Date.now(),
    };

    await redis.setWithTTL(
      RedisKeys.challenge(challengeId),
      JSON.stringify(challengeData),
      TTL.CHALLENGE
    );

    logger.info(
      { challengeId, deviceId, platform, isRecovery: !!whisperId },
      'Challenge issued'
    );

    // Audit log
    await this.auditLog('challenge_issued', whisperId, deviceId, clientIp, {
      challengeId,
      isRecovery: !!whisperId,
    });

    return {
      challengePayload: {
        challengeId,
        challenge: challengeBytes.toString('base64'),
        expiresAt,
      },
    };
  }

  /**
   * Step 2: Handle register_proof
   * Client proves key ownership with signature.
   * Server verifies and creates/recovers identity.
   */
  async handleRegisterProof(
    payload: RegisterProofPayload,
    clientIp: string
  ): Promise<AuthResult> {
    const {
      challengeId,
      deviceId,
      platform,
      whisperId: claimedWhisperId,
      encPublicKey,
      signPublicKey,
      signature,
      pushToken,
      voipToken,
    } = payload;

    // 1. Retrieve and consume challenge (single-use)
    const challengeJson = await redis.getAndDelete(RedisKeys.challenge(challengeId));
    if (!challengeJson) {
      logger.warn({ challengeId }, 'Challenge not found or expired');
      return {
        success: false,
        error: {
          code: 'AUTH_FAILED',
          message: 'Challenge expired or not found',
        },
      };
    }

    const challengeData: ChallengeData = JSON.parse(challengeJson);

    // 2. Verify challenge data matches
    if (challengeData.deviceId !== deviceId) {
      logger.warn({ challengeId, deviceId }, 'Device ID mismatch');
      return {
        success: false,
        error: {
          code: 'AUTH_FAILED',
          message: 'Device ID mismatch',
        },
      };
    }

    if (challengeData.platform !== platform) {
      logger.warn({ challengeId, platform }, 'Platform mismatch');
      return {
        success: false,
        error: {
          code: 'AUTH_FAILED',
          message: 'Platform mismatch',
        },
      };
    }

    // 3. Validate public keys format
    if (!isValidPublicKey(encPublicKey) || !isValidPublicKey(signPublicKey)) {
      logger.warn({ challengeId }, 'Invalid public key format');
      return {
        success: false,
        error: {
          code: 'INVALID_PAYLOAD',
          message: 'Invalid public key format',
        },
      };
    }

    // 4. Verify signature
    const challengeBytes = Buffer.from(challengeData.challengeBytes, 'base64');
    const sigValid = verifyChallengeSignature(signPublicKey, signature, challengeBytes);
    if (!sigValid) {
      logger.warn({ challengeId }, 'Signature verification failed');
      await this.auditLog('auth_failed', claimedWhisperId, deviceId, clientIp, {
        reason: 'bad_signature',
        challengeId,
      });
      return {
        success: false,
        error: {
          code: 'AUTH_FAILED',
          message: 'Signature verification failed',
        },
      };
    }

    // 5. Handle recovery vs new registration
    let whisperId: string;

    if (claimedWhisperId) {
      // RECOVERY: verify keys match
      const existingUser = await this.getUser(claimedWhisperId);
      if (!existingUser) {
        return {
          success: false,
          error: {
            code: 'NOT_FOUND',
            message: 'Whisper ID not found',
          },
        };
      }

      if (
        existingUser.enc_public_key !== encPublicKey ||
        existingUser.sign_public_key !== signPublicKey
      ) {
        logger.warn({ whisperId: claimedWhisperId }, 'Key mismatch on recovery');
        await this.auditLog('auth_failed', claimedWhisperId, deviceId, clientIp, {
          reason: 'key_mismatch',
          challengeId,
        });
        return {
          success: false,
          error: {
            code: 'AUTH_FAILED',
            message: 'Public keys do not match identity',
          },
        };
      }

      if (existingUser.status === 'banned') {
        return {
          success: false,
          error: {
            code: 'USER_BANNED',
            message: 'Account is banned',
          },
        };
      }

      whisperId = claimedWhisperId;
      logger.info({ whisperId, deviceId }, 'Identity recovered');
    } else {
      // Check if user already exists with these public keys (mnemonic-based recovery)
      const existingUserByKeys = await this.findUserByPublicKeys(encPublicKey, signPublicKey);

      if (existingUserByKeys) {
        // User exists with these keys - this is a mnemonic-based recovery
        if (existingUserByKeys.status === 'banned') {
          return {
            success: false,
            error: {
              code: 'USER_BANNED',
              message: 'Account is banned',
            },
          };
        }
        whisperId = existingUserByKeys.whisper_id;
        logger.info({ whisperId, deviceId }, 'Identity recovered by public keys');
      } else {
        // NEW REGISTRATION: server assigns Whisper ID
        whisperId = await this.createUser(encPublicKey, signPublicKey);
        logger.info({ whisperId, deviceId }, 'New identity created');
      }
    }

    // 6. Single-active-device: invalidate old sessions
    await this.invalidateOldSessions(whisperId);

    // 7. Create new session
    const sessionToken = generateSessionToken();
    const sessionExpiresAt = Date.now() + TTL.SESSION * 1000;
    const serverTime = Date.now();

    const sessionData: SessionData = {
      whisperId,
      deviceId,
      platform,
      createdAt: serverTime,
      expiresAt: sessionExpiresAt,
    };

    // Store session in Redis
    await redis.setWithTTL(
      RedisKeys.session(sessionToken),
      JSON.stringify(sessionData),
      TTL.SESSION
    );

    // Store active session mapping (for single-device enforcement)
    await redis.setWithTTL(
      RedisKeys.activeSession(whisperId),
      sessionToken,
      TTL.SESSION
    );

    // 8. Update/create device record
    await this.upsertDevice(whisperId, deviceId, platform, pushToken, voipToken);

    // 9. Audit log
    await this.auditLog(
      claimedWhisperId ? 'recovery' : 'register',
      whisperId,
      deviceId,
      clientIp,
      { challengeId }
    );

    logger.info(
      { whisperId, deviceId, platform },
      'Registration/recovery successful'
    );

    return {
      success: true,
      data: {
        success: true,
        whisperId,
        sessionToken,
        sessionExpiresAt,
        serverTime,
      },
    };
  }

  /**
   * Validate session token and return session data.
   * Returns null if invalid/expired.
   */
  async validateSession(sessionToken: string): Promise<SessionData | null> {
    const sessionJson = await redis.get(RedisKeys.session(sessionToken));
    if (!sessionJson) {
      return null;
    }

    const session: SessionData = JSON.parse(sessionJson);

    // Check expiration
    if (session.expiresAt < Date.now()) {
      await redis.del(RedisKeys.session(sessionToken));
      return null;
    }

    return session;
  }

  /**
   * Refresh session - returns new token, invalidates old.
   */
  async refreshSession(
    oldToken: string
  ): Promise<{ sessionToken: string; sessionExpiresAt: number; serverTime: number } | null> {
    const session = await this.validateSession(oldToken);
    if (!session) {
      return null;
    }

    // Delete old session
    await redis.del(RedisKeys.session(oldToken));

    // Create new session
    const sessionToken = generateSessionToken();
    const sessionExpiresAt = Date.now() + TTL.SESSION * 1000;
    const serverTime = Date.now();

    const newSession: SessionData = {
      ...session,
      createdAt: serverTime,
      expiresAt: sessionExpiresAt,
    };

    await redis.setWithTTL(
      RedisKeys.session(sessionToken),
      JSON.stringify(newSession),
      TTL.SESSION
    );

    // Update active session mapping
    await redis.setWithTTL(
      RedisKeys.activeSession(session.whisperId),
      sessionToken,
      TTL.SESSION
    );

    return { sessionToken, sessionExpiresAt, serverTime };
  }

  /**
   * Logout - invalidate session.
   */
  async logout(sessionToken: string): Promise<boolean> {
    const session = await this.validateSession(sessionToken);
    if (!session) {
      return false;
    }

    await redis.del(RedisKeys.session(sessionToken));
    await redis.del(RedisKeys.activeSession(session.whisperId));

    await this.auditLog('logout', session.whisperId, session.deviceId, undefined, {});

    return true;
  }

  /**
   * Delete account - permanently removes all user data.
   * This is irreversible and removes:
   * - User record
   * - All devices
   * - All group memberships (and owned groups)
   * - All contact backups
   * - All attachments and access grants
   * - All pending messages
   * - All sessions
   */
  async deleteAccount(
    sessionToken: string,
    clientIp?: string
  ): Promise<{ success: boolean; error?: { code: string; message: string } }> {
    const session = await this.validateSession(sessionToken);
    if (!session) {
      return {
        success: false,
        error: { code: 'AUTH_FAILED', message: 'Invalid or expired session' },
      };
    }

    const whisperId = session.whisperId;

    try {
      // Use transaction for database operations
      await withTransaction(async (client) => {
        // 1. Delete group memberships (before groups, to handle foreign keys)
        await client.query(
          'DELETE FROM group_members WHERE whisper_id = $1',
          [whisperId]
        );

        // 2. Delete owned groups (this will cascade to their members via ON DELETE CASCADE)
        // First get all groups owned by this user
        const ownedGroups = await client.query<{ group_id: string }>(
          'SELECT group_id FROM groups WHERE owner_whisper_id = $1',
          [whisperId]
        );

        // Delete members of owned groups
        for (const row of ownedGroups.rows) {
          await client.query(
            'DELETE FROM group_members WHERE group_id = $1',
            [row.group_id]
          );
        }

        // Delete the groups themselves
        await client.query(
          'DELETE FROM groups WHERE owner_whisper_id = $1',
          [whisperId]
        );

        // 3. Delete contact backups
        await client.query(
          'DELETE FROM contact_backups WHERE whisper_id = $1',
          [whisperId]
        );

        // 4. Delete attachment access grants (both given and received)
        await client.query(
          'DELETE FROM attachment_access WHERE whisper_id = $1',
          [whisperId]
        );

        // 5. Delete owned attachments
        await client.query(
          'DELETE FROM attachments WHERE owner_whisper_id = $1',
          [whisperId]
        );

        // 6. Delete devices
        await client.query(
          'DELETE FROM devices WHERE whisper_id = $1',
          [whisperId]
        );

        // 7. Delete bans (if any)
        await client.query(
          'DELETE FROM bans WHERE whisper_id = $1',
          [whisperId]
        );

        // 8. Audit log the deletion BEFORE deleting user
        await client.query(
          `INSERT INTO audit_events (event_type, whisper_id, ip_address, details)
           VALUES ($1, $2, $3, $4)`,
          ['account_deleted', whisperId, clientIp || null, JSON.stringify({ deletedAt: Date.now() })]
        );

        // 9. Finally delete the user record
        await client.query(
          'DELETE FROM users WHERE whisper_id = $1',
          [whisperId]
        );
      });

      // Clear Redis data (outside transaction)
      await redis.del(RedisKeys.session(sessionToken));
      await redis.del(RedisKeys.activeSession(whisperId));
      await redis.del(RedisKeys.pending(whisperId));
      await redis.del(RedisKeys.presence(whisperId));

      logger.info({ whisperId }, 'Account deleted successfully');

      return { success: true };
    } catch (err) {
      logger.error({ err, whisperId }, 'Failed to delete account');
      return {
        success: false,
        error: { code: 'INTERNAL_ERROR', message: 'Failed to delete account' },
      };
    }
  }

  // ===========================================================================
  // PRIVATE HELPERS
  // ===========================================================================

  private async getUser(
    whisperId: string
  ): Promise<{ enc_public_key: string; sign_public_key: string; status: string } | null> {
    const result = await query<{
      enc_public_key: string;
      sign_public_key: string;
      status: string;
    }>(
      'SELECT enc_public_key, sign_public_key, status FROM users WHERE whisper_id = $1',
      [whisperId]
    );
    return result.rows[0] || null;
  }

  /**
   * Find user by their public keys (for mnemonic-based recovery)
   * This allows recovery when user doesn't know their WhisperID
   */
  private async findUserByPublicKeys(
    encPublicKey: string,
    signPublicKey: string
  ): Promise<{ whisper_id: string; status: string } | null> {
    const result = await query<{
      whisper_id: string;
      status: string;
    }>(
      'SELECT whisper_id, status FROM users WHERE enc_public_key = $1 AND sign_public_key = $2',
      [encPublicKey, signPublicKey]
    );
    return result.rows[0] || null;
  }

  private async isUserBanned(whisperId: string): Promise<boolean> {
    const result = await query(
      `SELECT 1 FROM bans
       WHERE whisper_id = $1
       AND unbanned_at IS NULL
       AND (expires_at IS NULL OR expires_at > NOW())`,
      [whisperId]
    );
    return (result.rowCount ?? 0) > 0;
  }

  private async createUser(encPublicKey: string, signPublicKey: string): Promise<string> {
    // Generate unique Whisper ID with retry on collision
    let whisperId: string;
    let attempts = 0;
    const maxAttempts = 10;

    while (attempts < maxAttempts) {
      whisperId = generateWhisperId();
      try {
        await query(
          `INSERT INTO users (whisper_id, enc_public_key, sign_public_key, status)
           VALUES ($1, $2, $3, 'active')`,
          [whisperId, encPublicKey, signPublicKey]
        );
        return whisperId;
      } catch (err: unknown) {
        const pgError = err as { code?: string };
        if (pgError.code === '23505') {
          // Unique violation - ID collision, retry
          attempts++;
          logger.warn({ whisperId, attempts }, 'Whisper ID collision, retrying');
          continue;
        }
        throw err;
      }
    }

    throw new Error('Failed to generate unique Whisper ID after max attempts');
  }

  private async invalidateOldSessions(whisperId: string): Promise<void> {
    // Get current active session token
    const oldToken = await redis.get(RedisKeys.activeSession(whisperId));
    if (oldToken) {
      await redis.del(RedisKeys.session(oldToken));
      logger.info({ whisperId }, 'Invalidated old session (single-active-device)');
    }
  }

  /**
   * Update push tokens for an existing device.
   * Called via WS update_tokens message.
   */
  async updateTokens(
    whisperId: string,
    deviceId: string,
    pushToken?: string,
    voipToken?: string
  ): Promise<{ success: boolean; error?: { code: string; message: string } }> {
    try {
      // Check device exists
      const result = await query(
        `SELECT device_id FROM devices WHERE whisper_id = $1 AND device_id = $2`,
        [whisperId, deviceId]
      );

      if (result.rows.length === 0) {
        return {
          success: false,
          error: { code: 'NOT_FOUND', message: 'Device not found' },
        };
      }

      // Update tokens (only update provided tokens, preserve existing if not provided)
      const updates: string[] = [];
      const values: (string | null)[] = [];
      let paramIndex = 1;

      if (pushToken !== undefined) {
        updates.push(`push_token = $${paramIndex++}`);
        values.push(pushToken || null);
      }
      if (voipToken !== undefined) {
        updates.push(`voip_token = $${paramIndex++}`);
        values.push(voipToken || null);
      }

      if (updates.length > 0) {
        updates.push(`updated_at = NOW()`);
        values.push(whisperId, deviceId);

        await query(
          `UPDATE devices SET ${updates.join(', ')}
           WHERE whisper_id = $${paramIndex++} AND device_id = $${paramIndex}`,
          values
        );

        logger.info({ whisperId, deviceId }, 'Push tokens updated');
      }

      return { success: true };
    } catch (err) {
      logger.error({ err, whisperId, deviceId }, 'Failed to update tokens');
      return {
        success: false,
        error: { code: 'INTERNAL_ERROR', message: 'Failed to update tokens' },
      };
    }
  }

  private async upsertDevice(
    whisperId: string,
    deviceId: string,
    platform: 'ios' | 'android',
    pushToken?: string,
    voipToken?: string
  ): Promise<void> {
    await query(
      `INSERT INTO devices (whisper_id, device_id, platform, push_token, voip_token, last_seen_at)
       VALUES ($1, $2, $3, $4, $5, NOW())
       ON CONFLICT (whisper_id, device_id)
       DO UPDATE SET
         platform = EXCLUDED.platform,
         push_token = EXCLUDED.push_token,
         voip_token = EXCLUDED.voip_token,
         last_seen_at = NOW(),
         updated_at = NOW()`,
      [whisperId, deviceId, platform, pushToken || null, voipToken || null]
    );
  }

  private async auditLog(
    eventType: string,
    whisperId: string | undefined,
    deviceId: string | undefined,
    ipAddress: string | undefined,
    details: Record<string, unknown>
  ): Promise<void> {
    try {
      await query(
        `INSERT INTO audit_events (event_type, whisper_id, device_id, ip_address, details)
         VALUES ($1, $2, $3, $4, $5)`,
        [eventType, whisperId || null, deviceId || null, ipAddress || null, JSON.stringify(details)]
      );
    } catch (err) {
      // Don't fail main operation if audit fails
      logger.error({ err, eventType }, 'Failed to write audit log');
    }
  }
}

export const authService = new AuthService();
