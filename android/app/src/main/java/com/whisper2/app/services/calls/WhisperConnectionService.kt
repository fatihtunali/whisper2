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
        Logger.i("[WhisperConnectionService] onCreateIncomingConnection - extras: ${request?.extras}")

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

            // Get call data from extras
            request?.extras?.let { extras ->
                val callIdValue = extras.getString(EXTRA_CALL_ID, "")
                val callerNameValue = extras.getString(EXTRA_CALLER_NAME, "Unknown")
                val callerIdValue = extras.getString(EXTRA_CALLER_ID, "")
                val isVideo = extras.getBoolean(EXTRA_IS_VIDEO, false)

                // Store call info on the connection for onShowIncomingCallUi
                this.callId = callIdValue
                this.callerId = callerIdValue
                this.callerName = callerNameValue
                this.isVideoCall = isVideo

                Logger.i("[WhisperConnectionService] Setting up connection - callId: $callIdValue, caller: $callerNameValue, isVideo: $isVideo")

                setCallerDisplayName(callerNameValue, TelecomManager.PRESENTATION_ALLOWED)
                setAddress(request.address, TelecomManager.PRESENTATION_ALLOWED)

                if (isVideo) {
                    setVideoState(VideoProfile.STATE_BIDIRECTIONAL)
                }

                // Store call data for later
                putExtras(Bundle().apply {
                    putString(EXTRA_CALLER_ID, callerIdValue)
                    putBoolean(EXTRA_IS_VIDEO, isVideo)
                })
            }

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
    }
}
