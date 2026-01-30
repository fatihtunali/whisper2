package com.whisper2.app.data.local.db.dao

import androidx.room.*
import com.whisper2.app.data.local.db.entities.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY lastMessageTimestamp DESC")
    fun getAllConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE peerId = :peerId")
    fun getConversation(peerId: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE peerId = :peerId")
    suspend fun getConversationById(peerId: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conversation: ConversationEntity)

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Query("UPDATE conversations SET unreadCount = 0 WHERE peerId = :peerId")
    suspend fun markAsRead(peerId: String)

    @Query("UPDATE conversations SET isTyping = :isTyping WHERE peerId = :peerId")
    suspend fun setTyping(peerId: String, isTyping: Boolean)

    @Query("UPDATE conversations SET isTyping = 0")
    suspend fun clearAllTyping()

    @Query("DELETE FROM conversations WHERE peerId = :peerId")
    suspend fun deleteByPeerId(peerId: String)

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()

    @Query("UPDATE conversations SET disappearingTimer = :timer WHERE peerId = :peerId")
    suspend fun setDisappearingTimer(peerId: String, timer: String)

    @Query("UPDATE conversations SET isPinned = :isPinned WHERE peerId = :peerId")
    suspend fun setPinned(peerId: String, isPinned: Boolean)

    @Query("UPDATE conversations SET isMuted = :isMuted WHERE peerId = :peerId")
    suspend fun setMuted(peerId: String, isMuted: Boolean)
}
