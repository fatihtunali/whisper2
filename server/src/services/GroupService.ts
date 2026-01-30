/**
 * Whisper2 Group Service
 * Following WHISPER-REBUILD.md Step 5
 *
 * Handles:
 * - Group creation with member validation
 * - Group updates (title, add/remove members, role changes)
 * - Group message routing with pairwise fanout
 * - Membership validation for message routing
 */

import { v4 as uuidv4 } from 'uuid';
import { query } from '../db/postgres';
import { logger } from '../utils/logger';
import { connectionManager } from './ConnectionManager';
import { attachmentService } from './AttachmentService';
import * as redis from '../db/redis';
import { RedisKeys, TTL } from '../db/redis-keys';
import {
  verifySignature,
  isTimestampValid,
} from '../utils/crypto';
import { pushService } from './PushService';
import {
  ErrorCode,
  MessageReceivedPayload,
  AttachmentPointer,
} from '../types/protocol';
import { userExists, getUserKeys } from '../db/UserRepository';
import {
  validateAttachment,
  isDuplicateMessage,
  markMessageProcessed,
} from '../utils/validation';

// =============================================================================
// CONSTANTS (Frozen per spec)
// =============================================================================

const MAX_GROUP_MEMBERS = 50;
const MAX_GROUP_TITLE_LEN = 64;
const MAX_CIPHERTEXT_B64_LEN = 100_000;

// =============================================================================
// TYPES
// =============================================================================

export type GroupRole = 'owner' | 'admin' | 'member';

export interface GroupMember {
  whisperId: string;
  role: GroupRole;
  joinedAt: number;
  removedAt?: number;
}

export interface Group {
  groupId: string;
  title: string;
  ownerId: string;
  createdAt: number;
  updatedAt: number;
  members: GroupMember[];
}

export interface GroupCreatePayload {
  protocolVersion: number;
  cryptoVersion: number;
  sessionToken: string;
  title: string;
  memberIds: string[];
}

export interface GroupUpdatePayload {
  protocolVersion: number;
  cryptoVersion: number;
  sessionToken: string;
  groupId: string;
  title?: string;
  addMembers?: string[];
  removeMembers?: string[];
  roleChanges?: Array<{ whisperId: string; role: GroupRole }>;
}

export interface RecipientEnvelope {
  to: string;
  nonce: string;
  ciphertext: string;
  sig: string;
}

export interface GroupSendMessagePayload {
  protocolVersion: number;
  cryptoVersion: number;
  sessionToken: string;
  groupId: string;
  messageId: string;
  from: string;
  msgType: string;
  timestamp: number;
  recipients: RecipientEnvelope[];
  replyTo?: string;
  reactions?: Record<string, string[]>;
  attachment?: AttachmentPointer;
}

export interface GroupEventPayload {
  event: 'created' | 'updated' | 'member_added' | 'member_removed';
  group: Group;
  affectedMembers?: string[];
}

export interface ServiceResult<T = void> {
  success: boolean;
  error?: { code: ErrorCode; message: string };
  data?: T;
}

// =============================================================================
// GROUP SERVICE
// =============================================================================

