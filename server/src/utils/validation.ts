/**
 * Shared validation utilities - eliminates duplication across services
 */
import { redis } from '../db/redis';
import { isValidNonce } from './crypto';

// Attachment validation constants (single source of truth)
export const ATTACHMENT_CONSTANTS = {
  MAX_OBJECT_KEY_LEN: 255,
  OBJECT_KEY_PREFIX: 'whisper/att/',
  OBJECT_KEY_PATTERN: /^[a-zA-Z0-9_\-./]+$/,
};

// Comprehensive list of allowed content types
export const ALLOWED_CONTENT_TYPES = new Set([
  // Images
  'image/jpeg',
  'image/png',
  'image/gif',
  'image/webp',
  'image/heic',
  'image/heif',
  // Videos
  'video/mp4',
  'video/quicktime',
  'video/x-m4v',
  'video/3gpp',
  'video/webm',
  'video/x-msvideo',
  'video/x-matroska',
  // Audio
  'audio/aac',
  'audio/mp4',
  'audio/mpeg',
  'audio/ogg',
  'audio/wav',
  'audio/webm',
  'audio/x-m4a',
  'audio/m4a',
  'audio/flac',
  // Documents
  'application/pdf',
  'application/msword',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  'application/vnd.ms-excel',
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  'text/plain',
  'application/zip',
  'application/x-zip-compressed',
  // Generic
  'application/octet-stream',
]);

export interface AttachmentPointer {
  objectKey: string;
  contentType: string;
  ciphertextSize: number;
  fileNonce: string;
  fileKeyBox: {
    nonce: string;
    ciphertext: string;
  };
}

/**
 * Validate attachment object key format
 */
export function validateObjectKey(objectKey: string): { valid: boolean; error?: string } {
  const { MAX_OBJECT_KEY_LEN, OBJECT_KEY_PREFIX, OBJECT_KEY_PATTERN } = ATTACHMENT_CONSTANTS;

  if (!objectKey || objectKey.length === 0 || objectKey.length > MAX_OBJECT_KEY_LEN) {
    return { valid: false, error: 'Invalid attachment objectKey' };
  }
  if (!objectKey.startsWith(OBJECT_KEY_PREFIX)) {
    return { valid: false, error: `Attachment objectKey must start with '${OBJECT_KEY_PREFIX}'` };
  }
  if (!OBJECT_KEY_PATTERN.test(objectKey)) {
    return { valid: false, error: 'Invalid attachment objectKey format' };
  }
  if (objectKey.includes('..')) {
    return { valid: false, error: 'Invalid attachment objectKey (path traversal)' };
  }
  return { valid: true };
}

/**
 * Validate full attachment pointer
 */
export function validateAttachment(att: AttachmentPointer): { valid: boolean; error?: string } {
  // Validate object key
  const keyValidation = validateObjectKey(att.objectKey);
  if (!keyValidation.valid) {
    return keyValidation;
  }

  // Validate content type
  if (!att.contentType || !ALLOWED_CONTENT_TYPES.has(att.contentType)) {
    return { valid: false, error: 'Invalid attachment contentType' };
  }

  // Validate size
  if (!Number.isFinite(att.ciphertextSize) || att.ciphertextSize <= 0) {
    return { valid: false, error: 'Invalid attachment ciphertextSize' };
  }

  // Validate nonces
  if (!isValidNonce(att.fileNonce)) {
    return { valid: false, error: 'Invalid attachment fileNonce' };
  }
  if (!att.fileKeyBox || !isValidNonce(att.fileKeyBox.nonce)) {
    return { valid: false, error: 'Invalid attachment fileKeyBox.nonce' };
  }

  return { valid: true };
}

// Deduplication TTL in seconds
const DEDUP_TTL_SECONDS = 3600; // 1 hour

/**
 * Check if a message has already been processed (deduplication)
 */
export async function isDuplicateMessage(senderId: string, messageId: string): Promise<boolean> {
  const key = `dedup:${senderId}:${messageId}`;
  return await redis.exists(key);
}

/**
 * Mark a message as processed for deduplication
 */
export async function markMessageProcessed(senderId: string, messageId: string): Promise<void> {
  const key = `dedup:${senderId}:${messageId}`;
  await redis.setWithTTL(key, '1', DEDUP_TTL_SECONDS);
}
