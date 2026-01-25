/**
 * Step 3 Verification Tests: Contacts + Key Lookup + Encrypted Contacts Backup
 * Following WHISPER-REBUILD.md
 *
 * Test cases:
 * 1. Key lookup for existing active user
 * 2. Key lookup 404 for non-existent user
 * 3. Key lookup 400 for invalid Whisper ID format
 * 4. Key lookup 401 without auth
 * 5. PUT /backup/contacts creates new backup
 * 6. PUT /backup/contacts replaces existing backup
 * 7. GET /backup/contacts retrieves backup
 * 8. GET /backup/contacts 404 when no backup exists
 * 9. DELETE /backup/contacts removes backup
 * 10. DELETE /backup/contacts 404 when no backup exists
 * 11. PUT /backup/contacts validates nonce length
 * 12. PUT /backup/contacts 401 without auth
 */

import WebSocket from 'ws';
import * as sodium from 'sodium-native';
import { v4 as uuidv4 } from 'uuid';
import http from 'http';

const WS_URL = process.env.WS_URL || 'wss://whisper2.aiakademiturkiye.com/ws';
const HTTP_URL = process.env.HTTP_URL || 'https://whisper2.aiakademiturkiye.com';
const PROTOCOL_VERSION = 1;
const CRYPTO_VERSION = 1;

// =============================================================================
// CRYPTO HELPERS
// =============================================================================

function generateKeys() {
  const encPublicKey = Buffer.alloc(sodium.crypto_box_PUBLICKEYBYTES);
  const encSecretKey = Buffer.alloc(sodium.crypto_box_SECRETKEYBYTES);
  sodium.crypto_box_keypair(encPublicKey, encSecretKey);

  const signPublicKey = Buffer.alloc(sodium.crypto_sign_PUBLICKEYBYTES);
  const signSecretKey = Buffer.alloc(sodium.crypto_sign_SECRETKEYBYTES);
  sodium.crypto_sign_keypair(signPublicKey, signSecretKey);

  return {
    encPublicKey: encPublicKey.toString('base64'),
    encSecretKey,
    signPublicKey: signPublicKey.toString('base64'),
    signSecretKey,
  };
}

function signChallenge(challengeBytes: Buffer, signSecretKey: Buffer): string {
  const hash = Buffer.alloc(sodium.crypto_hash_sha256_BYTES);
  sodium.crypto_hash_sha256(hash, challengeBytes);

  const signature = Buffer.alloc(sodium.crypto_sign_BYTES);
  sodium.crypto_sign_detached(signature, hash, signSecretKey);
  return signature.toString('base64');
}

function generateNonce(): string {
  const nonce = Buffer.alloc(24);
  sodium.randombytes_buf(nonce);
  return nonce.toString('base64');
}

// =============================================================================
// HTTP HELPER
// =============================================================================

function httpRequest(
  method: string,
  path: string,
  body?: any,
  headers?: Record<string, string>
): Promise<{ status: number; data: any }> {
  return new Promise((resolve, reject) => {
    const url = new URL(path, HTTP_URL);
    const isHttps = url.protocol === 'https:';
    const httpModule = isHttps ? require('https') : http;

    const options = {
      hostname: url.hostname,
      port: url.port || (isHttps ? 443 : 80),
      path: url.pathname,
      method,
      headers: {
        'Content-Type': 'application/json',
        ...headers,
      },
    };

    const req = httpModule.request(options, (res: any) => {
      let data = '';
      res.on('data', (chunk: any) => (data += chunk));
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
// WEBSOCKET HELPER
// =============================================================================

interface WsClient {
  ws: WebSocket;
  whisperId?: string;
  sessionToken?: string;
  keys?: ReturnType<typeof generateKeys>;
  pendingMessages: any[];
  close: () => void;
}

function createClient(): Promise<WsClient> {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(WS_URL, {
      rejectUnauthorized: false,
    });

    const client: WsClient = {
      ws,
      pendingMessages: [],
      close: () => {
        if (ws.readyState === WebSocket.OPEN) {
          ws.close();
        }
      },
    };

    ws.on('message', (data: Buffer) => {
      try {
        const msg = JSON.parse(data.toString());
        client.pendingMessages.push(msg);
      } catch (e) {
        console.error('Failed to parse message:', e);
      }
    });

    ws.on('open', () => resolve(client));
    ws.on('error', reject);
  });
}

function sendAndWait(client: WsClient, msg: any, expectedType?: string, timeout = 5000): Promise<any> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Timeout waiting for response')), timeout);

    const checkMessages = () => {
      const idx = client.pendingMessages.findIndex(m =>
        !expectedType || m.type === expectedType || m.type === 'error'
      );
      if (idx >= 0) {
        clearTimeout(timer);
        const msg = client.pendingMessages.splice(idx, 1)[0];
        resolve(msg);
      }
    };

    client.ws.on('message', () => setTimeout(checkMessages, 50));
    checkMessages();

    client.ws.send(JSON.stringify(msg));
  });
}

