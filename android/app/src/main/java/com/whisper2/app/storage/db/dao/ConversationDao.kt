package com.whisper2.app.storage.db.dao

import androidx.room.*
import com.whisper2.app.storage.db.entities.ConversationEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for conversations
 */
@Dao
interface ConversationDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(conversation: ConversationEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(conversation: ConversationEntity): Long

    @Update
    fun update(conversation: ConversationEntity)

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun getById(id: String): ConversationEntity?

    @Query("SELECT * FROM conversations ORDER BY lastMessageAt DESC")
    fun getAll(): List<ConversationEntity>

    /** Reactive: Observe all conversations ordered by last message */
    @Query("SELECT * FROM conversations ORDER BY lastMessageAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    /** Reactive: Observe single conversation */
    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeById(id: String): Flow<ConversationEntity?>

    /** Reactive: Observe total unread count */
    @Query("SELECT SUM(unreadCount) FROM conversations")
    fun observeTotalUnreadCount(): Flow<Int?>

    @Query("SELECT EXISTS(SELECT 1 FROM conversations WHERE id = :id)")
    fun exists(id: String): Boolean

    @Query("SELECT COUNT(*) FROM conversations")
    fun count(): Int

    /**
     * Upsert: insert or update conversation
     * Updates lastMessageAt and increments unreadCount
     */
    @Transaction
    fun upsertWithNewMessage(
        conversationId: String,
        type: String,
        timestamp: Long,
        preview: String?,
        incrementUnread: Boolean = true
    ) {
        val existing = getById(conversationId)
        if (existing == null) {
            insert(ConversationEntity(
                id = conversationId,
                type = type,
                lastMessageAt = timestamp,
                unreadCount = if (incrementUnread) 1 else 0,
                lastMessagePreview = preview
            ))
        } else {
            val newUnreadCount = if (incrementUnread) existing.unreadCount + 1 else existing.unreadCount
            update(existing.copy(
                lastMessageAt = maxOf(existing.lastMessageAt, timestamp),
                unreadCount = newUnreadCount,
                lastMessagePreview = preview
            ))
        }
    }

    @Query("UPDATE conversations SET unreadCount = 0 WHERE id = :id")
    fun markAsRead(id: String)

    @Query("UPDATE conversations SET unreadCount = :count WHERE id = :id")
    fun setUnreadCount(id: String, count: Int)

    @Query("DELETE FROM conversations WHERE id = :id")
    fun delete(id: String)

    @Query("DELETE FROM conversations")
    fun deleteAll()
}
