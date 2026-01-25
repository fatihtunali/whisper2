/**
 * Whisper2 Schema Validator
 * Following WHISPER-REBUILD.md Section 3.1
 *
 * Rule: Server REJECTS invalid payloads with INVALID_PAYLOAD before business logic.
 * Uses AJV for JSON Schema validation.
 */

import Ajv, { ValidateFunction, ErrorObject } from 'ajv';
import addFormats from 'ajv-formats';
import { logger } from '../utils/logger';
import {
  registerBeginSchema,
  registerProofSchema,
  sessionRefreshSchema,
  logoutSchema,
} from './auth.schema';
import {
  sendMessageSchema,
  deliveryReceiptSchema,
  fetchPendingSchema,
} from './messaging.schema';
import {
  groupCreateSchema,
  groupUpdateSchema,
  groupSendMessageSchema,
} from './group.schema';

// =============================================================================
// AJV INSTANCE
// =============================================================================

const ajv = new Ajv({
  allErrors: true,
  removeAdditional: false,
  useDefaults: true,
  coerceTypes: false,
  strict: true,
});

// Add string formats (uuid, email, etc.)
addFormats(ajv);

// =============================================================================
// COMPILED VALIDATORS
// =============================================================================

const validators: Map<string, ValidateFunction> = new Map();

// Register auth schemas
validators.set('register_begin', ajv.compile(registerBeginSchema));
validators.set('register_proof', ajv.compile(registerProofSchema));
validators.set('session_refresh', ajv.compile(sessionRefreshSchema));
validators.set('logout', ajv.compile(logoutSchema));

// Ping schema
validators.set('ping', ajv.compile({
  type: 'object',
  properties: {
    timestamp: { type: 'number' },
  },
  required: ['timestamp'],
  additionalProperties: false,
}));

// Messaging schemas
validators.set('send_message', ajv.compile(sendMessageSchema));
validators.set('delivery_receipt', ajv.compile(deliveryReceiptSchema));
validators.set('fetch_pending', ajv.compile(fetchPendingSchema));

// Group schemas
validators.set('group_create', ajv.compile(groupCreateSchema));
validators.set('group_update', ajv.compile(groupUpdateSchema));
validators.set('group_send_message', ajv.compile(groupSendMessageSchema));

// =============================================================================
// VALIDATION FUNCTIONS
// =============================================================================

export interface ValidationResult {
  valid: boolean;
  errors?: string[];
}

/**
 * Validate a payload against its message type schema.
 */
export function validatePayload(
  messageType: string,
  payload: unknown
): ValidationResult {
  const validate = validators.get(messageType);

  if (!validate) {
    // Unknown message type - reject
    logger.warn({ messageType }, 'No validator for message type');
    return {
      valid: false,
      errors: [`Unknown message type: ${messageType}`],
    };
  }

  const valid = validate(payload);

  if (!valid) {
    const errors = formatErrors(validate.errors);
    logger.debug({ messageType, errors }, 'Payload validation failed');
    return { valid: false, errors };
  }

  return { valid: true };
}

/**
 * Format AJV errors into human-readable strings.
 */
function formatErrors(errors: ErrorObject[] | null | undefined): string[] {
  if (!errors) return [];

  return errors.map((err) => {
    const path = err.instancePath || 'payload';
    switch (err.keyword) {
      case 'required':
        return `${path}: missing required field '${err.params.missingProperty}'`;
      case 'type':
        return `${path}: expected ${err.params.type}`;
      case 'enum':
        return `${path}: must be one of ${JSON.stringify(err.params.allowedValues)}`;
      case 'pattern':
        return `${path}: invalid format`;
      case 'const':
        return `${path}: must be ${err.params.allowedValue}`;
      case 'minLength':
        return `${path}: too short (min ${err.params.limit})`;
      case 'maxLength':
        return `${path}: too long (max ${err.params.limit})`;
      case 'format':
        return `${path}: invalid ${err.params.format} format`;
      case 'additionalProperties':
        return `${path}: unexpected field '${err.params.additionalProperty}'`;
      default:
        return `${path}: ${err.message || 'validation failed'}`;
    }
  });
}

/**
 * Validate WS frame structure.
 * All WS frames must have: { type: string, payload: object, requestId?: string }
 */
export function validateWsFrame(data: unknown): ValidationResult {
  if (typeof data !== 'object' || data === null) {
    return { valid: false, errors: ['Frame must be an object'] };
  }

  const frame = data as Record<string, unknown>;

  if (typeof frame.type !== 'string' || frame.type.length === 0) {
    return { valid: false, errors: ['Frame must have a non-empty "type" string'] };
  }

  if (typeof frame.payload !== 'object' || frame.payload === null) {
    return { valid: false, errors: ['Frame must have a "payload" object'] };
  }

  if (frame.requestId !== undefined && typeof frame.requestId !== 'string') {
    return { valid: false, errors: ['"requestId" must be a string if provided'] };
  }

  return { valid: true };
}

/**
 * Check if message type requires authentication.
 */
export function requiresAuth(messageType: string): boolean {
  const publicTypes = new Set([
    'register_begin',
    'register_proof',
    'ping',
  ]);

  return !publicTypes.has(messageType);
}

/**
 * Add a new validator at runtime (for future message types).
 */
export function registerValidator(
  messageType: string,
  schema: object
): void {
  const validate = ajv.compile(schema);
  validators.set(messageType, validate);
  logger.info({ messageType }, 'Validator registered');
}
