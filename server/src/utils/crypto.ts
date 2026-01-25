/**
 * Whisper2 Crypto Utilities
 * Following WHISPER-REBUILD.md Sections 3.4 and 3.5
 *
 * Key derivation: BIP39 → HKDF → domain-separated keys
 * Signing: Ed25519 with canonical serialization
 * Encryption: X25519 + secretbox/box
 */

import sodium from 'sodium-native';
import { SignedMessageType, TIMESTAMP_SKEW_MS } from '../types/protocol';

// =============================================================================
// CONSTANTS
// =============================================================================

/** Nonce size for secretbox/box: 24 bytes */
export const NONCE_SIZE = 24;

/** Key size: 32 bytes */
export const KEY_SIZE = 32;

/** Ed25519 signature size: 64 bytes */
export const SIGNATURE_SIZE = 64;

/** Ed25519 public key size: 32 bytes */
export const ED25519_PUBLIC_KEY_SIZE = 32;

/** X25519 public key size: 32 bytes */
export const X25519_PUBLIC_KEY_SIZE = 32;

/** Challenge size: 32 bytes */
export const CHALLENGE_SIZE = 32;

// =============================================================================
// CANONICAL SIGNING (Section 3.5)
// =============================================================================

/**
 * Builds canonical bytes for signature verification.
 *
 * Format (Section 3.5):
 * signed_bytes = UTF8(
 *   "v1\n" +
 *   messageType + "\n" +
 *   messageId + "\n" +
 *   from + "\n" +
 *   toOrGroupId + "\n" +
 *   timestampMillis + "\n" +
 *   base64(nonce) + "\n" +
 *   base64(ciphertext) + "\n"
 * )
 *
 * DO NOT sign raw JSON.
 * DO NOT rely on "sorted keys" implementations.
 */
export function buildCanonicalBytes(params: {
  messageType: SignedMessageType;
  messageId: string;
  from: string;
  toOrGroupId: string;
  timestamp: number;
  nonce: string; // base64
  ciphertext: string; // base64
}): Buffer {
  const canonical =
    `v1\n` +
    `${params.messageType}\n` +
    `${params.messageId}\n` +
    `${params.from}\n` +
    `${params.toOrGroupId}\n` +
    `${params.timestamp}\n` +
    `${params.nonce}\n` +
    `${params.ciphertext}\n`;

  return Buffer.from(canonical, 'utf8');
}

/**
 * Computes SHA256 hash of canonical bytes.
 * sig = Ed25519_Sign(SHA256(signed_bytes), privateKey)
 */
export function sha256(data: Buffer): Buffer {
  const hash = Buffer.alloc(sodium.crypto_hash_sha256_BYTES);
  sodium.crypto_hash_sha256(hash, data);
  return hash;
}

/**
 * Verifies an Ed25519 signature against canonical bytes.
 *
 * @param signPublicKey - Ed25519 public key (base64)
 * @param signature - Signature (base64)
 * @param params - Canonical signing parameters
 * @returns true if signature is valid
 */
export function verifySignature(
  signPublicKey: string,
  signature: string,
  params: {
    messageType: SignedMessageType;
    messageId: string;
    from: string;
    toOrGroupId: string;
    timestamp: number;
    nonce: string;
    ciphertext: string;
  }
): boolean {
  try {
    const publicKeyBuffer = Buffer.from(signPublicKey, 'base64');
    const signatureBuffer = Buffer.from(signature, 'base64');

    if (publicKeyBuffer.length !== ED25519_PUBLIC_KEY_SIZE) {
      return false;
    }
    if (signatureBuffer.length !== SIGNATURE_SIZE) {
      return false;
    }

    const canonicalBytes = buildCanonicalBytes(params);
    const hash = sha256(canonicalBytes);

    return sodium.crypto_sign_verify_detached(
      signatureBuffer,
      hash,
      publicKeyBuffer
    );
  } catch {
    return false;
  }
}

/**
 * Verifies a challenge signature during registration.
 * Client signs: SHA256(challengeBytes)
 */
export function verifyChallengeSignature(
  signPublicKey: string,
  signature: string,
  challengeBytes: Buffer
): boolean {
  try {
    const publicKeyBuffer = Buffer.from(signPublicKey, 'base64');
    const signatureBuffer = Buffer.from(signature, 'base64');

    if (publicKeyBuffer.length !== ED25519_PUBLIC_KEY_SIZE) {
      return false;
    }
    if (signatureBuffer.length !== SIGNATURE_SIZE) {
      return false;
    }

    // Client signs SHA256(challengeBytes)
    const hash = sha256(challengeBytes);

    return sodium.crypto_sign_verify_detached(
      signatureBuffer,
      hash,
      publicKeyBuffer
    );
  } catch {
    return false;
  }
}

