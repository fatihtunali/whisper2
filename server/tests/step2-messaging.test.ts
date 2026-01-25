/**
 * Step 2 Verification Tests: Message Routing
 * Following WHISPER-REBUILD.md Section 4, 5
 *
 * Test cases:
 * 1. Two WS clients register (A & B)
 * 2. A sends message to B: A gets message_accepted, B gets message_received
 * 3. B sends delivery_receipt(delivered), A receives message_delivered
 * 4. B disconnects, A sends message, server stores in pending
 * 5. B reconnects + fetch_pending, receives pending, sends receipt, pending removed
 * 6. A resends same messageId: no duplicate delivery, but ACK returned
 */

import WebSocket from 'ws';
import * as sodium from 'sodium-native';
import { v4 as uuidv4 } from 'uuid';

const WS_URL = process.env.WS_URL || 'wss://whisper2.aiakademiturkiye.com/ws';
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

function generateNonce(): string {
  const nonce = Buffer.alloc(24);
  sodium.randombytes_buf(nonce);
  return nonce.toString('base64');
}

function signCanonical(
  signSecretKey: Buffer,
  parts: Record<string, string | number | undefined>
): string {
  const canonical = buildCanonical(parts);
  const signature = Buffer.alloc(sodium.crypto_sign_BYTES);
  const message = Buffer.from(canonical, 'utf8');
  sodium.crypto_sign_detached(signature, message, signSecretKey);
  return signature.toString('base64');
}

function buildCanonical(parts: Record<string, string | number | undefined>): string {
  const lines: string[] = ['v1'];
  const order = ['messageType', 'messageId', 'from', 'toOrGroupId', 'timestamp', 'nonce', 'ciphertext'];
  for (const key of order) {
    if (parts[key] !== undefined) {
      lines.push(String(parts[key]));
    }
  }
  return lines.join('\n');
}

function encrypt(plaintext: string, recipientPubKey: Buffer, senderSecretKey: Buffer): { nonce: string; ciphertext: string } {
  const nonce = Buffer.alloc(sodium.crypto_box_NONCEBYTES);
  sodium.randombytes_buf(nonce);

  const message = Buffer.from(plaintext, 'utf8');
  const ciphertext = Buffer.alloc(message.length + sodium.crypto_box_MACBYTES);

  sodium.crypto_box_easy(ciphertext, message, nonce, recipientPubKey, senderSecretKey);

  return {
    nonce: nonce.toString('base64'),
    ciphertext: ciphertext.toString('base64'),
  };
}

// =============================================================================
// WEBSOCKET CLIENT HELPER
// =============================================================================

interface WsClient {
  ws: WebSocket;
  whisperId?: string;
  sessionToken?: string;
  keys: ReturnType<typeof generateKeys>;
  messages: any[];
  connected: Promise<void>;
  close: () => void;
  send: (type: string, payload: any, requestId?: string) => void;
  waitForMessage: (type: string, timeout?: number) => Promise<any>;
}

function createClient(): WsClient {
  const keys = generateKeys();
  const messages: any[] = [];
  let resolveConnect: () => void;

  const connected = new Promise<void>((resolve) => {
    resolveConnect = resolve;
  });

  const ws = new WebSocket(WS_URL, {
    rejectUnauthorized: true,
  });

  ws.on('open', () => {
    resolveConnect();
  });

  ws.on('message', (data: Buffer) => {
    try {
      const msg = JSON.parse(data.toString());
      messages.push(msg);
    } catch (e) {
      console.error('Failed to parse message:', e);
    }
  });

  return {
    ws,
    keys,
    messages,
    connected,
    close: () => ws.close(),
    send: (type: string, payload: any, requestId?: string) => {
      const frame: any = { type, payload };
      if (requestId) frame.requestId = requestId;
      ws.send(JSON.stringify(frame));
    },
    waitForMessage: (type: string, timeout = 5000) => {
      return new Promise((resolve, reject) => {
        const startTime = Date.now();

        const check = () => {
          const idx = messages.findIndex(m => m.type === type);
          if (idx !== -1) {
            const msg = messages.splice(idx, 1)[0];
            resolve(msg);
            return;
          }

          if (Date.now() - startTime > timeout) {
            reject(new Error(`Timeout waiting for message type: ${type}`));
            return;
          }

          setTimeout(check, 50);
        };

        check();
      });
    },
  };
}

