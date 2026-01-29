package com.whisper2.app.services.calls

import android.os.Bundle
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import com.whisper2.app.core.Logger

/**
 * ConnectionService for handling VoIP calls with Android Telecom framework.
 * This is required for self-managed VoIP calls (like WhatsApp, Signal, etc.)
 */
class WhisperConnectionService : ConnectionService() {

    override fun onCreate() {
        super.onCreate()
        Logger.i("[WhisperConnectionService] Service created")

        // Clean up any stale connection from previous session
        activeConnection?.let { oldConnection ->
            Logger.w("[WhisperConnectionService] Cleaning up stale connection on service create")
            try {
                oldConnection.setDisconnected(android.telecom.DisconnectCause(android.telecom.DisconnectCause.LOCAL))
                oldConnection.destroy()
            } catch (e: Exception) {
                Logger.e("[WhisperConnectionService] Error cleaning up: ${e.message}")
            }
            activeConnection = null
        }
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        Logger.i("[WhisperConnectionService] onCreateIncomingConnection called")
        Logger.i("[WhisperConnectionService] Request address: ${request?.address}")
        Logger.i("[WhisperConnectionService] Request extras: ${request?.extras}")
        Logger.i("[WhisperConnectionService] Extras keys: ${request?.extras?.keySet()}")

        // Clean up any existing stale connection first
        activeConnection?.let { oldConnection ->
            Logger.w("[WhisperConnectionService] Cleaning up stale connection before new incoming call")
            try {
                oldConnection.setDisconnected(android.telecom.DisconnectCause(android.telecom.DisconnectCause.LOCAL))
                oldConnection.destroy()
            } catch (e: Exception) {
                Logger.e("[WhisperConnectionService] Error cleaning up old connection: ${e.message}")
            }
            activeConnection = null
        }

        val connection = WhisperConnection(applicationContext).apply {
            setInitializing()

            // Get call data from extras - try multiple locations as Android may wrap them
            val extras = request?.extras
            Logger.i("[WhisperConnectionService] Raw extras bundle: $extras")

            // Some Android versions nest extras under a different key
            val actualExtras = extras?.getBundle("android.telecom.extra.INCOMING_CALL_EXTRAS")
                ?: extras

            Logger.i("[WhisperConnectionService] Actual extras to read from: $actualExtras, keys: ${actualExtras?.keySet()}")

            actualExtras?.let { bundle ->
                var callIdValue = bundle.getString(EXTRA_CALL_ID, "")
                var callerNameValue = bundle.getString(EXTRA_CALLER_NAME, "")
                var callerIdValue = bundle.getString(EXTRA_CALLER_ID, "")
                var isVideo = bundle.getBoolean(EXTRA_IS_VIDEO, false)

                Logger.i("[WhisperConnectionService] *** EXTRAS isVideo=$isVideo ***")
                Logger.i("[WhisperConnectionService] Extracted values - callId: '$callIdValue', callerName: '$callerNameValue', callerId: '$callerIdValue'")

                // If extras are empty, fall back to pendingCallInfo
                if (callIdValue.isEmpty() && callerIdValue.isEmpty() && callerNameValue.isEmpty()) {
                    Logger.w("[WhisperConnectionService] Extras values are empty, checking pendingCallInfo")
                    pendingCallInfo?.let { pending ->
                        Logger.i("[WhisperConnectionService] *** FALLBACK TO pendingCallInfo - isVideo=${pending.isVideo} ***")
                        Logger.i("[WhisperConnectionService] pendingCallInfo: callId=${pending.callId}, callerName=${pending.callerName}")
                        callIdValue = pending.callId
                        callerIdValue = pending.callerId
                        callerNameValue = pending.callerName
                        isVideo = pending.isVideo
                    }
                }

                // Store call info on the connection for onShowIncomingCallUi
                this.callId = callIdValue
                this.callerId = callerIdValue
                this.callerName = if (callerNameValue.isNotEmpty()) callerNameValue else if (callerIdValue.isNotEmpty()) callerIdValue else "Unknown Caller"
                this.isVideoCall = isVideo

                Logger.i("[WhisperConnectionService] Final connection values - callId: ${this.callId}, callerName: ${this.callerName}, isVideo: ${this.isVideoCall}")

                setCallerDisplayName(this.callerName ?: "Unknown Caller", TelecomManager.PRESENTATION_ALLOWED)
                setAddress(request?.address, TelecomManager.PRESENTATION_ALLOWED)

                if (isVideo) {
                    setVideoState(VideoProfile.STATE_BIDIRECTIONAL)
                }

                // Store call data for later
                putExtras(Bundle().apply {
                    putString(EXTRA_CALLER_ID, callerIdValue)
                    putBoolean(EXTRA_IS_VIDEO, isVideo)
                })
            } ?: run {
                // Fallback to pendingCallInfo if extras are empty
                Logger.w("[WhisperConnectionService] No extras found, trying pendingCallInfo fallback")
                pendingCallInfo?.let { pending ->
                    Logger.i("[WhisperConnectionService] Using pendingCallInfo: callId=${pending.callId}, callerName=${pending.callerName}, isVideo=${pending.isVideo}")
                    this.callId = pending.callId
                    this.callerId = pending.callerId
                    this.callerName = pending.callerName
                    this.isVideoCall = pending.isVideo

                    setCallerDisplayName(pending.callerName, TelecomManager.PRESENTATION_ALLOWED)

                    if (pending.isVideo) {
                        setVideoState(VideoProfile.STATE_BIDIRECTIONAL)
                    }
                } ?: run {
                    Logger.e("[WhisperConnectionService] No extras AND no pendingCallInfo! Setting defaults")
                    this.callId = ""
                    this.callerId = ""
                    this.callerName = "Unknown Caller"
                    this.isVideoCall = false
                }
            }
            // Clear pendingCallInfo after use
            pendingCallInfo = null

            connectionCapabilities = Connection.CAPABILITY_MUTE or
                    Connection.CAPABILITY_SUPPORT_HOLD or
                    Connection.CAPABILITY_HOLD

            connectionProperties = Connection.PROPERTY_SELF_MANAGED

            setRinging()
        }

        // Store reference for CallService to access
        activeConnection = connection

        return connection
    }

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        Logger.e("[WhisperConnectionService] onCreateIncomingConnectionFailed - cleaning up")

