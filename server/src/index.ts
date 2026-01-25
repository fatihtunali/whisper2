/**
 * Whisper2 Server Entry Point
 * Following WHISPER-REBUILD.md
 *
 * Components:
 * - HTTP server for admin/health endpoints
 * - WebSocket server for messaging/auth
 * - PostgreSQL for durable storage
 * - Redis for volatile/TTL data
 */

import 'dotenv/config';
import http from 'http';
import express from 'express';
import { WebSocketServer } from 'ws';
import { logger } from './utils/logger';
import { getPool, closePool } from './db/postgres';
import { connectRedis, closeRedis } from './db/redis';
import { wsGateway } from './handlers/WsGateway';
import { createHttpRouter, errorHandler, requestLogger } from './handlers/HttpAdmin';

// =============================================================================
// CONFIGURATION
// =============================================================================

const PORT = parseInt(process.env.PORT || '3000', 10);
const HOST = process.env.HOST || '0.0.0.0';

// =============================================================================
// GRACEFUL SHUTDOWN
// =============================================================================

let isShuttingDown = false;

async function shutdown(signal: string): Promise<void> {
  if (isShuttingDown) return;
  isShuttingDown = true;

  logger.info({ signal }, 'Shutting down...');

  // Close WebSocket connections gracefully
  // Give clients 5 seconds to disconnect
  setTimeout(async () => {
    try {
      await closeRedis();
      await closePool();
      logger.info('Shutdown complete');
      process.exit(0);
    } catch (err) {
      logger.error({ err }, 'Error during shutdown');
      process.exit(1);
    }
  }, 5000);
}

process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));

// =============================================================================
// MAIN
// =============================================================================

async function main(): Promise<void> {
  logger.info('Starting Whisper2 server...');

  // 1. Connect to Redis
  logger.info('Connecting to Redis...');
  await connectRedis();
  logger.info('Redis connected');

  // 2. Verify PostgreSQL connection
  logger.info('Connecting to PostgreSQL...');
  const pool = getPool();
  await pool.query('SELECT 1');
  logger.info('PostgreSQL connected');

  // 3. Create Express app for HTTP
  const app = express();
  app.use(express.json());
  app.use(requestLogger);
  app.use('/', createHttpRouter());
  app.use(errorHandler);

  // 4. Create HTTP server
  const server = http.createServer(app);

  // 5. Create WebSocket server
  const wss = new WebSocketServer({
    server,
    path: '/ws',
    maxPayload: 512 * 1024, // 512KB max frame size
  });

  // 6. Initialize WebSocket gateway
  wsGateway.init(wss);

  // 7. Start server
  server.listen(PORT, HOST, () => {
    logger.info({ host: HOST, port: PORT }, 'Whisper2 server started');
    logger.info(`  HTTP:  http://${HOST}:${PORT}`);
    logger.info(`  WS:    ws://${HOST}:${PORT}/ws`);
    logger.info(`  Health: http://${HOST}:${PORT}/health`);
  });

  // 8. Handle server errors
  server.on('error', (err) => {
    logger.error({ err }, 'Server error');
    process.exit(1);
  });
}

// Run
main().catch((err) => {
  logger.error({ err }, 'Failed to start server');
  process.exit(1);
});