export class GroupService {
  /**
   * Create a new group.
   */
  async createGroup(
    payload: GroupCreatePayload,
    creatorWhisperId: string
  ): Promise<ServiceResult<Group>> {
    const { title, memberIds } = payload;

    // Validate title
    const trimmedTitle = title.trim();
    if (trimmedTitle.length < 1 || trimmedTitle.length > MAX_GROUP_TITLE_LEN) {
      return {
        success: false,
        error: {
          code: 'INVALID_PAYLOAD',
          message: `Title must be 1-${MAX_GROUP_TITLE_LEN} characters`,
        },
      };
    }

    // Validate memberIds
    const uniqueMembers = [...new Set(memberIds)];

    // Cannot include self
    if (uniqueMembers.includes(creatorWhisperId)) {
      return {
        success: false,
        error: {
          code: 'INVALID_PAYLOAD',
          message: 'Cannot include yourself in memberIds (you are added automatically)',
        },
      };
    }

    // Check total count (creator + members)
    if (uniqueMembers.length + 1 > MAX_GROUP_MEMBERS) {
      return {
        success: false,
        error: {
          code: 'INVALID_PAYLOAD',
          message: `Group cannot exceed ${MAX_GROUP_MEMBERS} members`,
        },
      };
    }

    // Verify all members exist and are active
    for (const memberId of uniqueMembers) {
      const exists = await userExists(memberId);
      if (!exists) {
        return {
          success: false,
          error: {
            code: 'NOT_FOUND',
            message: `User ${memberId} not found`,
          },
        };
      }
    }

    const groupId = uuidv4();
    const now = Date.now();

    try {
      // Insert group
      await query(
        `INSERT INTO groups (group_id, title, owner_whisper_id, created_at_ms, updated_at_ms)
         VALUES ($1, $2, $3, $4, $5)`,
        [groupId, trimmedTitle, creatorWhisperId, now, now]
      );

      // Insert owner as member
      await query(
        `INSERT INTO group_members (group_id, whisper_id, role, joined_at_ms)
         VALUES ($1, $2, 'owner', $3)`,
        [groupId, creatorWhisperId, now]
      );

      // Insert other members
      for (const memberId of uniqueMembers) {
        await query(
          `INSERT INTO group_members (group_id, whisper_id, role, joined_at_ms)
           VALUES ($1, $2, 'member', $3)`,
          [groupId, memberId, now]
        );
      }

      // Build group object
      const group: Group = {
        groupId,
        title: trimmedTitle,
        ownerId: creatorWhisperId,
        createdAt: now,
        updatedAt: now,
        members: [
          { whisperId: creatorWhisperId, role: 'owner', joinedAt: now },
          ...uniqueMembers.map(id => ({ whisperId: id, role: 'member' as GroupRole, joinedAt: now })),
        ],
      };

      // Emit group_event to all members
      const eventPayload: GroupEventPayload = {
        event: 'created',
        group,
      };

      this.emitGroupEvent(group.members.map(m => m.whisperId), eventPayload);

      logger.info({ groupId, creatorWhisperId, memberCount: group.members.length }, 'Group created');

      return { success: true, data: group };
    } catch (err) {
      logger.error({ err, groupId }, 'Failed to create group');
      return {
        success: false,
        error: { code: 'INTERNAL_ERROR', message: 'Failed to create group' },
      };
    }
  }

