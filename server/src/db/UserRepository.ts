/**
 * Shared user database queries - eliminates duplication across services
 */
import { query } from './postgres';

export interface UserKeys {
  enc_public_key: string;
  sign_public_key: string;
}

export interface UserWithStatus extends UserKeys {
  status: string;
}

/**
 * Check if a user exists and is active
 */
export async function userExists(whisperId: string): Promise<boolean> {
  const result = await query(
    `SELECT 1 FROM users WHERE whisper_id = $1 AND status = 'active'`,
    [whisperId]
  );
  return (result.rowCount ?? 0) > 0;
}

/**
 * Check if a user exists (any status)
 */
export async function userExistsAnyStatus(whisperId: string): Promise<boolean> {
  const result = await query(
    'SELECT 1 FROM users WHERE whisper_id = $1',
    [whisperId]
  );
  return (result.rowCount ?? 0) > 0;
}

/**
 * Get user's public keys (active users only)
 */
export async function getUserKeys(whisperId: string): Promise<UserKeys | null> {
  const result = await query<UserKeys>(
    `SELECT enc_public_key, sign_public_key FROM users WHERE whisper_id = $1 AND status = 'active'`,
    [whisperId]
  );
  return result.rows[0] || null;
}

/**
 * Get user's public keys and status
 */
export async function getUserWithStatus(whisperId: string): Promise<UserWithStatus | null> {
  const result = await query<UserWithStatus>(
    'SELECT enc_public_key, sign_public_key, status FROM users WHERE whisper_id = $1',
    [whisperId]
  );
  return result.rows[0] || null;
}
