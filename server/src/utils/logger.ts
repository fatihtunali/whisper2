/**
 * Whisper2 Logger
 * Following WHISPER-REBUILD.md Section 13
 *
 * JSON logs with:
 * - connId, whisperId, deviceId, messageId
 * - event, latencyMs, code
 *
 * NEVER log: tokens, keys, plaintext, full ciphertext (only sizes)
 */

import pino from 'pino';

export const logger = pino({
  level: process.env.LOG_LEVEL || 'info',
  transport:
    process.env.NODE_ENV === 'development'
      ? {
          target: 'pino-pretty',
          options: {
            colorize: true,
            translateTime: 'SYS:standard',
            ignore: 'pid,hostname',
          },
        }
      : undefined,
  redact: {
    paths: [
      'sessionToken',
      'token',
      'password',
      'secret',
      'privateKey',
      'signPrivateKey',
      'encPrivateKey',
      'ciphertext',
      'plaintext',
      'pushToken',
      'voipToken',
    ],
    censor: '[REDACTED]',
  },
  base: {
    service: 'whisper2-server',
    version: process.env.npm_package_version || '1.0.0',
  },
});

/**
 * Creates a child logger with connection context.
 */
export function createConnLogger(connId: string) {
  return logger.child({ connId });
}

/**
 * Creates a child logger with user context.
 */
export function createUserLogger(whisperId: string, deviceId?: string) {
  return logger.child({ whisperId, deviceId });
}
