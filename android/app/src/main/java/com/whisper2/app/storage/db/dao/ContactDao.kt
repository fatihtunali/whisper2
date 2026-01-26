package com.whisper2.app.storage.db.dao

import androidx.room.*
import com.whisper2.app.storage.db.entities.ContactEntity

/**
 * Data Access Object for contacts
 */
@Dao
interface ContactDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(contact: ContactEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(contacts: List<ContactEntity>)

    @Update
    fun update(contact: ContactEntity)

    @Query("SELECT * FROM contacts WHERE id = :id")
    fun getById(id: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE whisperId = :whisperId")
    fun getByWhisperId(whisperId: String): ContactEntity?

    @Query("SELECT * FROM contacts ORDER BY displayName ASC, whisperId ASC")
    fun getAll(): List<ContactEntity>

    @Query("SELECT * FROM contacts WHERE isBlocked = 0 ORDER BY displayName ASC, whisperId ASC")
    fun getAllNonBlocked(): List<ContactEntity>

    @Query("SELECT * FROM contacts WHERE isFavorite = 1 ORDER BY displayName ASC, whisperId ASC")
    fun getFavorites(): List<ContactEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM contacts WHERE whisperId = :whisperId)")
    fun exists(whisperId: String): Boolean

    @Query("SELECT COUNT(*) FROM contacts")
    fun count(): Int

    @Query("DELETE FROM contacts WHERE id = :id")
    fun delete(id: String)

    @Query("DELETE FROM contacts WHERE whisperId = :whisperId")
    fun deleteByWhisperId(whisperId: String)

    @Query("DELETE FROM contacts")
    fun deleteAll()

    /**
     * Replace all contacts with new list (for restore)
     * This is a transaction: delete all then insert new
     */
    @Transaction
    fun replaceAll(contacts: List<ContactEntity>) {
        deleteAll()
        insertAll(contacts)
    }
}
