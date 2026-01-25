/**
 * Whisper2 Auth JSON Schemas
 * Following WHISPER-REBUILD.md Section 3.3
 *
 * Server validates all incoming payloads with AJV.
 * Rule: Server REJECTS invalid payloads with INVALID_PAYLOAD before business logic.
 */

import { JSONSchemaType } from 'ajv';
import {
  RegisterBeginPayload,
  RegisterProofPayload,
  SessionRefreshPayload,
  LogoutPayload,
  PROTOCOL_VERSION,
  CRYPTO_VERSION,
} from '../types/protocol';

// =============================================================================
// COMMON DEFINITIONS
// =============================================================================

const whisperIdPattern = '^WSP-[A-Z2-7]{4}-[A-Z2-7]{4}-[A-Z2-7]{4}$';
const uuidPattern =
  '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';
const base64Pattern = '^[A-Za-z0-9+/]+=*$';

// =============================================================================
// REGISTER_BEGIN SCHEMA (Section 3.3 Step 1)
// =============================================================================

export const registerBeginSchema: JSONSchemaType<RegisterBeginPayload> = {
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
    whisperId: {
      type: 'string',
      pattern: whisperIdPattern,
      nullable: true,
    },
    deviceId: {
      type: 'string',
      format: 'uuid',
    },
    platform: {
      type: 'string',
      enum: ['ios', 'android'],
    },
  },
  required: ['protocolVersion', 'cryptoVersion', 'deviceId', 'platform'],
  additionalProperties: false,
};

// =============================================================================
// REGISTER_PROOF SCHEMA (Section 3.3 Step 3)
// =============================================================================

export const registerProofSchema: JSONSchemaType<RegisterProofPayload> = {
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
    challengeId: {
      type: 'string',
      format: 'uuid',
    },
    deviceId: {
      type: 'string',
      format: 'uuid',
    },
    platform: {
      type: 'string',
      enum: ['ios', 'android'],
    },
    whisperId: {
      type: 'string',
      pattern: whisperIdPattern,
      nullable: true,
    },
    encPublicKey: {
      type: 'string',
      pattern: base64Pattern,
      minLength: 40, // base64 of 32 bytes = ~44 chars
      maxLength: 48,
    },
    signPublicKey: {
      type: 'string',
      pattern: base64Pattern,
      minLength: 40,
      maxLength: 48,
    },
    signature: {
      type: 'string',
      pattern: base64Pattern,
      minLength: 80, // base64 of 64 bytes = ~88 chars
      maxLength: 92,
    },
    pushToken: {
      type: 'string',
      maxLength: 512,
      nullable: true,
    },
    voipToken: {
      type: 'string',
      maxLength: 512,
      nullable: true,
    },
  },
  required: [
    'protocolVersion',
    'cryptoVersion',
    'challengeId',
    'deviceId',
    'platform',
    'encPublicKey',
    'signPublicKey',
    'signature',
  ],
  additionalProperties: false,
};

// =============================================================================
// SESSION_REFRESH SCHEMA (Section 3.3)
// =============================================================================

export const sessionRefreshSchema: JSONSchemaType<SessionRefreshPayload> = {
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
// LOGOUT SCHEMA (Section 3.3)
// =============================================================================

export const logoutSchema: JSONSchemaType<LogoutPayload> = {
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
