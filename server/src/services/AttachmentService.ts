/**
 * Whisper2 Attachment Service
 * Following WHISPER-REBUILD.md Step 4
 *
 * Handles:
 * - Presigned upload URLs for encrypted attachments
 * - Presigned download URLs with access control
 * - Garbage collection of expired attachments
 * - Granting access to recipients
 */

import {
  S3Client,
  PutObjectCommand,
  GetObjectCommand,
  DeleteObjectCommand,
  HeadObjectCommand,
} from '@aws-sdk/client-s3';
import { getSignedUrl } from '@aws-sdk/s3-request-presigner';
import { v4 as uuidv4 } from 'uuid';
import { query } from '../db/postgres';
import { logger } from '../utils/logger';

// =============================================================================
// CONSTANTS (Frozen)
// =============================================================================

const WHISPER_ATTACH_PREFIX = process.env.WHISPER_ATTACH_PREFIX || 'whisper/att';
const MAX_ATTACHMENT_BYTES = parseInt(process.env.MAX_ATTACHMENT_BYTES || '104857600', 10); // 100MB
const PRESIGN_TTL_SEC = parseInt(process.env.PRESIGN_TTL_SEC || '300', 10); // 5 minutes
const ATTACHMENT_TTL_DAYS = parseInt(process.env.ATTACHMENT_TTL_DAYS || '30', 10);
const ATTACHMENT_TTL_MS = ATTACHMENT_TTL_DAYS * 24 * 60 * 60 * 1000;

// Allowed content types for original files (iOS + Android compatibility)
const ALLOWED_CONTENT_TYPES = new Set([
  // Images
  'image/jpeg',
  'image/png',
  'image/gif',
  'image/webp',
  'image/heic',
  'image/heif',
  'image/bmp',
  'image/tiff',
  'image/svg+xml',

  // Videos - iOS
  'video/mp4',
  'video/quicktime',
  'video/mov',
  'video/x-m4v',
  'video/m4v',

  // Videos - Android & cross-platform
  'video/3gpp',
  'video/3gpp2',
  'video/webm',
  'video/mpeg',
  'video/x-msvideo',  // AVI
  'video/avi',
  'video/x-matroska', // MKV
  'video/ogg',

  // Audio - iOS
  'audio/aac',
  'audio/m4a',
  'audio/x-m4a',
  'audio/mp4',
  'audio/x-caf',      // Core Audio Format
  'audio/aiff',
  'audio/x-aiff',

  // Audio - Android & cross-platform
  'audio/mpeg',       // MP3
  'audio/mp3',
  'audio/ogg',
  'audio/wav',
  'audio/x-wav',
  'audio/webm',
  'audio/3gpp',
  'audio/amr',
  'audio/flac',
  'audio/x-flac',

  // Documents
  'application/pdf',
  'application/msword',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  'application/vnd.ms-excel',
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  'application/vnd.ms-powerpoint',
  'application/vnd.openxmlformats-officedocument.presentationml.presentation',
  'text/plain',
  'text/csv',
  'application/zip',
  'application/x-zip-compressed',
  'application/json',

  // Fallback
  'application/octet-stream',
]);

// Object key validation
const OBJECT_KEY_PATTERN = /^[a-zA-Z0-9_\-./]+$/;
const MAX_OBJECT_KEY_LEN = 255;

// =============================================================================
// S3 CLIENT
// =============================================================================

let s3Client: S3Client | null = null;

function getS3Client(): S3Client {
  if (!s3Client) {
    const endpoint = process.env.SPACES_ENDPOINT;
    const region = process.env.SPACES_REGION || 'ams3';
    const accessKeyId = process.env.SPACES_ACCESS_KEY;
    const secretAccessKey = process.env.SPACES_SECRET_KEY;

    if (!endpoint || !accessKeyId || !secretAccessKey) {
      throw new Error('Missing Spaces configuration (SPACES_ENDPOINT, SPACES_ACCESS_KEY, SPACES_SECRET_KEY)');
    }

    s3Client = new S3Client({
      endpoint,
      region,
      credentials: {
        accessKeyId,
        secretAccessKey,
      },
      forcePathStyle: false,
    });
  }
  return s3Client;
}

