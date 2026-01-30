package com.whisper2.app.data.local.db.dao

import androidx.room.*
import com.whisper2.app.data.local.db.entities.ContactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts WHERE isMessageRequest = 0 ORDER BY displayName")
    fun getAllContacts(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE isMessageRequest = 1")
    fun getMessageRequests(): Flow<List<ContactEntity>>

    @Query("SELECT COUNT(*) FROM contacts WHERE isMessageRequest = 1")
    fun getPendingRequestCount(): Flow<Int>

    @Query("SELECT * FROM contacts WHERE whisperId = :whisperId")
    fun getContactByWhisperId(whisperId: String): Flow<ContactEntity?>

    @Query("SELECT * FROM contacts WHERE whisperId = :whisperId")
    suspend fun getContactById(whisperId: String): ContactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(contact: ContactEntity)

    @Update
    suspend fun update(contact: ContactEntity)

    @Query("UPDATE contacts SET displayName = :nickname WHERE whisperId = :whisperId")
    suspend fun updateNickname(whisperId: String, nickname: String)

    @Query("UPDATE contacts SET encPublicKey = :key WHERE whisperId = :whisperId")
    suspend fun updatePublicKey(whisperId: String, key: String)

    @Query("UPDATE contacts SET signPublicKey = :key WHERE whisperId = :whisperId")
    suspend fun updateSignPublicKey(whisperId: String, key: String)

    @Query("UPDATE contacts SET isBlocked = :blocked WHERE whisperId = :whisperId")
    suspend fun setBlocked(whisperId: String, blocked: Boolean)

    @Query("SELECT * FROM contacts WHERE isBlocked = 1 ORDER BY displayName")
    fun getBlockedContacts(): Flow<List<ContactEntity>>

    @Query("SELECT isBlocked FROM contacts WHERE whisperId = :whisperId")
    suspend fun isBlocked(whisperId: String): Boolean?

    @Query("DELETE FROM contacts WHERE whisperId = :whisperId")
    suspend fun deleteByWhisperId(whisperId: String)

    @Query("DELETE FROM contacts")
    suspend fun deleteAll()
}
