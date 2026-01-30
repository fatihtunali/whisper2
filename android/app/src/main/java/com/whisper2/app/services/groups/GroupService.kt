package com.whisper2.app.services.groups

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.whisper2.app.core.Constants
import com.whisper2.app.core.Logger
import com.whisper2.app.crypto.CryptoService
import com.whisper2.app.data.local.db.dao.ContactDao
import com.whisper2.app.data.local.db.dao.GroupDao
import com.whisper2.app.data.local.db.dao.MessageDao
import com.whisper2.app.data.local.db.entities.GroupEntity
import com.whisper2.app.data.local.db.entities.GroupInviteEntity
import com.whisper2.app.data.local.db.entities.GroupMemberEntity
import com.whisper2.app.data.local.db.entities.MessageEntity
import com.whisper2.app.data.local.prefs.SecureStorage
import com.whisper2.app.data.network.ws.*
import com.whisper2.app.di.ApplicationScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class GroupCreatePayload(
    val protocolVersion: Int = Constants.PROTOCOL_VERSION,
    val cryptoVersion: Int = Constants.CRYPTO_VERSION,
    val sessionToken: String,
    val title: String,
    val memberIds: List<String>
)

// Role change for group member (matches server protocol)
data class RoleChange(
    val whisperId: String,
    val role: String  // "admin" or "member"
)

data class GroupUpdatePayload(
    val protocolVersion: Int = Constants.PROTOCOL_VERSION,
    val cryptoVersion: Int = Constants.CRYPTO_VERSION,
    val sessionToken: String,
    val groupId: String,
    val addMembers: List<String>? = null,
    val removeMembers: List<String>? = null,
    val title: String? = null,
    val roleChanges: List<RoleChange>? = null
)

data class GroupInviteResponsePayload(
    val protocolVersion: Int = Constants.PROTOCOL_VERSION,
    val cryptoVersion: Int = Constants.CRYPTO_VERSION,
    val sessionToken: String,
    val groupId: String,
    val accepted: Boolean
)

// Group invite received payload (matches server protocol)
data class GroupInvitePayload(
    val groupId: String,
    val groupName: String,
    val inviterId: String,
    val inviterName: String,
    val memberCount: Int
)

// Server's group creation acknowledgment (matches server protocol.ts)
data class GroupCreateAckPayload(
    val groupId: String,
    val title: String,
    val memberIds: List<String>,
    val createdAt: Long
)

// Server's recipient envelope for group messages
data class RecipientEnvelope(
    val to: String,
    val nonce: String,
    val ciphertext: String,
    val sig: String
)

data class GroupSendMessagePayload(
    val protocolVersion: Int = Constants.PROTOCOL_VERSION,
    val cryptoVersion: Int = Constants.CRYPTO_VERSION,
    val sessionToken: String,
    val groupId: String,
    val messageId: String,
    val from: String,
    val msgType: String,
    val timestamp: Long,
    val recipients: List<RecipientEnvelope>,
    val replyTo: String? = null,
    val reactions: Map<String, List<String>>? = null,  // emoji -> whisperId[]
    val attachment: AttachmentPointer? = null
)

// Server's group member structure
data class ServerGroupMember(
    val whisperId: String,
    val role: String,
    val joinedAt: Long,
    val removedAt: Long? = null
)

// Server's group structure
data class ServerGroup(
    val groupId: String,
    val title: String,
    val ownerId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val members: List<ServerGroupMember>
)

// Server's group event payload (matches server exactly)
data class GroupEventPayload(
    val event: String,  // "created", "updated", "member_added", "member_removed"
    val group: ServerGroup,
    val affectedMembers: List<String>? = null
)

// Group message received payload
data class GroupMessageReceivedPayload(
    val groupId: String,
    val messageId: String,
    val from: String,
    val msgType: String,
    val timestamp: Long,
    val nonce: String,
    val ciphertext: String,
    val sig: String,
    val attachment: AttachmentPointer? = null
)

