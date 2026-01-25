/**
 * Whisper2 WebSocket Gateway
 * Following WHISPER-REBUILD.md Section 12.1
 *
 * WsGateway (AJV validate → auth check → dispatch)
 *
 * All messages flow through this gateway:
 * 1. Parse JSON
 * 2. Validate frame structure
 * 3. Validate payload schema
 * 4. Check auth if required
 * 5. Check rate limits
 * 6. Dispatch to handler
 */

import { WebSocket, WebSocketServer } from 'ws';
import { IncomingMessage } from 'http';
import { logger, createConnLogger } from '../utils/logger';
import { connectionManager, Connection } from '../services/ConnectionManager';
import { authService } from '../services/AuthService';
import { rateLimiter } from '../services/RateLimiter';
import { validateWsFrame, validatePayload, requiresAuth } from '../schemas/validator';
import {
  WsFrame,
  ErrorCode,
  ErrorPayload,
  MessageTypes,
  RegisterBeginPayload,
  RegisterProofPayload,
  SessionRefreshPayload,
  LogoutPayload,
  PingPayload,
} from '../types/protocol';

// =============================================================================
// CONSTANTS
// =============================================================================

const MAX_FRAME_SIZE = 512_000; // 512KB per spec Section 11

// =============================================================================
// WS GATEWAY
// =============================================================================

export class WsGateway {
  private wss: WebSocketServer | null = null;

  /**
   * Initialize WebSocket server.
   */
  init(wss: WebSocketServer): void {
    this.wss = wss;

    wss.on('connection', (ws: WebSocket, req: IncomingMessage) => {
      this.handleConnection(ws, req);
    });

    logger.info('WebSocket Gateway initialized');
  }

  /**
   * Handle new WebSocket connection.
   */
  private async handleConnection(
    ws: WebSocket,
    req: IncomingMessage
  ): Promise<void> {
    // Get client IP
    const ip = this.getClientIp(req);

    // Check IP rate limit for connections
    const ipRateLimit = await rateLimiter.checkIp(ip, 'ws_connect');
    if (!ipRateLimit.allowed) {
      logger.warn({ ip }, 'Connection rate limited');
      ws.close(4029, 'Rate limited');
      return;
    }

    // Create connection
    const connId = connectionManager.createConnection(ws, ip);
    const connLogger = createConnLogger(connId);

    connLogger.info({ ip }, 'WebSocket connected');

    // Set up event handlers
    ws.on('message', (data: Buffer) => {
      this.handleMessage(connId, data).catch((err) => {
        connLogger.error({ err }, 'Unhandled error in message handler');
      });
    });

    ws.on('close', (code: number, reason: Buffer) => {
      connLogger.info({ code, reason: reason.toString() }, 'WebSocket closed');
      connectionManager.removeConnection(connId, 'client_close').catch((err) => {
        connLogger.error({ err }, 'Error removing connection');
      });
    });

    ws.on('error', (err: Error) => {
      connLogger.error({ err }, 'WebSocket error');
    });

    ws.on('pong', () => {
      connectionManager.touchConnection(connId);
    });
  }

