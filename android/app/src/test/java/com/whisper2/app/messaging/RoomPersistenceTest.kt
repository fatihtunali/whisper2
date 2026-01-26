package com.whisper2.app.messaging

import com.whisper2.app.storage.db.entities.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Gate 1: Room persistence tests
 *
 * Tests basic CRUD operations on MessageEntity and ConversationEntity
 * using in-memory implementations (no actual Room for JVM tests)
 */
class RoomPersistenceTest {

    private lateinit var messageDao: InMemoryMessageDao
    private lateinit var conversationDao: InMemoryConversationDao

    @Before
    fun setup() {
        messageDao = InMemoryMessageDao()
        conversationDao = InMemoryConversationDao()
    }

    // ==========================================================================
    // Gate 1: Message persistence
    // ==========================================================================

    @Test
    fun `gate1 message insert and read`() {
        val message = MessageEntity(
            messageId = "msg-1",
            conversationId = "conv-1",
            from = "WSP-SENDER",
            to = "WSP-RECIPIENT",
            msgType = "text",
            timestamp = 1700000000000L,
            nonceB64 = "nonce-b64",
            ciphertextB64 = "ciphertext-b64",
            sigB64 = "sig-b64",
            text = "Hello",
            status = MessageStatus.DELIVERED
        )

        val result = messageDao.insert(message)

        assertTrue("Insert should succeed", result >= 0)
        assertEquals(1, messageDao.count())

        val saved = messageDao.getById("msg-1")
        assertNotNull(saved)
        assertEquals("msg-1", saved!!.messageId)
        assertEquals("Hello", saved.text)
        assertEquals("WSP-SENDER", saved.from)
    }

    @Test
    fun `gate1 duplicate messageId is ignored`() {
        val message1 = MessageEntity(
            messageId = "msg-dup",
            conversationId = "conv-1",
            from = "WSP-A",
            to = "WSP-B",
            msgType = "text",
            timestamp = 1700000000000L,
            nonceB64 = "n1",
            ciphertextB64 = "c1",
            sigB64 = "s1",
            text = "First"
        )

        val message2 = MessageEntity(
            messageId = "msg-dup", // Same ID
            conversationId = "conv-1",
            from = "WSP-A",
            to = "WSP-B",
            msgType = "text",
            timestamp = 1700000001000L,
            nonceB64 = "n2",
            ciphertextB64 = "c2",
            sigB64 = "s2",
            text = "Second"
        )

        messageDao.insert(message1)
        val result2 = messageDao.insert(message2)

        assertEquals("Duplicate insert should return -1", -1L, result2)
        assertEquals("Only one message should exist", 1, messageDao.count())

        val saved = messageDao.getById("msg-dup")
        assertEquals("First", saved?.text) // Original text preserved
    }

    @Test
    fun `gate1 message exists check`() {
        assertFalse(messageDao.exists("msg-x"))

        messageDao.insert(MessageEntity(
            messageId = "msg-x",
            conversationId = "c",
            from = "A",
            to = "B",
            msgType = "text",
            timestamp = 0,
            nonceB64 = "",
            ciphertextB64 = "",
            sigB64 = ""
        ))

        assertTrue(messageDao.exists("msg-x"))
    }

    @Test
    fun `gate1 message status update`() {
        messageDao.insert(MessageEntity(
            messageId = "msg-status",
            conversationId = "c",
            from = "A",
            to = "B",
            msgType = "text",
            timestamp = 0,
            nonceB64 = "",
            ciphertextB64 = "",
            sigB64 = "",
            status = MessageStatus.DELIVERED
        ))

        messageDao.updateStatus("msg-status", MessageStatus.READ)

        val saved = messageDao.getById("msg-status")
        assertEquals(MessageStatus.READ, saved?.status)
    }

    // ==========================================================================
    // Gate 1: Conversation persistence
    // ==========================================================================