  /**
   * Update group (title, members, roles).
   */
  async updateGroup(
    payload: GroupUpdatePayload,
    requesterWhisperId: string
  ): Promise<ServiceResult<Group>> {
    const { groupId, title, addMembers, removeMembers, roleChanges } = payload;

    // Get current membership
    const membership = await this.getMembership(groupId, requesterWhisperId);
    if (!membership || membership.removedAt) {
      return {
        success: false,
        error: { code: 'FORBIDDEN', message: 'Not a member of this group' },
      };
    }

    const isOwner = membership.role === 'owner';
    const isAdmin = membership.role === 'admin';
    const canManage = isOwner || isAdmin;

    // Get current group
    const group = await this.getGroup(groupId);
    if (!group) {
      return {
        success: false,
        error: { code: 'NOT_FOUND', message: 'Group not found' },
      };
    }

    const now = Date.now();
    const affectedMembers: string[] = [];

    try {
      // Title change (owner or admin)
      if (title !== undefined) {
        if (!canManage) {
          return {
            success: false,
            error: { code: 'FORBIDDEN', message: 'Only owner or admin can change title' },
          };
        }

        const trimmedTitle = title.trim();
        if (trimmedTitle.length < 1 || trimmedTitle.length > MAX_GROUP_TITLE_LEN) {
          return {
            success: false,
            error: { code: 'INVALID_PAYLOAD', message: `Title must be 1-${MAX_GROUP_TITLE_LEN} characters` },
          };
        }

        await query(
          `UPDATE groups SET title = $1, updated_at_ms = $2 WHERE group_id = $3`,
          [trimmedTitle, now, groupId]
        );
        group.title = trimmedTitle;
      }

      // Add members (owner or admin)
      if (addMembers && addMembers.length > 0) {
        if (!canManage) {
          return {
            success: false,
            error: { code: 'FORBIDDEN', message: 'Only owner or admin can add members' },
          };
        }

        const uniqueAdds = [...new Set(addMembers)];
        const currentActiveCount = group.members.filter(m => !m.removedAt).length;

        if (currentActiveCount + uniqueAdds.length > MAX_GROUP_MEMBERS) {
          return {
            success: false,
            error: { code: 'INVALID_PAYLOAD', message: `Group cannot exceed ${MAX_GROUP_MEMBERS} members` },
          };
        }

        for (const memberId of uniqueAdds) {
          const exists = await userExists(memberId);
          if (!exists) {
            return {
              success: false,
              error: { code: 'NOT_FOUND', message: `User ${memberId} not found` },
            };
          }

          // Upsert member (reactivate if previously removed)
          await query(
            `INSERT INTO group_members (group_id, whisper_id, role, joined_at_ms, removed_at_ms)
             VALUES ($1, $2, 'member', $3, NULL)
             ON CONFLICT (group_id, whisper_id) DO UPDATE SET
               role = CASE WHEN group_members.role = 'owner' THEN 'owner' ELSE 'member' END,
               joined_at_ms = $3,
               removed_at_ms = NULL`,
            [groupId, memberId, now]
          );

          affectedMembers.push(memberId);

          // Update local group object
          const existingMember = group.members.find(m => m.whisperId === memberId);
          if (existingMember) {
            existingMember.removedAt = undefined;
            existingMember.joinedAt = now;
          } else {
            group.members.push({ whisperId: memberId, role: 'member', joinedAt: now });
          }
        }
      }

      // Remove members (owner or admin)
      if (removeMembers && removeMembers.length > 0) {
        if (!canManage) {
          return {
            success: false,
            error: { code: 'FORBIDDEN', message: 'Only owner or admin can remove members' },
          };
        }

        for (const memberId of removeMembers) {
          // Cannot remove owner
          if (memberId === group.ownerId) {
            return {
              success: false,
              error: { code: 'FORBIDDEN', message: 'Cannot remove the group owner' },
            };
          }

          await query(
            `UPDATE group_members SET removed_at_ms = $1 WHERE group_id = $2 AND whisper_id = $3`,
            [now, groupId, memberId]
          );

          affectedMembers.push(memberId);

          const member = group.members.find(m => m.whisperId === memberId);
          if (member) {
            member.removedAt = now;
          }
        }
      }

      // Role changes (owner only)
      if (roleChanges && roleChanges.length > 0) {
        if (!isOwner) {
          return {
            success: false,
            error: { code: 'FORBIDDEN', message: 'Only owner can change roles' },
          };
        }

        for (const change of roleChanges) {
          // Cannot change owner's role
          if (change.whisperId === group.ownerId) {
            continue;
          }

          // Validate role
          if (!['admin', 'member'].includes(change.role)) {
            return {
              success: false,
              error: { code: 'INVALID_PAYLOAD', message: 'Invalid role' },
            };
          }

          await query(
            `UPDATE group_members SET role = $1 WHERE group_id = $2 AND whisper_id = $3 AND removed_at_ms IS NULL`,
            [change.role, groupId, change.whisperId]
          );

          const member = group.members.find(m => m.whisperId === change.whisperId);
          if (member) {
            member.role = change.role;
          }
        }
      }

      // Update timestamp
      await query(
        `UPDATE groups SET updated_at_ms = $1 WHERE group_id = $2`,
        [now, groupId]
      );
      group.updatedAt = now;

      // Emit group_event to all active members
      const activeMembers = group.members.filter(m => !m.removedAt).map(m => m.whisperId);
      const eventPayload: GroupEventPayload = {
        event: 'updated',
        group,
        affectedMembers,
      };

      // Also notify removed members
      const allNotify = [...new Set([...activeMembers, ...affectedMembers])];
      this.emitGroupEvent(allNotify, eventPayload);

      logger.info({ groupId, requesterWhisperId, affectedMembers }, 'Group updated');

      return { success: true, data: group };
    } catch (err) {
      logger.error({ err, groupId }, 'Failed to update group');
      return {
        success: false,
        error: { code: 'INTERNAL_ERROR', message: 'Failed to update group' },
      };
    }
  }