async function registerClient(client: WsClient): Promise<void> {
  const keys = generateKeys();
  client.keys = keys;
  const deviceId = uuidv4();

  // Step 1: register_begin
  const beginResp = await sendAndWait(client, {
    type: 'register_begin',
    requestId: uuidv4(),
    payload: {
      protocolVersion: PROTOCOL_VERSION,
      cryptoVersion: CRYPTO_VERSION,
      deviceId,
      platform: 'ios',
    },
  }, 'register_challenge');

  if (beginResp.type !== 'register_challenge') {
    throw new Error(`Expected register_challenge, got ${beginResp.type}: ${JSON.stringify(beginResp)}`);
  }

  const { challengeId, challenge } = beginResp.payload;
  const challengeBytes = Buffer.from(challenge, 'base64');
  const signature = signChallenge(challengeBytes, keys.signSecretKey);

  // Step 2: register_proof
  const proofResp = await sendAndWait(client, {
    type: 'register_proof',
    requestId: uuidv4(),
    payload: {
      protocolVersion: PROTOCOL_VERSION,
      cryptoVersion: CRYPTO_VERSION,
      challengeId,
      deviceId,
      platform: 'ios',
      encPublicKey: keys.encPublicKey,
      signPublicKey: keys.signPublicKey,
      signature,
    },
  }, 'register_ack');

  if (proofResp.type !== 'register_ack') {
    throw new Error(`Expected register_ack, got ${proofResp.type}: ${JSON.stringify(proofResp)}`);
  }

  client.whisperId = proofResp.payload.whisperId;
  client.sessionToken = proofResp.payload.sessionToken;
}

// =============================================================================
// TESTS
// =============================================================================

async function test1_KeyLookupActive(): Promise<boolean> {
  console.log('\nTest 1: Key lookup for active user');

  let clientA: WsClient | null = null;
  let clientB: WsClient | null = null;

  try {
    clientA = await createClient();
    clientB = await createClient();

    await registerClient(clientA);
    await registerClient(clientB);

    console.log(`  Client A: ${clientA.whisperId}`);
    console.log(`  Client B: ${clientB.whisperId}`);

    // A looks up B's keys
    const resp = await httpRequest(
      'GET',
      `/users/${clientB.whisperId}/keys`,
      null,
      { Authorization: `Bearer ${clientA.sessionToken}` }
    );

    if (resp.status !== 200) {
      throw new Error(`Expected 200, got ${resp.status}: ${JSON.stringify(resp.data)}`);
    }

    if (resp.data.whisperId !== clientB.whisperId) {
      throw new Error(`Wrong whisperId: ${resp.data.whisperId}`);
    }

    if (!resp.data.encPublicKey || !resp.data.signPublicKey) {
      throw new Error('Missing keys in response');
    }

    console.log('  Got keys successfully');
    console.log('  PASSED');
    return true;
  } catch (e) {
    console.log('  FAILED:', e);
    return false;
  } finally {
    clientA?.close();
    clientB?.close();
  }
}

async function test2_KeyLookup404(): Promise<boolean> {
  console.log('\nTest 2: Key lookup 404 for non-existent user');

  let client: WsClient | null = null;

  try {
    client = await createClient();
    await registerClient(client);

    const resp = await httpRequest(
      'GET',
      '/users/WSP-AAAA-BBBB-CCCC/keys',
      null,
      { Authorization: `Bearer ${client.sessionToken}` }
    );

    if (resp.status !== 404) {
      throw new Error(`Expected 404, got ${resp.status}`);
    }

    if (resp.data.error !== 'NOT_FOUND') {
      throw new Error(`Expected NOT_FOUND error, got ${resp.data.error}`);
    }

    console.log('  Got 404 NOT_FOUND as expected');
    console.log('  PASSED');
    return true;
  } catch (e) {
    console.log('  FAILED:', e);
    return false;
  } finally {
    client?.close();
  }
}

