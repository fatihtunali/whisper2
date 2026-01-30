package com.whisper2.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whisper2.app.data.local.db.dao.ContactDao
import com.whisper2.app.data.local.db.dao.GroupDao
import com.whisper2.app.data.local.db.entities.GroupEntity
import com.whisper2.app.data.local.prefs.SecureStorage
import com.whisper2.app.services.groups.GroupService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GroupMemberInfo(
    val memberId: String,
    val displayName: String,
    val role: String,
    val isCurrentUser: Boolean
)

@HiltViewModel
class GroupInfoViewModel @Inject constructor(
    private val groupDao: GroupDao,
    private val contactDao: ContactDao,
    private val groupService: GroupService,
    private val secureStorage: SecureStorage
) : ViewModel() {

    private var currentGroupId: String? = null

    private val _group = MutableStateFlow<GroupEntity?>(null)
    val group: StateFlow<GroupEntity?> = _group.asStateFlow()

    private val _members = MutableStateFlow<List<GroupMemberInfo>>(emptyList())
    val members: StateFlow<List<GroupMemberInfo>> = _members.asStateFlow()

    private val _isOwner = MutableStateFlow(false)
    val isOwner: StateFlow<Boolean> = _isOwner.asStateFlow()

    fun loadGroup(groupId: String) {
        if (currentGroupId == groupId) return
        currentGroupId = groupId

        viewModelScope.launch {
            // Load group
            groupDao.getGroupByIdFlow(groupId).collect { group ->
                _group.value = group
                _isOwner.value = group?.creatorId == secureStorage.whisperId
            }
        }

        viewModelScope.launch {
            // Load members
            groupDao.getGroupMembersFlow(groupId).collect { memberEntities ->
                val myId = secureStorage.whisperId
                val memberInfos = memberEntities.map { member ->
                    val contact = contactDao.getContactById(member.memberId)
                    GroupMemberInfo(
                        memberId = member.memberId,
                        displayName = contact?.displayName ?: member.memberId.takeLast(4),
                        role = member.role,
                        isCurrentUser = member.memberId == myId
                    )
                }.sortedWith(compareBy(
                    { it.role != "owner" }, // Owner first
                    { !it.isCurrentUser }, // Then current user
                    { it.displayName } // Then alphabetically
                ))
                _members.value = memberInfos
            }
        }
    }

    fun leaveGroup() {
        val groupId = currentGroupId ?: return
        viewModelScope.launch {
            groupService.leaveGroup(groupId)
        }
    }

    fun removeMember(memberId: String) {
        val groupId = currentGroupId ?: return
        viewModelScope.launch {
            groupService.removeMembers(groupId, listOf(memberId))
        }
    }
}
