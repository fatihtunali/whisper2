/**
 * Step 3 Tests: Contacts + Key Lookup + Encrypted Contacts Backup
 *
 * Test cases:
 * 1. Key lookup for existing active user
 * 2. Key lookup 404 for non-existent user
 * 3. Key lookup 403 for banned user
 * 4. PUT /backup/contacts creates new backup
 * 5. PUT /backup/contacts replaces existing backup
 * 6. GET /backup/contacts retrieves backup
 * 7. DELETE /backup/contacts removes backup
 * 8. All endpoints require authentication
 */

import WebSocket from 'ws';
import http from 'http';
import { v4 as uuidv4 } from 'uuid';
import sodium from 'sodium-native';
import { getPool, closePool } from '../src/db/postgres';
import { getRedis, closeRedis, connectRedis } from '../src/db/redis';

// =============================================================================
// TEST CONFIGURATION
// =============================================================================

const WS_URL = process.env.WS_URL || 'ws://localhost:3051/ws';
const HTTP_URL = process.env.HTTP_URL || 'http://localhost:3051';

// =============================================================================
// CRYPTO HELPERS
// =============================================================================

function generateKeyPairs(): {
  encPublicKey: Buffer;
  encSecretKey: Buffer;
  signPublicKey: Buffer;
  signSecretKey: Buffer;
} {
  const encPublicKey = Buffer.alloc(sodium.crypto_box_PUBLICKEYBYTES);
  const encSecretKey = Buffer.alloc(sodium.crypto_box_SECRETKEYBYTES);
  sodium.crypto_box_keypair(encPublicKey, encSecretKey);

  const signPublicKey = Buffer.alloc(sodium.crypto_sign_PUBLICKEYBYTES);
  const signSecretKey = Buffer.alloc(sodium.crypto_sign_SECRETKEYBYTES);
  sodium.crypto_sign_keypair(signPublicKey, signSecretKey);

  return { encPublicKey, encSecretKey, signPublicKey, signSecretKey };
}

function signChallenge(challengeBytes: Buffer, signSecretKey: Buffer): string {
  // Hash the challenge first
  const hash = Buffer.alloc(sodium.crypto_hash_sha256_BYTES);
  sodium.crypto_hash_sha256(hash, challengeBytes);

  // Sign the hash
  const signature = Buffer.alloc(sodium.crypto_sign_BYTES);
  sodium.crypto_sign_detached(signature, hash, signSecretKey);

  return signature.toString('base64');
}

// =============================================================================
// WEBSOCKET HELPERS
// =============================================================================

interface WsMessage {
  type: string;
  requestId?: string;
  payload: any;
}

function createWsClient(): Promise<WebSocket> {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(WS_URL);
    ws.on('open', () => resolve(ws));
    ws.on('error', reject);
  });
}

function sendAndWait(ws: WebSocket, message: WsMessage): Promise<WsMessage> {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('Timeout')), 5000);

    const handler = (data: Buffer) => {
      clearTimeout(timeout);
      ws.off('message', handler);
      try {
        const response = JSON.parse(data.toString());
        resolve(response);
      } catch (err) {
        reject(err);
      }
    };

    ws.on('message', handler);
    ws.send(JSON.stringify(message));
  });
}

// =============================================================================
// HTTP HELPERS
// =============================================================================

function httpRequest(
  method: string,
  path: string,
  body?: any,
  headers?: Record<string, string>
): Promise<{ status: number; data: any }> {
  return new Promise((resolve, reject) => {
    const url = new URL(path, HTTP_URL);
    const options: http.RequestOptions = {
      hostname: url.hostname,
      port: url.port,
      path: url.pathname,
      method,
      headers: {
        'Content-Type': 'application/json',
        ...headers,
      },
    };

    const req = http.request(options, (res) => {
      let data = '';
      res.on('data', (chunk) => (data += chunk));
      res.on('end', () => {
        try {
          resolve({
            status: res.statusCode || 0,
            data: data ? JSON.parse(data) : null,
          });
        } catch {
          resolve({ status: res.statusCode || 0, data });
        }
      });
    });

    req.on('error', reject);

    if (body) {
      req.write(JSON.stringify(body));
    }

    req.end();
  });
}

// =============================================================================
// REGISTRATION HELPER
// =============================================================================

