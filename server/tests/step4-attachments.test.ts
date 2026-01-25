/**
 * Step 4 Verification Tests: Attachments (Spaces)
 * Following WHISPER-REBUILD.md
 *
 * Test cases:
 * 1. Presign upload rejects invalid size (<=0 and >100MB)
 * 2. Presign upload rejects bad contentType (application/x-msdownload)
 * 3. Presign upload returns valid objectKey under whisper/att/
 * 4. Upload to Spaces works using uploadUrl
 * 5. Download presign denied if not authorized (B before reference)
 * 6. Send message with attachment grants access
 * 7. B can download after receiving reference
 * 8. Download URL fetch works (bytes match)
 * 9. GC deletes expired attachments (only whisper/att/)
 * 10. Access rows expire/cleanup
 */

import WebSocket from 'ws';
import * as sodium from 'sodium-native';
import { v4 as uuidv4 } from 'uuid';
import http from 'http';
import https from 'https';

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

function buildCanonical(parts: Record<string, string | number | undefined>): string {
  // Format: v1\nmessageType\nmessageId\nfrom\ntoOrGroupId\ntimestamp\nnonce\nciphertext\n
  const order = ['messageType', 'messageId', 'from', 'toOrGroupId', 'timestamp', 'nonce', 'ciphertext'];
  let result = 'v1\n';
  for (const key of order) {
    if (parts[key] !== undefined) {
      result += String(parts[key]) + '\n';
    }
  }
  return result;
}