function getBucket(): string {
  const bucket = process.env.SPACES_BUCKET;
  if (!bucket) {
    throw new Error('Missing SPACES_BUCKET configuration');
  }
  return bucket;
}

// =============================================================================
// TYPES
// =============================================================================

export interface PresignUploadRequest {
  contentType: string;
  sizeBytes: number;
}

export interface PresignUploadResponse {
  objectKey: string;
  uploadUrl: string;
  expiresAtMs: number;
  headers: {
    'Content-Type': string;
  };
}

export interface PresignDownloadRequest {
  objectKey: string;
}

export interface PresignDownloadResponse {
  objectKey: string;
  downloadUrl: string;
  expiresAtMs: number;
  sizeBytes: number;
  contentType: string;
}

export interface GCResult {
  success: boolean;
  deletedCount: number;
  failedCount: number;
  accessRowsDeleted: number;
}

// =============================================================================
// VALIDATION HELPERS
// =============================================================================

export function validateObjectKey(objectKey: string): { valid: boolean; error?: string } {
  if (!objectKey || objectKey.length === 0) {
    return { valid: false, error: 'Object key is required' };
  }

  if (objectKey.length > MAX_OBJECT_KEY_LEN) {
    return { valid: false, error: `Object key exceeds maximum length (${MAX_OBJECT_KEY_LEN})` };
  }

  if (!objectKey.startsWith(WHISPER_ATTACH_PREFIX + '/')) {
    return { valid: false, error: `Object key must start with '${WHISPER_ATTACH_PREFIX}/'` };
  }

  if (!OBJECT_KEY_PATTERN.test(objectKey)) {
    return { valid: false, error: 'Object key contains invalid characters' };
  }

  if (objectKey.includes('..')) {
    return { valid: false, error: 'Object key contains path traversal' };
  }

  return { valid: true };
}

export function isValidContentType(contentType: string): boolean {
  return ALLOWED_CONTENT_TYPES.has(contentType);
}

export function isValidSize(sizeBytes: number): boolean {
  return Number.isFinite(sizeBytes) && sizeBytes > 0 && sizeBytes <= MAX_ATTACHMENT_BYTES;
}

// =============================================================================
// ATTACHMENT SERVICE
// =============================================================================

export class AttachmentService {
  /**
   * Generate presigned upload URL for a new attachment.
   */
  async presignUpload(
    ownerWhisperId: string,
    request: PresignUploadRequest
  ): Promise<{ success: true; data: PresignUploadResponse } | { success: false; error: string; code: string }> {
    const { contentType, sizeBytes } = request;

    // Validate size
    if (!isValidSize(sizeBytes)) {
      return {
        success: false,
        error: `Invalid size. Must be > 0 and <= ${MAX_ATTACHMENT_BYTES} bytes`,
        code: 'INVALID_PAYLOAD',
      };
    }

    // Validate content type
    if (!isValidContentType(contentType)) {
      return {
        success: false,
        error: 'Content type not allowed',
        code: 'INVALID_PAYLOAD',
      };
    }

    // Generate object key: whisper/att/YYYY/MM/{ownerWhisperId}/{uuid}.bin
    const now = new Date();
    const year = now.getUTCFullYear();
    const month = String(now.getUTCMonth() + 1).padStart(2, '0');
    const objectKey = `${WHISPER_ATTACH_PREFIX}/${year}/${month}/${ownerWhisperId}/${uuidv4()}.bin`;

    // Validate generated key (should always pass, but safety check)
    const keyValidation = validateObjectKey(objectKey);
    if (!keyValidation.valid) {
      logger.error({ objectKey, error: keyValidation.error }, 'Generated invalid object key');
      return {
        success: false,
        error: 'Failed to generate object key',
        code: 'INTERNAL_ERROR',
      };
    }

    const createdAtMs = Date.now();
    const expiresAtMs = createdAtMs + ATTACHMENT_TTL_MS;

    try {
      // Insert attachment record
      await query(
        `INSERT INTO attachments (object_key, owner_whisper_id, size_bytes, content_type, created_at_ms, expires_at_ms, status)
         VALUES ($1, $2, $3, $4, $5, $6, 'active')`,
        [objectKey, ownerWhisperId, sizeBytes, contentType, createdAtMs, expiresAtMs]
      );

      // Generate presigned PUT URL
      // Upload Content-Type must be application/octet-stream (ciphertext)
      const client = getS3Client();
      const command = new PutObjectCommand({
        Bucket: getBucket(),
        Key: objectKey,
        ContentType: 'application/octet-stream',
        ContentLength: sizeBytes,
      });

      const uploadUrl = await getSignedUrl(client, command, { expiresIn: PRESIGN_TTL_SEC });

      logger.info(
        { objectKey, ownerWhisperId, sizeBytes, contentType },
        'Presigned upload URL generated'
      );

      return {
        success: true,
        data: {
          objectKey,
          uploadUrl,
          expiresAtMs,
          headers: {
            'Content-Type': 'application/octet-stream',
          },
        },
      };
    } catch (err) {
      logger.error({ err, ownerWhisperId }, 'Failed to generate presigned upload URL');
      return {
        success: false,
        error: 'Failed to generate upload URL',
        code: 'INTERNAL_ERROR',
      };
    }
  }

