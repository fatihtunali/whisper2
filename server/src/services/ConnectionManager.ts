/**
 * Whisper2 Connection Manager
 * Following WHISPER-REBUILD.md Section 12.1
 *
 * Maps WebSocket connections to identities:
 * - connId ↔ whisperId
 * - Handles presence updates on connect/disconnect
 */

import { WebSocket } from 'ws';
import { v4 as uuidv4 } from 'uuid';
import { RedisKeys, TTL, PresenceData } from '../db/redis-keys';
import * as redis from '../db/redis';
import { logger } from '../utils/logger';
import { SessionData } from '../db/redis-keys';

// =============================================================================
// TYPES
// =============================================================================

export interface Connection {
  connId: string;
  ws: WebSocket;
  ip: string;
  whisperId?: string;
  deviceId?: string;
  platform?: 'ios' | 'android';
  sessionToken?: string;
  authenticatedAt?: number;
  lastActivity: number;
}

// =============================================================================
// CONNECTION MANAGER
// =============================================================================

export class ConnectionManager {
  /** In-memory map: connId → Connection */
  private connections: Map<string, Connection> = new Map();

  /** In-memory map: whisperId → connId (for fast lookup) */
  private userConnections: Map<string, string> = new Map();

  /** Heartbeat interval handle */
  private heartbeatInterval: NodeJS.Timeout | null = null;

  /** Ping interval in ms (30 seconds) */
  private readonly PING_INTERVAL = 30_000;

  /** Connection timeout - if no pong received within this time, close connection (90 seconds) */
  private readonly CONNECTION_TIMEOUT = 90_000;

  /**
   * Register a new WebSocket connection.
   * Returns a unique connection ID.
   */
  createConnection(ws: WebSocket, ip: string): string {
    const connId = uuidv4();
    const conn: Connection = {
      connId,
      ws,
      ip,
      lastActivity: Date.now(),
    };

    this.connections.set(connId, conn);

    logger.debug({ connId, ip }, 'Connection created');

    return connId;
  }

  /**
   * Get connection by ID.
   */
  getConnection(connId: string): Connection | undefined {
    return this.connections.get(connId);
  }

  /**
   * Get connection by whisperId.
   */
  getConnectionByUser(whisperId: string): Connection | undefined {
    const connId = this.userConnections.get(whisperId);
    if (!connId) return undefined;
    return this.connections.get(connId);
  }

  /**
   * Check if user is connected.
   */
  isUserConnected(whisperId: string): boolean {
    return this.userConnections.has(whisperId);
  }

  /**
   * Authenticate a connection after successful register_proof.
   * Links the connection to a whisperId.
   */
  async authenticateConnection(
    connId: string,
    session: SessionData,
    sessionToken: string
  ): Promise<void> {
    const conn = this.connections.get(connId);
    if (!conn) {
      throw new Error(`Connection ${connId} not found`);
    }

    const { whisperId, deviceId, platform } = session;

    // If user was already connected elsewhere, handle old connection
    const oldConnId = this.userConnections.get(whisperId);
    if (oldConnId && oldConnId !== connId) {
      const oldConn = this.connections.get(oldConnId);
      if (oldConn && oldConn.deviceId === deviceId) {
        // Same device reconnecting - just close old connection silently
        // Don't send kick message to avoid reconnection loop
        logger.info({ oldConnId, connId, whisperId, deviceId }, 'Same device reconnecting - closing old connection silently');
        this.removeConnection(oldConnId, 'reconnect');
      } else {
        // Different device - kick with message
        await this.kickConnection(oldConnId, 'new_session');
      }
    }

    // Update connection with auth info
    conn.whisperId = whisperId;
    conn.deviceId = deviceId;
    conn.platform = platform;
    conn.sessionToken = sessionToken;
    conn.authenticatedAt = Date.now();
    conn.lastActivity = Date.now();

    // Map user to connection
    this.userConnections.set(whisperId, connId);

    // Store in Redis for distributed access (if multi-node)
    await redis.setWithTTL(
      RedisKeys.connection(connId),
      whisperId,
      TTL.SESSION
    );
    await redis.setWithTTL(
      RedisKeys.userConnection(whisperId),
      connId,
      TTL.SESSION
    );

    // Set presence
    await this.setPresence(whisperId, connId, platform);

    logger.info({ connId, whisperId, deviceId, platform }, 'Connection authenticated');
  }

  /**
   * Remove a connection on disconnect or kick.
   *
   * NOTE: The in-memory maps are deleted first (synchronous), then Redis (async).
   * This ordering is intentional - if a new connection arrives between these operations,
   * the Redis check (redisConnId === connId) prevents deleting the NEW connection's entry.
   * The in-memory map is the source of truth; Redis is for distributed access/presence.
   */
  async removeConnection(connId: string, reason?: string): Promise<void> {
    const conn = this.connections.get(connId);
    if (!conn) return;

    const { whisperId, deviceId } = conn;

    // Remove from in-memory maps first (synchronous, source of truth)
    this.connections.delete(connId);
    if (whisperId) {
      // Only remove user mapping if this is the current connection
      const currentConnId = this.userConnections.get(whisperId);
      if (currentConnId === connId) {
        this.userConnections.delete(whisperId);
      }
    }

    // Clean up Redis (async, check prevents race with new connections)
    await redis.del(RedisKeys.connection(connId));
    if (whisperId) {
      // Only remove if this was the active connection (prevents deleting new connection's entry)
      const redisConnId = await redis.get(RedisKeys.userConnection(whisperId));
      if (redisConnId === connId) {
        await redis.del(RedisKeys.userConnection(whisperId));
        await this.clearPresence(whisperId);
      }
    }

    logger.info(
      { connId, whisperId, deviceId, reason },
      'Connection removed'
    );
  }

