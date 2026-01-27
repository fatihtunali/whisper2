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
  SendMessagePayload,
  DeliveryReceiptPayload,
  FetchPendingPayload,
  UpdateTokensPayload,
  GetTurnCredentialsPayload,
  CallInitiatePayload,
  CallRingingPayload,
  CallAnswerPayload,
  CallIceCandidatePayload,
  CallEndPayload,
  TypingPayload,
} from '../types/protocol';
import { messageRouter } from '../services/MessageRouter';
import {
  groupService,
  GroupCreatePayload,
  GroupUpdatePayload,
  GroupSendMessagePayload,
} from '../services/GroupService';
import { callService } from '../services/CallService';

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
      // Send error frame before closing for clean client handling
      try {
        ws.send(JSON.stringify({
          type: 'error',
          payload: {
            code: 'RATE_LIMITED',
            message: 'Too many connections',
          },
        }));
      } catch {
        // Ignore send errors during rate limit
      }
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

      // Messaging handlers
      case MessageTypes.SEND_MESSAGE:
        await this.handleSendMessage(conn, payload as SendMessagePayload, requestId);
        break;

      case MessageTypes.DELIVERY_RECEIPT:
        await this.handleDeliveryReceipt(conn, payload as DeliveryReceiptPayload, requestId);
        break;

      case MessageTypes.FETCH_PENDING:
        await this.handleFetchPending(conn, payload as FetchPendingPayload, requestId);
        break;

      // Group handlers
      case MessageTypes.GROUP_CREATE:
        await this.handleGroupCreate(conn, payload as GroupCreatePayload, requestId);
        break;

      case MessageTypes.GROUP_UPDATE:
        await this.handleGroupUpdate(conn, payload as GroupUpdatePayload, requestId);
        break;

      case MessageTypes.GROUP_SEND_MESSAGE:
        await this.handleGroupSendMessage(conn, payload as GroupSendMessagePayload, requestId);
        break;

      // Push token update handler
      case MessageTypes.UPDATE_TOKENS:
        await this.handleUpdateTokens(conn, payload as UpdateTokensPayload, requestId);
        break;

      // Call handlers
      case MessageTypes.GET_TURN_CREDENTIALS:
        await this.handleGetTurnCredentials(conn, payload as GetTurnCredentialsPayload, requestId);
        break;

      case MessageTypes.CALL_INITIATE:
        await this.handleCallInitiate(conn, payload as CallInitiatePayload, requestId);
        break;

      case MessageTypes.CALL_RINGING:
        await this.handleCallRinging(conn, payload as CallRingingPayload, requestId);
        break;

      case MessageTypes.CALL_ANSWER:
        await this.handleCallAnswer(conn, payload as CallAnswerPayload, requestId);
        break;

      case MessageTypes.CALL_ICE_CANDIDATE:
        await this.handleCallIceCandidate(conn, payload as CallIceCandidatePayload, requestId);
        break;

      case MessageTypes.CALL_END:
        await this.handleCallEnd(conn, payload as CallEndPayload, requestId);
        break;

      // Typing indicator handler
      case MessageTypes.TYPING:
        await this.handleTyping(conn, payload as TypingPayload, requestId);
        break;

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
  // MESSAGING HANDLERS
  // ===========================================================================

  private async handleSendMessage(
    conn: Connection,
    payload: SendMessagePayload,
    requestId?: string
  ): Promise<void> {
    if (!conn.whisperId) {
      this.sendError(conn.ws, 'NOT_REGISTERED', 'Not authenticated', requestId);
      return;
    }

    const result = await messageRouter.routeMessage(payload, conn.whisperId);

    if (!result.success || !result.data) {
      this.sendError(
        conn.ws,
        result.error?.code || 'INTERNAL_ERROR',
        result.error?.message || 'Failed to route message',
        requestId
      );
      return;
    }

    this.send(conn.ws, {
      type: MessageTypes.MESSAGE_ACCEPTED,
      requestId,
      payload: result.data,
    });
  }

  private async handleDeliveryReceipt(
    conn: Connection,
    payload: DeliveryReceiptPayload,
    requestId?: string
  ): Promise<void> {
    if (!conn.whisperId) {
      this.sendError(conn.ws, 'NOT_REGISTERED', 'Not authenticated', requestId);
      return;
    }

    const result = await messageRouter.handleReceipt(payload, conn.whisperId);

    if (!result.success) {
      this.sendError(
        conn.ws,
        result.error?.code || 'INTERNAL_ERROR',
        result.error?.message || 'Failed to process receipt',
        requestId
      );
      return;
    }

    // Notify original sender if they're online
    if (result.senderNotification && result.senderWhisperId) {
      connectionManager.sendToUser(result.senderWhisperId, {
        type: MessageTypes.MESSAGE_DELIVERED,
        payload: result.senderNotification,
      });
    }

    // No response needed for receipt, but we could send an ACK
  }

  private async handleFetchPending(
    conn: Connection,
    payload: FetchPendingPayload,
    requestId?: string
  ): Promise<void> {
    if (!conn.whisperId) {
      this.sendError(conn.ws, 'NOT_REGISTERED', 'Not authenticated', requestId);
      return;
    }

    const result = await messageRouter.fetchPending(payload, conn.whisperId);

    if (!result.success || !result.data) {
      this.sendError(
        conn.ws,
        result.error?.code || 'INTERNAL_ERROR',
        result.error?.message || 'Failed to fetch pending',
        requestId
      );
      return;
    }

    this.send(conn.ws, {
      type: MessageTypes.PENDING_MESSAGES,
      requestId,
      payload: result.data,
    });
  }

  // ===========================================================================
  // GROUP HANDLERS
  // ===========================================================================

  private async handleGroupCreate(
    conn: Connection,
    payload: GroupCreatePayload,
    requestId?: string
  ): Promise<void> {
    if (!conn.whisperId) {
      this.sendError(conn.ws, 'NOT_REGISTERED', 'Not authenticated', requestId);
      return;
    }

    const result = await groupService.createGroup(payload, conn.whisperId);

    if (!result.success || !result.data) {
      this.sendError(
        conn.ws,
        result.error?.code || 'INTERNAL_ERROR',
        result.error?.message || 'Failed to create group',
        requestId
      );
      return;
    }

    // group_event is already emitted by groupService to all members
    // No additional response needed as creator receives group_event too
  }

  private async handleGroupUpdate(
    conn: Connection,
    payload: GroupUpdatePayload,
    requestId?: string
  ): Promise<void> {
    if (!conn.whisperId) {
      this.sendError(conn.ws, 'NOT_REGISTERED', 'Not authenticated', requestId);
      return;
    }

    const result = await groupService.updateGroup(payload, conn.whisperId);

    if (!result.success) {
      this.sendError(
        conn.ws,
        result.error?.code || 'INTERNAL_ERROR',
        result.error?.message || 'Failed to update group',
        requestId
      );
      return;
    }

    // group_event is already emitted by groupService to all affected members
  }

  private async handleGroupSendMessage(
    conn: Connection,
    payload: GroupSendMessagePayload,
    requestId?: string
  ): Promise<void> {
    if (!conn.whisperId) {
      this.sendError(conn.ws, 'NOT_REGISTERED', 'Not authenticated', requestId);
      return;
    }

    const result = await groupService.sendGroupMessage(payload, conn.whisperId);

    if (!result.success || !result.data) {
      this.sendError(
        conn.ws,
        result.error?.code || 'INTERNAL_ERROR',
        result.error?.message || 'Failed to send group message',
        requestId
      );
      return;
    }

    this.send(conn.ws, {
      type: MessageTypes.MESSAGE_ACCEPTED,
      requestId,
      payload: result.data,
    });
  }

  // ===========================================================================
  // PUSH TOKEN HANDLERS
  // ===========================================================================

  private async handleUpdateTokens(
    conn: Connection,
    payload: UpdateTokensPayload,
    requestId?: string
  ): Promise<void> {
    if (!conn.whisperId || !conn.deviceId) {
      this.sendError(conn.ws, 'NOT_REGISTERED', 'Not authenticated', requestId);
      return;
    }

    const result = await authService.updateTokens(
      conn.whisperId,
      conn.deviceId,
      payload.pushToken,
      payload.voipToken
    );

    if (!result.success) {
      this.sendError(
        conn.ws,
        (result.error?.code as ErrorCode) || 'INTERNAL_ERROR',
        result.error?.message || 'Failed to update tokens',
        requestId
      );
      return;
    }

    // Send ACK
    this.send(conn.ws, {
      type: 'tokens_updated',
      requestId,
      payload: { success: true },
    });
  }

  // ===========================================================================
  // CALL HANDLERS
  // ===========================================================================

  private async handleGetTurnCredentials(
    conn: Connection,
    payload: GetTurnCredentialsPayload,
    requestId?: string
  ): Promise<void> {
    if (!conn.whisperId) {
      this.sendError(conn.ws, 'NOT_REGISTERED', 'Not authenticated', requestId);
      return;
    }

    const result = await callService.getTurnCredentials(conn.whisperId);

    if (!result.success || !result.data) {
      this.sendError(
        conn.ws,
        (result.error?.code as ErrorCode) || 'INTERNAL_ERROR',
        result.error?.message || 'Failed to get TURN credentials',
        requestId
      );
      return;
    }

    this.send(conn.ws, {
      type: MessageTypes.TURN_CREDENTIALS,
      requestId,
      payload: result.data,
    });
  }

  private async handleCallInitiate(
    conn: Connection,
    payload: CallInitiatePayload,
    requestId?: string
  ): Promise<void> {
    if (!conn.whisperId) {
      this.sendError(conn.ws, 'NOT_REGISTERED', 'Not authenticated', requestId);
      return;
    }

    const result = await callService.initiateCall(payload, conn.whisperId);

    if (!result.success) {
      this.sendError(
        conn.ws,
        (result.error?.code as ErrorCode) || 'INTERNAL_ERROR',
        result.error?.message || 'Failed to initiate call',
        requestId
      );
      return;
    }

    // ACK is implicit - callee will receive call_incoming
  }

  private async handleCallRinging(
    conn: Connection,
    payload: CallRingingPayload,
    requestId?: string
  ): Promise<void> {
    if (!conn.whisperId) {
      this.sendError(conn.ws, 'NOT_REGISTERED', 'Not authenticated', requestId);
      return;
    }

    const result = await callService.handleRinging(payload, conn.whisperId);

    if (!result.success) {
      this.sendError(
        conn.ws,
        (result.error?.code as ErrorCode) || 'INTERNAL_ERROR',
        result.error?.message || 'Failed to process ringing',
        requestId
      );
      return;
    }

    // Caller receives call_ringing notification
  }

  private async handleCallAnswer(
    conn: Connection,
    payload: CallAnswerPayload,
    requestId?: string
  ): Promise<void> {
    if (!conn.whisperId) {
      this.sendError(conn.ws, 'NOT_REGISTERED', 'Not authenticated', requestId);
      return;
    }

    const result = await callService.handleAnswer(payload, conn.whisperId);

    if (!result.success) {
      this.sendError(
        conn.ws,
        (result.error?.code as ErrorCode) || 'INTERNAL_ERROR',
        result.error?.message || 'Failed to process answer',
        requestId
      );
      return;
    }

    // Caller receives call_answer
  }

  private async handleCallIceCandidate(
    conn: Connection,
    payload: CallIceCandidatePayload,
    requestId?: string
  ): Promise<void> {
    if (!conn.whisperId) {
      this.sendError(conn.ws, 'NOT_REGISTERED', 'Not authenticated', requestId);
      return;
    }

    const result = await callService.handleIceCandidate(payload, conn.whisperId);

    if (!result.success) {
      this.sendError(
        conn.ws,
        (result.error?.code as ErrorCode) || 'INTERNAL_ERROR',
        result.error?.message || 'Failed to relay ICE candidate',
        requestId
      );
      return;
    }

    // Peer receives ice_candidate
  }

  private async handleCallEnd(
    conn: Connection,
    payload: CallEndPayload,
    requestId?: string
  ): Promise<void> {
    if (!conn.whisperId) {
      this.sendError(conn.ws, 'NOT_REGISTERED', 'Not authenticated', requestId);
      return;
    }

    const result = await callService.handleEnd(payload, conn.whisperId);

    if (!result.success) {
      this.sendError(
        conn.ws,
        (result.error?.code as ErrorCode) || 'INTERNAL_ERROR',
        result.error?.message || 'Failed to end call',
        requestId
      );
      return;
    }

    // Peer receives call_end
  }

  // ===========================================================================
  // TYPING HANDLER
  // ===========================================================================

  private async handleTyping(
    conn: Connection,
    payload: TypingPayload,
    requestId?: string
  ): Promise<void> {
    if (!conn.whisperId) {
      this.sendError(conn.ws, 'NOT_REGISTERED', 'Not authenticated', requestId);
      return;
    }

    // Forward typing indicator to the recipient if they're online
    const sent = connectionManager.sendToUser(payload.to, {
      type: MessageTypes.TYPING_NOTIFICATION,
      payload: {
        from: conn.whisperId,
        isTyping: payload.isTyping,
        timestamp: Date.now(),
      },
    });

    // No ACK needed for typing indicators - fire and forget
    if (!sent) {
      // Recipient is offline, just ignore
      logger.debug({ from: conn.whisperId, to: payload.to }, 'Typing indicator not delivered: recipient offline');
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