  /**
   * Generate presigned download URL for an attachment.
   * Requires authorization: owner OR has access grant.
   */
  async presignDownload(
    requesterWhisperId: string,
    request: PresignDownloadRequest
  ): Promise<{ success: true; data: PresignDownloadResponse } | { success: false; error: string; code: string }> {
    const { objectKey } = request;

    // Validate object key
    const keyValidation = validateObjectKey(objectKey);
    if (!keyValidation.valid) {
      return {
        success: false,
        error: keyValidation.error || 'Invalid object key',
        code: 'INVALID_PAYLOAD',
      };
    }

    try {
      // Get attachment record
      const attachmentResult = await query<{
        object_key: string;
        owner_whisper_id: string;
        size_bytes: number;
        content_type: string;
        expires_at_ms: string;
        status: string;
      }>(
        `SELECT object_key, owner_whisper_id, size_bytes, content_type, expires_at_ms, status
         FROM attachments
         WHERE object_key = $1`,
        [objectKey]
      );

      if (attachmentResult.rows.length === 0) {
        return {
          success: false,
          error: 'Attachment not found',
          code: 'NOT_FOUND',
        };
      }

      const attachment = attachmentResult.rows[0];

      // Check status
      if (attachment.status !== 'active') {
        return {
          success: false,
          error: 'Attachment is not available',
          code: 'NOT_FOUND',
        };
      }

      // Check expiry
      const now = Date.now();
      if (parseInt(attachment.expires_at_ms, 10) <= now) {
        return {
          success: false,
          error: 'Attachment has expired',
          code: 'NOT_FOUND',
        };
      }

      // Check authorization: owner OR has access grant
      const isOwner = attachment.owner_whisper_id === requesterWhisperId;

      if (!isOwner) {
        const accessResult = await query(
          `SELECT 1 FROM attachment_access
           WHERE object_key = $1 AND whisper_id = $2 AND expires_at_ms > $3`,
          [objectKey, requesterWhisperId, now]
        );

        if (accessResult.rows.length === 0) {
          return {
            success: false,
            error: 'Not authorized to access this attachment',
            code: 'FORBIDDEN',
          };
        }
      }

      // Generate presigned GET URL
      const client = getS3Client();
      const command = new GetObjectCommand({
        Bucket: getBucket(),
        Key: objectKey,
      });

      const downloadUrl = await getSignedUrl(client, command, { expiresIn: PRESIGN_TTL_SEC });

      logger.info(
        { objectKey, requesterWhisperId },
        'Presigned download URL generated'
      );

      return {
        success: true,
        data: {
          objectKey,
          downloadUrl,
          expiresAtMs: parseInt(attachment.expires_at_ms, 10),
          sizeBytes: parseInt(String(attachment.size_bytes), 10),
          contentType: attachment.content_type,
        },
      };
    } catch (err) {
      logger.error({ err, objectKey, requesterWhisperId }, 'Failed to generate presigned download URL');
      return {
        success: false,
        error: 'Failed to generate download URL',
        code: 'INTERNAL_ERROR',
      };
    }
  }

