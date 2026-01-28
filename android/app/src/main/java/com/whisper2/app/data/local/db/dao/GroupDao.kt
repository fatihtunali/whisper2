package com.whisper2.app.data.local.db.dao

import androidx.room.*
import com.whisper2.app.data.local.db.entities.GroupEntity
import com.whisper2.app.data.local.db.entities.GroupMemberEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {
    @Query("SELECT * FROM groups ORDER BY updatedAt DESC")
    fun getAllGroupsFlow(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups ORDER BY updatedAt DESC")
    suspend fun getAllGroups(): List<GroupEntity>

    @Query("SELECT * FROM groups WHERE groupId = :groupId")
    fun getGroupByIdFlow(groupId: String): Flow<GroupEntity?>

    @Query("SELECT * FROM groups WHERE groupId = :groupId")
    suspend fun getGroupById(groupId: String): GroupEntity?

    @Query("SELECT * FROM group_members WHERE groupId = :groupId")
    fun getGroupMembersFlow(groupId: String): Flow<List<GroupMemberEntity>>

    @Query("SELECT * FROM group_members WHERE groupId = :groupId")
    suspend fun getGroupMembers(groupId: String): List<GroupMemberEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(group: GroupEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMember(member: GroupMemberEntity)

    @Query("UPDATE groups SET name = :name, updatedAt = :updatedAt WHERE groupId = :groupId")
    suspend fun updateName(groupId: String, name: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE groups SET lastMessagePreview = :preview, lastMessageTimestamp = :timestamp, updatedAt = :timestamp WHERE groupId = :groupId")
    suspend fun updateLastMessage(groupId: String, preview: String, timestamp: Long)

    @Query("UPDATE groups SET unreadCount = unreadCount + 1 WHERE groupId = :groupId")
    suspend fun incrementUnreadCount(groupId: String)

    @Query("UPDATE groups SET unreadCount = 0 WHERE groupId = :groupId")
    suspend fun resetUnreadCount(groupId: String)

    @Query("DELETE FROM group_members WHERE groupId = :groupId AND memberId = :memberId")
    suspend fun removeMember(groupId: String, memberId: String)

    @Query("DELETE FROM groups WHERE groupId = :groupId")
    suspend fun delete(groupId: String)

    @Query("DELETE FROM group_members WHERE groupId = :groupId")
    suspend fun deleteGroupMembers(groupId: String)

    @Query("DELETE FROM groups")
    suspend fun deleteAll()
}
