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

data class GroupCreateAckPayload(
    val groupId: String,
    val title: String,
    val memberIds: List<String>,
    val createdAt: Long
)

data class GroupUpdatePayload(
    val protocolVersion: Int = Constants.PROTOCOL_VERSION,
    val cryptoVersion: Int = Constants.CRYPTO_VERSION,
    val sessionToken: String,
    val groupId: String,
    val addMembers: List<String>? = null,
    val removeMembers: List<String>? = null,
    val title: String? = null
)

data class GroupSendMessagePayload(
    val protocolVersion: Int = Constants.PROTOCOL_VERSION,
    val cryptoVersion: Int = Constants.CRYPTO_VERSION,
    val sessionToken: String,
    val groupId: String,
    val messageId: String,
    val from: String,
    val to: String,
    val msgType: String,
    val timestamp: Long,
    val nonce: String,
    val ciphertext: String,
    val sig: String,
    val attachment: AttachmentPointer? = null
)

data class GroupEventPayload(
    val groupId: String,
    val eventType: String,
    val actorId: String? = null,
    val targetId: String? = null,
    val title: String? = null,
    val memberIds: List<String>? = null,
    val timestamp: Long,
    val messageId: String? = null,
    val from: String? = null,
    val msgType: String? = null,
    val nonce: String? = null,
    val ciphertext: String? = null,
    val sig: String? = null
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

        // Send encrypted message to each member individually
        // This is how Whisper2 handles group encryption - per-member encryption
        for (member in members) {
            if (member.memberId == myId) continue

            val contact = contactDao.getContactById(member.memberId)
            val memberPubKeyBase64 = contact?.encPublicKey ?: continue

            try {
                val memberPubKey = Base64.decode(memberPubKeyBase64, Base64.NO_WRAP)
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

                val payload = GroupSendMessagePayload(
                    sessionToken = sessionToken,
                    groupId = groupId,
                    messageId = messageId,
                    from = myId,
                    to = member.memberId,
                    msgType = Constants.ContentType.TEXT,
                    timestamp = timestamp,
                    nonce = Base64.encodeToString(nonce, Base64.NO_WRAP),
                    ciphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
                    sig = Base64.encodeToString(signature, Base64.NO_WRAP)
                )

                wsClient.send(WsFrame(Constants.MsgType.GROUP_SEND_MESSAGE, payload = payload))
            } catch (e: Exception) {
                Logger.e("Failed to send to ${member.memberId}", e)
            }
        }

        // Update group last message
        groupDao.updateLastMessage(groupId, content, timestamp)

        return Result.success(Unit)
    }

    // MARK: - Message Handling

    private suspend fun handleMessage(frame: WsFrame<JsonElement>) {
        when (frame.type) {
            "group_create_ack" -> handleGroupCreateAck(frame.payload)
            Constants.MsgType.GROUP_EVENT -> handleGroupEvent(frame.payload)
        }
    }

    private suspend fun handleGroupCreateAck(payload: JsonElement) {
        try {
            val ack = gson.fromJson(payload, GroupCreateAckPayload::class.java)
            val myId = secureStorage.whisperId ?: return

            val group = GroupEntity(
                groupId = ack.groupId,
                name = ack.title,
                creatorId = myId,
                memberCount = ack.memberIds.size,
                createdAt = ack.createdAt,
                updatedAt = ack.createdAt
            )
            groupDao.insert(group)

            // Add members
            ack.memberIds.forEach { memberId ->
                groupDao.insertMember(GroupMemberEntity(
                    groupId = ack.groupId,
                    memberId = memberId,
                    joinedAt = ack.createdAt
                ))
            }

            Logger.d("Group created: ${ack.groupId}")
        } catch (e: Exception) {
            Logger.e("Failed to handle group create ack", e)
        }
    }

    private suspend fun handleGroupEvent(payload: JsonElement) {
        try {
            val event = gson.fromJson(payload, GroupEventPayload::class.java)
            val myId = secureStorage.whisperId ?: return

            when (event.eventType) {
                "created" -> {
                    // New group created (we were added)
                    if (event.memberIds != null && event.title != null) {
                        val group = GroupEntity(
                            groupId = event.groupId,
                            name = event.title,
                            creatorId = event.actorId ?: "",
                            memberCount = event.memberIds.size,
                            createdAt = event.timestamp,
                            updatedAt = event.timestamp
                        )
                        groupDao.insert(group)

                        event.memberIds.forEach { memberId ->
                            groupDao.insertMember(GroupMemberEntity(
                                groupId = event.groupId,
                                memberId = memberId,
                                joinedAt = event.timestamp
                            ))
                        }
                    }
                }

                "member_added" -> {
                    event.targetId?.let { targetId ->
                        groupDao.insertMember(GroupMemberEntity(
                            groupId = event.groupId,
                            memberId = targetId,
                            joinedAt = event.timestamp
                        ))
                        val group = groupDao.getGroupById(event.groupId)
                        group?.let {
                            groupDao.insert(it.copy(memberCount = it.memberCount + 1, updatedAt = event.timestamp))
                        }
                    }
                }

                "member_removed", "member_left" -> {
                    event.targetId?.let { targetId ->
                        groupDao.removeMember(event.groupId, targetId)
                        val group = groupDao.getGroupById(event.groupId)
                        group?.let {
                            groupDao.insert(it.copy(memberCount = maxOf(0, it.memberCount - 1), updatedAt = event.timestamp))
                        }

                        // If we were removed, delete the group locally
                        if (targetId == myId) {
                            groupDao.delete(event.groupId)
                        }
                    }
                }

                "title_changed" -> {
                    event.title?.let { title ->
                        groupDao.updateName(event.groupId, title)
                    }
                }

                "message_received" -> {
                    // Handle incoming group message
                    handleIncomingGroupMessage(event)
                }
            }

            _groupEvents.emit(event)
        } catch (e: Exception) {
            Logger.e("Failed to handle group event", e)
        }
    }

    private suspend fun handleIncomingGroupMessage(event: GroupEventPayload) {
        val myId = secureStorage.whisperId ?: return
        val myPrivKey = secureStorage.encPrivateKey ?: return
        val from = event.from ?: return
        val messageId = event.messageId ?: return
        val ciphertextB64 = event.ciphertext ?: return
        val nonceB64 = event.nonce ?: return

        // Get sender's public key
        val contact = contactDao.getContactById(from)
        val senderPubKeyBase64 = contact?.encPublicKey ?: return

        try {
            val senderPubKey = Base64.decode(senderPubKeyBase64, Base64.NO_WRAP)
            val ciphertext = Base64.decode(ciphertextB64, Base64.NO_WRAP)
            val nonce = Base64.decode(nonceB64, Base64.NO_WRAP)

            val plaintext = cryptoService.boxOpen(ciphertext, nonce, senderPubKey, myPrivKey)
            val content = String(plaintext, Charsets.UTF_8)

            // Check for duplicates
            val existing = messageDao.getMessageById(messageId)
            if (existing != null) return

            // Store message
            val message = MessageEntity(
                id = messageId,
                conversationId = event.groupId,
                groupId = event.groupId,
                from = from,
                to = event.groupId,
                contentType = event.msgType ?: Constants.ContentType.TEXT,
                content = content,
                timestamp = event.timestamp,
                status = Constants.MessageStatus.DELIVERED,
                direction = Constants.Direction.INCOMING
            )
            messageDao.insert(message)

            // Update group
            groupDao.updateLastMessage(event.groupId, content, event.timestamp)
            groupDao.incrementUnreadCount(event.groupId)

            Logger.d("Received group message in ${event.groupId} from $from")
        } catch (e: Exception) {
            Logger.e("Failed to decrypt group message", e)
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
