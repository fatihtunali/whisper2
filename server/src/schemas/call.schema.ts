/**
 * Whisper2 Call JSON Schemas
 * Following WHISPER-REBUILD.md Step 7
 *
 * Schemas for:
 * - get_turn_credentials
 * - call_initiate
 * - call_ringing
 * - call_answer
 * - call_ice_candidate
 * - call_end
 */

import { PROTOCOL_VERSION, CRYPTO_VERSION } from '../types/protocol';

// =============================================================================
// COMMON PATTERNS
// =============================================================================

const whisperIdPattern = '^WSP-[A-Z2-7]{4}-[A-Z2-7]{4}-[A-Z2-7]{4}$';
const base64Pattern = '^[A-Za-z0-9+/]+=*$';

// =============================================================================
// GET_TURN_CREDENTIALS SCHEMA
// =============================================================================

export const getTurnCredentialsSchema = {
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
  },
  required: ['protocolVersion', 'cryptoVersion', 'sessionToken'],
  additionalProperties: false,
};

// =============================================================================
// CALL_INITIATE SCHEMA
// =============================================================================

export const callInitiateSchema = {
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
    callId: {
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
    isVideo: {
      type: 'boolean',
    },
    timestamp: {
      type: 'number',
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
  required: [
    'protocolVersion',
    'cryptoVersion',
    'sessionToken',
    'callId',
    'from',
    'to',
    'isVideo',
    'timestamp',
    'nonce',
    'ciphertext',
    'sig',
  ],
  additionalProperties: false,
};

// =============================================================================
// CALL_RINGING SCHEMA
// =============================================================================

export const callRingingSchema = {
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
    callId: {
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
    timestamp: {
      type: 'number',
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
  required: [
    'protocolVersion',
    'cryptoVersion',
    'sessionToken',
    'callId',
    'from',
    'to',
    'timestamp',
    'nonce',
    'ciphertext',
    'sig',
  ],
  additionalProperties: false,
};

// =============================================================================
// CALL_ANSWER SCHEMA
// =============================================================================

export const callAnswerSchema = {
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
    callId: {
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
    timestamp: {
      type: 'number',
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
  required: [
    'protocolVersion',
    'cryptoVersion',
    'sessionToken',
    'callId',
    'from',
    'to',
    'timestamp',
    'nonce',
    'ciphertext',
    'sig',
  ],
  additionalProperties: false,
};

// =============================================================================
// CALL_ICE_CANDIDATE SCHEMA
// =============================================================================

export const callIceCandidateSchema = {
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
    callId: {
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
    timestamp: {
      type: 'number',
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
  required: [
    'protocolVersion',
    'cryptoVersion',
    'sessionToken',
    'callId',
    'from',
    'to',
    'timestamp',
    'nonce',
    'ciphertext',
    'sig',
  ],
  additionalProperties: false,
};

// =============================================================================
// CALL_END SCHEMA
// =============================================================================

export const callEndSchema = {
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
    callId: {
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
    timestamp: {
      type: 'number',
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
    reason: {
      type: 'string',
      enum: ['ended', 'declined', 'busy', 'timeout', 'failed', 'cancelled'],
    },
  },
  required: [
    'protocolVersion',
    'cryptoVersion',
    'sessionToken',
    'callId',
    'from',
    'to',
    'timestamp',
    'nonce',
    'ciphertext',
    'sig',
    'reason',
  ],
  additionalProperties: false,
};
