/**
 * Whisper2 Database Migration Runner
 *
 * Runs SQL migration files in order.
 * Usage: npm run migrate
 */

import 'dotenv/config';
import fs from 'fs';
import path from 'path';
import { getPool, closePool } from './postgres';
import { logger } from '../utils/logger';

const MIGRATIONS_DIR = path.join(__dirname, '../../migrations');

interface Migration {
  name: string;
  sql: string;
}

async function loadMigrations(): Promise<Migration[]> {
  const files = fs.readdirSync(MIGRATIONS_DIR)
    .filter(f => f.endsWith('.sql'))
    .sort();

  return files.map(file => ({
    name: file,
    sql: fs.readFileSync(path.join(MIGRATIONS_DIR, file), 'utf8'),
  }));
}

async function createMigrationsTable(): Promise<void> {
  const pool = getPool();
  await pool.query(`
    CREATE TABLE IF NOT EXISTS _migrations (
      id SERIAL PRIMARY KEY,
      name VARCHAR(255) NOT NULL UNIQUE,
      executed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `);
}

async function getExecutedMigrations(): Promise<Set<string>> {
  const pool = getPool();
  const result = await pool.query<{ name: string }>(
    'SELECT name FROM _migrations ORDER BY id'
  );
  return new Set(result.rows.map(r => r.name));
}

async function recordMigration(name: string): Promise<void> {
  const pool = getPool();
  await pool.query(
    'INSERT INTO _migrations (name) VALUES ($1)',
    [name]
  );
}

async function runMigrations(): Promise<void> {
  logger.info('Starting migrations...');

  const pool = getPool();

  // Test connection
  await pool.query('SELECT 1');
  logger.info('Connected to PostgreSQL');

  // Create migrations table
  await createMigrationsTable();

  // Get executed migrations
  const executed = await getExecutedMigrations();
  logger.info({ count: executed.size }, 'Previously executed migrations');

  // Load and run pending migrations
  const migrations = await loadMigrations();
  let runCount = 0;

  for (const migration of migrations) {
    if (executed.has(migration.name)) {
      logger.debug({ name: migration.name }, 'Skipping (already executed)');
      continue;
    }

    logger.info({ name: migration.name }, 'Running migration...');

    try {
      await pool.query(migration.sql);
      await recordMigration(migration.name);
      runCount++;
      logger.info({ name: migration.name }, 'Migration completed');
    } catch (err) {
      logger.error({ err, name: migration.name }, 'Migration failed');
      throw err;
    }
  }

  if (runCount === 0) {
    logger.info('No new migrations to run');
  } else {
    logger.info({ count: runCount }, 'Migrations completed');
  }
}

// Main
runMigrations()
  .then(() => closePool())
  .then(() => {
    logger.info('Done');
    process.exit(0);
  })
  .catch((err) => {
    logger.error({ err }, 'Migration failed');
    process.exit(1);
  });