async function test3_KeyLookup400(): Promise<boolean> {
  console.log('\nTest 3: Key lookup 400 for invalid Whisper ID');

  let client: WsClient | null = null;

  try {
    client = await createClient();
    await registerClient(client);

    const resp = await httpRequest(
      'GET',
      '/users/invalid-whisper-id/keys',
      null,
      { Authorization: `Bearer ${client.sessionToken}` }
    );

    if (resp.status !== 400) {
      throw new Error(`Expected 400, got ${resp.status}`);
    }

    console.log('  Got 400 as expected');
    console.log('  PASSED');
    return true;
  } catch (e) {
    console.log('  FAILED:', e);
    return false;
  } finally {
    client?.close();
  }
}

async function test4_KeyLookup401(): Promise<boolean> {
  console.log('\nTest 4: Key lookup 401 without auth');

  try {
    const resp = await httpRequest(
      'GET',
      '/users/WSP-AAAA-BBBB-CCCC/keys'
    );

    if (resp.status !== 401) {
      throw new Error(`Expected 401, got ${resp.status}`);
    }

    console.log('  Got 401 as expected');
    console.log('  PASSED');
    return true;
  } catch (e) {
    console.log('  FAILED:', e);
    return false;
  }
}

async function test5_PutBackupCreate(): Promise<boolean> {
  console.log('\nTest 5: PUT /backup/contacts creates new backup');

  let client: WsClient | null = null;

  try {
    client = await createClient();
    await registerClient(client);

    const nonce = generateNonce();
    const ciphertext = Buffer.from('encrypted contacts data').toString('base64');

    const resp = await httpRequest(
      'PUT',
      '/backup/contacts',
      { nonce, ciphertext },
      { Authorization: `Bearer ${client.sessionToken}` }
    );

    if (resp.status !== 201) {
      throw new Error(`Expected 201, got ${resp.status}: ${JSON.stringify(resp.data)}`);
    }

    if (!resp.data.success || !resp.data.created) {
      throw new Error(`Expected success=true, created=true`);
    }

    console.log(`  Created backup, size: ${resp.data.sizeBytes} bytes`);
    console.log('  PASSED');
    return true;
  } catch (e) {
    console.log('  FAILED:', e);
    return false;
  } finally {
    client?.close();
  }
}

async function test6_PutBackupReplace(): Promise<boolean> {
  console.log('\nTest 6: PUT /backup/contacts replaces existing backup');

  let client: WsClient | null = null;

  try {
    client = await createClient();
    await registerClient(client);

    const nonce1 = generateNonce();
    const ciphertext1 = Buffer.from('first backup').toString('base64');

    // Create first backup
    await httpRequest(
      'PUT',
      '/backup/contacts',
      { nonce: nonce1, ciphertext: ciphertext1 },
      { Authorization: `Bearer ${client.sessionToken}` }
    );

    // Replace with second backup
    const nonce2 = generateNonce();
    const ciphertext2 = Buffer.from('second backup - updated').toString('base64');

    const resp = await httpRequest(
      'PUT',
      '/backup/contacts',
      { nonce: nonce2, ciphertext: ciphertext2 },
      { Authorization: `Bearer ${client.sessionToken}` }
    );

    if (resp.status !== 200) {
      throw new Error(`Expected 200, got ${resp.status}`);
    }

    if (resp.data.created !== false) {
      throw new Error('Expected created=false for replacement');
    }

    console.log('  Replaced backup successfully');
    console.log('  PASSED');
    return true;
  } catch (e) {
    console.log('  FAILED:', e);
    return false;
  } finally {
    client?.close();
  }
}

