package com.whisper2.app.data.local.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "call_records")
data class CallRecordEntity(
    @PrimaryKey val callId: String,
    val peerId: String,
    val peerName: String?,
    val isVideo: Boolean,
    val direction: String,
    val status: String,
    val duration: Int?,
    val startedAt: Long,
    val endedAt: Long?
)
