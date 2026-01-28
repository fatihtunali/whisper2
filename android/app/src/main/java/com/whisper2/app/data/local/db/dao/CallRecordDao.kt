package com.whisper2.app.data.local.db.dao

import androidx.room.*
import com.whisper2.app.data.local.db.entities.CallRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CallRecordDao {
    @Query("SELECT * FROM call_records ORDER BY startedAt DESC")
    fun getAllCallRecords(): Flow<List<CallRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: CallRecordEntity)

    @Query("DELETE FROM call_records")
    suspend fun deleteAll()
}