        // Clean up any stale connection
        activeConnection?.let { oldConnection ->
            try {
                oldConnection.setDisconnected(android.telecom.DisconnectCause(android.telecom.DisconnectCause.ERROR))
                oldConnection.destroy()
            } catch (e: Exception) {
                Logger.e("[WhisperConnectionService] Error cleaning up: ${e.message}")
            }
            activeConnection = null
        }

        // Stop foreground service if running
        CallForegroundService.stopService(applicationContext)

        super.onCreateIncomingConnectionFailed(connectionManagerPhoneAccount, request)
    }

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        Logger.i("[WhisperConnectionService] onCreateOutgoingConnection")

        // Clean up any existing stale connection first
        activeConnection?.let { oldConnection ->
            Logger.w("[WhisperConnectionService] Cleaning up stale connection before new outgoing call")
            try {
                oldConnection.setDisconnected(android.telecom.DisconnectCause(android.telecom.DisconnectCause.LOCAL))
                oldConnection.destroy()
            } catch (e: Exception) {
                Logger.e("[WhisperConnectionService] Error cleaning up old connection: ${e.message}")
            }
            activeConnection = null
        }

        val connection = WhisperConnection(applicationContext).apply {
            setInitializing()
            setAddress(request?.address, TelecomManager.PRESENTATION_ALLOWED)

            request?.extras?.let { extras ->
                val calleeName = extras.getString(EXTRA_CALLER_NAME, "Unknown")
                val isVideo = extras.getBoolean(EXTRA_IS_VIDEO, false)

                setCallerDisplayName(calleeName, TelecomManager.PRESENTATION_ALLOWED)

                if (isVideo) {
                    setVideoState(VideoProfile.STATE_BIDIRECTIONAL)
                }
            }

            connectionCapabilities = Connection.CAPABILITY_MUTE or
                    Connection.CAPABILITY_SUPPORT_HOLD or
                    Connection.CAPABILITY_HOLD

            connectionProperties = Connection.PROPERTY_SELF_MANAGED

            setDialing()
        }

        activeConnection = connection

        return connection
    }

    override fun onCreateOutgoingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        Logger.e("[WhisperConnectionService] onCreateOutgoingConnectionFailed - cleaning up")

        // Clean up any stale connection
        activeConnection?.let { oldConnection ->
            try {
                oldConnection.setDisconnected(android.telecom.DisconnectCause(android.telecom.DisconnectCause.ERROR))
                oldConnection.destroy()
            } catch (e: Exception) {
                Logger.e("[WhisperConnectionService] Error cleaning up: ${e.message}")
            }
            activeConnection = null
        }

        super.onCreateOutgoingConnectionFailed(connectionManagerPhoneAccount, request)
    }

    companion object {
        const val EXTRA_CALLER_NAME = "caller_name"
        const val EXTRA_CALLER_ID = "caller_id"
        const val EXTRA_IS_VIDEO = "is_video"
        const val EXTRA_CALL_ID = "call_id"

        // Active connection reference (for CallService to interact with)
        @Volatile
        var activeConnection: WhisperConnection? = null

        // Pending call info - set by TelecomCallManager before addNewIncomingCall
        // Used as fallback if extras don't come through TelecomManager
        data class PendingCallInfo(
            val callId: String,
            val callerId: String,
            val callerName: String,
            val isVideo: Boolean
        )

        @Volatile
        var pendingCallInfo: PendingCallInfo? = null
    }
}
