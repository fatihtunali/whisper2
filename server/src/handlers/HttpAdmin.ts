/**
 * Whisper2 HTTP Admin API
 * Following WHISPER-REBUILD.md Section 15
 *
 * HTTP is ONLY for:
 * - Health/ready/metrics endpoints
 * - Admin endpoints (bans, reports)
 * - Attachments (presign, download)
 * - Backup (contacts)
 *
 * NO auth endpoints here (auth is WebSocket only).
 */

import express, { Request, Response, NextFunction, Router } from 'express';
import { logger } from '../utils/logger';
import { query, checkHealth as checkPostgres } from '../db/postgres';
import { checkHealth as checkRedis, get as redisGet } from '../db/redis';
import { wsGateway } from './WsGateway';
import { connectionManager } from '../services/ConnectionManager';
import { RedisKeys, SessionData } from '../db/redis-keys';
import { isValidWhisperId } from '../utils/crypto';
import { rateLimiter } from '../services/RateLimiter';

// =============================================================================
// TYPES
// =============================================================================

/**
 * Extended Request with authenticated session data.
 * Note: We use interface merging so session/whisperId are added to Request
 */
export interface AuthenticatedRequest extends Request {
  authSession?: SessionData;
  whisperId?: string;
}

interface HealthStatus {
  status: 'healthy' | 'degraded' | 'unhealthy';
  postgres: boolean;
  redis: boolean;
  uptime: number;
  timestamp: number;
}

interface MetricsData {
  connections: {
    total: number;
    authenticated: number;
  };
  uptime: number;
  memory: {
    heapUsed: number;
    heapTotal: number;
    external: number;
    rss: number;
  };
  timestamp: number;
}

// =============================================================================
// START TIME
// =============================================================================

const startTime = Date.now();

// =============================================================================
// AUTH MIDDLEWARE
// =============================================================================

/**
 * HTTP Authentication Middleware.
 * Validates sessionToken from Authorization header.
 * Format: Authorization: Bearer <sessionToken>
 */
export async function requireAuth(
  req: AuthenticatedRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  const authHeader = req.headers.authorization;

  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    res.status(401).json({
      error: 'AUTH_FAILED',
      message: 'Missing or invalid Authorization header',
    });
    return;
  }

  const sessionToken = authHeader.slice(7); // Remove "Bearer " prefix

  if (!sessionToken) {
    res.status(401).json({
      error: 'AUTH_FAILED',
      message: 'Missing session token',
    });
    return;
  }

  try {
    // Get session from Redis
    const sessionKey = RedisKeys.session(sessionToken);
    const sessionJson = await redisGet(sessionKey);

    if (!sessionJson) {
      res.status(401).json({
        error: 'AUTH_FAILED',
        message: 'Invalid or expired session',
      });
      return;
    }

    const session: SessionData = JSON.parse(sessionJson);

    // Check if session is expired
    if (session.expiresAt < Date.now()) {
      res.status(401).json({
        error: 'AUTH_FAILED',
        message: 'Session expired',
      });
      return;
    }

    // Check if user is banned
    const userResult = await query<{ status: string }>(
      'SELECT status FROM users WHERE whisper_id = $1',
      [session.whisperId]
    );

    if (userResult.rows.length === 0) {
      res.status(401).json({
        error: 'AUTH_FAILED',
        message: 'User not found',
      });
      return;
    }

    if (userResult.rows[0].status === 'banned') {
      res.status(403).json({
        error: 'USER_BANNED',
        message: 'User account is banned',
      });
      return;
    }

    // Attach session to request
    req.authSession = session;
    req.whisperId = session.whisperId;

    next();
  } catch (err) {
    logger.error({ err }, 'Auth middleware error');
    res.status(500).json({
      error: 'INTERNAL_ERROR',
      message: 'Authentication failed',
    });
  }
}

// =============================================================================
// ROUTER
// =============================================================================