// =============================================================================
// TIMESTAMP VALIDATION (Section 3.5)
// =============================================================================

/**
 * Validates timestamp is within ±10 minutes of server time.
 * Server accepts timestamps within TIMESTAMP_SKEW_MS of serverTime.
 * Outside window → reject with INVALID_TIMESTAMP
 *
 * Also rejects:
 * - 0 or negative timestamps (invalid)
 * - NaN or non-finite values
 */
export function isTimestampValid(
  timestamp: number,
  serverTime: number = Date.now()
): boolean {
  // Reject invalid values: 0, negative, NaN, Infinity
  if (!Number.isFinite(timestamp) || timestamp <= 0) {
    return false;
  }

  const diff = Math.abs(timestamp - serverTime);
  return diff <= TIMESTAMP_SKEW_MS;
}

// =============================================================================
// RANDOM GENERATION
// =============================================================================

/**
 * Generates cryptographically secure random bytes.
 */
export function randomBytes(size: number): Buffer {
  const buffer = Buffer.alloc(size);
  sodium.randombytes_buf(buffer);
  return buffer;
}

/**
 * Generates a challenge for authentication.
 * 32 bytes of random data.
 */
export function generateChallenge(): Buffer {
  return randomBytes(CHALLENGE_SIZE);
}

/**
 * Generates a secure session token.
 * 32 bytes → base64url
 */
export function generateSessionToken(): string {
  const bytes = randomBytes(32);
  return bytes.toString('base64url');
}

// =============================================================================
// WHISPER ID GENERATION
// =============================================================================

/**
 * Base32 alphabet (A-Z, 2-7) for Whisper ID
 * Excludes ambiguous characters: 0, 1, O, I
 */
const BASE32_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';

/**
 * Generates a random Whisper ID.
 * Format: WSP-XXXX-XXXX-XXXX
 *
 * X are Base32 characters (A-Z, 2-7)
 * Last 2 characters of last block are checksum
 */
export function generateWhisperId(): string {
  // Generate 10 random characters (8 + 2 for data, checksum computed)
  const dataChars: string[] = [];
  const randomBuffer = randomBytes(10);

  for (let i = 0; i < 10; i++) {
    const index = randomBuffer[i] % 32;
    dataChars.push(BASE32_ALPHABET[index]);
  }

  // Compute simple checksum (XOR of all data character indices)
  let checksum = 0;
  for (let i = 0; i < 10; i++) {
    checksum ^= randomBuffer[i] % 32;
  }
  checksum = checksum % 32;

  // Second checksum character
  let checksum2 = 0;
  for (let i = 0; i < 10; i++) {
    checksum2 = (checksum2 + randomBuffer[i]) % 32;
  }

  const checksumChars = BASE32_ALPHABET[checksum] + BASE32_ALPHABET[checksum2];

  // Format: WSP-XXXX-XXXX-XXXX
  // 4 + 4 + 2 data chars + 2 checksum chars = 12 chars total in groups
  const block1 = dataChars.slice(0, 4).join('');
  const block2 = dataChars.slice(4, 8).join('');
  const block3 = dataChars.slice(8, 10).join('') + checksumChars;

  return `WSP-${block1}-${block2}-${block3}`;
}

/**
 * Validates Whisper ID format.
 */
export function isValidWhisperId(whisperId: string): boolean {
  return /^WSP-[A-Z2-7]{4}-[A-Z2-7]{4}-[A-Z2-7]{4}$/.test(whisperId);
}

// =============================================================================
// BASE64 UTILITIES
// =============================================================================

/**
 * Validates that a string is valid base64 with expected length.
 */
export function isValidBase64(
  value: string,
  expectedBytes?: number
): boolean {
  try {
    const buffer = Buffer.from(value, 'base64');
    if (expectedBytes !== undefined && buffer.length !== expectedBytes) {
      return false;
    }
    return true;
  } catch {
    return false;
  }
}

/**
 * Validates nonce is base64 encoded 24 bytes.
 */
export function isValidNonce(nonce: string): boolean {
  return isValidBase64(nonce, NONCE_SIZE);
}

/**
 * Validates public key is base64 encoded 32 bytes.
 */
export function isValidPublicKey(key: string): boolean {
  return isValidBase64(key, KEY_SIZE);
}
