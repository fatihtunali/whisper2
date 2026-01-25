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
import { checkHealth as checkPostgres } from '../db/postgres';
import { checkHealth as checkRedis } from '../db/redis';
import { wsGateway } from './WsGateway';
import { connectionManager } from '../services/ConnectionManager';

// =============================================================================
// TYPES
// =============================================================================

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

  // TODO: Add backup endpoints in later steps:
  // - PUT /backup/contacts
  // - GET /backup/contacts
  // - DELETE /backup/contacts

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
