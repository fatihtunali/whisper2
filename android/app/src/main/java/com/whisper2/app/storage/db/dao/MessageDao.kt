package com.whisper2.app.storage.db.dao

import androidx.room.*
import com.whisper2.app.storage.db.entities.MessageEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for messages
 */
@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(message: MessageEntity): Long

    @Update
    fun update(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE messageId = :messageId")
    fun getById(messageId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp DESC")
    fun getByConversation(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp DESC LIMIT :limit")
    fun getByConversationPaged(conversationId: String, limit: Int): List<MessageEntity>

    /** Reactive: Observe messages for a conversation (newest first) */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp DESC")
    fun observeByConversation(conversationId: String): Flow<List<MessageEntity>>

    /** Reactive: Observe single message by ID */
    @Query("SELECT * FROM messages WHERE messageId = :messageId")
    fun observeById(messageId: String): Flow<MessageEntity?>

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE messageId = :messageId)")
    fun exists(messageId: String): Boolean

    @Query("SELECT COUNT(*) FROM messages")
    fun count(): Int

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    fun countByConversation(conversationId: String): Int

    @Query("UPDATE messages SET status = :status WHERE messageId = :messageId")
    fun updateStatus(messageId: String, status: String)

    /** Update attachment local path when downloaded */
    @Query("UPDATE messages SET attachmentLocalPath = :localPath WHERE messageId = :messageId")
    fun updateAttachmentLocalPath(messageId: String, localPath: String)

    @Query("DELETE FROM messages WHERE messageId = :messageId")
    fun delete(messageId: String)

    @Query("DELETE FROM messages")
    fun deleteAll()
}
