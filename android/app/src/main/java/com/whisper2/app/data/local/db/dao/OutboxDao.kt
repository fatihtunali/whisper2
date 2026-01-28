package com.whisper2.app.data.local.db.dao

import androidx.room.*
import com.whisper2.app.data.local.db.entities.OutboxEntity

@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox WHERE status = 'pending' ORDER BY createdAt ASC")
    suspend fun getPending(): List<OutboxEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: OutboxEntity)

    @Query("UPDATE outbox SET status = :status WHERE messageId = :messageId")
    suspend fun updateStatus(messageId: String, status: String)

    @Query("UPDATE outbox SET retryCount = retryCount + 1, lastAttemptAt = :timestamp WHERE messageId = :messageId")
    suspend fun incrementRetry(messageId: String, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM outbox WHERE messageId = :messageId")
    suspend fun delete(messageId: String)

    @Query("DELETE FROM outbox")
    suspend fun deleteAll()
}