  /**
   * Send group message with pairwise fanout.
   */
  async sendGroupMessage(
    payload: GroupSendMessagePayload,
    senderWhisperId: string
  ): Promise<ServiceResult<{ messageId: string; status: string }>> {
    const { groupId, messageId, from, msgType, timestamp, recipients, attachment } = payload;

    // Validate from === session
    if (from !== senderWhisperId) {
      return {
        success: false,
        error: { code: 'FORBIDDEN', message: 'Sender does not match authenticated identity' },
      };
    }

    // Validate timestamp
    if (!isTimestampValid(timestamp)) {
      return {
        success: false,
        error: { code: 'INVALID_TIMESTAMP', message: 'Timestamp outside allowed window' },
      };
    }

    // Check sender is active member
    const senderMembership = await this.getMembership(groupId, senderWhisperId);
    if (!senderMembership || senderMembership.removedAt) {
      return {
        success: false,
        error: { code: 'FORBIDDEN', message: 'You are not a member of this group' },
      };
    }

    // Get active members
    const activeMembers = await this.getActiveMembers(groupId);
    const activeMemberIds = new Set(activeMembers.map(m => m.whisperId));

    // Validate recipients
    if (!recipients || recipients.length === 0) {
      return {
        success: false,
        error: { code: 'INVALID_PAYLOAD', message: 'Recipients cannot be empty' },
      };
    }

    // Get sender's signPublicKey
    const senderKeys = await getUserKeys(senderWhisperId);
    if (!senderKeys) {
      return {
        success: false,
        error: { code: 'NOT_FOUND', message: 'Sender not found' },
      };
    }

    // Validate each recipient envelope
    for (const envelope of recipients) {
      // Skip if recipient is sender
      if (envelope.to === senderWhisperId) {
        continue;
      }

      // Must be active member
      if (!activeMemberIds.has(envelope.to)) {
        return {
          success: false,
          error: { code: 'FORBIDDEN', message: `Recipient ${envelope.to} is not an active member` },
        };
      }

      // Validate nonce
      if (!isValidNonce(envelope.nonce)) {
        return {
          success: false,
          error: { code: 'INVALID_PAYLOAD', message: 'Invalid nonce format' },
        };
      }

      // Validate ciphertext size
      if (envelope.ciphertext.length > MAX_CIPHERTEXT_B64_LEN) {
        return {
          success: false,
          error: { code: 'INVALID_PAYLOAD', message: 'Ciphertext too large' },
        };
      }

      // Verify signature using canonical format with toOrGroupId = groupId
      const sigValid = verifySignature(senderKeys.sign_public_key, envelope.sig, {
        messageType: 'group_send_message',
        messageId,
        from,
        toOrGroupId: groupId,
        timestamp,
        nonce: envelope.nonce,
        ciphertext: envelope.ciphertext,
      });

      if (!sigValid) {
        logger.warn({ messageId, from, to: envelope.to }, 'Group message signature verification failed');
        return {
          success: false,
          error: { code: 'AUTH_FAILED', message: 'Signature verification failed' },
        };
      }
    }

    // Validate attachment if present
    if (attachment) {
      const attValidation = validateAttachment(attachment);
      if (!attValidation.valid) {
        return {
          success: false,
          error: { code: 'INVALID_PAYLOAD', message: attValidation.error! },
        };
      }
    }

    // Check idempotency
    const isDuplicate = await isDuplicateMessage(senderWhisperId, messageId);
    if (isDuplicate) {
      logger.info({ messageId, from }, 'Duplicate group message, returning ACK');
      return {
        success: true,
        data: { messageId, status: 'sent' },
      };
    }

    // Mark as processed
    await markMessageProcessed(senderWhisperId, messageId);

    // Route to each recipient
    for (const envelope of recipients) {
      if (envelope.to === senderWhisperId) {
        continue;
      }

      const messageReceived: MessageReceivedPayload = {
        messageId,
        groupId,
        from,
        to: envelope.to,
        msgType: msgType as any,
        timestamp,
        nonce: envelope.nonce,
        ciphertext: envelope.ciphertext,
        sig: envelope.sig,
      };

      if (payload.replyTo) {
        messageReceived.replyTo = payload.replyTo;
      }
      if (payload.reactions && Object.keys(payload.reactions).length > 0) {
        messageReceived.reactions = payload.reactions;
      }
      if (attachment) {
        messageReceived.attachment = attachment;
      }

      // Try online delivery
      const delivered = connectionManager.sendToUser(envelope.to, {
        type: 'message_received',
        payload: messageReceived,
      });

      if (!delivered) {
        // Check pending count BEFORE storing
        const pendingCountBefore = await pushService.getPendingCountBefore(envelope.to);

        // Store in pending queue
        await this.storePending(envelope.to, messageReceived);

        // Trigger push only on 0→1 transition
        if (pendingCountBefore === 0) {
          await pushService.sendWake(envelope.to, 'message');
        }
      }

      // Grant attachment access
      if (attachment) {
        await attachmentService.grantAccess(attachment.objectKey, envelope.to);
      }
    }

    logger.info({ messageId, groupId, from, recipientCount: recipients.length }, 'Group message routed');

    return {
      success: true,
      data: { messageId, status: 'sent' },
    };
  }

