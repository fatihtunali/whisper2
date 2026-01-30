/**
 * Whisper2 Redis Connection
 * Following WHISPER-REBUILD.md Section 12.2
 *
 * Redis = volatile / TTL data:
 * - sessions, challenges
 * - presence, pending messages
 * - rate limits, call state
 *
 * Redis is non-authoritative (safe to lose)
 */

import Redis from 'ioredis';
import { logger } from '../utils/logger';

// =============================================================================
// REDIS CLIENT
// =============================================================================

let redis: Redis | null = null;

export function getRedis(): Redis {
  if (!redis) {
    redis = new Redis({
      host: process.env.REDIS_HOST || 'localhost',
      port: parseInt(process.env.REDIS_PORT || '6379', 10),
      password: process.env.REDIS_PASSWORD || undefined,
      db: parseInt(process.env.REDIS_DB || '0', 10),
      maxRetriesPerRequest: 3,
      retryStrategy: (times: number) => {
        if (times > 10) {
          logger.error('Redis connection failed after 10 retries');
          return null;
        }
        return Math.min(times * 100, 3000);
      },
      lazyConnect: true,
    });

    redis.on('error', (err) => {
      logger.error({ err }, 'Redis connection error');
    });

    redis.on('connect', () => {
      logger.info('Redis connected');
    });

    redis.on('reconnecting', () => {
      logger.info('Redis reconnecting');
    });
  }

  return redis;
}

export async function connectRedis(): Promise<void> {
  const client = getRedis();
  if (client.status !== 'ready') {
    await client.connect();
  }
}

export async function checkHealth(): Promise<boolean> {
  try {
    const result = await getRedis().ping();
    return result === 'PONG';
  } catch {
    return false;
  }
}

export async function closeRedis(): Promise<void> {
  if (redis) {
    await redis.quit();
    redis = null;
    logger.info('Redis connection closed');
  }
}

// =============================================================================
// KEY-VALUE HELPERS
// =============================================================================

export async function setWithTTL(
  key: string,
  value: string,
  ttlSeconds: number
): Promise<void> {
  await getRedis().setex(key, ttlSeconds, value);
}

export async function get(key: string): Promise<string | null> {
  return getRedis().get(key);
}

export async function del(key: string): Promise<number> {
  return getRedis().del(key);
}

export async function exists(key: string): Promise<boolean> {
  const result = await getRedis().exists(key);
  return result === 1;
}

/**
 * Get and delete atomically (for single-use tokens like challenges).
 */
export async function getAndDelete(key: string): Promise<string | null> {
  return getRedis().getdel(key);
}

// =============================================================================
// LIST HELPERS (for pending messages)
// =============================================================================

export async function listPush(key: string, value: string): Promise<number> {
  return getRedis().rpush(key, value);
}

export async function listRange(
  key: string,
  start: number,
  stop: number
): Promise<string[]> {
  return getRedis().lrange(key, start, stop);
}

export async function listRemove(
  key: string,
  count: number,
  value: string
): Promise<number> {
  return getRedis().lrem(key, count, value);
}

export async function listLength(key: string): Promise<number> {
  return getRedis().llen(key);
}

export async function expire(key: string, ttlSeconds: number): Promise<boolean> {
  const result = await getRedis().expire(key, ttlSeconds);
  return result === 1;
}

/**
 * Atomically set key if it doesn't exist (with TTL).
 * Returns true if key was set (new), false if it already existed (duplicate).
 * This is atomic - no race condition between check and set.
 */
export async function setIfNotExists(
  key: string,
  value: string,
  ttlSeconds: number
): Promise<boolean> {
  // SET key value EX ttl NX - returns 'OK' if set, null if key exists
  const result = await getRedis().set(key, value, 'EX', ttlSeconds, 'NX');
  return result === 'OK';
}

// =============================================================================
// RATE LIMIT HELPERS
// =============================================================================

export async function incrementWithTTL(
  key: string,
  ttlSeconds: number
): Promise<number> {
  const multi = getRedis().multi();
  multi.incr(key);
  multi.expire(key, ttlSeconds);
  const results = await multi.exec();

  if (!results || results[0][1] === null) {
    throw new Error('Failed to increment rate limit counter');
  }

  return results[0][1] as number;
}
