/**
 * Test Vectors Verification
 *
 * This test verifies that the frozen test vectors can be reproduced
 * using the server's crypto libraries. If these tests fail, cross-platform
 * recovery is broken.
 */

// eslint-disable-next-line @typescript-eslint/no-var-requires
const sodium = require('sodium-native');
import * as crypto from 'crypto';
import {
  TEST_MNEMONIC,
  TEST_PASSPHRASE,
  EXPECTED_BIP39_SEED_HEX,
  EXPECTED_ENC_SEED_HEX,
  EXPECTED_SIGN_SEED_HEX,
  EXPECTED_CONTACTS_KEY_HEX,
  EXPECTED_ENC_PUBLIC_KEY_HEX,
  EXPECTED_SIGN_PUBLIC_KEY_HEX,
  EXPECTED_ENC_PUBLIC_KEY_B64,
  EXPECTED_SIGN_PUBLIC_KEY_B64,
  HKDF_SALT,
  HKDF_INFO,
  BIP39_SEED_LENGTH,
  DERIVED_KEY_LENGTH,
} from '../src/utils/test-vectors';

// =============================================================================
// HELPER FUNCTIONS
// =============================================================================

/**
 * BIP39 mnemonic to seed using PBKDF2-HMAC-SHA512
 */
function mnemonicToSeed(mnemonic: string, passphrase: string = ''): Buffer {
  // Normalize mnemonic (NFKD)
  const normalizedMnemonic = mnemonic.normalize('NFKD');
  const normalizedPassphrase = passphrase.normalize('NFKD');

  // PBKDF2-HMAC-SHA512, salt = "mnemonic" + passphrase, 2048 iterations
  const salt = Buffer.from('mnemonic' + normalizedPassphrase, 'utf8');
  return crypto.pbkdf2Sync(normalizedMnemonic, salt, 2048, 64, 'sha512');
}

/**
 * HKDF-SHA256 key derivation
 */
function hkdfDerive(ikm: Buffer, salt: string, info: string, length: number): Buffer {
  const saltBuffer = Buffer.from(salt, 'utf8');
  const infoBuffer = Buffer.from(info, 'utf8');

  // HKDF-Extract
  const prk = crypto.createHmac('sha256', saltBuffer).update(ikm).digest();

  // HKDF-Expand
  let t = Buffer.alloc(0);
  let okm = Buffer.alloc(0);
  let i = 1;

  while (okm.length < length) {
    const hmac = crypto.createHmac('sha256', prk);
    hmac.update(Buffer.concat([t, infoBuffer, Buffer.from([i])]));
    t = hmac.digest();
    okm = Buffer.concat([okm, t]);
    i++;
  }

  return okm.slice(0, length);
}

// =============================================================================
// TESTS
// =============================================================================

let passed = 0;
let failed = 0;

function test(name: string, fn: () => void) {
  try {
    fn();
    console.log(`✅ ${name}`);
    passed++;
  } catch (error) {
    console.error(`❌ ${name}`);
    console.error(`   ${error}`);
    failed++;
  }
}

function assertEqual(actual: string, expected: string, message: string) {
  if (actual !== expected) {
    throw new Error(`${message}\n   Expected: ${expected}\n   Actual:   ${actual}`);
  }
}