async function registerClient(client: WsClient, deviceId: string): Promise<void> {
  await client.connected;

  // Step 1: register_begin (no keys - just device info)
  client.send('register_begin', {
    protocolVersion: PROTOCOL_VERSION,
    cryptoVersion: CRYPTO_VERSION,
    deviceId,
    platform: 'ios', // must be 'ios' or 'android'
  }, 'reg-1');

  // Step 2: wait for register_challenge
  const challenge = await client.waitForMessage('register_challenge');

  // Step 3: Sign SHA256(challenge)
  const challengeBytes = Buffer.from(challenge.payload.challenge, 'base64');
  const challengeHash = Buffer.alloc(sodium.crypto_hash_sha256_BYTES);
  sodium.crypto_hash_sha256(challengeHash, challengeBytes);

  const signature = Buffer.alloc(sodium.crypto_sign_BYTES);
  sodium.crypto_sign_detached(
    signature,
    challengeHash,
    client.keys.signSecretKey
  );

  // Step 4: send register_proof (includes keys + signature)
  client.send('register_proof', {
    protocolVersion: PROTOCOL_VERSION,
    cryptoVersion: CRYPTO_VERSION,
    challengeId: challenge.payload.challengeId,
    deviceId,
    platform: 'ios',
    encPublicKey: client.keys.encPublicKey,
    signPublicKey: client.keys.signPublicKey,
    signature: signature.toString('base64'),
  }, 'reg-2');

  // Step 5: wait for register_ack
  const ack = await client.waitForMessage('register_ack');

  client.whisperId = ack.payload.whisperId;
  client.sessionToken = ack.payload.sessionToken;
}

// =============================================================================
// TESTS
// =============================================================================

async function test1_TwoClientsRegister(): Promise<boolean> {
  console.log('\n=== Test 1: Two WS clients register (A & B) ===');

  const clientA = createClient();
  const clientB = createClient();

  try {
    await Promise.all([
      registerClient(clientA, uuidv4()),
      registerClient(clientB, uuidv4()),
    ]);

    console.log('  Client A whisperId:', clientA.whisperId);
    console.log('  Client B whisperId:', clientB.whisperId);

    if (!clientA.whisperId || !clientB.whisperId) {
      console.log('  FAILED: Missing whisperId');
      return false;
    }

    if (!clientA.sessionToken || !clientB.sessionToken) {
      console.log('  FAILED: Missing sessionToken');
      return false;
    }

    console.log('  PASSED');
    return true;
  } finally {
    clientA.close();
    clientB.close();
  }
}

async function test2_SendMessageOnline(): Promise<{ clientA: WsClient; clientB: WsClient; messageId: string } | null> {
  console.log('\n=== Test 2: A sends message to B (online) ===');

  const clientA = createClient();
  const clientB = createClient();

  try {
    await Promise.all([
      registerClient(clientA, uuidv4()),
      registerClient(clientB, uuidv4()),
    ]);

    // A sends message to B
    const messageId = uuidv4();
    const timestamp = Date.now();
    const { nonce, ciphertext } = encrypt(
      'Hello B!',
      Buffer.from(clientB.keys.encPublicKey, 'base64'),
      clientA.keys.encSecretKey
    );

    const sig = signCanonical(clientA.keys.signSecretKey, {
      messageType: 'send_message',
      messageId,
      from: clientA.whisperId,
      toOrGroupId: clientB.whisperId,
      timestamp,
      nonce,
      ciphertext,
    });

    clientA.send('send_message', {
      protocolVersion: PROTOCOL_VERSION,
      cryptoVersion: CRYPTO_VERSION,
      sessionToken: clientA.sessionToken,
      messageId,
      from: clientA.whisperId,
      to: clientB.whisperId,
      msgType: 'text',
      timestamp,
      nonce,
      ciphertext,
      sig,
    }, 'msg-1');

    // A should get message_accepted
    const accepted = await clientA.waitForMessage('message_accepted');
    console.log('  A received message_accepted:', accepted.payload.messageId === messageId);

    if (accepted.payload.messageId !== messageId) {
      console.log('  FAILED: message_accepted has wrong messageId');
      clientA.close();
      clientB.close();
      return null;
    }

    // B should get message_received
    const received = await clientB.waitForMessage('message_received');
    console.log('  B received message_received:', received.payload.messageId === messageId);

    if (received.payload.messageId !== messageId) {
      console.log('  FAILED: message_received has wrong messageId');
      clientA.close();
      clientB.close();
      return null;
    }

    console.log('  PASSED');
    return { clientA, clientB, messageId };
  } catch (e) {
    console.log('  FAILED:', e);
    clientA.close();
    clientB.close();
    return null;
  }
}