  /**
   * Handle incoming WebSocket message.
   */
  private async handleMessage(connId: string, data: Buffer): Promise<void> {
    const conn = connectionManager.getConnection(connId);
    if (!conn) {
      logger.error({ connId }, 'Connection not found for message');
      return;
    }

    const connLogger = createConnLogger(connId);

    // 1. Check frame size
    if (data.length > MAX_FRAME_SIZE) {
      connLogger.warn({ size: data.length }, 'Frame too large');
      this.sendError(conn.ws, 'INVALID_PAYLOAD', 'Frame exceeds maximum size');
      return;
    }

    // 2. Parse JSON
    let frame: WsFrame;
    try {
      frame = JSON.parse(data.toString('utf8'));
    } catch {
      connLogger.warn('Invalid JSON in frame');
      this.sendError(conn.ws, 'INVALID_PAYLOAD', 'Invalid JSON');
      return;
    }

    // 3. Validate frame structure
    const frameValidation = validateWsFrame(frame);
    if (!frameValidation.valid) {
      connLogger.warn({ errors: frameValidation.errors }, 'Invalid frame structure');
      this.sendError(conn.ws, 'INVALID_PAYLOAD', frameValidation.errors?.join('; ') || 'Invalid frame', frame.requestId);
      return;
    }

    const { type, payload, requestId } = frame;

    // 4. Validate payload schema (if validator exists)
    const payloadValidation = validatePayload(type, payload);
    if (!payloadValidation.valid) {
      connLogger.warn({ type, errors: payloadValidation.errors }, 'Invalid payload');
      this.sendError(conn.ws, 'INVALID_PAYLOAD', payloadValidation.errors?.join('; ') || 'Invalid payload', requestId);
      return;
    }

    // 5. Check auth if required
    if (requiresAuth(type)) {
      if (!conn.whisperId || !conn.sessionToken) {
        connLogger.warn({ type }, 'Auth required but not authenticated');
        this.sendError(conn.ws, 'NOT_REGISTERED', 'Authentication required', requestId);
        return;
      }

      // Validate session is still valid
      const session = await authService.validateSession(conn.sessionToken);
      if (!session) {
        connLogger.warn({ type }, 'Session expired');
        this.sendError(conn.ws, 'AUTH_FAILED', 'Session expired', requestId);
        return;
      }
    }

    // 6. Check rate limits
    const rateLimitResult = conn.whisperId
      ? await rateLimiter.checkBoth(conn.ip, conn.whisperId, type)
      : await rateLimiter.checkIp(conn.ip, type);

    if (!rateLimitResult.allowed) {
      connLogger.warn({ type }, 'Rate limited');
      this.sendError(conn.ws, 'RATE_LIMITED', 'Too many requests', requestId);
      return;
    }

    // 7. Update last activity
    connectionManager.touchConnection(connId);

    // 8. Dispatch to handler
    connLogger.debug({ type }, 'Dispatching message');

    try {
      await this.dispatch(conn, type, payload, requestId);
    } catch (err) {
      connLogger.error({ err, type }, 'Handler error');
      this.sendError(conn.ws, 'INTERNAL_ERROR', 'Internal server error', requestId);
    }
  }

  /**
   * Dispatch message to appropriate handler.
   */
  private async dispatch(
    conn: Connection,
    type: string,
    payload: unknown,
    requestId?: string
  ): Promise<void> {
    switch (type) {
      case MessageTypes.REGISTER_BEGIN:
        await this.handleRegisterBegin(conn, payload as RegisterBeginPayload, requestId);
        break;

      case MessageTypes.REGISTER_PROOF:
        await this.handleRegisterProof(conn, payload as RegisterProofPayload, requestId);
        break;

      case MessageTypes.SESSION_REFRESH:
        await this.handleSessionRefresh(conn, payload as SessionRefreshPayload, requestId);
        break;

      case MessageTypes.LOGOUT:
        await this.handleLogout(conn, payload as LogoutPayload, requestId);
        break;

      case MessageTypes.PING:
        await this.handlePing(conn, payload as PingPayload, requestId);
        break;

      // TODO: Add messaging, groups, calls handlers in later steps

      default:
        logger.warn({ type }, 'Unknown message type');
        this.sendError(conn.ws, 'INVALID_PAYLOAD', `Unknown message type: ${type}`, requestId);
    }
  }

  // ===========================================================================
  // AUTH HANDLERS
  // ===========================================================================

  private async handleRegisterBegin(
    conn: Connection,
    payload: RegisterBeginPayload,
    requestId?: string
  ): Promise<void> {
    const result = await authService.handleRegisterBegin(payload, conn.ip);

    if (result.error) {
      this.sendError(conn.ws, result.error.code, result.error.message, requestId);
      return;
    }

    this.send(conn.ws, {
      type: MessageTypes.REGISTER_CHALLENGE,
      requestId,
      payload: result.challengePayload,
    });
  }

