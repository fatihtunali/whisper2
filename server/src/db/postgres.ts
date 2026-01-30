/**
 * Whisper2 PostgreSQL Connection
 * Following WHISPER-REBUILD.md Section 12.2
 *
 * Postgres = durable storage for:
 * - users, devices, groups, group_members
 * - bans, reports, audit_events
 * - attachments, contact_backups
 */

import { Pool, PoolClient, QueryResult, QueryResultRow } from 'pg';
import { logger } from '../utils/logger';
import { EventEmitter } from 'events';

// Increase default max listeners to support many concurrent users
EventEmitter.defaultMaxListeners = 200;

// =============================================================================
// CONNECTION POOL
// =============================================================================

let pool: Pool | null = null;

export function getPool(): Pool {
  if (!pool) {
    pool = new Pool({
      host: process.env.POSTGRES_HOST || 'localhost',
      port: parseInt(process.env.POSTGRES_PORT || '5432', 10),
      database: process.env.POSTGRES_DB || 'whisper2',
      user: process.env.POSTGRES_USER || 'whisper2',
      password: process.env.POSTGRES_PASSWORD,
      max: parseInt(process.env.POSTGRES_POOL_SIZE || '100', 10),
      idleTimeoutMillis: 30000,
      connectionTimeoutMillis: 5000,
    });

    // Increase max listeners to support many concurrent users
    pool.setMaxListeners(500);

    pool.on('error', (err) => {
      logger.error({ err }, 'Unexpected PostgreSQL pool error');
    });

    pool.on('connect', (client) => {
      // Increase max listeners on individual client connections
      // to support many concurrent operations per connection
      client.setMaxListeners(200);

      // Also increase max listeners on the underlying Connection object
      // This fixes "MaxListenersExceededWarning: 11 wakeup listeners added to [Connection]"
      const connection = (client as any).connection;
      if (connection && typeof connection.setMaxListeners === 'function') {
        connection.setMaxListeners(200);
      }

      logger.debug('PostgreSQL client connected');
    });
  }

  return pool;
}

// =============================================================================
// QUERY HELPERS
// =============================================================================

export async function query<T extends QueryResultRow = QueryResultRow>(
  text: string,
  params?: unknown[]
): Promise<QueryResult<T>> {
  const start = Date.now();
  const result = await getPool().query<T>(text, params);
  const duration = Date.now() - start;

  logger.debug(
    {
      query: text.substring(0, 100),
      duration,
      rows: result.rowCount,
    },
    'Executed query'
  );

  return result;
}

export async function getClient(): Promise<PoolClient> {
  return getPool().connect();
}

// =============================================================================
// TRANSACTION HELPER
// =============================================================================

export async function withTransaction<T>(
  callback: (client: PoolClient) => Promise<T>
): Promise<T> {
  const client = await getClient();

  try {
    await client.query('BEGIN');
    const result = await callback(client);
    await client.query('COMMIT');
    return result;
  } catch (error) {
    await client.query('ROLLBACK');
    throw error;
  } finally {
    client.release();
  }
}

// =============================================================================
// HEALTH CHECK
// =============================================================================

export async function checkHealth(): Promise<boolean> {
  try {
    const result = await query('SELECT 1');
    return result.rowCount === 1;
  } catch {
    return false;
  }
}

// =============================================================================
// SHUTDOWN
// =============================================================================

export async function closePool(): Promise<void> {
  if (pool) {
    await pool.end();
    pool = null;
    logger.info('PostgreSQL pool closed');
  }
}
