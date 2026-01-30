package com.whisper2.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whisper2.app.data.local.db.dao.GroupDao
import com.whisper2.app.data.local.db.entities.GroupEntity
import com.whisper2.app.data.local.db.entities.GroupInviteEntity
import com.whisper2.app.services.groups.GroupService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GroupsViewModel @Inject constructor(
    private val groupDao: GroupDao,
    private val groupService: GroupService
) : ViewModel() {

    val groups: StateFlow<List<GroupEntity>> = groupDao
        .getAllGroupsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingInvites: StateFlow<List<GroupInviteEntity>> = groupService
        .getPendingInvitesFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun createGroup(name: String, memberIds: List<String>) {
        viewModelScope.launch {
            groupService.createGroup(name, memberIds)
        }
    }

    fun leaveGroup(groupId: String) {
        viewModelScope.launch {
            groupService.leaveGroup(groupId)
        }
    }

    fun acceptInvite(groupId: String) {
        viewModelScope.launch {
            groupService.respondToInvite(groupId, accepted = true)
        }
    }

    fun declineInvite(groupId: String) {
        viewModelScope.launch {
            groupService.respondToInvite(groupId, accepted = false)
        }
    }
}