  private async handleRegisterProof(
    conn: Connection,
    payload: RegisterProofPayload,
    requestId?: string
  ): Promise<void> {
    const result = await authService.handleRegisterProof(payload, conn.ip);

    if (!result.success || !result.data) {
      this.sendError(
        conn.ws,
        result.error?.code || 'AUTH_FAILED',
        result.error?.message || 'Authentication failed',
        requestId
      );
      return;
    }

    // Authenticate the connection
    await connectionManager.authenticateConnection(
      conn.connId,
      {
        whisperId: result.data.whisperId,
        deviceId: payload.deviceId,
        platform: payload.platform,
        createdAt: Date.now(),
        expiresAt: result.data.sessionExpiresAt,
      },
      result.data.sessionToken
    );

    this.send(conn.ws, {
      type: MessageTypes.REGISTER_ACK,
      requestId,
      payload: result.data,
    });
  }

  private async handleSessionRefresh(
    conn: Connection,
    payload: SessionRefreshPayload,
    requestId?: string
  ): Promise<void> {
    const result = await authService.refreshSession(payload.sessionToken);

    if (!result) {
      this.sendError(conn.ws, 'AUTH_FAILED', 'Session expired or invalid', requestId);
      return;
    }

    // Update connection's session token
    conn.sessionToken = result.sessionToken;

    this.send(conn.ws, {
      type: MessageTypes.SESSION_REFRESH_ACK,
      requestId,
      payload: result,
    });
  }

  private async handleLogout(
    conn: Connection,
    payload: LogoutPayload,
    requestId?: string
  ): Promise<void> {
    await authService.logout(payload.sessionToken);

    // Close connection after logout
    conn.ws.close(1000, 'Logged out');
  }

  private async handlePing(
    conn: Connection,
    payload: PingPayload,
    requestId?: string
  ): Promise<void> {
    this.send(conn.ws, {
      type: MessageTypes.PONG,
      requestId,
      payload: {
        timestamp: payload.timestamp,
        serverTime: Date.now(),
      },
    });

    // Refresh presence if authenticated
    if (conn.whisperId) {
      await connectionManager.refreshPresence(conn.whisperId);
    }
  }

  // ===========================================================================
  // HELPERS
  // ===========================================================================

  private send(ws: WebSocket, message: WsFrame): void {
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify(message));
    }
  }

  private sendError(
    ws: WebSocket,
    code: ErrorCode,
    message: string,
    requestId?: string
  ): void {
    const errorPayload: ErrorPayload = {
      code,
      message,
      requestId,
    };

    this.send(ws, {
      type: MessageTypes.ERROR,
      payload: errorPayload,
    });
  }

  private getClientIp(req: IncomingMessage): string {
    // Check for forwarded IP (behind proxy/load balancer)
    const forwarded = req.headers['x-forwarded-for'];
    if (forwarded) {
      const ips = Array.isArray(forwarded) ? forwarded[0] : forwarded.split(',')[0];
      return ips.trim();
    }

    const realIp = req.headers['x-real-ip'];
    if (realIp) {
      return Array.isArray(realIp) ? realIp[0] : realIp;
    }

    return req.socket.remoteAddress || 'unknown';
  }

  /**
   * Broadcast to all authenticated connections (admin use).
   */
  broadcast(message: WsFrame): number {
    let count = 0;
    for (const connId of connectionManager.getAllConnectionIds()) {
      const conn = connectionManager.getConnection(connId);
      if (conn?.whisperId && conn.ws.readyState === WebSocket.OPEN) {
        this.send(conn.ws, message);
        count++;
      }
    }
    return count;
  }

  /**
   * Get stats for metrics.
   */
  getStats(): { totalConnections: number; authenticatedConnections: number } {
    return {
      totalConnections: connectionManager.getConnectionCount(),
      authenticatedConnections: connectionManager.getAuthenticatedCount(),
    };
  }
}

export const wsGateway = new WsGateway();