async function test7_GetBackup(): Promise<boolean> {
  console.log('\nTest 7: GET /backup/contacts retrieves backup');

  let client: WsClient | null = null;

  try {
    client = await createClient();
    await registerClient(client);

    const nonce = generateNonce();
    const ciphertext = Buffer.from('test backup data').toString('base64');

    // Create backup
    await httpRequest(
      'PUT',
      '/backup/contacts',
      { nonce, ciphertext },
      { Authorization: `Bearer ${client.sessionToken}` }
    );

    // Retrieve backup
    const resp = await httpRequest(
      'GET',
      '/backup/contacts',
      null,
      { Authorization: `Bearer ${client.sessionToken}` }
    );

    if (resp.status !== 200) {
      throw new Error(`Expected 200, got ${resp.status}`);
    }

    if (resp.data.nonce !== nonce) {
      throw new Error('Nonce mismatch');
    }

    if (resp.data.ciphertext !== ciphertext) {
      throw new Error('Ciphertext mismatch');
    }

    console.log('  Retrieved backup successfully');
    console.log('  PASSED');
    return true;
  } catch (e) {
    console.log('  FAILED:', e);
    return false;
  } finally {
    client?.close();
  }
}

async function test8_GetBackup404(): Promise<boolean> {
  console.log('\nTest 8: GET /backup/contacts 404 when no backup exists');

  let client: WsClient | null = null;

  try {
    client = await createClient();
    await registerClient(client);

    // Don't create any backup, just try to get
    const resp = await httpRequest(
      'GET',
      '/backup/contacts',
      null,
      { Authorization: `Bearer ${client.sessionToken}` }
    );

    if (resp.status !== 404) {
      throw new Error(`Expected 404, got ${resp.status}`);
    }

    console.log('  Got 404 as expected');
    console.log('  PASSED');
    return true;
  } catch (e) {
    console.log('  FAILED:', e);
    return false;
  } finally {
    client?.close();
  }
}

async function test9_DeleteBackup(): Promise<boolean> {
  console.log('\nTest 9: DELETE /backup/contacts removes backup');

  let client: WsClient | null = null;

  try {
    client = await createClient();
    await registerClient(client);

    const nonce = generateNonce();
    const ciphertext = Buffer.from('backup to delete').toString('base64');

    // Create backup
    await httpRequest(
      'PUT',
      '/backup/contacts',
      { nonce, ciphertext },
      { Authorization: `Bearer ${client.sessionToken}` }
    );

    // Delete backup
    const resp = await httpRequest(
      'DELETE',
      '/backup/contacts',
      null,
      { Authorization: `Bearer ${client.sessionToken}` }
    );

    if (resp.status !== 200) {
      throw new Error(`Expected 200, got ${resp.status}`);
    }

    // Verify it's deleted
    const getResp = await httpRequest(
      'GET',
      '/backup/contacts',
      null,
      { Authorization: `Bearer ${client.sessionToken}` }
    );

    if (getResp.status !== 404) {
      throw new Error(`Expected 404 after delete, got ${getResp.status}`);
    }

    console.log('  Deleted backup successfully');
    console.log('  PASSED');
    return true;
  } catch (e) {
    console.log('  FAILED:', e);
    return false;
  } finally {
    client?.close();
  }
}

async function test10_DeleteBackup404(): Promise<boolean> {
  console.log('\nTest 10: DELETE /backup/contacts 404 when no backup');

  let client: WsClient | null = null;

  try {
    client = await createClient();
    await registerClient(client);

    // Try to delete without creating
    const resp = await httpRequest(
      'DELETE',
      '/backup/contacts',
      null,
      { Authorization: `Bearer ${client.sessionToken}` }
    );

    if (resp.status !== 404) {
      throw new Error(`Expected 404, got ${resp.status}`);
    }

    console.log('  Got 404 as expected');
    console.log('  PASSED');
    return true;
  } catch (e) {
    console.log('  FAILED:', e);
    return false;
  } finally {
    client?.close();
  }
}

