/**
 * Step 5 Verification Tests: Groups (Pairwise Fanout)
 * Following WHISPER-REBUILD.md Step 5
 *
 * Test cases:
 * 1. Create group succeeds and emits group_event to all
 * 2. Create group rejects > MAX_GROUP_MEMBERS
 * 3. Non-member cannot send group message
 * 4. Member sends group message online delivery
 * 5. Offline member gets group message via pending
 * 6. Remove member blocks future messages
 * 7. Add member does not receive past messages
 * 8. Attachment group message grants access to recipients
 * 9. Removed member still cannot download future attachments
 * 10. Role enforcement
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

  const hash = Buffer.alloc(sodium.crypto_hash_sha256_BYTES);
  sodium.crypto_hash_sha256(hash, canonicalBytes);

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
    const requestId = msg.requestId;
    let resolved = false;

    const checkMessages = () => {
      if (resolved) return;
      // Match by requestId if available (check both top-level and in payload), otherwise by type
      const idx = client.pendingMessages.findIndex(m => {
        const msgRequestId = m.requestId || m.payload?.requestId;
        if (requestId && msgRequestId === requestId) return true;
        if (!requestId && (!expectedType || m.type === expectedType || m.type === 'error')) return true;
        return false;
      });
      if (idx >= 0) {
        resolved = true;
        clearTimeout(timer);
        const msg = client.pendingMessages.splice(idx, 1)[0];
        resolve(msg);
      }
    };

    const listener = () => setTimeout(checkMessages, 50);
    client.ws.on('message', listener);
    checkMessages();

    client.ws.send(JSON.stringify(msg));
  });
}

function waitForMessage(client: WsClient, expectedType: string, timeout = 5000): Promise<any> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`Timeout waiting for ${expectedType}`)), timeout);
    let resolved = false;

    const checkMessages = () => {
      if (resolved) return;
      const idx = client.pendingMessages.findIndex(m => m.type === expectedType);
      if (idx >= 0) {
        resolved = true;
        clearTimeout(timer);
        const msg = client.pendingMessages.splice(idx, 1)[0];
        resolve(msg);
      }
    };

    const listener = () => setTimeout(checkMessages, 50);
    client.ws.on('message', listener);
    checkMessages();
  });
}

async function registerClient(client: WsClient): Promise<void> {
  const keys = generateKeys();
  client.keys = keys;
  const deviceId = uuidv4();

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

async function test1_CreateGroupSucceeds(): Promise<boolean> {
  console.log('\nTest 1: Create group succeeds and emits group_event to all');

  let clientA: WsClient | null = null;
  let clientB: WsClient | null = null;
  let clientC: WsClient | null = null;

  try {
    clientA = await createClient();
    clientB = await createClient();
    clientC = await createClient();

    await registerClient(clientA);
    await registerClient(clientB);
    await registerClient(clientC);

    // A creates group with B, C
    clientA.ws.send(JSON.stringify({
      type: 'group_create',
      requestId: uuidv4(),
      payload: {
        protocolVersion: PROTOCOL_VERSION,
        cryptoVersion: CRYPTO_VERSION,
        sessionToken: clientA.sessionToken,
        title: 'Test Group',
        memberIds: [clientB.whisperId, clientC.whisperId],
      },
    }));

    // All should receive group_event
    const eventA = await waitForMessage(clientA, 'group_event');
    const eventB = await waitForMessage(clientB, 'group_event');
    const eventC = await waitForMessage(clientC, 'group_event');

    if (eventA.payload.event !== 'created') {
      throw new Error(`Expected 'created' event, got ${eventA.payload.event}`);
    }

    const group = eventA.payload.group;
    if (!group.groupId || !group.title || !group.members) {
      throw new Error('Missing group fields');
    }

    if (group.members.length !== 3) {
      throw new Error(`Expected 3 members, got ${group.members.length}`);
    }

    const ownerMember = group.members.find((m: any) => m.whisperId === clientA.whisperId);
    if (!ownerMember || ownerMember.role !== 'owner') {
      throw new Error('Creator should be owner');
    }

    console.log(`  Group created: ${group.groupId}`);
    console.log(`  Members: ${group.members.length}`);
    console.log('  All clients received group_event');
    console.log('  PASSED');
    return true;
  } catch (e) {
    console.log('  FAILED:', e);
    return false;
  } finally {
    clientA?.close();
    clientB?.close();
    clientC?.close();
  }
}

async function test2_CreateGroupRejectsMaxMembers(): Promise<boolean> {
  console.log('\nTest 2: Create group rejects > MAX_GROUP_MEMBERS');

  let client: WsClient | null = null;

  try {
    client = await createClient();
    await registerClient(client);

    // Try to create with 51 fake members (valid pattern but exceed MAX_GROUP_MEMBERS)
    // Pattern requires [A-Z2-7] only, so we use letters and valid digits
    const fakeMembers = Array.from({ length: 51 }, (_, i) => {
      // Generate unique 4-char segments using only valid chars (A-Z, 2-7)
      const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
      const idx1 = i % chars.length;
      const idx2 = Math.floor(i / chars.length) % chars.length;
      return `WSP-TEST-FA${chars[idx1]}${chars[idx2]}-TEST`;
    });

    const resp = await sendAndWait(client, {
      type: 'group_create',
      requestId: uuidv4(),
      payload: {
        protocolVersion: PROTOCOL_VERSION,
        cryptoVersion: CRYPTO_VERSION,
        sessionToken: client.sessionToken,
        title: 'Big Group',
        memberIds: fakeMembers,
      },
    }, 'error');

    if (resp.type !== 'error') {
      throw new Error(`Expected error, got ${resp.type}`);
    }

    console.log(`  Rejected with error: ${resp.payload.code}`);
    console.log('  PASSED');
    return true;
  } catch (e) {
    console.log('  FAILED:', e);
    return false;
  } finally {
    client?.close();
  }
}

async function test3_NonMemberCannotSendGroupMessage(): Promise<boolean> {
  console.log('\nTest 3: Non-member cannot send group message');

  let clientA: WsClient | null = null;
  let clientB: WsClient | null = null;
  let clientD: WsClient | null = null;

  try {
    clientA = await createClient();
    clientB = await createClient();
    clientD = await createClient();

    await registerClient(clientA);
    await registerClient(clientB);
    await registerClient(clientD);

    // A creates group with B only (D not included)
    clientA.ws.send(JSON.stringify({
      type: 'group_create',
      requestId: uuidv4(),
      payload: {
        protocolVersion: PROTOCOL_VERSION,
        cryptoVersion: CRYPTO_VERSION,
        sessionToken: clientA.sessionToken,
        title: 'AB Group',
        memberIds: [clientB.whisperId],
      },
    }));

    const eventA = await waitForMessage(clientA, 'group_event');
    const groupId = eventA.payload.group.groupId;

    // D tries to send message to this group
    const messageId = uuidv4();
    const timestamp = Date.now();
    const nonce = generateNonce();
    const ciphertext = Buffer.from('test').toString('base64');

    const sig = signCanonical(clientD.keys!.signSecretKey, {
      messageType: 'group_send_message',
      messageId,
      from: clientD.whisperId!,
      toOrGroupId: groupId,
      timestamp,
      nonce,
      ciphertext,
    });

    const resp = await sendAndWait(clientD, {
      type: 'group_send_message',
      requestId: uuidv4(),
      payload: {
        protocolVersion: PROTOCOL_VERSION,
        cryptoVersion: CRYPTO_VERSION,
        sessionToken: clientD.sessionToken,
        groupId,
        messageId,
        from: clientD.whisperId,
        msgType: 'text',
        timestamp,
        recipients: [{
          to: clientB.whisperId,
          nonce,
          ciphertext,
          sig,
        }],
      },
    }, 'error');

    if (resp.type !== 'error' || resp.payload.code !== 'FORBIDDEN') {
      throw new Error(`Expected FORBIDDEN error, got ${resp.type}: ${JSON.stringify(resp.payload)}`);
    }

    console.log('  D (non-member) rejected with FORBIDDEN');
    console.log('  PASSED');
    return true;
  } catch (e) {
    console.log('  FAILED:', e);
    return false;
  } finally {
    clientA?.close();
    clientB?.close();
    clientD?.close();
  }
}

async function test4_MemberSendsGroupMessageOnline(): Promise<boolean> {
  console.log('\nTest 4: Member sends group message online delivery');

  let clientA: WsClient | null = null;
  let clientB: WsClient | null = null;
  let clientC: WsClient | null = null;

  try {
    clientA = await createClient();
    clientB = await createClient();
    clientC = await createClient();

    await registerClient(clientA);
    await registerClient(clientB);
    await registerClient(clientC);

    // A creates group with B, C
    clientA.ws.send(JSON.stringify({
      type: 'group_create',
      requestId: uuidv4(),
      payload: {
        protocolVersion: PROTOCOL_VERSION,
        cryptoVersion: CRYPTO_VERSION,
        sessionToken: clientA.sessionToken,
        title: 'Test Group',
        memberIds: [clientB.whisperId, clientC.whisperId],
      },
    }));

    const eventA = await waitForMessage(clientA, 'group_event');
    await waitForMessage(clientB, 'group_event');
    await waitForMessage(clientC, 'group_event');

    const groupId = eventA.payload.group.groupId;

    // A sends group message to B and C
    const messageId = uuidv4();
    const timestamp = Date.now();

    const nonceB = generateNonce();
    const ciphertextB = Buffer.from('Hello B').toString('base64');
    const sigB = signCanonical(clientA.keys!.signSecretKey, {
      messageType: 'group_send_message',
      messageId,
      from: clientA.whisperId!,
      toOrGroupId: groupId,
      timestamp,
      nonce: nonceB,
      ciphertext: ciphertextB,
    });

    const nonceC = generateNonce();
    const ciphertextC = Buffer.from('Hello C').toString('base64');
    const sigC = signCanonical(clientA.keys!.signSecretKey, {
      messageType: 'group_send_message',
      messageId,
      from: clientA.whisperId!,
      toOrGroupId: groupId,
      timestamp,
      nonce: nonceC,
      ciphertext: ciphertextC,
    });

    const sendResp = await sendAndWait(clientA, {
      type: 'group_send_message',
      requestId: uuidv4(),
      payload: {
        protocolVersion: PROTOCOL_VERSION,
        cryptoVersion: CRYPTO_VERSION,
        sessionToken: clientA.sessionToken,
        groupId,
        messageId,
        from: clientA.whisperId,
        msgType: 'text',
        timestamp,
        recipients: [
          { to: clientB.whisperId, nonce: nonceB, ciphertext: ciphertextB, sig: sigB },
          { to: clientC.whisperId, nonce: nonceC, ciphertext: ciphertextC, sig: sigC },
        ],
      },
    }, 'message_accepted');

    if (sendResp.type !== 'message_accepted') {
      throw new Error(`Expected message_accepted, got ${sendResp.type}: ${JSON.stringify(sendResp)}`);
    }

    // B and C should receive message_received with groupId
    const msgB = await waitForMessage(clientB, 'message_received');
    const msgC = await waitForMessage(clientC, 'message_received');

    if (msgB.payload.groupId !== groupId || msgC.payload.groupId !== groupId) {
      throw new Error('Missing groupId in message_received');
    }

    console.log('  A sent group message');
    console.log('  B received message_received with groupId');
    console.log('  C received message_received with groupId');
    console.log('  PASSED');
    return true;
  } catch (e) {
    console.log('  FAILED:', e);
    return false;
  } finally {
    clientA?.close();
    clientB?.close();
    clientC?.close();
  }
}

async function test5_OfflineMemberGetsGroupMessagePending(): Promise<boolean> {
  console.log('\nTest 5: Offline member gets group message via pending');

  let clientA: WsClient | null = null;
  let clientB: WsClient | null = null;
  let clientC: WsClient | null = null;

  try {
    clientA = await createClient();
    clientB = await createClient();
    clientC = await createClient();

    await registerClient(clientA);
    await registerClient(clientB);
    await registerClient(clientC);

    const clientCWhisperId = clientC.whisperId!;
    const clientCKeys = clientC.keys!;

    // A creates group with B, C
    clientA.ws.send(JSON.stringify({
      type: 'group_create',
      requestId: uuidv4(),
      payload: {
        protocolVersion: PROTOCOL_VERSION,
        cryptoVersion: CRYPTO_VERSION,
        sessionToken: clientA.sessionToken,
        title: 'Offline Test Group',
        memberIds: [clientB.whisperId, clientC.whisperId],
      },
    }));

    const eventA = await waitForMessage(clientA, 'group_event');
    await waitForMessage(clientB, 'group_event');
    await waitForMessage(clientC, 'group_event');

    const groupId = eventA.payload.group.groupId;

    // C disconnects
    clientC.close();
    clientC = null;
    await new Promise(resolve => setTimeout(resolve, 500));

    // A sends group message to B and C
    const messageId = uuidv4();
    const timestamp = Date.now();

    const nonceB = generateNonce();
    const ciphertextB = Buffer.from('Hello B offline').toString('base64');
    const sigB = signCanonical(clientA.keys!.signSecretKey, {
      messageType: 'group_send_message',
      messageId,
      from: clientA.whisperId!,
      toOrGroupId: groupId,
      timestamp,
      nonce: nonceB,
      ciphertext: ciphertextB,
    });

    const nonceC = generateNonce();
    const ciphertextC = Buffer.from('Hello C offline').toString('base64');
    const sigC = signCanonical(clientA.keys!.signSecretKey, {
      messageType: 'group_send_message',
      messageId,
      from: clientA.whisperId!,
      toOrGroupId: groupId,
      timestamp,
      nonce: nonceC,
      ciphertext: ciphertextC,
    });

    await sendAndWait(clientA, {
      type: 'group_send_message',
      requestId: uuidv4(),
      payload: {
        protocolVersion: PROTOCOL_VERSION,
        cryptoVersion: CRYPTO_VERSION,
        sessionToken: clientA.sessionToken,
        groupId,
        messageId,
        from: clientA.whisperId,
        msgType: 'text',
        timestamp,
        recipients: [
          { to: clientB.whisperId, nonce: nonceB, ciphertext: ciphertextB, sig: sigB },
          { to: clientCWhisperId, nonce: nonceC, ciphertext: ciphertextC, sig: sigC },
        ],
      },
    }, 'message_accepted');

    // B receives online
    const msgB = await waitForMessage(clientB, 'message_received');
    console.log('  B received online');

    // C reconnects and fetches pending
    clientC = await createClient();
    clientC.keys = clientCKeys;

    const beginResp = await sendAndWait(clientC, {
      type: 'register_begin',
      requestId: uuidv4(),
      payload: {
        protocolVersion: PROTOCOL_VERSION,
        cryptoVersion: CRYPTO_VERSION,
        whisperId: clientCWhisperId,
        deviceId: uuidv4(),
        platform: 'ios',
      },
    }, 'register_challenge');

    const challengeBytes = Buffer.from(beginResp.payload.challenge, 'base64');
    const signature = signChallenge(challengeBytes, clientCKeys.signSecretKey);

    const proofResp = await sendAndWait(clientC, {
      type: 'register_proof',
      requestId: uuidv4(),
      payload: {
        protocolVersion: PROTOCOL_VERSION,
        cryptoVersion: CRYPTO_VERSION,
        challengeId: beginResp.payload.challengeId,
        whisperId: clientCWhisperId,
        deviceId: uuidv4(),
        platform: 'ios',
        encPublicKey: clientCKeys.encPublicKey,
        signPublicKey: clientCKeys.signPublicKey,
        signature,
      },
    }, 'register_ack');

    clientC.whisperId = proofResp.payload.whisperId;
    clientC.sessionToken = proofResp.payload.sessionToken;

    // Fetch pending
    const pendingResp = await sendAndWait(clientC, {
      type: 'fetch_pending',
      requestId: uuidv4(),
      payload: {
        protocolVersion: PROTOCOL_VERSION,
        cryptoVersion: CRYPTO_VERSION,
        sessionToken: clientC.sessionToken,
      },
    }, 'pending_messages');

    if (pendingResp.type !== 'pending_messages') {
      throw new Error(`Expected pending_messages, got ${pendingResp.type}: ${JSON.stringify(pendingResp)}`);
    }

    const messages = pendingResp.payload?.messages || [];
    const pendingMsg = messages.find((m: any) => m.messageId === messageId);
    if (!pendingMsg) {
      throw new Error(`Group message not found in pending. Got ${messages.length} messages: ${JSON.stringify(messages.map((m: any) => m.messageId))}`);
    }

    if (pendingMsg.groupId !== groupId) {
      throw new Error('Missing groupId in pending message');
    }

    console.log('  C received group message via pending');
    console.log('  PASSED');
    return true;
  } catch (e) {
    console.log('  FAILED:', e);
    return false;
  } finally {
    clientA?.close();
    clientB?.close();
    clientC?.close();
  }
}

async function test6_RemoveMemberBlocksFutureMessages(): Promise<boolean> {
  console.log('\nTest 6: Remove member blocks future messages');

  let clientA: WsClient | null = null;
  let clientB: WsClient | null = null;
  let clientC: WsClient | null = null;

  try {
    clientA = await createClient();
    clientB = await createClient();
    clientC = await createClient();

    await registerClient(clientA);
    await registerClient(clientB);
    await registerClient(clientC);

    // A creates group with B, C
    clientA.ws.send(JSON.stringify({
      type: 'group_create',
      requestId: uuidv4(),
      payload: {
        protocolVersion: PROTOCOL_VERSION,
        cryptoVersion: CRYPTO_VERSION,
        sessionToken: clientA.sessionToken,
        title: 'Remove Test Group',
        memberIds: [clientB.whisperId, clientC.whisperId],
      },
    }));

    const eventA = await waitForMessage(clientA, 'group_event');
    await waitForMessage(clientB, 'group_event');
    await waitForMessage(clientC, 'group_event');

    const groupId = eventA.payload.group.groupId;

    // A removes C
    clientA.ws.send(JSON.stringify({
      type: 'group_update',
      requestId: uuidv4(),
      payload: {
        protocolVersion: PROTOCOL_VERSION,
        cryptoVersion: CRYPTO_VERSION,
        sessionToken: clientA.sessionToken,
        groupId,
        removeMembers: [clientC.whisperId],
      },
    }));

    // Wait for update events
    await waitForMessage(clientA, 'group_event');
    await waitForMessage(clientB, 'group_event');
    await waitForMessage(clientC, 'group_event');

    await new Promise(resolve => setTimeout(resolve, 300));

    // Clear C's messages
    clientC.pendingMessages.length = 0;

    // A sends group message to B and C (C should be rejected)
    const messageId = uuidv4();
    const timestamp = Date.now();

    const nonceB = generateNonce();
    const ciphertextB = Buffer.from('After removal').toString('base64');
    const sigB = signCanonical(clientA.keys!.signSecretKey, {
      messageType: 'group_send_message',
      messageId,
      from: clientA.whisperId!,
      toOrGroupId: groupId,
      timestamp,
      nonce: nonceB,
      ciphertext: ciphertextB,
    });

    const nonceC = generateNonce();
    const ciphertextC = Buffer.from('After removal C').toString('base64');
    const sigC = signCanonical(clientA.keys!.signSecretKey, {
      messageType: 'group_send_message',
      messageId,
      from: clientA.whisperId!,
      toOrGroupId: groupId,
      timestamp,
      nonce: nonceC,
      ciphertext: ciphertextC,
    });

    // This should fail because C is no longer an active member
    const sendResp = await sendAndWait(clientA, {
      type: 'group_send_message',
      requestId: uuidv4(),
      payload: {
        protocolVersion: PROTOCOL_VERSION,
        cryptoVersion: CRYPTO_VERSION,
        sessionToken: clientA.sessionToken,
        groupId,
        messageId,
        from: clientA.whisperId,
        msgType: 'text',
        timestamp,
        recipients: [
          { to: clientB.whisperId, nonce: nonceB, ciphertext: ciphertextB, sig: sigB },
          { to: clientC.whisperId, nonce: nonceC, ciphertext: ciphertextC, sig: sigC },
        ],
      },
    }, 'error');

    // Should get error because C is not active member
    if (sendResp.type !== 'error') {
      throw new Error(`Expected error for removed member, got ${sendResp.type}`);
    }

    console.log('  A removed C from group');
    console.log('  Message to C rejected (not active member)');
    console.log('  PASSED');
    return true;
  } catch (e) {
    console.log('  FAILED:', e);
    return false;
  } finally {
    clientA?.close();
    clientB?.close();
    clientC?.close();
  }
}

async function test7_AddMemberDoesNotReceivePastMessages(): Promise<boolean> {
  console.log('\nTest 7: Add member does not receive past messages');

  let clientA: WsClient | null = null;
  let clientB: WsClient | null = null;
  let clientD: WsClient | null = null;

  try {
    clientA = await createClient();
    clientB = await createClient();
    clientD = await createClient();

    await registerClient(clientA);
    await registerClient(clientB);
    await registerClient(clientD);

    // A creates group with B only
    clientA.ws.send(JSON.stringify({
      type: 'group_create',
      requestId: uuidv4(),
      payload: {
        protocolVersion: PROTOCOL_VERSION,
        cryptoVersion: CRYPTO_VERSION,
        sessionToken: clientA.sessionToken,
        title: 'Add Test Group',
        memberIds: [clientB.whisperId],
      },
    }));

    const eventA = await waitForMessage(clientA, 'group_event');
    await waitForMessage(clientB, 'group_event');

    const groupId = eventA.payload.group.groupId;

    // A sends message to B (before D joins)
    const messageId1 = uuidv4();
    const timestamp1 = Date.now();
    const nonce1 = generateNonce();
    const ciphertext1 = Buffer.from('Before D').toString('base64');
    const sig1 = signCanonical(clientA.keys!.signSecretKey, {
      messageType: 'group_send_message',
      messageId: messageId1,
      from: clientA.whisperId!,
      toOrGroupId: groupId,
      timestamp: timestamp1,
      nonce: nonce1,
      ciphertext: ciphertext1,
    });

    await sendAndWait(clientA, {
      type: 'group_send_message',
      requestId: uuidv4(),
      payload: {
        protocolVersion: PROTOCOL_VERSION,
        cryptoVersion: CRYPTO_VERSION,
        sessionToken: clientA.sessionToken,
        groupId,
        messageId: messageId1,
        from: clientA.whisperId,
        msgType: 'text',
        timestamp: timestamp1,
        recipients: [
          { to: clientB.whisperId, nonce: nonce1, ciphertext: ciphertext1, sig: sig1 },
        ],
      },
    }, 'message_accepted');

    await waitForMessage(clientB, 'message_received');
    console.log('  Message sent before D joined');

    // A adds D to the group
    clientA.ws.send(JSON.stringify({
      type: 'group_update',
      requestId: uuidv4(),
      payload: {
        protocolVersion: PROTOCOL_VERSION,
        cryptoVersion: CRYPTO_VERSION,
        sessionToken: clientA.sessionToken,
        groupId,
        addMembers: [clientD.whisperId],
      },
    }));

    await waitForMessage(clientA, 'group_event');
    await waitForMessage(clientB, 'group_event');
    await waitForMessage(clientD, 'group_event');

    console.log('  D added to group');

    // D fetches pending - should NOT receive the old message
    const pendingResp = await sendAndWait(clientD, {
      type: 'fetch_pending',
      requestId: uuidv4(),
      payload: {
        protocolVersion: PROTOCOL_VERSION,
        cryptoVersion: CRYPTO_VERSION,
        sessionToken: clientD.sessionToken,
      },
    }, 'pending_messages');

    const oldMsg = pendingResp.payload.messages.find((m: any) => m.messageId === messageId1);
    if (oldMsg) {
      throw new Error('D received past message (should not)');
    }

    console.log('  D did not receive past messages');
    console.log('  PASSED');
    return true;
  } catch (e) {
    console.log('  FAILED:', e);
    return false;
  } finally {
    clientA?.close();
    clientB?.close();
    clientD?.close();
  }
}

async function test8_AttachmentGroupMessageGrantsAccess(): Promise<boolean> {
  console.log('\nTest 8: Attachment group message grants access to recipients');

  let clientA: WsClient | null = null;
  let clientB: WsClient | null = null;
  let clientD: WsClient | null = null;

  try {
    clientA = await createClient();
    clientB = await createClient();
    clientD = await createClient();

    await registerClient(clientA);
    await registerClient(clientB);
    await registerClient(clientD);

    // A creates group with B, D
    clientA.ws.send(JSON.stringify({
      type: 'group_create',
      requestId: uuidv4(),
      payload: {
        protocolVersion: PROTOCOL_VERSION,
        cryptoVersion: CRYPTO_VERSION,
        sessionToken: clientA.sessionToken,
        title: 'Attachment Group',
        memberIds: [clientB.whisperId, clientD.whisperId],
      },
    }));

    const eventA = await waitForMessage(clientA, 'group_event');
    await waitForMessage(clientB, 'group_event');
    await waitForMessage(clientD, 'group_event');

    const groupId = eventA.payload.group.groupId;

    // A creates attachment
    const testData = Buffer.alloc(256);
    sodium.randombytes_buf(testData);

    const uploadResp = await httpRequest(
      'POST',
      '/attachments/presign/upload',
      { contentType: 'application/octet-stream', sizeBytes: testData.length },
      { Authorization: `Bearer ${clientA.sessionToken}` }
    );

    if (uploadResp.status !== 200) {
      throw new Error(`Upload presign failed: ${uploadResp.status}`);
    }

    const { objectKey, uploadUrl } = uploadResp.data;
    await uploadToPresignedUrl(uploadUrl, testData);
    console.log(`  A uploaded attachment: ${objectKey}`);

    // A sends group message with attachment to B and D
    const messageId = uuidv4();
    const timestamp = Date.now();

    const nonceB = generateNonce();
    const ciphertextB = Buffer.from('Attachment for B').toString('base64');
    const sigB = signCanonical(clientA.keys!.signSecretKey, {
      messageType: 'group_send_message',
      messageId,
      from: clientA.whisperId!,
      toOrGroupId: groupId,
      timestamp,
      nonce: nonceB,
      ciphertext: ciphertextB,
    });

    const nonceD = generateNonce();
    const ciphertextD = Buffer.from('Attachment for D').toString('base64');
    const sigD = signCanonical(clientA.keys!.signSecretKey, {
      messageType: 'group_send_message',
      messageId,
      from: clientA.whisperId!,
      toOrGroupId: groupId,
      timestamp,
      nonce: nonceD,
      ciphertext: ciphertextD,
    });

    await sendAndWait(clientA, {
      type: 'group_send_message',
      requestId: uuidv4(),
      payload: {
        protocolVersion: PROTOCOL_VERSION,
        cryptoVersion: CRYPTO_VERSION,
        sessionToken: clientA.sessionToken,
        groupId,
        messageId,
        from: clientA.whisperId,
        msgType: 'file',
        timestamp,
        recipients: [
          { to: clientB.whisperId, nonce: nonceB, ciphertext: ciphertextB, sig: sigB },
          { to: clientD.whisperId, nonce: nonceD, ciphertext: ciphertextD, sig: sigD },
        ],
        attachment: {
          objectKey,
          contentType: 'application/octet-stream',
          ciphertextSize: testData.length,
          fileNonce: generateNonce(),
          fileKeyBox: {
            nonce: generateNonce(),
            ciphertext: Buffer.from('key').toString('base64'),
          },
        },
      },
    }, 'message_accepted');

    await waitForMessage(clientB, 'message_received');
    await waitForMessage(clientD, 'message_received');

    await new Promise(resolve => setTimeout(resolve, 300));

    // B and D should be able to download
    const downloadB = await httpRequest(
      'POST',
      '/attachments/presign/download',
      { objectKey },
      { Authorization: `Bearer ${clientB.sessionToken}` }
    );

    const downloadD = await httpRequest(
      'POST',
      '/attachments/presign/download',
      { objectKey },
      { Authorization: `Bearer ${clientD.sessionToken}` }
    );

    if (downloadB.status !== 200) {
      throw new Error(`B cannot download: ${downloadB.status}`);
    }

    if (downloadD.status !== 200) {
      throw new Error(`D cannot download: ${downloadD.status}`);
    }

    console.log('  B can download attachment');
    console.log('  D can download attachment');
    console.log('  PASSED');
    return true;
  } catch (e) {
    console.log('  FAILED:', e);
    return false;
  } finally {
    clientA?.close();
    clientB?.close();
    clientD?.close();
  }
}

async function test9_RemovedMemberCannotDownloadFutureAttachments(): Promise<boolean> {
  console.log('\nTest 9: Removed member still cannot download future attachments');

  let clientA: WsClient | null = null;
  let clientB: WsClient | null = null;
  let clientD: WsClient | null = null;

  try {
    clientA = await createClient();
    clientB = await createClient();
    clientD = await createClient();

    await registerClient(clientA);
    await registerClient(clientB);
    await registerClient(clientD);

    // A creates group with B, D
    clientA.ws.send(JSON.stringify({
      type: 'group_create',
      requestId: uuidv4(),
      payload: {
        protocolVersion: PROTOCOL_VERSION,
        cryptoVersion: CRYPTO_VERSION,
        sessionToken: clientA.sessionToken,
        title: 'Remove Attachment Group',
        memberIds: [clientB.whisperId, clientD.whisperId],
      },
    }));

    const eventA = await waitForMessage(clientA, 'group_event');
    await waitForMessage(clientB, 'group_event');
    await waitForMessage(clientD, 'group_event');

    const groupId = eventA.payload.group.groupId;

    // A removes D
    clientA.ws.send(JSON.stringify({
      type: 'group_update',
      requestId: uuidv4(),
      payload: {
        protocolVersion: PROTOCOL_VERSION,
        cryptoVersion: CRYPTO_VERSION,
        sessionToken: clientA.sessionToken,
        groupId,
        removeMembers: [clientD.whisperId],
      },
    }));

    await waitForMessage(clientA, 'group_event');
    await waitForMessage(clientB, 'group_event');
    await waitForMessage(clientD, 'group_event');

    console.log('  D removed from group');

    // A creates new attachment and sends to B only
    const testData = Buffer.alloc(128);
    sodium.randombytes_buf(testData);

    const uploadResp = await httpRequest(
      'POST',
      '/attachments/presign/upload',
      { contentType: 'application/octet-stream', sizeBytes: testData.length },
      { Authorization: `Bearer ${clientA.sessionToken}` }
    );

    const { objectKey, uploadUrl } = uploadResp.data;
    await uploadToPresignedUrl(uploadUrl, testData);

    const messageId = uuidv4();
    const timestamp = Date.now();
    const nonce = generateNonce();
    const ciphertext = Buffer.from('After D removed').toString('base64');
    const sig = signCanonical(clientA.keys!.signSecretKey, {
      messageType: 'group_send_message',
      messageId,
      from: clientA.whisperId!,
      toOrGroupId: groupId,
      timestamp,
      nonce,
      ciphertext,
    });

    await sendAndWait(clientA, {
      type: 'group_send_message',
      requestId: uuidv4(),
      payload: {
        protocolVersion: PROTOCOL_VERSION,
        cryptoVersion: CRYPTO_VERSION,
        sessionToken: clientA.sessionToken,
        groupId,
        messageId,
        from: clientA.whisperId,
        msgType: 'file',
        timestamp,
        recipients: [
          { to: clientB.whisperId, nonce, ciphertext, sig },
        ],
        attachment: {
          objectKey,
          contentType: 'application/octet-stream',
          ciphertextSize: testData.length,
          fileNonce: generateNonce(),
          fileKeyBox: {
            nonce: generateNonce(),
            ciphertext: Buffer.from('key').toString('base64'),
          },
        },
      },
    }, 'message_accepted');

    await waitForMessage(clientB, 'message_received');

    // D should NOT be able to download this new attachment
    const downloadD = await httpRequest(
      'POST',
      '/attachments/presign/download',
      { objectKey },
      { Authorization: `Bearer ${clientD.sessionToken}` }
    );

    if (downloadD.status !== 403) {
      throw new Error(`D should not be able to download: ${downloadD.status}`);
    }

    console.log('  D cannot download new attachment (403 FORBIDDEN)');
    console.log('  PASSED');
    return true;
  } catch (e) {
    console.log('  FAILED:', e);
    return false;
  } finally {
    clientA?.close();
    clientB?.close();
    clientD?.close();
  }
}

async function test10_RoleEnforcement(): Promise<boolean> {
  console.log('\nTest 10: Role enforcement');

  let clientA: WsClient | null = null;
  let clientB: WsClient | null = null;
  let clientC: WsClient | null = null;

  try {
    clientA = await createClient();
    clientB = await createClient();
    clientC = await createClient();

    await registerClient(clientA);
    await registerClient(clientB);
    await registerClient(clientC);

    // A creates group with B, C
    clientA.ws.send(JSON.stringify({
      type: 'group_create',
      requestId: uuidv4(),
      payload: {
        protocolVersion: PROTOCOL_VERSION,
        cryptoVersion: CRYPTO_VERSION,
        sessionToken: clientA.sessionToken,
        title: 'Role Test Group',
        memberIds: [clientB.whisperId, clientC.whisperId],
      },
    }));

    const eventA = await waitForMessage(clientA, 'group_event');
    await waitForMessage(clientB, 'group_event');
    await waitForMessage(clientC, 'group_event');

    const groupId = eventA.payload.group.groupId;

    // B (member) tries to promote self to admin - should fail
    const promoteSelfResp = await sendAndWait(clientB, {
      type: 'group_update',
      requestId: uuidv4(),
      payload: {
        protocolVersion: PROTOCOL_VERSION,
        cryptoVersion: CRYPTO_VERSION,
        sessionToken: clientB.sessionToken,
        groupId,
        roleChanges: [{ whisperId: clientB.whisperId, role: 'admin' }],
      },
    }, 'error');

    if (promoteSelfResp.type !== 'error' || promoteSelfResp.payload.code !== 'FORBIDDEN') {
      throw new Error(`B should not be able to promote self: ${promoteSelfResp.type}`);
    }
    console.log('  B (member) cannot promote self - FORBIDDEN');

    // A (owner) promotes B to admin
    clientA.ws.send(JSON.stringify({
      type: 'group_update',
      requestId: uuidv4(),
      payload: {
        protocolVersion: PROTOCOL_VERSION,
        cryptoVersion: CRYPTO_VERSION,
        sessionToken: clientA.sessionToken,
        groupId,
        roleChanges: [{ whisperId: clientB.whisperId, role: 'admin' }],
      },
    }));

    await waitForMessage(clientA, 'group_event');
    await waitForMessage(clientB, 'group_event');
    console.log('  A (owner) promoted B to admin - OK');

    // Clear pending messages
    clientA.pendingMessages.length = 0;
    clientB.pendingMessages.length = 0;
    clientC.pendingMessages.length = 0;

    // B (admin) can remove C
    clientB.ws.send(JSON.stringify({
      type: 'group_update',
      requestId: uuidv4(),
      payload: {
        protocolVersion: PROTOCOL_VERSION,
        cryptoVersion: CRYPTO_VERSION,
        sessionToken: clientB.sessionToken,
        groupId,
        removeMembers: [clientC.whisperId],
      },
    }));

    const removeEvent = await waitForMessage(clientB, 'group_event');
    if (removeEvent.type === 'error') {
      throw new Error(`B (admin) should be able to remove C: ${removeEvent.payload.message}`);
    }
    console.log('  B (admin) can remove C - OK');

    console.log('  PASSED');
    return true;
  } catch (e) {
    console.log('  FAILED:', e);
    return false;
  } finally {
    clientA?.close();
    clientB?.close();
    clientC?.close();
  }
}

// =============================================================================
// MAIN
// =============================================================================

async function runTests(): Promise<void> {
  console.log('========================================');
  console.log('Step 5: Groups (Pairwise Fanout)');
  console.log('WS Server:', WS_URL);
  console.log('HTTP Server:', HTTP_URL);
  console.log('========================================');

  const results: { name: string; passed: boolean }[] = [];

  results.push({ name: 'Test 1: Create group succeeds', passed: await test1_CreateGroupSucceeds() });
  await new Promise(resolve => setTimeout(resolve, 300));

  results.push({ name: 'Test 2: Create group rejects > MAX_GROUP_MEMBERS', passed: await test2_CreateGroupRejectsMaxMembers() });
  await new Promise(resolve => setTimeout(resolve, 300));

  results.push({ name: 'Test 3: Non-member cannot send group message', passed: await test3_NonMemberCannotSendGroupMessage() });
  await new Promise(resolve => setTimeout(resolve, 300));

  results.push({ name: 'Test 4: Member sends group message online', passed: await test4_MemberSendsGroupMessageOnline() });
  await new Promise(resolve => setTimeout(resolve, 300));

  results.push({ name: 'Test 5: Offline member gets group message pending', passed: await test5_OfflineMemberGetsGroupMessagePending() });
  await new Promise(resolve => setTimeout(resolve, 300));

  results.push({ name: 'Test 6: Remove member blocks future messages', passed: await test6_RemoveMemberBlocksFutureMessages() });
  await new Promise(resolve => setTimeout(resolve, 300));

  results.push({ name: 'Test 7: Add member does not receive past messages', passed: await test7_AddMemberDoesNotReceivePastMessages() });
  await new Promise(resolve => setTimeout(resolve, 300));

  results.push({ name: 'Test 8: Attachment group message grants access', passed: await test8_AttachmentGroupMessageGrantsAccess() });
  await new Promise(resolve => setTimeout(resolve, 300));

  results.push({ name: 'Test 9: Removed member cannot download future attachments', passed: await test9_RemovedMemberCannotDownloadFutureAttachments() });
  await new Promise(resolve => setTimeout(resolve, 300));

  results.push({ name: 'Test 10: Role enforcement', passed: await test10_RoleEnforcement() });

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