async function registerUser(ws: WebSocket): Promise<{
  whisperId: string;
  sessionToken: string;
  encPublicKey: string;
  signPublicKey: string;
  signSecretKey: Buffer;
}> {
  const keys = generateKeyPairs();
  const deviceId = uuidv4();

  // Step 1: register_begin
  const beginResponse = await sendAndWait(ws, {
    type: 'register_begin',
    requestId: uuidv4(),
    payload: {
      protocolVersion: 1,
      cryptoVersion: 1,
      deviceId,
      platform: 'ios',
    },
  });

  expect(beginResponse.type).toBe('register_challenge');
  const { challengeId, challenge } = beginResponse.payload;

  // Step 2: register_proof
  const challengeBytes = Buffer.from(challenge, 'base64');
  const signature = signChallenge(challengeBytes, keys.signSecretKey);

  const proofResponse = await sendAndWait(ws, {
    type: 'register_proof',
    requestId: uuidv4(),
    payload: {
      protocolVersion: 1,
      cryptoVersion: 1,
      challengeId,
      deviceId,
      platform: 'ios',
      encPublicKey: keys.encPublicKey.toString('base64'),
      signPublicKey: keys.signPublicKey.toString('base64'),
      signature,
    },
  });

  expect(proofResponse.type).toBe('register_ack');
  expect(proofResponse.payload.success).toBe(true);

  return {
    whisperId: proofResponse.payload.whisperId,
    sessionToken: proofResponse.payload.sessionToken,
    encPublicKey: keys.encPublicKey.toString('base64'),
    signPublicKey: keys.signPublicKey.toString('base64'),
    signSecretKey: keys.signSecretKey,
  };
}

// =============================================================================
// TESTS
// =============================================================================

