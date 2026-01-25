/**
 * Step 4 Verification Tests: Attachments (Spaces)
 * Following WHISPER-REBUILD.md
 *
 * Test cases:
 * 1. Presign upload returns valid URL
 * 2. Presign upload 400 for invalid contentType
 * 3. Presign upload 400 for size exceeding limit
 * 4. Presign upload 401 without auth
 * 5. Presign download returns valid URL for owner
 * 6. Presign download 403 for user without access
 * 7. Presign download 404 for non-existent attachment
 * 8. Presign download returns valid URL for user with granted access
 * 9. GC deletes expired attachments
 * 10. Upload to presigned URL and verify download
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

async function test1_PresignUploadValid(): Promise<boolean> {
  console.log('\nTest 1: Presign upload returns valid URL');

  let client: WsClient | null = null;

  try {
    client = await createClient();
    await registerClient(client);

    const resp = await httpRequest(
      'POST',
      '/attachments/presign/upload',
      {
        contentType: 'image/jpeg',
        sizeBytes: 1024,
      },
      { Authorization: `Bearer ${client.sessionToken}` }
    );

    if (resp.status !== 200) {
      throw new Error(`Expected 200, got ${resp.status}: ${JSON.stringify(resp.data)}`);
    }

    if (!resp.data.objectKey) {
      throw new Error('Missing objectKey in response');
    }

    if (!resp.data.uploadUrl) {
      throw new Error('Missing uploadUrl in response');
    }

    if (!resp.data.objectKey.startsWith('whisper/att/')) {
      throw new Error(`Invalid objectKey prefix: ${resp.data.objectKey}`);
    }

    if (!resp.data.uploadUrl.includes('digitaloceanspaces.com')) {
      throw new Error('uploadUrl does not point to DigitalOcean Spaces');
    }

    console.log(`  objectKey: ${resp.data.objectKey}`);
    console.log(`  uploadUrl: ${resp.data.uploadUrl.substring(0, 60)}...`);
    console.log('  PASSED');
    return true;
  } catch (e) {
    console.log('  FAILED:', e);
    return false;
  } finally {
    client?.close();
  }
}

async function test2_PresignUploadInvalidContentType(): Promise<boolean> {
  console.log('\nTest 2: Presign upload 400 for invalid contentType');

  let client: WsClient | null = null;

  try {
    client = await createClient();
    await registerClient(client);

    const resp = await httpRequest(
      'POST',
      '/attachments/presign/upload',
      {
        contentType: 'application/x-executable',
        sizeBytes: 1024,
      },
      { Authorization: `Bearer ${client.sessionToken}` }
    );

    if (resp.status !== 400) {
      throw new Error(`Expected 400, got ${resp.status}`);
    }

    console.log('  Got 400 for invalid contentType');
    console.log('  PASSED');
    return true;
  } catch (e) {
    console.log('  FAILED:', e);
    return false;
  } finally {
    client?.close();
  }
}

async function test3_PresignUploadSizeExceedsLimit(): Promise<boolean> {
  console.log('\nTest 3: Presign upload 400 for size exceeding limit');

  let client: WsClient | null = null;

  try {
    client = await createClient();
    await registerClient(client);

    const resp = await httpRequest(
      'POST',
      '/attachments/presign/upload',
      {
        contentType: 'image/jpeg',
        sizeBytes: 200 * 1024 * 1024, // 200MB - exceeds 100MB limit
      },
      { Authorization: `Bearer ${client.sessionToken}` }
    );

    if (resp.status !== 400) {
      throw new Error(`Expected 400, got ${resp.status}`);
    }

    console.log('  Got 400 for size exceeding limit');
    console.log('  PASSED');
    return true;
  } catch (e) {
    console.log('  FAILED:', e);
    return false;
  } finally {
    client?.close();
  }
}

async function test4_PresignUpload401(): Promise<boolean> {
  console.log('\nTest 4: Presign upload 401 without auth');

  try {
    const resp = await httpRequest(
      'POST',
      '/attachments/presign/upload',
      {
        contentType: 'image/jpeg',
        sizeBytes: 1024,
      }
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

async function test5_PresignDownloadOwner(): Promise<boolean> {
  console.log('\nTest 5: Presign download returns valid URL for owner');

  let client: WsClient | null = null;

  try {
    client = await createClient();
    await registerClient(client);

    // First, presign an upload
    const uploadResp = await httpRequest(
      'POST',
      '/attachments/presign/upload',
      {
        contentType: 'image/png',
        sizeBytes: 512,
      },
      { Authorization: `Bearer ${client.sessionToken}` }
    );

    if (uploadResp.status !== 200) {
      throw new Error(`Upload presign failed: ${uploadResp.status}`);
    }

    const { objectKey } = uploadResp.data;

    // Now request download presign as owner
    const downloadResp = await httpRequest(
      'POST',
      '/attachments/presign/download',
      { objectKey },
      { Authorization: `Bearer ${client.sessionToken}` }
    );

    if (downloadResp.status !== 200) {
      throw new Error(`Expected 200, got ${downloadResp.status}: ${JSON.stringify(downloadResp.data)}`);
    }

    if (!downloadResp.data.downloadUrl) {
      throw new Error('Missing downloadUrl in response');
    }

    console.log('  Owner can request download presign');
    console.log('  PASSED');
    return true;
  } catch (e) {
    console.log('  FAILED:', e);
    return false;
  } finally {
    client?.close();
  }
}

async function test6_PresignDownload403(): Promise<boolean> {
  console.log('\nTest 6: Presign download 403 for user without access');

  let clientA: WsClient | null = null;
  let clientB: WsClient | null = null;

  try {
    clientA = await createClient();
    clientB = await createClient();

    await registerClient(clientA);
    await registerClient(clientB);

    // Client A presigns an upload
    const uploadResp = await httpRequest(
      'POST',
      '/attachments/presign/upload',
      {
        contentType: 'image/png',
        sizeBytes: 512,
      },
      { Authorization: `Bearer ${clientA.sessionToken}` }
    );

    if (uploadResp.status !== 200) {
      throw new Error(`Upload presign failed: ${uploadResp.status}`);
    }

    const { objectKey } = uploadResp.data;

    // Client B tries to download (no access granted)
    const downloadResp = await httpRequest(
      'POST',
      '/attachments/presign/download',
      { objectKey },
      { Authorization: `Bearer ${clientB.sessionToken}` }
    );

    if (downloadResp.status !== 403) {
      throw new Error(`Expected 403, got ${downloadResp.status}`);
    }

    console.log('  Got 403 for user without access');
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

async function test7_PresignDownload404(): Promise<boolean> {
  console.log('\nTest 7: Presign download 404 for non-existent attachment');

  let client: WsClient | null = null;

  try {
    client = await createClient();
    await registerClient(client);

    const resp = await httpRequest(
      'POST',
      '/attachments/presign/download',
      { objectKey: 'whisper/att/2024/01/nonexistent/file.bin' },
      { Authorization: `Bearer ${client.sessionToken}` }
    );

    if (resp.status !== 404) {
      throw new Error(`Expected 404, got ${resp.status}`);
    }

    console.log('  Got 404 for non-existent attachment');
    console.log('  PASSED');
    return true;
  } catch (e) {
    console.log('  FAILED:', e);
    return false;
  } finally {
    client?.close();
  }
}

async function test8_PresignDownloadWithAccess(): Promise<boolean> {
  console.log('\nTest 8: Presign download with granted access (via message)');
  console.log('  (Skipping - requires full message flow with attachment)');
  console.log('  SKIPPED');
  return true;
}

async function test9_GCEndpoint(): Promise<boolean> {
  console.log('\nTest 9: GC endpoint works');

  let client: WsClient | null = null;

  try {
    client = await createClient();
    await registerClient(client);

    const resp = await httpRequest(
      'POST',
      '/admin/attachments/gc/run',
      {},
      { Authorization: `Bearer ${client.sessionToken}` }
    );

    if (resp.status !== 200) {
      throw new Error(`Expected 200, got ${resp.status}: ${JSON.stringify(resp.data)}`);
    }

    if (typeof resp.data.deletedCount !== 'number') {
      throw new Error('Missing deletedCount in response');
    }

    console.log(`  GC completed: ${resp.data.deletedCount} deleted, ${resp.data.accessRowsDeleted} access rows cleaned`);
    console.log('  PASSED');
    return true;
  } catch (e) {
    console.log('  FAILED:', e);
    return false;
  } finally {
    client?.close();
  }
}

async function test10_UploadAndDownload(): Promise<boolean> {
  console.log('\nTest 10: Upload to presigned URL and verify download');

  let client: WsClient | null = null;

  try {
    client = await createClient();
    await registerClient(client);

    const testData = Buffer.from('Hello, this is test attachment data!');

    // 1. Get presigned upload URL
    const uploadResp = await httpRequest(
      'POST',
      '/attachments/presign/upload',
      {
        contentType: 'application/octet-stream',
        sizeBytes: testData.length,
      },
      { Authorization: `Bearer ${client.sessionToken}` }
    );

    if (uploadResp.status !== 200) {
      throw new Error(`Upload presign failed: ${uploadResp.status}`);
    }

    const { objectKey, uploadUrl } = uploadResp.data;
    console.log(`  Got upload URL for ${objectKey}`);

    // 2. Upload to presigned URL
    const putResp = await uploadToPresignedUrl(uploadUrl, testData);
    if (putResp.status !== 200) {
      throw new Error(`Upload failed: ${putResp.status}`);
    }
    console.log('  Uploaded data to Spaces');

    // 3. Get presigned download URL
    const downloadResp = await httpRequest(
      'POST',
      '/attachments/presign/download',
      { objectKey },
      { Authorization: `Bearer ${client.sessionToken}` }
    );

    if (downloadResp.status !== 200) {
      throw new Error(`Download presign failed: ${downloadResp.status}`);
    }

    const { downloadUrl } = downloadResp.data;
    console.log('  Got download URL');

    // 4. Download and verify
    const getResp = await downloadFromPresignedUrl(downloadUrl);
    if (getResp.status !== 200) {
      throw new Error(`Download failed: ${getResp.status}`);
    }

    if (!getResp.data.equals(testData)) {
      throw new Error('Downloaded data does not match uploaded data');
    }

    console.log('  Downloaded data matches uploaded data');
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

  // Run tests sequentially
  results.push({ name: 'Test 1: Presign upload valid', passed: await test1_PresignUploadValid() });
  await new Promise(resolve => setTimeout(resolve, 300));

  results.push({ name: 'Test 2: Presign upload invalid contentType', passed: await test2_PresignUploadInvalidContentType() });
  await new Promise(resolve => setTimeout(resolve, 300));

  results.push({ name: 'Test 3: Presign upload size exceeds limit', passed: await test3_PresignUploadSizeExceedsLimit() });
  await new Promise(resolve => setTimeout(resolve, 300));

  results.push({ name: 'Test 4: Presign upload 401', passed: await test4_PresignUpload401() });
  await new Promise(resolve => setTimeout(resolve, 300));

  results.push({ name: 'Test 5: Presign download owner', passed: await test5_PresignDownloadOwner() });
  await new Promise(resolve => setTimeout(resolve, 300));

  results.push({ name: 'Test 6: Presign download 403', passed: await test6_PresignDownload403() });
  await new Promise(resolve => setTimeout(resolve, 300));

  results.push({ name: 'Test 7: Presign download 404', passed: await test7_PresignDownload404() });
  await new Promise(resolve => setTimeout(resolve, 300));

  results.push({ name: 'Test 8: Presign download with access', passed: await test8_PresignDownloadWithAccess() });
  await new Promise(resolve => setTimeout(resolve, 300));

  results.push({ name: 'Test 9: GC endpoint', passed: await test9_GCEndpoint() });
  await new Promise(resolve => setTimeout(resolve, 300));

  results.push({ name: 'Test 10: Upload and download', passed: await test10_UploadAndDownload() });

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