  /**
   * Kick a connection (force disconnect).
   */
  async kickConnection(connId: string, reason: string): Promise<void> {
    const conn = this.connections.get(connId);
    if (!conn) return;

    logger.info({ connId, whisperId: conn.whisperId, reason }, 'Kicking connection');

    // Send kick message before closing
    try {
      if (conn.ws.readyState === WebSocket.OPEN) {
        conn.ws.send(
          JSON.stringify({
            type: 'error',
            payload: {
              code: 'AUTH_FAILED',
              message: `Session terminated: ${reason}`,
            },
          })
        );
        conn.ws.close(4001, reason);
      }
    } catch (err) {
      logger.error({ err, connId }, 'Error kicking connection');
    }

    await this.removeConnection(connId, reason);
  }

  /**
   * Update last activity timestamp.
   */
  touchConnection(connId: string): void {
    const conn = this.connections.get(connId);
    if (conn) {
      conn.lastActivity = Date.now();
    }
  }

  /**
   * Get all connection IDs (for admin/metrics).
   */
  getAllConnectionIds(): string[] {
    return Array.from(this.connections.keys());
  }

  /**
   * Get count of active connections.
   */
  getConnectionCount(): number {
    return this.connections.size;
  }

  /**
   * Get count of authenticated connections.
   */
  getAuthenticatedCount(): number {
    return this.userConnections.size;
  }

  /**
   * Send message to a specific user if connected.
   * Returns true if sent, false if user not connected.
   */
  sendToUser(whisperId: string, message: object): boolean {
    const conn = this.getConnectionByUser(whisperId);
    if (!conn || conn.ws.readyState !== WebSocket.OPEN) {
      return false;
    }

    try {
      conn.ws.send(JSON.stringify(message));
      return true;
    } catch (err) {
      logger.error({ err, whisperId }, 'Failed to send message to user');
      return false;
    }
  }

  // ===========================================================================
  // PRESENCE HELPERS
  // ===========================================================================

  private async setPresence(
    whisperId: string,
    connId: string,
    platform: 'ios' | 'android'
  ): Promise<void> {
    const presenceData: PresenceData = {
      connId,
      lastSeen: Date.now(),
      platform,
    };

    await redis.setWithTTL(
      RedisKeys.presence(whisperId),
      JSON.stringify(presenceData),
      TTL.PRESENCE
    );

    // TODO: Broadcast presence to relevant contacts
  }

  private async clearPresence(whisperId: string): Promise<void> {
    await redis.del(RedisKeys.presence(whisperId));
    // TODO: Broadcast offline status to relevant contacts
  }

  /**
   * Refresh presence TTL (call periodically on activity).
   */
  async refreshPresence(whisperId: string): Promise<void> {
    const conn = this.getConnectionByUser(whisperId);
    if (conn && conn.platform) {
      await this.setPresence(whisperId, conn.connId, conn.platform);
    }
  }

  // ===========================================================================
  // HEARTBEAT / PING-PONG
  // ===========================================================================

  /**
   * Start the heartbeat interval to ping all connections.
   * Call this once when the server starts.
   */
  startHeartbeat(): void {
    if (this.heartbeatInterval) {
      return; // Already running
    }

    logger.info({ pingInterval: this.PING_INTERVAL, timeout: this.CONNECTION_TIMEOUT }, 'Starting connection heartbeat');

    this.heartbeatInterval = setInterval(() => {
      this.pingAllConnections();
    }, this.PING_INTERVAL);

    // Don't prevent Node.js from exiting
    this.heartbeatInterval.unref();
  }

  /**
   * Stop the heartbeat interval.
   * Call this during graceful shutdown.
   */
  stopHeartbeat(): void {
    if (this.heartbeatInterval) {
      clearInterval(this.heartbeatInterval);
      this.heartbeatInterval = null;
      logger.info('Connection heartbeat stopped');
    }
  }

  /**
   * Ping all connections and close stale ones.
   */
  private pingAllConnections(): void {
    const now = Date.now();
    const staleConnections: Array<{ connId: string; reason: 'timeout' | 'ping_failed' | 'not_open' }> = [];

    for (const [connId, conn] of this.connections) {
      // Check if connection is stale (no activity within timeout)
      if (now - conn.lastActivity > this.CONNECTION_TIMEOUT) {
        staleConnections.push({ connId, reason: 'timeout' });
        continue;
      }

      // Send WebSocket ping frame
      if (conn.ws.readyState === WebSocket.OPEN) {
        try {
          conn.ws.ping();
        } catch (err) {
          logger.error({ err, connId }, 'Failed to send ping');
          staleConnections.push({ connId, reason: 'ping_failed' });
        }
      } else {
        // Connection not open, mark for removal
        staleConnections.push({ connId, reason: 'not_open' });
      }
    }

    // Remove stale connections
    for (const { connId, reason } of staleConnections) {
      const conn = this.connections.get(connId);
      logger.warn(
        { connId, whisperId: conn?.whisperId, lastActivity: conn?.lastActivity, reason },
        'Closing stale connection'
      );

      try {
        if (conn?.ws.readyState === WebSocket.OPEN) {
          conn.ws.close(4000, `Connection ${reason}`);
        }
      } catch {
        // Ignore close errors
      }

      this.removeConnection(connId, reason).catch((err) => {
        logger.error({ err, connId }, 'Error removing stale connection');
      });
    }

    if (staleConnections.length > 0) {
      logger.info({ removed: staleConnections.length, remaining: this.connections.size }, 'Heartbeat cleanup complete');
    }
  }
}

export const connectionManager = new ConnectionManager();