describe('Step 3: Contacts + Key Lookup + Encrypted Contacts Backup', () => {
  let clientA: { ws: WebSocket; whisperId: string; sessionToken: string };
  let clientB: { ws: WebSocket; whisperId: string; sessionToken: string };

  beforeAll(async () => {
    // Connect to Redis
    await connectRedis();

    // Clear rate limit keys for test isolation
    const redis = getRedis();
    const keys = await redis.keys('ratelimit:*');
    if (keys.length > 0) {
      await redis.del(...keys);
    }

    // Create two clients
    const wsA = await createWsClient();
    const regA = await registerUser(wsA);
    clientA = { ws: wsA, whisperId: regA.whisperId, sessionToken: regA.sessionToken };

    const wsB = await createWsClient();
    const regB = await registerUser(wsB);
    clientB = { ws: wsB, whisperId: regB.whisperId, sessionToken: regB.sessionToken };
  });

  afterAll(async () => {
    clientA.ws.close();
    clientB.ws.close();

    // Clean up test data
    const pool = getPool();
    await pool.query('DELETE FROM contact_backups WHERE whisper_id IN ($1, $2)', [
      clientA.whisperId,
      clientB.whisperId,
    ]);

    await closePool();
    await closeRedis();
  });

  // ===========================================================================
  // KEY LOOKUP TESTS
  // ===========================================================================

  describe('Key Lookup', () => {
    test('1. GET /users/:whisperId/keys returns keys for active user', async () => {
      const response = await httpRequest(
        'GET',
        `/users/${clientB.whisperId}/keys`,
        null,
        { Authorization: `Bearer ${clientA.sessionToken}` }
      );

      expect(response.status).toBe(200);
      expect(response.data.whisperId).toBe(clientB.whisperId);
      expect(response.data.encPublicKey).toBeDefined();
      expect(response.data.signPublicKey).toBeDefined();
      expect(response.data.status).toBe('active');
    });

    test('2. GET /users/:whisperId/keys returns 404 for non-existent user', async () => {
      const fakeWhisperId = 'WSP-AAAA-BBBB-CCCC';
      const response = await httpRequest(
        'GET',
        `/users/${fakeWhisperId}/keys`,
        null,
        { Authorization: `Bearer ${clientA.sessionToken}` }
      );

      expect(response.status).toBe(404);
      expect(response.data.error).toBe('NOT_FOUND');
    });

    test('3. GET /users/:whisperId/keys returns 400 for invalid Whisper ID format', async () => {
      const response = await httpRequest(
        'GET',
        `/users/invalid-id/keys`,
        null,
        { Authorization: `Bearer ${clientA.sessionToken}` }
      );

      expect(response.status).toBe(400);
      expect(response.data.error).toBe('INVALID_PAYLOAD');
    });

    test('4. GET /users/:whisperId/keys returns 401 without auth', async () => {
      const response = await httpRequest(
        'GET',
        `/users/${clientB.whisperId}/keys`,
        null
      );

      expect(response.status).toBe(401);
      expect(response.data.error).toBe('AUTH_FAILED');
    });
  });

  // ===========================================================================
  // CONTACTS BACKUP TESTS
  // ===========================================================================

  describe('Contacts Backup', () => {
    const testNonce = Buffer.alloc(24);
    sodium.randombytes_buf(testNonce);
    const testNonceB64 = testNonce.toString('base64');

    const testCiphertext = Buffer.from('encrypted contacts data here');
    const testCiphertextB64 = testCiphertext.toString('base64');

    test('5. PUT /backup/contacts creates new backup', async () => {
      const response = await httpRequest(
        'PUT',
        '/backup/contacts',
        { nonce: testNonceB64, ciphertext: testCiphertextB64 },
        { Authorization: `Bearer ${clientA.sessionToken}` }
      );

      expect(response.status).toBe(201);
      expect(response.data.success).toBe(true);
      expect(response.data.created).toBe(true);
      expect(response.data.sizeBytes).toBe(testCiphertext.length);
      expect(response.data.updatedAt).toBeDefined();
    });

    test('6. PUT /backup/contacts replaces existing backup', async () => {
      const newCiphertext = Buffer.from('updated encrypted contacts');
      const newCiphertextB64 = newCiphertext.toString('base64');

      const response = await httpRequest(
        'PUT',
        '/backup/contacts',
        { nonce: testNonceB64, ciphertext: newCiphertextB64 },
        { Authorization: `Bearer ${clientA.sessionToken}` }
      );

      expect(response.status).toBe(200);
      expect(response.data.success).toBe(true);
      expect(response.data.created).toBe(false);
      expect(response.data.sizeBytes).toBe(newCiphertext.length);
    });

    test('7. GET /backup/contacts retrieves backup', async () => {
      const response = await httpRequest(
        'GET',
        '/backup/contacts',
        null,
        { Authorization: `Bearer ${clientA.sessionToken}` }
      );

      expect(response.status).toBe(200);
      expect(response.data.nonce).toBe(testNonceB64);
      expect(response.data.ciphertext).toBeDefined();
      expect(response.data.sizeBytes).toBeGreaterThan(0);
      expect(response.data.updatedAt).toBeDefined();
    });

    test('8. GET /backup/contacts returns 404 when no backup exists', async () => {
      const response = await httpRequest(
        'GET',
        '/backup/contacts',
        null,
        { Authorization: `Bearer ${clientB.sessionToken}` }
      );

      expect(response.status).toBe(404);
      expect(response.data.error).toBe('NOT_FOUND');
    });

    test('9. DELETE /backup/contacts removes backup', async () => {
      const response = await httpRequest(
        'DELETE',
        '/backup/contacts',
        null,
        { Authorization: `Bearer ${clientA.sessionToken}` }
      );

      expect(response.status).toBe(200);
      expect(response.data.success).toBe(true);
    });

    test('10. DELETE /backup/contacts returns 404 when no backup exists', async () => {
      const response = await httpRequest(
        'DELETE',
        '/backup/contacts',
        null,
        { Authorization: `Bearer ${clientA.sessionToken}` }
      );

      expect(response.status).toBe(404);
      expect(response.data.error).toBe('NOT_FOUND');
    });

    test('11. PUT /backup/contacts validates nonce length', async () => {
      const invalidNonce = Buffer.alloc(16).toString('base64'); // Wrong size

      const response = await httpRequest(
        'PUT',
        '/backup/contacts',
        { nonce: invalidNonce, ciphertext: testCiphertextB64 },
        { Authorization: `Bearer ${clientA.sessionToken}` }
      );

      expect(response.status).toBe(400);
      expect(response.data.error).toBe('INVALID_PAYLOAD');
    });

    test('12. PUT /backup/contacts requires auth', async () => {
      const response = await httpRequest(
        'PUT',
        '/backup/contacts',
        { nonce: testNonceB64, ciphertext: testCiphertextB64 }
      );

      expect(response.status).toBe(401);
      expect(response.data.error).toBe('AUTH_FAILED');
    });
  });
});
