/**
 * Firebase Admin SDK Initialization
 *
 * Initializes Firebase Admin SDK for FCM push notifications.
 * Credentials are loaded from environment variables.
 */

import * as admin from 'firebase-admin';
import { logger } from '../utils/logger';

let firebaseApp: admin.app.App | null = null;

/**
 * Initialize Firebase Admin SDK
 *
 * Expects either:
 * - FIREBASE_SERVICE_ACCOUNT_JSON: Full JSON string of service account
 * - Or individual fields: FIREBASE_PROJECT_ID, FIREBASE_CLIENT_EMAIL, FIREBASE_PRIVATE_KEY
 */
export function initializeFirebase(): boolean {
  if (firebaseApp) {
    logger.debug('Firebase already initialized');
    return true;
  }

  try {
    // Option 1: Full service account JSON
    const serviceAccountJson = process.env.FIREBASE_SERVICE_ACCOUNT_JSON;
    if (serviceAccountJson) {
      const serviceAccount = JSON.parse(serviceAccountJson);
      firebaseApp = admin.initializeApp({
        credential: admin.credential.cert(serviceAccount),
      });
      logger.info('Firebase initialized with service account JSON');
      return true;
    }

    // Option 2: Individual fields
    const projectId = process.env.FIREBASE_PROJECT_ID;
    const clientEmail = process.env.FIREBASE_CLIENT_EMAIL;
    const privateKey = process.env.FIREBASE_PRIVATE_KEY?.replace(/\\n/g, '\n');

    if (projectId && clientEmail && privateKey) {
      firebaseApp = admin.initializeApp({
        credential: admin.credential.cert({
          projectId,
          clientEmail,
          privateKey,
        }),
      });
      logger.info({ projectId }, 'Firebase initialized with individual credentials');
      return true;
    }

    logger.warn('Firebase not configured - FCM push notifications will be disabled');
    return false;
  } catch (error) {
    logger.error({ error }, 'Failed to initialize Firebase');
    return false;
  }
}

/**
 * Check if Firebase is initialized and ready
 */
export function isFirebaseReady(): boolean {
  return firebaseApp !== null;
}

/**
 * Get Firebase Messaging instance
 */
export function getMessaging(): admin.messaging.Messaging | null {
  if (!firebaseApp) {
    return null;
  }
  return admin.messaging(firebaseApp);
}

/**
 * Send FCM message
 *
 * @param token Device FCM token
 * @param data Data payload (wake-only)
 * @param options Message options (priority, etc.)
 * @returns Message ID on success, null on failure
 */
export async function sendFcmMessage(
  token: string,
  data: Record<string, string>,
  options: {
    priority?: 'high' | 'normal';
    channelId?: string;
    ttl?: number;
  } = {}
): Promise<{ success: boolean; messageId?: string; error?: string; shouldInvalidateToken?: boolean }> {
  const messaging = getMessaging();
  if (!messaging) {
    return { success: false, error: 'Firebase not initialized' };
  }

  try {
    const message: admin.messaging.Message = {
      token,
      data,
      android: {
        priority: options.priority || 'high',
        ttl: (options.ttl || 60) * 1000, // Convert to milliseconds
        notification: undefined, // Data-only message for wake
      },
    };

    // Add channel ID for Android notification channels
    if (options.channelId) {
      message.android!.notification = {
        channelId: options.channelId,
      };
    }

    const messageId = await messaging.send(message);
    logger.debug({ messageId, token: token.substring(0, 20) + '...' }, 'FCM message sent');

    return { success: true, messageId };
  } catch (error: any) {
    const errorCode = error?.code || error?.errorInfo?.code;
    const errorMessage = error?.message || 'Unknown error';

    logger.warn({ errorCode, errorMessage, token: token.substring(0, 20) + '...' }, 'FCM send failed');

    // Check if token should be invalidated
    const invalidTokenCodes = [
      'messaging/invalid-registration-token',
      'messaging/registration-token-not-registered',
      'messaging/invalid-argument',
    ];

    const shouldInvalidateToken = invalidTokenCodes.includes(errorCode);

    return {
      success: false,
      error: errorMessage,
      shouldInvalidateToken,
    };
  }
}