@Singleton
class GroupService @Inject constructor(
    private val groupDao: GroupDao,
    private val messageDao: MessageDao,
    private val contactDao: ContactDao,
    private val wsClient: WsClientImpl,
    private val cryptoService: CryptoService,
    private val secureStorage: SecureStorage,
    private val gson: Gson,
    @ApplicationScope private val scope: CoroutineScope
) {
    private val _groupEvents = MutableSharedFlow<GroupEventPayload>(extraBufferCapacity = 100)
    val groupEvents: SharedFlow<GroupEventPayload> = _groupEvents.asSharedFlow()

    init {
        setupMessageHandler()
    }

    private fun setupMessageHandler() {
        scope.launch {
            wsClient.messages.collect { frame ->
                handleMessage(frame)
            }
        }
    }

    // MARK: - Create Group

    suspend fun createGroup(title: String, memberIds: List<String>): Result<String> {
        val sessionToken = secureStorage.sessionToken ?: return Result.failure(Exception("Not authenticated"))
        val myId = secureStorage.whisperId ?: return Result.failure(Exception("Not authenticated"))

        // Validate all members have public keys
        for (memberId in memberIds) {
            val contact = contactDao.getContactById(memberId)
            if (contact?.encPublicKey == null) {
                return Result.failure(Exception("Missing public key for member: $memberId"))
            }
        }

        return try {
            val payload = GroupCreatePayload(
                sessionToken = sessionToken,
                title = title,
                memberIds = memberIds
            )

            wsClient.send(WsFrame(Constants.MsgType.GROUP_CREATE, payload = payload))

            // Group will be added when we receive the ack
            // Return temp group ID
            val tempGroupId = "GRP-${UUID.randomUUID().toString().take(8).uppercase()}"
            Result.success(tempGroupId)
        } catch (e: Exception) {
            Logger.e("Failed to create group", e)
            Result.failure(e)
        }
    }

    // MARK: - Update Group

    suspend fun addMembers(groupId: String, memberIds: List<String>): Result<Unit> {
        val sessionToken = secureStorage.sessionToken ?: return Result.failure(Exception("Not authenticated"))

        // Validate all new members have public keys
        for (memberId in memberIds) {
            val contact = contactDao.getContactById(memberId)
            if (contact?.encPublicKey == null) {
                return Result.failure(Exception("Missing public key for member: $memberId"))
            }
        }

        return try {
            val payload = GroupUpdatePayload(
                sessionToken = sessionToken,
                groupId = groupId,
                addMembers = memberIds
            )

            wsClient.send(WsFrame(Constants.MsgType.GROUP_UPDATE, payload = payload))
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e("Failed to add members", e)
            Result.failure(e)
        }
    }

    suspend fun removeMembers(groupId: String, memberIds: List<String>): Result<Unit> {
        val sessionToken = secureStorage.sessionToken ?: return Result.failure(Exception("Not authenticated"))

        return try {
            val payload = GroupUpdatePayload(
                sessionToken = sessionToken,
                groupId = groupId,
                removeMembers = memberIds
            )

            wsClient.send(WsFrame(Constants.MsgType.GROUP_UPDATE, payload = payload))
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e("Failed to remove members", e)
            Result.failure(e)
        }
    }

    suspend fun updateGroupTitle(groupId: String, title: String): Result<Unit> {
        val sessionToken = secureStorage.sessionToken ?: return Result.failure(Exception("Not authenticated"))

        return try {
            val payload = GroupUpdatePayload(
                sessionToken = sessionToken,
                groupId = groupId,
                title = title
            )

            wsClient.send(WsFrame(Constants.MsgType.GROUP_UPDATE, payload = payload))

            // Update locally
            groupDao.updateName(groupId, title)
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e("Failed to update group title", e)
            Result.failure(e)
        }
    }

    suspend fun leaveGroup(groupId: String): Result<Unit> {
        val sessionToken = secureStorage.sessionToken ?: return Result.failure(Exception("Not authenticated"))
        val myId = secureStorage.whisperId ?: return Result.failure(Exception("Not authenticated"))

        return try {
            val payload = GroupUpdatePayload(
                sessionToken = sessionToken,
                groupId = groupId,
                removeMembers = listOf(myId)
            )

            wsClient.send(WsFrame(Constants.MsgType.GROUP_UPDATE, payload = payload))

            // Remove locally
            groupDao.delete(groupId)
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e("Failed to leave group", e)
            Result.failure(e)
        }
    }

    // MARK: - Send Group Message

    suspend fun sendMessage(groupId: String, content: String): Result<Unit> {
        val sessionToken = secureStorage.sessionToken ?: return Result.failure(Exception("Not authenticated"))
        val myId = secureStorage.whisperId ?: return Result.failure(Exception("Not authenticated"))
        val myPrivKey = secureStorage.encPrivateKey ?: return Result.failure(Exception("No encryption key"))
        val mySignPrivKey = secureStorage.signPrivateKey ?: return Result.failure(Exception("No signing key"))

        val group = groupDao.getGroupById(groupId) ?: return Result.failure(Exception("Group not found"))
        val members = groupDao.getGroupMembers(groupId)

        val messageId = UUID.randomUUID().toString().lowercase()
        val timestamp = System.currentTimeMillis()

        // Save message locally first (optimistic)
        val message = MessageEntity(
            id = messageId,
            conversationId = groupId,
            groupId = groupId,
            from = myId,
            to = groupId,
            contentType = Constants.ContentType.TEXT,
            content = content,
            timestamp = timestamp,
            status = Constants.MessageStatus.PENDING,
            direction = Constants.Direction.OUTGOING
        )
        messageDao.insert(message)

        // Build recipient envelopes - encrypt for each member (pairwise encryption)
        val recipients = mutableListOf<RecipientEnvelope>()

        for (member in members) {
            if (member.memberId == myId) continue

            val contact = contactDao.getContactById(member.memberId)
            val memberPubKeyBase64 = contact?.encPublicKey ?: continue

            try {
                val memberPubKey = Base64.decode(memberPubKeyBase64.replace(" ", "+").trim(), Base64.NO_WRAP)
                val nonce = cryptoService.generateNonce()
                val ciphertext = cryptoService.boxSeal(
                    content.toByteArray(Charsets.UTF_8),
                    nonce,
                    memberPubKey,
                    myPrivKey
                )

                val signature = cryptoService.signMessage(
                    messageType = Constants.MsgType.GROUP_SEND_MESSAGE,
                    messageId = messageId,
                    from = myId,
                    toOrGroupId = member.memberId,
                    timestamp = timestamp,
                    nonce = nonce,
                    ciphertext = ciphertext,
                    privateKey = mySignPrivKey
                )

                recipients.add(RecipientEnvelope(
                    to = member.memberId,
                    nonce = Base64.encodeToString(nonce, Base64.NO_WRAP),
                    ciphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
                    sig = Base64.encodeToString(signature, Base64.NO_WRAP)
                ))
            } catch (e: Exception) {
                Logger.e("[GroupService] Failed to encrypt for ${member.memberId}", e)
            }
        }

        if (recipients.isEmpty()) {
            Logger.w("[GroupService] No valid recipients for group message")
            messageDao.updateStatus(messageId, Constants.MessageStatus.FAILED)
            return Result.failure(Exception("No valid recipients"))
        }

        // Send single message with all recipient envelopes
        val payload = GroupSendMessagePayload(
            sessionToken = sessionToken,
            groupId = groupId,
            messageId = messageId,
            from = myId,
            msgType = Constants.ContentType.TEXT,
            timestamp = timestamp,
            recipients = recipients
        )

        try {
            wsClient.send(WsFrame(Constants.MsgType.GROUP_SEND_MESSAGE, payload = payload))
            messageDao.updateStatus(messageId, Constants.MessageStatus.SENT)
            Logger.i("[GroupService] Sent group message to ${recipients.size} recipients")
        } catch (e: Exception) {
            Logger.e("[GroupService] Failed to send group message", e)
            messageDao.updateStatus(messageId, Constants.MessageStatus.FAILED)
            return Result.failure(e)
        }

        // Update group last message
        groupDao.updateLastMessage(groupId, content, timestamp)

        return Result.success(Unit)
    }

    // MARK: - Respond to Group Invite

    suspend fun respondToInvite(groupId: String, accepted: Boolean): Result<Unit> {
        val sessionToken = secureStorage.sessionToken ?: return Result.failure(Exception("Not authenticated"))

        return try {
            val payload = GroupInviteResponsePayload(
                sessionToken = sessionToken,
                groupId = groupId,
                accepted = accepted
            )

            wsClient.send(WsFrame(Constants.MsgType.GROUP_INVITE_RESPONSE, payload = payload))

            // Remove the invite from local storage
            groupDao.deleteInvite(groupId)

            Logger.i("[GroupService] Responded to group invite: $groupId, accepted: $accepted")
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e("[GroupService] Failed to respond to group invite", e)
            Result.failure(e)
        }
    }

    // MARK: - Kick Member (alias for removeMembers with single member)

    suspend fun kickMember(groupId: String, memberId: String): Result<Unit> {
        return removeMembers(groupId, listOf(memberId))
    }

    // MARK: - Get Pending Invites

    fun getPendingInvitesFlow() = groupDao.getAllInvitesFlow()

    suspend fun getPendingInvites(): List<GroupInviteEntity> = groupDao.getAllInvites()

    // MARK: - Message Handling

    private suspend fun handleMessage(frame: WsFrame<JsonElement>) {
        when (frame.type) {
            Constants.MsgType.GROUP_EVENT -> handleGroupEvent(frame.payload)
            "group_message" -> handleGroupMessage(frame.payload)
            "group_invite" -> handleGroupInvite(frame.payload)
        }
    }

    private suspend fun handleGroupInvite(payload: JsonElement) {
        try {
            val invite = gson.fromJson(payload, GroupInvitePayload::class.java)
            Logger.i("[GroupService] Received group invite: ${invite.groupId} from ${invite.inviterName}")

            // Store the invite locally
            val inviteEntity = GroupInviteEntity(
                groupId = invite.groupId,
                groupName = invite.groupName,
                inviterId = invite.inviterId,
                inviterName = invite.inviterName,
                memberCount = invite.memberCount,
                createdAt = System.currentTimeMillis()
            )
            groupDao.insertInvite(inviteEntity)
        } catch (e: Exception) {
            Logger.e("[GroupService] Failed to handle group invite", e)
        }
    }

    private suspend fun handleGroupEvent(payload: JsonElement) {
        try {
            val event = gson.fromJson(payload, GroupEventPayload::class.java)
            val myId = secureStorage.whisperId ?: return
            val serverGroup = event.group

            Logger.d("[GroupService] Received group event: ${event.event} for group ${serverGroup.groupId}")

            when (event.event) {
                "created" -> {
                    // New group created (we were added or we created it)
                    val activeMembers = serverGroup.members.filter { it.removedAt == null }

                    val group = GroupEntity(
                        groupId = serverGroup.groupId,
                        name = serverGroup.title,
                        creatorId = serverGroup.ownerId,
                        memberCount = activeMembers.size,
                        createdAt = serverGroup.createdAt,
                        updatedAt = serverGroup.updatedAt
                    )
                    groupDao.insert(group)

                    // Clear existing members and add fresh
                    groupDao.deleteGroupMembers(serverGroup.groupId)
                    activeMembers.forEach { member ->
                        groupDao.insertMember(GroupMemberEntity(
                            groupId = serverGroup.groupId,
                            memberId = member.whisperId,
                            joinedAt = member.joinedAt,
                            role = member.role
                        ))
                    }

                    Logger.i("[GroupService] Group created: ${serverGroup.groupId} with ${activeMembers.size} members")
                }

                "updated" -> {
                    // Group was updated (title change, members added/removed)
                    val activeMembers = serverGroup.members.filter { it.removedAt == null }

                    // Check if we're still a member
                    val amIMember = activeMembers.any { it.whisperId == myId }

                    if (amIMember) {
                        val group = GroupEntity(
                            groupId = serverGroup.groupId,
                            name = serverGroup.title,
                            creatorId = serverGroup.ownerId,
                            memberCount = activeMembers.size,
                            createdAt = serverGroup.createdAt,
                            updatedAt = serverGroup.updatedAt
                        )
                        groupDao.insert(group)

                        // Refresh members
                        groupDao.deleteGroupMembers(serverGroup.groupId)
                        activeMembers.forEach { member ->
                            groupDao.insertMember(GroupMemberEntity(
                                groupId = serverGroup.groupId,
                                memberId = member.whisperId,
                                joinedAt = member.joinedAt,
                                role = member.role
                            ))
                        }

                        Logger.i("[GroupService] Group updated: ${serverGroup.groupId}")
                    } else {
                        // We were removed from the group
                        groupDao.delete(serverGroup.groupId)
                        Logger.i("[GroupService] Removed from group: ${serverGroup.groupId}")
                    }
                }

                "member_added" -> {
                    // Member was added - refresh full group
                    val activeMembers = serverGroup.members.filter { it.removedAt == null }
                    groupDao.insert(GroupEntity(
                        groupId = serverGroup.groupId,
                        name = serverGroup.title,
                        creatorId = serverGroup.ownerId,
                        memberCount = activeMembers.size,
                        createdAt = serverGroup.createdAt,
                        updatedAt = serverGroup.updatedAt
                    ))

                    event.affectedMembers?.forEach { memberId ->
                        val memberData = serverGroup.members.find { it.whisperId == memberId }
                        groupDao.insertMember(GroupMemberEntity(
                            groupId = serverGroup.groupId,
                            memberId = memberId,
                            joinedAt = memberData?.joinedAt ?: System.currentTimeMillis(),
                            role = memberData?.role ?: "member"
                        ))
                    }

                    Logger.i("[GroupService] Members added to group: ${serverGroup.groupId}")
                }

                "member_removed" -> {
                    // Member was removed
                    val activeMembers = serverGroup.members.filter { it.removedAt == null }
                    val amIMember = activeMembers.any { it.whisperId == myId }

                    if (amIMember) {
                        groupDao.insert(GroupEntity(
                            groupId = serverGroup.groupId,
                            name = serverGroup.title,
                            creatorId = serverGroup.ownerId,
                            memberCount = activeMembers.size,
                            createdAt = serverGroup.createdAt,
                            updatedAt = serverGroup.updatedAt
                        ))

                        event.affectedMembers?.forEach { memberId ->
                            groupDao.removeMember(serverGroup.groupId, memberId)
                        }

                        Logger.i("[GroupService] Members removed from group: ${serverGroup.groupId}")
                    } else {
                        // We were removed
                        groupDao.delete(serverGroup.groupId)
                        Logger.i("[GroupService] We were removed from group: ${serverGroup.groupId}")
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e("[GroupService] Failed to handle group event", e)
        }
    }

    private suspend fun handleGroupMessage(payload: JsonElement) {
        try {
            val msg = gson.fromJson(payload, GroupMessageReceivedPayload::class.java)
            handleIncomingGroupMessagePayload(msg)
        } catch (e: Exception) {
            Logger.e("[GroupService] Failed to handle group message", e)
        }
    }

    private suspend fun handleIncomingGroupMessagePayload(msg: GroupMessageReceivedPayload) {
        val myId = secureStorage.whisperId ?: return
        val myPrivKey = secureStorage.encPrivateKey ?: return

        // Get sender's public key
        val contact = contactDao.getContactById(msg.from)
        val senderPubKeyBase64 = contact?.encPublicKey ?: run {
            Logger.w("[GroupService] Unknown sender: ${msg.from}")
            return
        }

        try {
            val senderPubKey = Base64.decode(senderPubKeyBase64.replace(" ", "+").trim(), Base64.NO_WRAP)
            val ciphertext = Base64.decode(msg.ciphertext, Base64.NO_WRAP)
            val nonce = Base64.decode(msg.nonce, Base64.NO_WRAP)

            val plaintext = cryptoService.boxOpen(ciphertext, nonce, senderPubKey, myPrivKey)
            val content = String(plaintext, Charsets.UTF_8)

            // Check for duplicates
            val existing = messageDao.getMessageById(msg.messageId)
            if (existing != null) {
                Logger.d("[GroupService] Duplicate message: ${msg.messageId}")
                return
            }

            // Store message
            val message = MessageEntity(
                id = msg.messageId,
                conversationId = msg.groupId,
                groupId = msg.groupId,
                from = msg.from,
                to = msg.groupId,
                contentType = msg.msgType,
                content = content,
                timestamp = msg.timestamp,
                status = Constants.MessageStatus.DELIVERED,
                direction = Constants.Direction.INCOMING,
                // Attachment metadata
                attachmentBlobId = msg.attachment?.objectKey,
                attachmentKey = msg.attachment?.fileKeyBox?.ciphertext,
                attachmentNonce = msg.attachment?.fileKeyBox?.nonce,
                attachmentMimeType = msg.attachment?.contentType,
                attachmentSize = msg.attachment?.ciphertextSize?.toLong()
            )
            messageDao.insert(message)

            // Update group
            groupDao.updateLastMessage(msg.groupId, content, msg.timestamp)
            groupDao.incrementUnreadCount(msg.groupId)

            Logger.i("[GroupService] Received group message in ${msg.groupId} from ${msg.from}")
        } catch (e: Exception) {
            Logger.e("[GroupService] Failed to decrypt group message", e)
        }
    }

    // MARK: - Helpers

    suspend fun markAsRead(groupId: String) {
        groupDao.resetUnreadCount(groupId)
    }

    fun getGroupFlow(groupId: String) = groupDao.getGroupByIdFlow(groupId)

    fun getAllGroupsFlow() = groupDao.getAllGroupsFlow()

    fun getGroupMessagesFlow(groupId: String) = messageDao.getMessagesForConversation(groupId)
}
