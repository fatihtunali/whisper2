/**
 * Whisper2 Messaging JSON Schemas
 * Following WHISPER-REBUILD.md Section 4
 *
 * Schemas for:
 * - send_message
 * - delivery_receipt
 * - fetch_pending
 */

import { PROTOCOL_VERSION, CRYPTO_VERSION } from '../types/protocol';

// =============================================================================
// COMMON PATTERNS
// =============================================================================

const whisperIdPattern = '^WSP-[A-Z2-7]{4}-[A-Z2-7]{4}-[A-Z2-7]{4}$';
const base64Pattern = '^[A-Za-z0-9+/]+=*$';

// =============================================================================
// SEND_MESSAGE SCHEMA (Section 4.1)
// =============================================================================

export const sendMessageSchema = {
  type: 'object',
  properties: {
    protocolVersion: {
      type: 'integer',
      const: PROTOCOL_VERSION,
    },
    cryptoVersion: {
      type: 'integer',
      const: CRYPTO_VERSION,
    },
    sessionToken: {
      type: 'string',
      minLength: 32,
      maxLength: 64,
    },
    messageId: {
      type: 'string',
      format: 'uuid',
    },
    from: {
      type: 'string',
      pattern: whisperIdPattern,
    },
    to: {
      type: 'string',
      pattern: whisperIdPattern,
    },
    msgType: {
      type: 'string',
      enum: ['text', 'image', 'voice', 'file', 'system'],
    },
    timestamp: {
      type: 'number',
    },
    nonce: {
      type: 'string',
      pattern: base64Pattern,
      minLength: 30, // base64 of 24 bytes
      maxLength: 36,
    },
    ciphertext: {
      type: 'string',
      pattern: base64Pattern,
    },
    sig: {
      type: 'string',
      pattern: base64Pattern,
      minLength: 80, // base64 of 64 bytes
      maxLength: 92,
    },
    replyTo: {
      type: 'string',
      format: 'uuid',
      nullable: true,
    },
    reactions: {
      type: 'object',
      additionalProperties: {
        type: 'array',
        items: { type: 'string', pattern: whisperIdPattern },
      },
      nullable: true,
    },
    attachment: {
      type: 'object',
      properties: {
        objectKey: { type: 'string', minLength: 1, maxLength: 255 },
        contentType: { type: 'string', minLength: 1, maxLength: 100 },
        ciphertextSize: { type: 'number', minimum: 0 },
        fileNonce: { type: 'string', pattern: base64Pattern },
        fileKeyBox: {
          type: 'object',
          properties: {
            nonce: { type: 'string', pattern: base64Pattern },
            ciphertext: { type: 'string', pattern: base64Pattern },
          },
          required: ['nonce', 'ciphertext'],
          additionalProperties: false,
        },
      },
      required: ['objectKey', 'contentType', 'ciphertextSize', 'fileNonce', 'fileKeyBox'],
      additionalProperties: false,
      nullable: true,
    },
  },
  required: [
    'protocolVersion',
    'cryptoVersion',
    'sessionToken',
    'messageId',
    'from',
    'to',
    'msgType',
    'timestamp',
    'nonce',
    'ciphertext',
    'sig',
  ],
  additionalProperties: false,
};

// =============================================================================
// DELIVERY_RECEIPT SCHEMA (Section 4.3)
// =============================================================================

export const deliveryReceiptSchema = {
  type: 'object',
  properties: {
    protocolVersion: {
      type: 'integer',
      const: PROTOCOL_VERSION,
    },
    cryptoVersion: {
      type: 'integer',
      const: CRYPTO_VERSION,
    },
    sessionToken: {
      type: 'string',
      minLength: 32,
      maxLength: 64,
    },
    messageId: {
      type: 'string',
      format: 'uuid',
    },
    from: {
      type: 'string',
      pattern: whisperIdPattern,
    },
    to: {
      type: 'string',
      pattern: whisperIdPattern,
    },
    status: {
      type: 'string',
      enum: ['delivered', 'read'],
    },
    timestamp: {
      type: 'number',
    },
  },
  required: [
    'protocolVersion',
    'cryptoVersion',
    'sessionToken',
    'messageId',
    'from',
    'to',
    'status',
    'timestamp',
  ],
  additionalProperties: false,
};

// =============================================================================
// FETCH_PENDING SCHEMA (Section 5.2)
// =============================================================================

export const fetchPendingSchema = {
  type: 'object',
  properties: {
    protocolVersion: {
      type: 'integer',
      const: PROTOCOL_VERSION,
    },
    cryptoVersion: {
      type: 'integer',
      const: CRYPTO_VERSION,
    },
    sessionToken: {
      type: 'string',
      minLength: 32,
      maxLength: 64,
    },
    cursor: {
      type: 'string',
      nullable: true,
    },
    limit: {
      type: 'number',
      minimum: 1,
      maximum: 100,
      nullable: true,
    },
  },
  required: ['protocolVersion', 'cryptoVersion', 'sessionToken'],
  additionalProperties: false,
};