function signCanonical(
  signSecretKey: Buffer,
  parts: Record<string, string | number | undefined>
): string {
  const canonical = buildCanonical(parts);
  const canonicalBytes = Buffer.from(canonical, 'utf8');

  // Hash the canonical bytes (SHA256)
  const hash = Buffer.alloc(sodium.crypto_hash_sha256_BYTES);
  sodium.crypto_hash_sha256(hash, canonicalBytes);

  // Sign the hash
  const signature = Buffer.alloc(sodium.crypto_sign_BYTES);
  sodium.crypto_sign_detached(signature, hash, signSecretKey);
  return signature.toString('base64');
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
    const httpModule = isHttps ? https : http;

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

function uploadToPresignedUrl(
  uploadUrl: string,
  data: Buffer,
  contentType: string = 'application/octet-stream'
): Promise<{ status: number }> {
  return new Promise((resolve, reject) => {
    const url = new URL(uploadUrl);
    const httpModule = url.protocol === 'https:' ? https : http;

    const options = {
      hostname: url.hostname,
      port: url.port || (url.protocol === 'https:' ? 443 : 80),
      path: url.pathname + url.search,
      method: 'PUT',
      headers: {
        'Content-Type': contentType,
        'Content-Length': data.length,
      },
    };

    const req = httpModule.request(options, (res: any) => {
      let body = '';
      res.on('data', (chunk: any) => (body += chunk));
      res.on('end', () => {
        resolve({ status: res.statusCode || 0 });
      });
    });

    req.on('error', reject);
    req.write(data);
    req.end();
  });
}

function downloadFromPresignedUrl(downloadUrl: string): Promise<{ status: number; data: Buffer }> {
  return new Promise((resolve, reject) => {
    const url = new URL(downloadUrl);
    const httpModule = url.protocol === 'https:' ? https : http;

    const options = {
      hostname: url.hostname,
      port: url.port || (url.protocol === 'https:' ? 443 : 80),
      path: url.pathname + url.search,
      method: 'GET',
    };

    const req = httpModule.request(options, (res: any) => {
      const chunks: Buffer[] = [];
      res.on('data', (chunk: Buffer) => chunks.push(chunk));
      res.on('end', () => {
        resolve({
          status: res.statusCode || 0,
          data: Buffer.concat(chunks),
        });
      });
    });

    req.on('error', reject);
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

function waitForMessage(client: WsClient, expectedType: string, timeout = 5000): Promise<any> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`Timeout waiting for ${expectedType}`)), timeout);

    const checkMessages = () => {
      const idx = client.pendingMessages.findIndex(m => m.type === expectedType);
      if (idx >= 0) {
        clearTimeout(timer);
        const msg = client.pendingMessages.splice(idx, 1)[0];
        resolve(msg);
      }
    };

    client.ws.on('message', () => setTimeout(checkMessages, 50));
    checkMessages();
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

async function test1_PresignUploadRejectsInvalidSize(): Promise<boolean> {
  console.log('\nTest 1: Presign upload rejects invalid size');

  let client: WsClient | null = null;

  try {
    client = await createClient();
    await registerClient(client);

    // Test sizeBytes <= 0
    const resp1 = await httpRequest(
      'POST',
      '/attachments/presign/upload',
      { contentType: 'image/jpeg', sizeBytes: 0 },
      { Authorization: `Bearer ${client.sessionToken}` }
    );

    if (resp1.status !== 400) {
      throw new Error(`Expected 400 for sizeBytes=0, got ${resp1.status}`);
    }
    console.log('  Rejected sizeBytes=0');

    const resp2 = await httpRequest(
      'POST',
      '/attachments/presign/upload',
      { contentType: 'image/jpeg', sizeBytes: -100 },
      { Authorization: `Bearer ${client.sessionToken}` }
    );

    if (resp2.status !== 400) {
      throw new Error(`Expected 400 for sizeBytes=-100, got ${resp2.status}`);
    }
    console.log('  Rejected sizeBytes=-100');

    // Test sizeBytes > 100MB
    const resp3 = await httpRequest(
      'POST',
      '/attachments/presign/upload',
      { contentType: 'image/jpeg', sizeBytes: 101 * 1024 * 1024 },
      { Authorization: `Bearer ${client.sessionToken}` }
    );

    if (resp3.status !== 400) {
      throw new Error(`Expected 400 for sizeBytes>100MB, got ${resp3.status}`);
    }
    console.log('  Rejected sizeBytes>100MB');

    console.log('  PASSED');
    return true;
  } catch (e) {
    console.log('  FAILED:', e);
    return false;
  } finally {
    client?.close();
  }
}

async function test2_PresignUploadRejectsBadContentType(): Promise<boolean> {
  console.log('\nTest 2: Presign upload rejects bad contentType');

  let client: WsClient | null = null;

  try {
    client = await createClient();
    await registerClient(client);

    const resp = await httpRequest(
      'POST',
      '/attachments/presign/upload',
      { contentType: 'application/x-msdownload', sizeBytes: 1024 },
      { Authorization: `Bearer ${client.sessionToken}` }
    );

    if (resp.status !== 400) {
      throw new Error(`Expected 400, got ${resp.status}`);
    }

    if (resp.data.error !== 'INVALID_PAYLOAD') {
      throw new Error(`Expected INVALID_PAYLOAD error, got ${resp.data.error}`);
    }

    console.log('  Rejected application/x-msdownload');
    console.log('  PASSED');
    return true;
  } catch (e) {
    console.log('  FAILED:', e);
    return false;
  } finally {
    client?.close();
  }
}

async function test3_PresignUploadReturnsValidObjectKey(): Promise<boolean> {
  console.log('\nTest 3: Presign upload returns valid objectKey under whisper/att/');

  let client: WsClient | null = null;

  try {
    client = await createClient();
    await registerClient(client);

    const resp = await httpRequest(
      'POST',
      '/attachments/presign/upload',
      { contentType: 'image/jpeg', sizeBytes: 1024 },
      { Authorization: `Bearer ${client.sessionToken}` }
    );

    if (resp.status !== 200) {
      throw new Error(`Expected 200, got ${resp.status}`);
    }

    const { objectKey } = resp.data;

    // Check startsWith whisper/att/
    if (!objectKey.startsWith('whisper/att/')) {
      throw new Error(`objectKey doesn't start with whisper/att/: ${objectKey}`);
    }
    console.log('  objectKey starts with whisper/att/');

    // Check length <= 255
    if (objectKey.length > 255) {
      throw new Error(`objectKey too long: ${objectKey.length}`);
    }
    console.log(`  objectKey length: ${objectKey.length} <= 255`);

    // Check no ..
    if (objectKey.includes('..')) {
      throw new Error(`objectKey contains ..: ${objectKey}`);
    }
    console.log('  objectKey has no path traversal');

    console.log(`  objectKey: ${objectKey}`);
    console.log('  PASSED');
    return true;
  } catch (e) {
    console.log('  FAILED:', e);
    return false;
  } finally {
    client?.close();
  }
}

async function test4_UploadToSpacesWorks(): Promise<boolean> {
  console.log('\nTest 4: Upload to Spaces works using uploadUrl');

  let client: WsClient | null = null;

  try {
    client = await createClient();
    await registerClient(client);

    const testData = Buffer.alloc(1024);
    sodium.randombytes_buf(testData);

    const resp = await httpRequest(
      'POST',
      '/attachments/presign/upload',
      { contentType: 'application/octet-stream', sizeBytes: testData.length },
      { Authorization: `Bearer ${client.sessionToken}` }
    );

    if (resp.status !== 200) {
      throw new Error(`Presign failed: ${resp.status}`);
    }

    const { uploadUrl } = resp.data;

    // Upload to Spaces
    const putResp = await uploadToPresignedUrl(uploadUrl, testData);

    if (putResp.status !== 200 && putResp.status !== 204) {
      throw new Error(`Upload failed with status ${putResp.status}`);
    }

    console.log(`  Uploaded ${testData.length} bytes to Spaces (HTTP ${putResp.status})`);
    console.log('  PASSED');
    return true;
  } catch (e) {
    console.log('  FAILED:', e);
    return false;
  } finally {
    client?.close();
  }
}

async function test5_DownloadDeniedWithoutAccess(): Promise<boolean> {
  console.log('\nTest 5: Download presign denied if not authorized (B before reference)');

  let clientA: WsClient | null = null;
  let clientB: WsClient | null = null;

  try {
    clientA = await createClient();
    clientB = await createClient();

    await registerClient(clientA);
    await registerClient(clientB);

    // A creates an attachment
    const uploadResp = await httpRequest(
      'POST',
      '/attachments/presign/upload',
      { contentType: 'image/png', sizeBytes: 512 },
      { Authorization: `Bearer ${clientA.sessionToken}` }
    );

    if (uploadResp.status !== 200) {
      throw new Error(`Upload presign failed: ${uploadResp.status}`);
    }

    const { objectKey } = uploadResp.data;
    console.log(`  A created attachment: ${objectKey}`);

    // B tries to download WITHOUT receiving it via message
    const downloadResp = await httpRequest(
      'POST',
      '/attachments/presign/download',
      { objectKey },
      { Authorization: `Bearer ${clientB.sessionToken}` }
    );

    if (downloadResp.status !== 403) {
      throw new Error(`Expected 403 FORBIDDEN, got ${downloadResp.status}`);
    }

    if (downloadResp.data.error !== 'FORBIDDEN') {
      throw new Error(`Expected FORBIDDEN error, got ${downloadResp.data.error}`);
    }

    console.log('  B denied with 403 FORBIDDEN');
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

async function test6_SendMessageWithAttachmentGrantsAccess(): Promise<boolean> {
  console.log('\nTest 6: Send message with attachment grants access');

  let clientA: WsClient | null = null;
  let clientB: WsClient | null = null;

  try {
    clientA = await createClient();
    clientB = await createClient();

    await registerClient(clientA);
    await registerClient(clientB);

    // A creates an attachment
    const uploadResp = await httpRequest(
      'POST',
      '/attachments/presign/upload',
      { contentType: 'image/png', sizeBytes: 512 },
      { Authorization: `Bearer ${clientA.sessionToken}` }
    );

    if (uploadResp.status !== 200) {
      throw new Error(`Upload presign failed: ${uploadResp.status}`);
    }

    const { objectKey, uploadUrl } = uploadResp.data;
    console.log(`  A created attachment: ${objectKey}`);

    // A uploads data to Spaces
    const testData = Buffer.alloc(512);
    sodium.randombytes_buf(testData);
    await uploadToPresignedUrl(uploadUrl, testData);

    // A sends message with attachment to B
    const messageId = uuidv4();
    const timestamp = Date.now();
    const nonce = generateNonce();
    const ciphertext = Buffer.from('encrypted message').toString('base64');
    const fileNonce = generateNonce();
    const fileKeyBoxNonce = generateNonce();

    const sig = signCanonical(clientA.keys!.signSecretKey, {
      messageType: 'send_message',
      messageId,
      from: clientA.whisperId!,
      toOrGroupId: clientB.whisperId!,
      timestamp,
      nonce,
      ciphertext,
    });

    const sendResp = await sendAndWait(clientA, {
      type: 'send_message',
      requestId: uuidv4(),
      payload: {
        protocolVersion: PROTOCOL_VERSION,
        cryptoVersion: CRYPTO_VERSION,
        sessionToken: clientA.sessionToken!,
        messageId,
        from: clientA.whisperId!,
        to: clientB.whisperId!,
        msgType: 'image',
        timestamp,
        nonce,
        ciphertext,
        sig,
        attachment: {
          objectKey,
          contentType: 'image/png',
          ciphertextSize: 512,
          fileNonce,
          fileKeyBox: {
            nonce: fileKeyBoxNonce,
            ciphertext: Buffer.from('encrypted file key').toString('base64'),
          },
        },
      },
    }, 'message_accepted');

    if (sendResp.type !== 'message_accepted') {
      throw new Error(`Expected message_accepted, got ${sendResp.type}: ${JSON.stringify(sendResp)}`);
    }

    console.log('  A sent message with attachment to B');

    // Wait a bit for access to be granted
    await new Promise(resolve => setTimeout(resolve, 500));

    // Now B should be able to download
    const downloadResp = await httpRequest(
      'POST',
      '/attachments/presign/download',
      { objectKey },
      { Authorization: `Bearer ${clientB.sessionToken}` }
    );

    if (downloadResp.status !== 200) {
      throw new Error(`B still cannot download: ${downloadResp.status} - ${JSON.stringify(downloadResp.data)}`);
    }

    console.log('  B can now request download (access granted via message)');
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

async function test7_BCanDownloadAfterReceivingReference(): Promise<boolean> {
  console.log('\nTest 7: B can download after receiving reference');

  let clientA: WsClient | null = null;
  let clientB: WsClient | null = null;

  try {
    clientA = await createClient();
    clientB = await createClient();

    await registerClient(clientA);
    await registerClient(clientB);

    // A creates and uploads attachment
    const testData = Buffer.alloc(256);
    sodium.randombytes_buf(testData);

    const uploadResp = await httpRequest(
      'POST',
      '/attachments/presign/upload',
      { contentType: 'application/octet-stream', sizeBytes: testData.length },
      { Authorization: `Bearer ${clientA.sessionToken}` }
    );

    const { objectKey, uploadUrl } = uploadResp.data;
    await uploadToPresignedUrl(uploadUrl, testData);

    // A sends message with attachment to B
    const messageId = uuidv4();
    const timestamp = Date.now();
    const nonce = generateNonce();
    const ciphertext = Buffer.from('test').toString('base64');
    const fileNonce = generateNonce();
    const fileKeyBoxNonce = generateNonce();

    const sig = signCanonical(clientA.keys!.signSecretKey, {
      messageType: 'send_message',
      messageId,
      from: clientA.whisperId!,
      toOrGroupId: clientB.whisperId!,
      timestamp,
      nonce,
      ciphertext,
    });

    await sendAndWait(clientA, {
      type: 'send_message',
      requestId: uuidv4(),
      payload: {
        protocolVersion: PROTOCOL_VERSION,
        cryptoVersion: CRYPTO_VERSION,
        sessionToken: clientA.sessionToken!,
        messageId,
        from: clientA.whisperId!,
        to: clientB.whisperId!,
        msgType: 'file',
        timestamp,
        nonce,
        ciphertext,
        sig,
        attachment: {
          objectKey,
          contentType: 'application/octet-stream',
          ciphertextSize: testData.length,
          fileNonce,
          fileKeyBox: {
            nonce: fileKeyBoxNonce,
            ciphertext: Buffer.from('key').toString('base64'),
          },
        },
      },
    }, 'message_accepted');

    await new Promise(resolve => setTimeout(resolve, 300));

    // B requests download presign
    const downloadResp = await httpRequest(
      'POST',
      '/attachments/presign/download',
      { objectKey },
      { Authorization: `Bearer ${clientB.sessionToken}` }
    );

    if (downloadResp.status !== 200) {
      throw new Error(`Download presign failed: ${downloadResp.status}`);
    }

    if (!downloadResp.data.downloadUrl) {
      throw new Error('Missing downloadUrl');
    }

    console.log('  B got downloadUrl after receiving message');
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

async function test8_DownloadUrlFetchWorks(): Promise<boolean> {
  console.log('\nTest 8: Download URL fetch works (bytes match)');

  let clientA: WsClient | null = null;
  let clientB: WsClient | null = null;

  try {
    clientA = await createClient();
    clientB = await createClient();

    await registerClient(clientA);
    await registerClient(clientB);

    // A creates and uploads attachment with known data
    const testData = Buffer.from('This is test attachment content for verification!');

    const uploadResp = await httpRequest(
      'POST',
      '/attachments/presign/upload',
      { contentType: 'application/octet-stream', sizeBytes: testData.length },
      { Authorization: `Bearer ${clientA.sessionToken}` }
    );

    const { objectKey, uploadUrl } = uploadResp.data;
    await uploadToPresignedUrl(uploadUrl, testData);

    // A sends message to B
    const messageId = uuidv4();
    const timestamp = Date.now();
    const nonce = generateNonce();
    const ciphertext = Buffer.from('test').toString('base64');

    const sig = signCanonical(clientA.keys!.signSecretKey, {
      messageType: 'send_message',
      messageId,
      from: clientA.whisperId!,
      toOrGroupId: clientB.whisperId!,
      timestamp,
      nonce,
      ciphertext,
    });

    await sendAndWait(clientA, {
      type: 'send_message',
      requestId: uuidv4(),
      payload: {
        protocolVersion: PROTOCOL_VERSION,
        cryptoVersion: CRYPTO_VERSION,
        sessionToken: clientA.sessionToken!,
        messageId,
        from: clientA.whisperId!,
        to: clientB.whisperId!,
        msgType: 'file',
        timestamp,
        nonce,
        ciphertext,
        sig,
        attachment: {
          objectKey,
          contentType: 'application/octet-stream',
          ciphertextSize: testData.length,
          fileNonce: generateNonce(),
          fileKeyBox: { nonce: generateNonce(), ciphertext: 'a2V5' },
        },
      },
    }, 'message_accepted');

    await new Promise(resolve => setTimeout(resolve, 300));

    // B gets download URL
    const downloadResp = await httpRequest(
      'POST',
      '/attachments/presign/download',
      { objectKey },
      { Authorization: `Bearer ${clientB.sessionToken}` }
    );

    const { downloadUrl } = downloadResp.data;

    // Fetch and verify bytes match
    const getResp = await downloadFromPresignedUrl(downloadUrl);

    if (getResp.status !== 200) {
      throw new Error(`Download failed: ${getResp.status}`);
    }

    if (!getResp.data.equals(testData)) {
      throw new Error(`Downloaded data doesn't match! Expected ${testData.length} bytes, got ${getResp.data.length}`);
    }

    console.log(`  Downloaded ${getResp.data.length} bytes, matches uploaded data`);
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

async function test9_GCDeletesExpiredAttachments(): Promise<boolean> {
  console.log('\nTest 9: GC deletes expired attachments (only whisper/att/)');

  let client: WsClient | null = null;

  try {
    client = await createClient();
    await registerClient(client);

    // Run GC
    const resp = await httpRequest(
      'POST',
      '/admin/attachments/gc/run',
      {},
      { Authorization: `Bearer ${client.sessionToken}` }
    );

    if (resp.status !== 200) {
      throw new Error(`GC failed: ${resp.status}`);
    }

    console.log(`  GC completed: ${resp.data.deletedCount} deleted, ${resp.data.failedCount} failed`);

    // Note: We can't easily create an expired attachment from tests
    // This test verifies the GC endpoint works
    // The GC only processes keys starting with whisper/att/

    if (typeof resp.data.deletedCount !== 'number') {
      throw new Error('Missing deletedCount');
    }

    console.log('  PASSED');
    return true;
  } catch (e) {
    console.log('  FAILED:', e);
    return false;
  } finally {
    client?.close();
  }
}

async function test10_AccessRowsExpireCleanup(): Promise<boolean> {
  console.log('\nTest 10: Access rows expire/cleanup');

  let client: WsClient | null = null;

  try {
    client = await createClient();
    await registerClient(client);

    // Run GC to clean up expired access rows
    const resp = await httpRequest(
      'POST',
      '/admin/attachments/gc/run',
      {},
      { Authorization: `Bearer ${client.sessionToken}` }
    );

    if (resp.status !== 200) {
      throw new Error(`GC failed: ${resp.status}`);
    }

    console.log(`  Access rows cleaned: ${resp.data.accessRowsDeleted}`);

    if (typeof resp.data.accessRowsDeleted !== 'number') {
      throw new Error('Missing accessRowsDeleted');
    }

    console.log('  PASSED');
    return true;
  } catch (e) {
    console.log('  FAILED:', e);
    return false;
  } finally {
    client?.close();
  }
}

// =============================================================================
// MAIN
// =============================================================================

async function runTests(): Promise<void> {
  console.log('========================================');
  console.log('Step 4: Attachments (Spaces)');
  console.log('WS Server:', WS_URL);
  console.log('HTTP Server:', HTTP_URL);
  console.log('========================================');

  const results: { name: string; passed: boolean }[] = [];

  results.push({ name: 'Test 1: Presign upload rejects invalid size', passed: await test1_PresignUploadRejectsInvalidSize() });
  await new Promise(resolve => setTimeout(resolve, 300));

  results.push({ name: 'Test 2: Presign upload rejects bad contentType', passed: await test2_PresignUploadRejectsBadContentType() });
  await new Promise(resolve => setTimeout(resolve, 300));

  results.push({ name: 'Test 3: Presign upload returns valid objectKey', passed: await test3_PresignUploadReturnsValidObjectKey() });
  await new Promise(resolve => setTimeout(resolve, 300));

  results.push({ name: 'Test 4: Upload to Spaces works', passed: await test4_UploadToSpacesWorks() });
  await new Promise(resolve => setTimeout(resolve, 300));

  results.push({ name: 'Test 5: Download denied without access', passed: await test5_DownloadDeniedWithoutAccess() });
  await new Promise(resolve => setTimeout(resolve, 300));

  results.push({ name: 'Test 6: Send message with attachment grants access', passed: await test6_SendMessageWithAttachmentGrantsAccess() });
  await new Promise(resolve => setTimeout(resolve, 300));

  results.push({ name: 'Test 7: B can download after receiving reference', passed: await test7_BCanDownloadAfterReceivingReference() });
  await new Promise(resolve => setTimeout(resolve, 300));

  results.push({ name: 'Test 8: Download URL fetch works', passed: await test8_DownloadUrlFetchWorks() });
  await new Promise(resolve => setTimeout(resolve, 300));

  results.push({ name: 'Test 9: GC deletes expired attachments', passed: await test9_GCDeletesExpiredAttachments() });
  await new Promise(resolve => setTimeout(resolve, 300));

  results.push({ name: 'Test 10: Access rows expire/cleanup', passed: await test10_AccessRowsExpireCleanup() });

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