async function test11_PutBackupInvalidNonce(): Promise<boolean> {
  console.log('\nTest 11: PUT /backup/contacts validates nonce length');

  let client: WsClient | null = null;

  try {
    client = await createClient();
    await registerClient(client);

    const invalidNonce = Buffer.alloc(16).toString('base64'); // Wrong size
    const ciphertext = Buffer.from('test').toString('base64');

    const resp = await httpRequest(
      'PUT',
      '/backup/contacts',
      { nonce: invalidNonce, ciphertext },
      { Authorization: `Bearer ${client.sessionToken}` }
    );

    if (resp.status !== 400) {
      throw new Error(`Expected 400, got ${resp.status}`);
    }

    console.log('  Got 400 for invalid nonce');
    console.log('  PASSED');
    return true;
  } catch (e) {
    console.log('  FAILED:', e);
    return false;
  } finally {
    client?.close();
  }
}

async function test12_PutBackup401(): Promise<boolean> {
  console.log('\nTest 12: PUT /backup/contacts 401 without auth');

  try {
    const nonce = generateNonce();
    const ciphertext = Buffer.from('test').toString('base64');

    const resp = await httpRequest(
      'PUT',
      '/backup/contacts',
      { nonce, ciphertext }
    );

    if (resp.status !== 401) {
      throw new Error(`Expected 401, got ${resp.status}`);
    }

    console.log('  Got 401 as expected');
    console.log('  PASSED');
    return true;
  } catch (e) {
    console.log('  FAILED:', e);
    return false;
  }
}

// =============================================================================
// MAIN
// =============================================================================

async function runTests(): Promise<void> {
  console.log('========================================');
  console.log('Step 3: Contacts + Key Lookup + Backup');
  console.log('WS Server:', WS_URL);
  console.log('HTTP Server:', HTTP_URL);
  console.log('========================================');

  const results: { name: string; passed: boolean }[] = [];

  // Run tests sequentially
  results.push({ name: 'Test 1: Key lookup active user', passed: await test1_KeyLookupActive() });
  await new Promise(resolve => setTimeout(resolve, 300));

  results.push({ name: 'Test 2: Key lookup 404', passed: await test2_KeyLookup404() });
  await new Promise(resolve => setTimeout(resolve, 300));

  results.push({ name: 'Test 3: Key lookup 400', passed: await test3_KeyLookup400() });
  await new Promise(resolve => setTimeout(resolve, 300));

  results.push({ name: 'Test 4: Key lookup 401', passed: await test4_KeyLookup401() });
  await new Promise(resolve => setTimeout(resolve, 300));

  results.push({ name: 'Test 5: PUT backup create', passed: await test5_PutBackupCreate() });
  await new Promise(resolve => setTimeout(resolve, 300));

  results.push({ name: 'Test 6: PUT backup replace', passed: await test6_PutBackupReplace() });
  await new Promise(resolve => setTimeout(resolve, 300));

  results.push({ name: 'Test 7: GET backup', passed: await test7_GetBackup() });
  await new Promise(resolve => setTimeout(resolve, 300));

  results.push({ name: 'Test 8: GET backup 404', passed: await test8_GetBackup404() });
  await new Promise(resolve => setTimeout(resolve, 300));

  results.push({ name: 'Test 9: DELETE backup', passed: await test9_DeleteBackup() });
  await new Promise(resolve => setTimeout(resolve, 300));

  results.push({ name: 'Test 10: DELETE backup 404', passed: await test10_DeleteBackup404() });

  // Longer delay before test 11 to avoid rate limit on registrations
  // IP rate limit is 10 per 60 seconds, tests 1-10 used ~10 registrations
  console.log('\n  (waiting 60s for rate limit window to reset...)');
  await new Promise(resolve => setTimeout(resolve, 60000));

  results.push({ name: 'Test 11: PUT backup invalid nonce', passed: await test11_PutBackupInvalidNonce() });
  await new Promise(resolve => setTimeout(resolve, 300));

  results.push({ name: 'Test 12: PUT backup 401', passed: await test12_PutBackup401() });

  // Summary
  console.log('\n========================================');
  console.log('RESULTS SUMMARY');
  console.log('========================================');

  let passed = 0;
  let failed = 0;

  for (const r of results) {
    const status = r.passed ? 'PASSED' : 'FAILED';
    console.log(`  ${status}: ${r.name}`);
    if (r.passed) passed++;
    else failed++;
  }

  console.log('----------------------------------------');
  console.log(`Total: ${passed}/${results.length} passed`);

  process.exit(failed > 0 ? 1 : 0);
}

runTests().catch((e) => {
  console.error('Test runner error:', e);
  process.exit(1);
});
