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
import { initializeFirebase } from './services/firebase';

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

  // 3. Initialize Firebase (optional - for FCM push notifications)
  logger.info('Initializing Firebase...');
  const firebaseReady = initializeFirebase();
  if (firebaseReady) {
    logger.info('Firebase initialized - FCM push notifications enabled');
  } else {
    logger.warn('Firebase not configured - FCM push notifications disabled');
  }

  // 4. Create Express app for HTTP
  const app = express();
  app.use(express.json());
  app.use(requestLogger);
  app.use('/', createHttpRouter());
  app.use(errorHandler);

  // 5. Create HTTP server
  const server = http.createServer(app);

  // 6. Create WebSocket server
  const wss = new WebSocketServer({
    server,
    path: '/ws',
    maxPayload: 512 * 1024, // 512KB max frame size
  });

  // 7. Initialize WebSocket gateway
  wsGateway.init(wss);

  // 8. Start server
  server.listen(PORT, HOST, () => {
    logger.info({ host: HOST, port: PORT }, 'Whisper2 server started');
    logger.info(`  HTTP:  http://${HOST}:${PORT}`);
    logger.info(`  WS:    ws://${HOST}:${PORT}/ws`);
    logger.info(`  Health: http://${HOST}:${PORT}/health`);
  });

  // 9. Handle server errors
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