    @Test
    fun `gate1 conversation insert and read`() {
        val conv = ConversationEntity(
            id = "conv-1",
            type = ConversationType.DIRECT,
            lastMessageAt = 1700000000000L,
            unreadCount = 0
        )

        conversationDao.insert(conv)

        assertEquals(1, conversationDao.count())

        val saved = conversationDao.getById("conv-1")
        assertNotNull(saved)
        assertEquals("conv-1", saved!!.id)
        assertEquals(ConversationType.DIRECT, saved.type)
    }

    @Test
    fun `gate1 conversation upsert with new message`() {
        // First message creates conversation
        conversationDao.upsertWithNewMessage(
            conversationId = "peer-1",
            type = ConversationType.DIRECT,
            timestamp = 1700000000000L,
            preview = "Hello",
            incrementUnread = true
        )

        var conv = conversationDao.getById("peer-1")
        assertNotNull(conv)
        assertEquals(1, conv!!.unreadCount)
        assertEquals(1700000000000L, conv.lastMessageAt)
        assertEquals("Hello", conv.lastMessagePreview)

        // Second message updates conversation
        conversationDao.upsertWithNewMessage(
            conversationId = "peer-1",
            type = ConversationType.DIRECT,
            timestamp = 1700000001000L,
            preview = "World",
            incrementUnread = true
        )

        conv = conversationDao.getById("peer-1")
        assertEquals(2, conv!!.unreadCount)
        assertEquals(1700000001000L, conv.lastMessageAt)
        assertEquals("World", conv.lastMessagePreview)
    }

    @Test
    fun `gate1 conversation mark as read`() {
        conversationDao.insert(ConversationEntity(
            id = "conv-read",
            unreadCount = 5
        ))

        conversationDao.markAsRead("conv-read")

        val conv = conversationDao.getById("conv-read")
        assertEquals(0, conv?.unreadCount)
    }
}

// ==========================================================================
// In-Memory Implementations for JVM Testing
// ==========================================================================

class InMemoryMessageDao {
    private val messages = mutableMapOf<String, MessageEntity>()

    fun insert(message: MessageEntity): Long {
        if (messages.containsKey(message.messageId)) {
            return -1L
        }
        messages[message.messageId] = message
        return 1L
    }

    fun getById(messageId: String): MessageEntity? = messages[messageId]

    fun exists(messageId: String): Boolean = messages.containsKey(messageId)

    fun count(): Int = messages.size

    fun updateStatus(messageId: String, status: String) {
        messages[messageId]?.let {
            messages[messageId] = it.copy(status = status)
        }
    }

    fun getByConversation(conversationId: String): List<MessageEntity> =
        messages.values.filter { it.conversationId == conversationId }
            .sortedByDescending { it.timestamp }

    fun deleteAll() = messages.clear()
}

class InMemoryConversationDao {
    private val conversations = mutableMapOf<String, ConversationEntity>()

    fun insert(conversation: ConversationEntity): Long {
        if (conversations.containsKey(conversation.id)) {
            return -1L
        }
        conversations[conversation.id] = conversation
        return 1L
    }

    fun getById(id: String): ConversationEntity? = conversations[id]

    fun exists(id: String): Boolean = conversations.containsKey(id)

    fun count(): Int = conversations.size

    fun update(conversation: ConversationEntity) {
        conversations[conversation.id] = conversation
    }

    fun upsertWithNewMessage(
        conversationId: String,
        type: String,
        timestamp: Long,
        preview: String?,
        incrementUnread: Boolean = true
    ) {
        val existing = conversations[conversationId]
        if (existing == null) {
            conversations[conversationId] = ConversationEntity(
                id = conversationId,
                type = type,
                lastMessageAt = timestamp,
                unreadCount = if (incrementUnread) 1 else 0,
                lastMessagePreview = preview
            )
        } else {
            conversations[conversationId] = existing.copy(
                lastMessageAt = maxOf(existing.lastMessageAt, timestamp),
                unreadCount = if (incrementUnread) existing.unreadCount + 1 else existing.unreadCount,
                lastMessagePreview = preview
            )
        }
    }

    fun markAsRead(id: String) {
        conversations[id]?.let {
            conversations[id] = it.copy(unreadCount = 0)
        }
    }

    fun deleteAll() = conversations.clear()
}
