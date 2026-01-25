/**
 * Whisper2 Group JSON Schemas
 * Following WHISPER-REBUILD.md Step 5
 *
 * Schemas for:
 * - group_create
 * - group_update
 * - group_send_message
 */

import { PROTOCOL_VERSION, CRYPTO_VERSION } from '../types/protocol';

// =============================================================================
// COMMON PATTERNS
// =============================================================================

const whisperIdPattern = '^WSP-[A-Z2-7]{4}-[A-Z2-7]{4}-[A-Z2-7]{4}$';
const base64Pattern = '^[A-Za-z0-9+/]+=*$';

// =============================================================================
// GROUP_CREATE SCHEMA
// =============================================================================

export const groupCreateSchema = {
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
    title: {
      type: 'string',
      minLength: 1,
      maxLength: 64,
    },
    memberIds: {
      type: 'array',
      items: {
        type: 'string',
        pattern: whisperIdPattern,
      },
      minItems: 0,
      maxItems: 49, // Max 50 total including creator
    },
  },
  required: ['protocolVersion', 'cryptoVersion', 'sessionToken', 'title', 'memberIds'],
  additionalProperties: false,
};

// =============================================================================
// GROUP_UPDATE SCHEMA
// =============================================================================

export const groupUpdateSchema = {
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
    groupId: {
      type: 'string',
      format: 'uuid',
    },
    title: {
      type: 'string',
      minLength: 1,
      maxLength: 64,
    },
    addMembers: {
      type: 'array',
      items: {
        type: 'string',
        pattern: whisperIdPattern,
      },
    },
    removeMembers: {
      type: 'array',
      items: {
        type: 'string',
        pattern: whisperIdPattern,
      },
    },
    roleChanges: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          whisperId: {
            type: 'string',
            pattern: whisperIdPattern,
          },
          role: {
            type: 'string',
            enum: ['admin', 'member'],
          },
        },
        required: ['whisperId', 'role'],
        additionalProperties: false,
      },
    },
  },
  required: ['protocolVersion', 'cryptoVersion', 'sessionToken', 'groupId'],
  additionalProperties: false,
};

// =============================================================================
// GROUP_SEND_MESSAGE SCHEMA
// =============================================================================

export const groupSendMessageSchema = {
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
    groupId: {
      type: 'string',
      format: 'uuid',
    },
    messageId: {
      type: 'string',
      format: 'uuid',
    },
    from: {
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
    recipients: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          to: {
            type: 'string',
            pattern: whisperIdPattern,
          },
          nonce: {
            type: 'string',
            pattern: base64Pattern,
            minLength: 30,
            maxLength: 36,
          },
          ciphertext: {
            type: 'string',
            pattern: base64Pattern,
          },
          sig: {
            type: 'string',
            pattern: base64Pattern,
            minLength: 80,
            maxLength: 92,
          },
        },
        required: ['to', 'nonce', 'ciphertext', 'sig'],
        additionalProperties: false,
      },
      minItems: 1,
      maxItems: 49, // Max group members - 1 (excluding sender)
    },
    replyTo: {
      type: 'string',
      format: 'uuid',
    },
    reactions: {
      type: 'object',
      additionalProperties: {
        type: 'array',
        items: { type: 'string', pattern: whisperIdPattern },
      },
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
    },
  },
  required: [
    'protocolVersion',
    'cryptoVersion',
    'sessionToken',
    'groupId',
    'messageId',
    'from',
    'msgType',
    'timestamp',
    'recipients',
  ],
  additionalProperties: false,
};