async function runTests() {
  console.log('\n=== TEST VECTORS VERIFICATION ===\n');

  // -------------------------------------------------------------------------
  // BIP39 Seed Derivation
  // -------------------------------------------------------------------------

  test('BIP39 seed derivation produces expected 64-byte seed', () => {
    const seed = mnemonicToSeed(TEST_MNEMONIC, TEST_PASSPHRASE);
    assertEqual(seed.length.toString(), BIP39_SEED_LENGTH.toString(), 'Seed length mismatch');
    assertEqual(seed.toString('hex'), EXPECTED_BIP39_SEED_HEX, 'BIP39 seed mismatch');
  });

  // -------------------------------------------------------------------------
  // HKDF Key Derivation
  // -------------------------------------------------------------------------

  test('HKDF produces correct encryption seed', () => {
    const seed = Buffer.from(EXPECTED_BIP39_SEED_HEX, 'hex');
    const encSeed = hkdfDerive(seed, HKDF_SALT, HKDF_INFO.ENCRYPTION, DERIVED_KEY_LENGTH);
    assertEqual(encSeed.length.toString(), DERIVED_KEY_LENGTH.toString(), 'Key length mismatch');
    assertEqual(encSeed.toString('hex'), EXPECTED_ENC_SEED_HEX, 'Encryption seed mismatch');
  });

  test('HKDF produces correct signing seed', () => {
    const seed = Buffer.from(EXPECTED_BIP39_SEED_HEX, 'hex');
    const signSeed = hkdfDerive(seed, HKDF_SALT, HKDF_INFO.SIGNING, DERIVED_KEY_LENGTH);
    assertEqual(signSeed.length.toString(), DERIVED_KEY_LENGTH.toString(), 'Key length mismatch');
    assertEqual(signSeed.toString('hex'), EXPECTED_SIGN_SEED_HEX, 'Signing seed mismatch');
  });

  test('HKDF produces correct contacts key', () => {
    const seed = Buffer.from(EXPECTED_BIP39_SEED_HEX, 'hex');
    const contactsKey = hkdfDerive(seed, HKDF_SALT, HKDF_INFO.CONTACTS, DERIVED_KEY_LENGTH);
    assertEqual(contactsKey.length.toString(), DERIVED_KEY_LENGTH.toString(), 'Key length mismatch');
    assertEqual(contactsKey.toString('hex'), EXPECTED_CONTACTS_KEY_HEX, 'Contacts key mismatch');
  });

  // -------------------------------------------------------------------------
  // Public Key Derivation
  // -------------------------------------------------------------------------

  test('X25519 public key derivation from encryption seed', () => {
    const encSeed = Buffer.from(EXPECTED_ENC_SEED_HEX, 'hex');

    const publicKey = Buffer.alloc(sodium.crypto_box_PUBLICKEYBYTES);
    const secretKey = Buffer.alloc(sodium.crypto_box_SECRETKEYBYTES);
    sodium.crypto_box_seed_keypair(publicKey, secretKey, encSeed);

    assertEqual(publicKey.toString('hex'), EXPECTED_ENC_PUBLIC_KEY_HEX, 'X25519 public key mismatch');
    assertEqual(publicKey.toString('base64'), EXPECTED_ENC_PUBLIC_KEY_B64, 'X25519 public key base64 mismatch');
  });

  test('Ed25519 public key derivation from signing seed', () => {
    const signSeed = Buffer.from(EXPECTED_SIGN_SEED_HEX, 'hex');

    const publicKey = Buffer.alloc(sodium.crypto_sign_PUBLICKEYBYTES);
    const secretKey = Buffer.alloc(sodium.crypto_sign_SECRETKEYBYTES);
    sodium.crypto_sign_seed_keypair(publicKey, secretKey, signSeed);

    assertEqual(publicKey.toString('hex'), EXPECTED_SIGN_PUBLIC_KEY_HEX, 'Ed25519 public key mismatch');
    assertEqual(publicKey.toString('base64'), EXPECTED_SIGN_PUBLIC_KEY_B64, 'Ed25519 public key base64 mismatch');
  });

  // -------------------------------------------------------------------------
  // Full Chain Test
  // -------------------------------------------------------------------------

  test('Full chain: mnemonic -> BIP39 -> HKDF -> keypairs', () => {
    // Start from mnemonic
    const seed = mnemonicToSeed(TEST_MNEMONIC, TEST_PASSPHRASE);

    // Derive domain seeds
    const encSeed = hkdfDerive(seed, HKDF_SALT, HKDF_INFO.ENCRYPTION, DERIVED_KEY_LENGTH);
    const signSeed = hkdfDerive(seed, HKDF_SALT, HKDF_INFO.SIGNING, DERIVED_KEY_LENGTH);

    // Derive keypairs
    const encPublicKey = Buffer.alloc(sodium.crypto_box_PUBLICKEYBYTES);
    const encSecretKey = Buffer.alloc(sodium.crypto_box_SECRETKEYBYTES);
    sodium.crypto_box_seed_keypair(encPublicKey, encSecretKey, encSeed);

    const signPublicKey = Buffer.alloc(sodium.crypto_sign_PUBLICKEYBYTES);
    const signSecretKey = Buffer.alloc(sodium.crypto_sign_SECRETKEYBYTES);
    sodium.crypto_sign_seed_keypair(signPublicKey, signSecretKey, signSeed);

    // Verify
    assertEqual(encPublicKey.toString('base64'), EXPECTED_ENC_PUBLIC_KEY_B64, 'Full chain enc key mismatch');
    assertEqual(signPublicKey.toString('base64'), EXPECTED_SIGN_PUBLIC_KEY_B64, 'Full chain sign key mismatch');
  });

  // -------------------------------------------------------------------------
  // Summary
  // -------------------------------------------------------------------------

  console.log('\n=== SUMMARY ===\n');
  console.log(`Passed: ${passed}`);
  console.log(`Failed: ${failed}`);

  if (failed > 0) {
    console.error('\n❌ TEST VECTORS VERIFICATION FAILED');
    console.error('Cross-platform recovery is BROKEN. DO NOT DEPLOY.');
    process.exit(1);
  } else {
    console.log('\n✅ All test vectors verified successfully');
    console.log('Cross-platform recovery is compatible.');
  }
}

runTests().catch((err) => {
  console.error('Test execution failed:', err);
  process.exit(1);
});