async function test3_DeliveryReceipt(
  clientA: WsClient,
  clientB: WsClient,
  messageId: string
): Promise<boolean> {
  console.log('\n=== Test 3: B sends delivery_receipt, A receives message_delivered ===');

  try {
    // B sends delivery_receipt
    clientB.send('delivery_receipt', {
      protocolVersion: PROTOCOL_VERSION,
      cryptoVersion: CRYPTO_VERSION,
      sessionToken: clientB.sessionToken,
      messageId,
      from: clientB.whisperId,
      to: clientA.whisperId,
      status: 'delivered',
      timestamp: Date.now(),
    }, 'receipt-1');

    // A should get message_delivered
    const delivered = await clientA.waitForMessage('message_delivered');
    console.log('  A received message_delivered:', delivered.payload.messageId === messageId);

    if (delivered.payload.messageId !== messageId) {
      console.log('  FAILED: message_delivered has wrong messageId');
      return false;
    }

    console.log('  PASSED');
    return true;
  } catch (e) {
    console.log('  FAILED:', e);
    return false;
  }
}

async function test4_OfflineMessage(): Promise<{ clientA: WsClient; messageId: string; clientBKeys: ReturnType<typeof generateKeys>; clientBWhisperId: string } | null> {
  console.log('\n=== Test 4: B disconnects, A sends message (stored in pending) ===');

  const clientA = createClient();
  const clientB = createClient();

  try {
    await Promise.all([
      registerClient(clientA, uuidv4()),
      registerClient(clientB, uuidv4()),
    ]);

    const clientBWhisperId = clientB.whisperId!;
    const clientBKeys = clientB.keys;

    // B disconnects
    clientB.close();
    await new Promise(resolve => setTimeout(resolve, 500));

    // A sends message to B (who is offline)
    const messageId = uuidv4();
    const timestamp = Date.now();
    const { nonce, ciphertext } = encrypt(
      'Hello offline B!',
      Buffer.from(clientBKeys.encPublicKey, 'base64'),
      clientA.keys.encSecretKey
    );

    const sig = signCanonical(clientA.keys.signSecretKey, {
      messageType: 'send_message',
      messageId,
      from: clientA.whisperId,
      toOrGroupId: clientBWhisperId,
      timestamp,
      nonce,
      ciphertext,
    });

    clientA.send('send_message', {
      protocolVersion: PROTOCOL_VERSION,
      cryptoVersion: CRYPTO_VERSION,
      sessionToken: clientA.sessionToken,
      messageId,
      from: clientA.whisperId,
      to: clientBWhisperId,
      msgType: 'text',
      timestamp,
      nonce,
      ciphertext,
      sig,
    }, 'msg-2');

    // A should get message_accepted
    const accepted = await clientA.waitForMessage('message_accepted');
    console.log('  A received message_accepted:', accepted.payload.messageId === messageId);

    if (accepted.payload.messageId !== messageId) {
      console.log('  FAILED: message_accepted has wrong messageId');
      clientA.close();
      return null;
    }

    console.log('  Message stored in pending queue for offline B');
    console.log('  PASSED');
    return { clientA, messageId, clientBKeys, clientBWhisperId };
  } catch (e) {
    console.log('  FAILED:', e);
    clientA.close();
    return null;
  }
}