  /**
   * Grant download access to a recipient when they receive a message with attachment.
   */
  async grantAccess(objectKey: string, recipientWhisperId: string): Promise<boolean> {
    const keyValidation = validateObjectKey(objectKey);
    if (!keyValidation.valid) {
      logger.warn({ objectKey, error: keyValidation.error }, 'Invalid object key for access grant');
      return false;
    }

    try {
      // Get attachment expiry
      const attachmentResult = await query<{ expires_at_ms: string }>(
        `SELECT expires_at_ms FROM attachments WHERE object_key = $1 AND status = 'active'`,
        [objectKey]
      );

      if (attachmentResult.rows.length === 0) {
        logger.warn({ objectKey }, 'Attachment not found for access grant');
        return false;
      }

      const expiresAtMs = parseInt(attachmentResult.rows[0].expires_at_ms, 10);
      const grantedAtMs = Date.now();

      // Insert access row (upsert to handle duplicates)
      await query(
        `INSERT INTO attachment_access (object_key, whisper_id, granted_at_ms, expires_at_ms)
         VALUES ($1, $2, $3, $4)
         ON CONFLICT (object_key, whisper_id) DO UPDATE SET
           granted_at_ms = EXCLUDED.granted_at_ms,
           expires_at_ms = EXCLUDED.expires_at_ms`,
        [objectKey, recipientWhisperId, grantedAtMs, expiresAtMs]
      );

      logger.info(
        { objectKey, recipientWhisperId },
        'Attachment access granted'
      );

      return true;
    } catch (err) {
      logger.error({ err, objectKey, recipientWhisperId }, 'Failed to grant attachment access');
      return false;
    }
  }

  /**
   * Run garbage collection:
   * 1. Delete expired attachments from Spaces and mark as deleted
   * 2. Clean up expired access rows
   */
  async runGC(): Promise<GCResult> {
    const now = Date.now();
    let deletedCount = 0;
    let failedCount = 0;
    let accessRowsDeleted = 0;

    try {
      // Find expired attachments
      const expiredResult = await query<{ object_key: string }>(
        `SELECT object_key FROM attachments
         WHERE status = 'active' AND expires_at_ms <= $1`,
        [now]
      );

      logger.info({ count: expiredResult.rows.length }, 'Found expired attachments for GC');

      const client = getS3Client();
      const bucket = getBucket();

      for (const row of expiredResult.rows) {
        const objectKey = row.object_key;

        // Safety: only delete keys starting with whisper/att/
        if (!objectKey.startsWith(WHISPER_ATTACH_PREFIX + '/')) {
          logger.error({ objectKey }, 'GC refusing to delete key outside prefix');
          failedCount++;
          continue;
        }

        try {
          // Delete from Spaces
          await client.send(new DeleteObjectCommand({
            Bucket: bucket,
            Key: objectKey,
          }));

          // Mark as deleted in DB
          await query(
            `UPDATE attachments SET status = 'deleted', deleted_at_ms = $1 WHERE object_key = $2`,
            [now, objectKey]
          );

          deletedCount++;
          logger.info({ objectKey }, 'GC deleted attachment');
        } catch (err) {
          logger.error({ err, objectKey }, 'GC failed to delete attachment');
          failedCount++;
        }
      }

      // Clean up expired access rows
      const accessResult = await query(
        `DELETE FROM attachment_access WHERE expires_at_ms <= $1`,
        [now]
      );

      accessRowsDeleted = accessResult.rowCount || 0;

      logger.info(
        { deletedCount, failedCount, accessRowsDeleted },
        'GC completed'
      );

      return {
        success: true,
        deletedCount,
        failedCount,
        accessRowsDeleted,
      };
    } catch (err) {
      logger.error({ err }, 'GC failed');
      return {
        success: false,
        deletedCount,
        failedCount,
        accessRowsDeleted,
      };
    }
  }

  /**
   * Check if an attachment exists and is active.
   */
  async exists(objectKey: string): Promise<boolean> {
    try {
      const result = await query(
        `SELECT 1 FROM attachments WHERE object_key = $1 AND status = 'active'`,
        [objectKey]
      );
      return result.rows.length > 0;
    } catch {
      return false;
    }
  }
}

export const attachmentService = new AttachmentService();
