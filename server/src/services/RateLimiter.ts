/**
 * Whisper2 Rate Limiter
 * Following WHISPER-REBUILD.md Section 13
 *
 * Rate limiting per IP + per whisperId:
 * - register, send_message, fetch_pending
 * - update_tokens, group ops, call ops
 */

import { RedisKeys, TTL } from '../db/redis-keys';
import * as redis from '../db/redis';
import { logger } from '../utils/logger';

// =============================================================================
// RATE LIMIT CONFIGURATION
// =============================================================================

export interface RateLimitConfig {
  maxRequests: number;
  windowSeconds: number;
}

/**
 * Rate limits per action type.
 * These are tuned for legitimate use while preventing abuse.
 */
export const RATE_LIMITS: Record<string, RateLimitConfig> = {
  // Auth - more strict (unauthenticated)
  register_begin: { maxRequests: 5, windowSeconds: 60 },
  register_proof: { maxRequests: 5, windowSeconds: 60 },

  // Messaging
  send_message: { maxRequests: 60, windowSeconds: 60 }, // 1 msg/sec avg
  fetch_pending: { maxRequests: 30, windowSeconds: 60 },
  delivery_receipt: { maxRequests: 120, windowSeconds: 60 },

  // Groups
  group_create: { maxRequests: 10, windowSeconds: 60 },
  group_update: { maxRequests: 20, windowSeconds: 60 },
  group_send_message: { maxRequests: 60, windowSeconds: 60 },

  // Calls
  get_turn_credentials: { maxRequests: 10, windowSeconds: 60 },
  call_initiate: { maxRequests: 10, windowSeconds: 60 },
  call_ringing: { maxRequests: 10, windowSeconds: 60 },
  call_answer: { maxRequests: 10, windowSeconds: 60 },
  call_ice_candidate: { maxRequests: 100, windowSeconds: 60 }, // ICE can be chatty
  call_end: { maxRequests: 10, windowSeconds: 60 },

  // Tokens
  update_tokens: { maxRequests: 10, windowSeconds: 60 },

  // Presence/typing
  typing: { maxRequests: 30, windowSeconds: 60 }, // max 1 per 2 sec as per spec
  presence: { maxRequests: 10, windowSeconds: 60 },

  // Session
  session_refresh: { maxRequests: 10, windowSeconds: 60 },
  logout: { maxRequests: 10, windowSeconds: 60 },

  // Key lookup (Step 3)
  keys_lookup: { maxRequests: 30, windowSeconds: 60 }, // ~1 per 2 sec

  // Contacts backup (Step 3)
  contacts_backup: { maxRequests: 10, windowSeconds: 60 },

  // Attachments (Step 4)
  attachment_presign_upload: { maxRequests: 20, windowSeconds: 60 },
  attachment_presign_download: { maxRequests: 60, windowSeconds: 60 },
  attachment_gc: { maxRequests: 1, windowSeconds: 60 }, // Admin only, very rare

  // Default (for unknown actions)
  default: { maxRequests: 30, windowSeconds: 60 },
};

// Stricter limits for IP-based (unauthenticated)
export const IP_RATE_LIMITS: Record<string, RateLimitConfig> = {
  register_begin: { maxRequests: 30, windowSeconds: 60 },
  register_proof: { maxRequests: 30, windowSeconds: 60 },
  ws_connect: { maxRequests: 60, windowSeconds: 60 },
  default: { maxRequests: 100, windowSeconds: 60 },
};

// =============================================================================
// RATE LIMITER SERVICE
// =============================================================================

export interface RateLimitResult {
  allowed: boolean;
  current: number;
  limit: number;
  remaining: number;
  resetAt: number; // timestamp ms
}

// Set to true to disable rate limiting (for testing)
const RATE_LIMITS_DISABLED = process.env.DISABLE_RATE_LIMITS === 'true';

export class RateLimiter {
  /**
   * Check rate limit for authenticated user (by whisperId).
   */
  async checkUser(
    whisperId: string,
    action: string
  ): Promise<RateLimitResult> {
    if (RATE_LIMITS_DISABLED) {
      return { allowed: true, current: 0, limit: 999999, remaining: 999999, resetAt: Date.now() + 60000 };
    }
    const config = RATE_LIMITS[action] || RATE_LIMITS.default;
    const key = RedisKeys.rateLimit(whisperId, action);

    return this.check(key, config);
  }

  /**
   * Check rate limit by IP (for unauthenticated requests).
   */
  async checkIp(ip: string, action: string): Promise<RateLimitResult> {
    if (RATE_LIMITS_DISABLED) {
      return { allowed: true, current: 0, limit: 999999, remaining: 999999, resetAt: Date.now() + 60000 };
    }
    const config = IP_RATE_LIMITS[action] || IP_RATE_LIMITS.default;
    const key = RedisKeys.rateLimitIp(ip, action);

    return this.check(key, config);
  }

  /**
   * Check rate limit for both IP and user (combined).
   * Both must pass for the request to be allowed.
   */
  async checkBoth(
    ip: string,
    whisperId: string,
    action: string
  ): Promise<RateLimitResult> {
    const [ipResult, userResult] = await Promise.all([
      this.checkIp(ip, action),
      this.checkUser(whisperId, action),
    ]);

    // Return the more restrictive result
    if (!ipResult.allowed) {
      return ipResult;
    }
    if (!userResult.allowed) {
      return userResult;
    }

    // Both allowed, return user result (more relevant)
    return userResult;
  }

  /**
   * Core rate limit check using Redis counter with TTL.
   */
  private async check(
    key: string,
    config: RateLimitConfig
  ): Promise<RateLimitResult> {
    const current = await redis.incrementWithTTL(key, config.windowSeconds);
    const allowed = current <= config.maxRequests;
    const remaining = Math.max(0, config.maxRequests - current);
    const resetAt = Date.now() + config.windowSeconds * 1000;

    if (!allowed) {
      logger.warn(
        { key, current, limit: config.maxRequests },
        'Rate limit exceeded'
      );
    }

    return {
      allowed,
      current,
      limit: config.maxRequests,
      remaining,
      resetAt,
    };
  }

  /**
   * Reset rate limit for a key (admin use).
   */
  async reset(whisperId: string, action: string): Promise<void> {
    const key = RedisKeys.rateLimit(whisperId, action);
    await redis.del(key);
  }

  /**
   * Reset IP rate limit (admin use).
   */
  async resetIp(ip: string, action: string): Promise<void> {
    const key = RedisKeys.rateLimitIp(ip, action);
    await redis.del(key);
  }
}

export const rateLimiter = new RateLimiter();