export function createHttpRouter(): Router {
  const router = Router();

  // Health check - basic liveness
  router.get('/health', async (_req: Request, res: Response) => {
    const [postgresOk, redisOk] = await Promise.all([
      checkPostgres(),
      checkRedis(),
    ]);

    const status: HealthStatus = {
      status: postgresOk && redisOk ? 'healthy' : 'degraded',
      postgres: postgresOk,
      redis: redisOk,
      uptime: Date.now() - startTime,
      timestamp: Date.now(),
    };

    if (!postgresOk && !redisOk) {
      status.status = 'unhealthy';
    }

    const httpStatus = status.status === 'healthy' ? 200 : status.status === 'degraded' ? 200 : 503;

    res.status(httpStatus).json(status);
  });

  // Ready check - full readiness for traffic
  router.get('/ready', async (_req: Request, res: Response) => {
    const [postgresOk, redisOk] = await Promise.all([
      checkPostgres(),
      checkRedis(),
    ]);

    if (postgresOk && redisOk) {
      res.status(200).json({ ready: true });
    } else {
      res.status(503).json({
        ready: false,
        postgres: postgresOk,
        redis: redisOk,
      });
    }
  });

  // Metrics endpoint
  router.get('/metrics', async (_req: Request, res: Response) => {
    const memUsage = process.memoryUsage();
    const wsStats = wsGateway.getStats();

    const metrics: MetricsData = {
      connections: {
        total: wsStats.totalConnections,
        authenticated: wsStats.authenticatedConnections,
      },
      uptime: Date.now() - startTime,
      memory: {
        heapUsed: memUsage.heapUsed,
        heapTotal: memUsage.heapTotal,
        external: memUsage.external,
        rss: memUsage.rss,
      },
      timestamp: Date.now(),
    };

    res.json(metrics);
  });

  // Prometheus-style metrics (optional, for monitoring)
  router.get('/metrics/prometheus', async (_req: Request, res: Response) => {
    const memUsage = process.memoryUsage();
    const wsStats = wsGateway.getStats();

    const lines = [
      '# HELP whisper2_connections_total Total WebSocket connections',
      '# TYPE whisper2_connections_total gauge',
      `whisper2_connections_total ${wsStats.totalConnections}`,
      '',
      '# HELP whisper2_connections_authenticated Authenticated WebSocket connections',
      '# TYPE whisper2_connections_authenticated gauge',
      `whisper2_connections_authenticated ${wsStats.authenticatedConnections}`,
      '',
      '# HELP whisper2_uptime_seconds Server uptime in seconds',
      '# TYPE whisper2_uptime_seconds counter',
      `whisper2_uptime_seconds ${Math.floor((Date.now() - startTime) / 1000)}`,
      '',
      '# HELP whisper2_memory_heap_used_bytes Heap memory used',
      '# TYPE whisper2_memory_heap_used_bytes gauge',
      `whisper2_memory_heap_used_bytes ${memUsage.heapUsed}`,
      '',
      '# HELP whisper2_memory_heap_total_bytes Total heap memory',
      '# TYPE whisper2_memory_heap_total_bytes gauge',
      `whisper2_memory_heap_total_bytes ${memUsage.heapTotal}`,
      '',
      '# HELP whisper2_memory_rss_bytes Resident set size',
      '# TYPE whisper2_memory_rss_bytes gauge',
      `whisper2_memory_rss_bytes ${memUsage.rss}`,
    ];

    res.set('Content-Type', 'text/plain; charset=utf-8');
    res.send(lines.join('\n'));
  });

  // ===========================================================================
  // USER KEY LOOKUP (Step 3.1)
  // ===========================================================================

  /**
   * GET /users/:whisperId/keys
   *
   * Lookup a user's public keys for "add contact by ID/QR".
   * Requires authentication.
   *
   * Response:
   * {
   *   "whisperId": "WSP-XXXX-XXXX-XXXX",
   *   "encPublicKey": "base64...",
   *   "signPublicKey": "base64...",
   *   "status": "active"
   * }
   *
   * Errors:
   * - 400 INVALID_PAYLOAD: Invalid Whisper ID format
   * - 401 AUTH_FAILED: Missing or invalid session
   * - 403 USER_BANNED: Requester is banned
   * - 403 FORBIDDEN: Target user is banned
   * - 404 NOT_FOUND: Target user not found
   * - 429 RATE_LIMITED: Too many requests
   */
  router.get(
    '/users/:whisperId/keys',
    requireAuth,
    async (req: AuthenticatedRequest, res: Response) => {
      const targetWhisperId = req.params.whisperId;
      const requesterWhisperId = req.whisperId!;
      const ip = req.ip || req.socket.remoteAddress || 'unknown';

      // 1. Validate Whisper ID format
      if (!isValidWhisperId(targetWhisperId)) {
        res.status(400).json({
          error: 'INVALID_PAYLOAD',
          message: 'Invalid Whisper ID format',
        });
        return;
      }

      // 2. Check rate limit
      const rateResult = await rateLimiter.checkBoth(
        ip,
        requesterWhisperId,
        'keys_lookup'
      );

      if (!rateResult.allowed) {
        res.status(429).json({
          error: 'RATE_LIMITED',
          message: 'Too many requests',
          retryAfter: Math.ceil((rateResult.resetAt - Date.now()) / 1000),
        });
        return;
      }

      // 3. Lookup target user keys
      try {
        const result = await query<{
          whisper_id: string;
          enc_public_key: string;
          sign_public_key: string;
          status: string;
        }>(
          `SELECT whisper_id, enc_public_key, sign_public_key, status
           FROM users
           WHERE whisper_id = $1`,
          [targetWhisperId]
        );

        if (result.rows.length === 0) {
          res.status(404).json({
            error: 'NOT_FOUND',
            message: 'User not found',
          });
          return;
        }

        const user = result.rows[0];

        // Check if target is banned
        if (user.status === 'banned') {
          res.status(403).json({
            error: 'FORBIDDEN',
            message: 'User is not available',
          });
          return;
        }

        // Return public keys
        logger.info(
          { requester: requesterWhisperId, target: targetWhisperId },
          'Key lookup successful'
        );

        res.json({
          whisperId: user.whisper_id,
          encPublicKey: user.enc_public_key,
          signPublicKey: user.sign_public_key,
          status: user.status,
        });
      } catch (err) {
        logger.error({ err, targetWhisperId }, 'Key lookup failed');
        res.status(500).json({
          error: 'INTERNAL_ERROR',
          message: 'Failed to lookup user keys',
        });
      }
    }
  );

  // TODO: Add admin endpoints in later steps:
  // - GET /admin/stats
  // - GET /admin/reports
  // - POST /admin/reports/:id/review
  // - POST /admin/ban
  // - POST /admin/unban
  // - GET /admin/bans

  // TODO: Add attachment endpoints in later steps:
  // - POST /attachments/presign
  // - GET /attachments/:objectKey/download
  // - POST /admin/attachments/gc/run

  // ===========================================================================
  // CONTACTS BACKUP (Steps 3.3-3.5)
  // ===========================================================================

  /**
   * PUT /backup/contacts
   *
   * Upload or replace encrypted contacts backup.
   * Zero-knowledge: server cannot decrypt the blob.
   *
   * Request body:
   * {
   *   "nonce": "base64(24 bytes)",
   *   "ciphertext": "base64"
   * }
   *
   * Response: 200 OK or 201 Created
   * {
   *   "success": true,
   *   "created": true/false,
   *   "sizeBytes": 1234,
   *   "updatedAt": timestamp
   * }
   */
  router.put(
    '/backup/contacts',
    requireAuth,
    async (req: AuthenticatedRequest, res: Response) => {
      const whisperId = req.whisperId!;
      const ip = req.ip || req.socket.remoteAddress || 'unknown';

      // 1. Rate limit
      const rateResult = await rateLimiter.checkBoth(
        ip,
        whisperId,
        'contacts_backup'
      );

      if (!rateResult.allowed) {
        res.status(429).json({
          error: 'RATE_LIMITED',
          message: 'Too many requests',
          retryAfter: Math.ceil((rateResult.resetAt - Date.now()) / 1000),
        });
        return;
      }

      // 2. Validate request body
      const { nonce, ciphertext } = req.body;

      if (!nonce || typeof nonce !== 'string') {
        res.status(400).json({
          error: 'INVALID_PAYLOAD',
          message: 'Missing or invalid nonce',
        });
        return;
      }

      if (!ciphertext || typeof ciphertext !== 'string') {
        res.status(400).json({
          error: 'INVALID_PAYLOAD',
          message: 'Missing or invalid ciphertext',
        });
        return;
      }

      // Validate nonce is 24 bytes base64 (32 chars with padding)
      try {
        const nonceBytes = Buffer.from(nonce, 'base64');
        if (nonceBytes.length !== 24) {
          res.status(400).json({
            error: 'INVALID_PAYLOAD',
            message: 'Nonce must be exactly 24 bytes',
          });
          return;
        }
      } catch {
        res.status(400).json({
          error: 'INVALID_PAYLOAD',
          message: 'Invalid base64 nonce',
        });
        return;
      }

      // Validate ciphertext is valid base64
      try {
        Buffer.from(ciphertext, 'base64');
      } catch {
        res.status(400).json({
          error: 'INVALID_PAYLOAD',
          message: 'Invalid base64 ciphertext',
        });
        return;
      }

      // Calculate size
      const sizeBytes = Buffer.from(ciphertext, 'base64').length;

      // Max backup size: 1MB (plenty for contact lists)
      const MAX_BACKUP_SIZE = 1024 * 1024;
      if (sizeBytes > MAX_BACKUP_SIZE) {
        res.status(400).json({
          error: 'INVALID_PAYLOAD',
          message: `Backup exceeds maximum size (${MAX_BACKUP_SIZE / 1024}KB)`,
        });
        return;
      }

      // 3. Upsert backup
      try {
        const result = await query(
          `INSERT INTO contact_backups (whisper_id, nonce_b64, ciphertext_b64, size_bytes, updated_at)
           VALUES ($1, $2, $3, $4, NOW())
           ON CONFLICT (whisper_id) DO UPDATE SET
             nonce_b64 = EXCLUDED.nonce_b64,
             ciphertext_b64 = EXCLUDED.ciphertext_b64,
             size_bytes = EXCLUDED.size_bytes,
             updated_at = NOW()
           RETURNING (xmax = 0) AS is_insert, updated_at`,
          [whisperId, nonce, ciphertext, sizeBytes]
        );

        const isInsert = result.rows[0].is_insert;
        const updatedAt = result.rows[0].updated_at;

        logger.info(
          { whisperId, sizeBytes, isInsert },
          'Contacts backup saved'
        );

        res.status(isInsert ? 201 : 200).json({
          success: true,
          created: isInsert,
          sizeBytes,
          updatedAt: new Date(updatedAt).getTime(),
        });
      } catch (err) {
        logger.error({ err, whisperId }, 'Failed to save contacts backup');
        res.status(500).json({
          error: 'INTERNAL_ERROR',
          message: 'Failed to save backup',
        });
      }
    }
  );

  /**
   * GET /backup/contacts
   *
   * Download encrypted contacts backup.
   *
   * Response:
   * {
   *   "nonce": "base64(24 bytes)",
   *   "ciphertext": "base64",
   *   "sizeBytes": 1234,
   *   "updatedAt": timestamp
   * }
   *
   * 404 if no backup exists.
   */
  router.get(
    '/backup/contacts',
    requireAuth,
    async (req: AuthenticatedRequest, res: Response) => {
      const whisperId = req.whisperId!;
      const ip = req.ip || req.socket.remoteAddress || 'unknown';

      // 1. Rate limit
      const rateResult = await rateLimiter.checkBoth(
        ip,
        whisperId,
        'contacts_backup'
      );

      if (!rateResult.allowed) {
        res.status(429).json({
          error: 'RATE_LIMITED',
          message: 'Too many requests',
          retryAfter: Math.ceil((rateResult.resetAt - Date.now()) / 1000),
        });
        return;
      }

      // 2. Fetch backup
      try {
        const result = await query<{
          nonce_b64: string;
          ciphertext_b64: string;
          size_bytes: number;
          updated_at: Date;
        }>(
          `SELECT nonce_b64, ciphertext_b64, size_bytes, updated_at
           FROM contact_backups
           WHERE whisper_id = $1`,
          [whisperId]
        );

        if (result.rows.length === 0) {
          res.status(404).json({
            error: 'NOT_FOUND',
            message: 'No backup found',
          });
          return;
        }

        const backup = result.rows[0];

        logger.info({ whisperId }, 'Contacts backup retrieved');

        res.json({
          nonce: backup.nonce_b64,
          ciphertext: backup.ciphertext_b64,
          sizeBytes: backup.size_bytes,
          updatedAt: new Date(backup.updated_at).getTime(),
        });
      } catch (err) {
        logger.error({ err, whisperId }, 'Failed to fetch contacts backup');
        res.status(500).json({
          error: 'INTERNAL_ERROR',
          message: 'Failed to fetch backup',
        });
      }
    }
  );

  /**
   * DELETE /backup/contacts
   *
   * Delete contacts backup.
   *
   * Response: 200 OK
   * {
   *   "success": true
   * }
   *
   * 404 if no backup exists.
   */
  router.delete(
    '/backup/contacts',
    requireAuth,
    async (req: AuthenticatedRequest, res: Response) => {
      const whisperId = req.whisperId!;
      const ip = req.ip || req.socket.remoteAddress || 'unknown';

      // 1. Rate limit
      const rateResult = await rateLimiter.checkBoth(
        ip,
        whisperId,
        'contacts_backup'
      );

      if (!rateResult.allowed) {
        res.status(429).json({
          error: 'RATE_LIMITED',
          message: 'Too many requests',
          retryAfter: Math.ceil((rateResult.resetAt - Date.now()) / 1000),
        });
        return;
      }

      // 2. Delete backup
      try {
        const result = await query(
          `DELETE FROM contact_backups WHERE whisper_id = $1`,
          [whisperId]
        );

        if (result.rowCount === 0) {
          res.status(404).json({
            error: 'NOT_FOUND',
            message: 'No backup found',
          });
          return;
        }

        logger.info({ whisperId }, 'Contacts backup deleted');

        res.json({
          success: true,
        });
      } catch (err) {
        logger.error({ err, whisperId }, 'Failed to delete contacts backup');
        res.status(500).json({
          error: 'INTERNAL_ERROR',
          message: 'Failed to delete backup',
        });
      }
    }
  );

  return router;
}

// =============================================================================
// ERROR HANDLER
// =============================================================================

export function errorHandler(
  err: Error,
  _req: Request,
  res: Response,
  _next: NextFunction
): void {
  logger.error({ err }, 'HTTP error');

  res.status(500).json({
    error: 'INTERNAL_ERROR',
    message: 'Internal server error',
  });
}

// =============================================================================
// REQUEST LOGGER MIDDLEWARE
// =============================================================================

export function requestLogger(
  req: Request,
  res: Response,
  next: NextFunction
): void {
  const start = Date.now();

  res.on('finish', () => {
    const duration = Date.now() - start;
    logger.info(
      {
        method: req.method,
        path: req.path,
        status: res.statusCode,
        duration,
      },
      'HTTP request'
    );
  });

  next();
}