async function test5_FetchPending(
  clientA: WsClient,
  messageId: string,
  clientBKeys: ReturnType<typeof generateKeys>,
  clientBWhisperId: string
): Promise<boolean> {
  console.log('\n=== Test 5: B reconnects, fetch_pending, receives pending, sends receipt ===');

  const clientB = createClient();

  try {
    await clientB.connected;

    // B needs to re-register with same keys to get same whisperId
    // Since we use single-active-device, we register as a new session
    const newDeviceId = uuidv4();
    clientB.send('register_begin', {
      protocolVersion: PROTOCOL_VERSION,
      cryptoVersion: CRYPTO_VERSION,
      deviceId: newDeviceId,
      platform: 'ios',
    }, 'reg-b1');

    const challenge = await clientB.waitForMessage('register_challenge');

    // Sign SHA256(challenge)
    const challengeBytes = Buffer.from(challenge.payload.challenge, 'base64');
    const challengeHash = Buffer.alloc(sodium.crypto_hash_sha256_BYTES);
    sodium.crypto_hash_sha256(challengeHash, challengeBytes);

    const signature = Buffer.alloc(sodium.crypto_sign_BYTES);
    sodium.crypto_sign_detached(
      signature,
      challengeHash,
      clientBKeys.signSecretKey
    );

    clientB.send('register_proof', {
      protocolVersion: PROTOCOL_VERSION,
      cryptoVersion: CRYPTO_VERSION,
      challengeId: challenge.payload.challengeId,
      deviceId: newDeviceId,
      platform: 'ios',
      encPublicKey: clientBKeys.encPublicKey,
      signPublicKey: clientBKeys.signPublicKey,
      signature: signature.toString('base64'),
    }, 'reg-b2');

    const ack = await clientB.waitForMessage('register_ack');
    clientB.whisperId = ack.payload.whisperId;
    clientB.sessionToken = ack.payload.sessionToken;

    console.log('  B reconnected with whisperId:', clientB.whisperId);

    // B should be the same whisperId (same keys)
    if (clientB.whisperId !== clientBWhisperId) {
      console.log('  FAILED: B got different whisperId after reconnect');
      clientB.close();
      clientA.close();
      return false;
    }

    // B fetches pending messages
    clientB.send('fetch_pending', {
      protocolVersion: PROTOCOL_VERSION,
      cryptoVersion: CRYPTO_VERSION,
      sessionToken: clientB.sessionToken,
    }, 'fetch-1');

    const pending = await clientB.waitForMessage('pending_messages');
    console.log('  B received pending_messages with', pending.payload.messages.length, 'messages');

    const pendingMsg = pending.payload.messages.find((m: any) => m.messageId === messageId);
    if (!pendingMsg) {
      console.log('  FAILED: Pending message not found');
      clientB.close();
      clientA.close();
      return false;
    }

    // B sends delivery receipt
    clientB.send('delivery_receipt', {
      protocolVersion: PROTOCOL_VERSION,
      cryptoVersion: CRYPTO_VERSION,
      sessionToken: clientB.sessionToken,
      messageId,
      from: clientB.whisperId,
      to: clientA.whisperId,
      status: 'delivered',
      timestamp: Date.now(),
    }, 'receipt-2');

    // A should get message_delivered
    const delivered = await clientA.waitForMessage('message_delivered');
    console.log('  A received message_delivered:', delivered.payload.messageId === messageId);

    // Verify pending is removed by fetching again
    await new Promise(resolve => setTimeout(resolve, 200));

    clientB.send('fetch_pending', {
      protocolVersion: PROTOCOL_VERSION,
      cryptoVersion: CRYPTO_VERSION,
      sessionToken: clientB.sessionToken,
    }, 'fetch-2');

    const pending2 = await clientB.waitForMessage('pending_messages');
    const stillPending = pending2.payload.messages.find((m: any) => m.messageId === messageId);

    if (stillPending) {
      console.log('  FAILED: Message still in pending after receipt');
      clientB.close();
      clientA.close();
      return false;
    }

    console.log('  Message removed from pending after receipt');
    console.log('  PASSED');

    clientB.close();
    clientA.close();
    return true;
  } catch (e) {
    console.log('  FAILED:', e);
    clientB.close();
    clientA.close();
    return false;
  }
}