  /**
   * Get group by ID.
   */
  async getGroup(groupId: string): Promise<Group | null> {
    const groupResult = await query<{
      group_id: string;
      title: string;
      owner_whisper_id: string;
      created_at_ms: string;
      updated_at_ms: string;
    }>(
      `SELECT group_id, title, owner_whisper_id, created_at_ms, updated_at_ms
       FROM groups WHERE group_id = $1`,
      [groupId]
    );

    if (groupResult.rows.length === 0) {
      return null;
    }

    const row = groupResult.rows[0];
    const members = await this.getMembers(groupId);

    return {
      groupId: row.group_id,
      title: row.title,
      ownerId: row.owner_whisper_id,
      createdAt: parseInt(row.created_at_ms, 10),
      updatedAt: parseInt(row.updated_at_ms, 10),
      members,
    };
  }

  /**
   * Get all members of a group.
   */
  async getMembers(groupId: string): Promise<GroupMember[]> {
    const result = await query<{
      whisper_id: string;
      role: string;
      joined_at_ms: string;
      removed_at_ms: string | null;
    }>(
      `SELECT whisper_id, role, joined_at_ms, removed_at_ms
       FROM group_members WHERE group_id = $1`,
      [groupId]
    );

    return result.rows.map(row => ({
      whisperId: row.whisper_id,
      role: row.role as GroupRole,
      joinedAt: parseInt(row.joined_at_ms, 10),
      removedAt: row.removed_at_ms ? parseInt(row.removed_at_ms, 10) : undefined,
    }));
  }

  /**
   * Get active members of a group.
   */
  async getActiveMembers(groupId: string): Promise<GroupMember[]> {
    const result = await query<{
      whisper_id: string;
      role: string;
      joined_at_ms: string;
    }>(
      `SELECT whisper_id, role, joined_at_ms
       FROM group_members WHERE group_id = $1 AND removed_at_ms IS NULL`,
      [groupId]
    );

    return result.rows.map(row => ({
      whisperId: row.whisper_id,
      role: row.role as GroupRole,
      joinedAt: parseInt(row.joined_at_ms, 10),
    }));
  }

  /**
   * Get membership info for a user in a group.
   */
  async getMembership(groupId: string, whisperId: string): Promise<GroupMember | null> {
    const result = await query<{
      role: string;
      joined_at_ms: string;
      removed_at_ms: string | null;
    }>(
      `SELECT role, joined_at_ms, removed_at_ms
       FROM group_members WHERE group_id = $1 AND whisper_id = $2`,
      [groupId, whisperId]
    );

    if (result.rows.length === 0) {
      return null;
    }

    const row = result.rows[0];
    return {
      whisperId,
      role: row.role as GroupRole,
      joinedAt: parseInt(row.joined_at_ms, 10),
      removedAt: row.removed_at_ms ? parseInt(row.removed_at_ms, 10) : undefined,
    };
  }

  // ===========================================================================
  // PRIVATE HELPERS
  // ===========================================================================

  private async storePending(recipientId: string, message: MessageReceivedPayload): Promise<void> {
    const key = RedisKeys.pending(recipientId);
    await redis.listPush(key, JSON.stringify(message));
    await redis.expire(key, TTL.PENDING);
  }

  private emitGroupEvent(memberIds: string[], eventPayload: GroupEventPayload): void {
    for (const memberId of memberIds) {
      connectionManager.sendToUser(memberId, {
        type: 'group_event',
        payload: eventPayload,
      });
    }
  }
}

export const groupService = new GroupService();