async function test6_Deduplication(): Promise<boolean> {
  console.log('\n=== Test 6: A resends same messageId (dedup - ACK but no duplicate delivery) ===');

  const clientA = createClient();
  const clientB = createClient();

  try {
    await Promise.all([
      registerClient(clientA, uuidv4()),
      registerClient(clientB, uuidv4()),
    ]);

    // A sends message to B
    const messageId = uuidv4();
    const timestamp = Date.now();
    const { nonce, ciphertext } = encrypt(
      'Dedup test!',
      Buffer.from(clientB.keys.encPublicKey, 'base64'),
      clientA.keys.encSecretKey
    );

    const sig = signCanonical(clientA.keys.signSecretKey, {
      messageType: 'send_message',
      messageId,
      from: clientA.whisperId,
      toOrGroupId: clientB.whisperId,
      timestamp,
      nonce,
      ciphertext,
    });

    const sendPayload = {
      protocolVersion: PROTOCOL_VERSION,
      cryptoVersion: CRYPTO_VERSION,
      sessionToken: clientA.sessionToken,
      messageId,
      from: clientA.whisperId,
      to: clientB.whisperId,
      msgType: 'text',
      timestamp,
      nonce,
      ciphertext,
      sig,
    };

    // First send
    clientA.send('send_message', sendPayload, 'msg-dup-1');

    const accepted1 = await clientA.waitForMessage('message_accepted');
    console.log('  First send - A received message_accepted');

    const received1 = await clientB.waitForMessage('message_received');
    console.log('  First send - B received message_received');

    // Wait a bit
    await new Promise(resolve => setTimeout(resolve, 200));

    // Clear B's messages
    clientB.messages.length = 0;

    // Second send (same messageId)
    clientA.send('send_message', sendPayload, 'msg-dup-2');

    // A should still get message_accepted (idempotent)
    const accepted2 = await clientA.waitForMessage('message_accepted');
    console.log('  Second send - A received message_accepted:', accepted2.payload.messageId === messageId);

    // B should NOT receive message_received again
    let receivedDuplicate = false;
    try {
      await clientB.waitForMessage('message_received', 1000);
      receivedDuplicate = true;
    } catch {
      // Expected timeout
    }

    if (receivedDuplicate) {
      console.log('  FAILED: B received duplicate message');
      return false;
    }

    console.log('  B did NOT receive duplicate (correct dedup behavior)');
    console.log('  PASSED');
    return true;
  } catch (e) {
    console.log('  FAILED:', e);
    return false;
  } finally {
    clientA.close();
    clientB.close();
  }
}

// =============================================================================
// MAIN
// =============================================================================

async function runTests(): Promise<void> {
  console.log('========================================');
  console.log('Step 2 Verification Tests: Message Routing');
  console.log('Server:', WS_URL);
  console.log('========================================');

  const results: { name: string; passed: boolean }[] = [];

  // Test 1
  results.push({ name: 'Test 1: Two clients register', passed: await test1_TwoClientsRegister() });

  // Test 2
  const test2Result = await test2_SendMessageOnline();
  results.push({ name: 'Test 2: Send message (online)', passed: test2Result !== null });

  // Test 3 (depends on Test 2)
  if (test2Result) {
    results.push({
      name: 'Test 3: Delivery receipt',
      passed: await test3_DeliveryReceipt(test2Result.clientA, test2Result.clientB, test2Result.messageId)
    });
    test2Result.clientA.close();
    test2Result.clientB.close();
  } else {
    results.push({ name: 'Test 3: Delivery receipt', passed: false });
  }

  // Wait between tests
  await new Promise(resolve => setTimeout(resolve, 500));

  // Test 4
  const test4Result = await test4_OfflineMessage();
  results.push({ name: 'Test 4: Offline message (pending)', passed: test4Result !== null });

  // Test 5 (depends on Test 4)
  if (test4Result) {
    results.push({
      name: 'Test 5: Fetch pending + receipt',
      passed: await test5_FetchPending(
        test4Result.clientA,
        test4Result.messageId,
        test4Result.clientBKeys,
        test4Result.clientBWhisperId
      )
    });
  } else {
    results.push({ name: 'Test 5: Fetch pending + receipt', passed: false });
  }

  // Wait between tests
  await new Promise(resolve => setTimeout(resolve, 500));

  // Test 6
  results.push({ name: 'Test 6: Deduplication', passed: await test6_Deduplication() });

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
