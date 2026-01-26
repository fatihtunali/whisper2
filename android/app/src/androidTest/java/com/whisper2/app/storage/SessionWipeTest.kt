package com.whisper2.app.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.whisper2.app.core.Constants
import com.whisper2.app.services.auth.SessionManager
import com.whisper2.app.storage.key.SecurePrefs
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Session Wipe Tests
 * Verifies force logout clears all sensitive material
 */
@RunWith(AndroidJUnit4::class)
class SessionWipeTest {

    private lateinit var securePrefs: SecurePrefs
    private lateinit var sessionManager: SessionManager

    @Before
    fun setup() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        securePrefs = SecurePrefs(ctx)
        sessionManager = SessionManager(securePrefs)

        // Clean slate
        securePrefs.clear()
    }

    @Test
    fun force_logout_wipes_all_sensitive_material() {
        // Setup: populate all sensitive fields
        securePrefs.putString(Constants.StorageKey.SESSION_TOKEN, "sess_abc123")
        securePrefs.putBytes(Constants.StorageKey.ENC_PRIVATE_KEY, ByteArray(32) { 1 })
        securePrefs.putBytes(Constants.StorageKey.ENC_PUBLIC_KEY, ByteArray(32) { 2 })
        securePrefs.putBytes(Constants.StorageKey.SIGN_PRIVATE_KEY, ByteArray(64) { 3 })
        securePrefs.putBytes(Constants.StorageKey.SIGN_PUBLIC_KEY, ByteArray(32) { 4 })
        securePrefs.putBytes(Constants.StorageKey.CONTACTS_KEY, ByteArray(32) { 5 })
        securePrefs.putString(Constants.StorageKey.DEVICE_ID, "device-xyz")
        securePrefs.putString(Constants.StorageKey.WHISPER_ID, "WSP-AAAA-BBBB-CCCC")
        securePrefs.putString(Constants.StorageKey.FCM_TOKEN, "fcm_token_123")

        // Verify all exist before wipe
        assertNotNull(securePrefs.getString(Constants.StorageKey.SESSION_TOKEN))
        assertNotNull(securePrefs.getBytesOrNull(Constants.StorageKey.ENC_PRIVATE_KEY))
        assertNotNull(securePrefs.getBytesOrNull(Constants.StorageKey.SIGN_PRIVATE_KEY))
        assertNotNull(securePrefs.getString(Constants.StorageKey.DEVICE_ID))

        // Action: force logout
        sessionManager.forceLogout(reason = "test_wipe")

        // Assert: ALL sensitive material is gone
        assertNull("sessionToken must be null", securePrefs.getString(Constants.StorageKey.SESSION_TOKEN))
        assertNull("encPrivateKey must be null", securePrefs.getBytesOrNull(Constants.StorageKey.ENC_PRIVATE_KEY))
        assertNull("encPublicKey must be null", securePrefs.getBytesOrNull(Constants.StorageKey.ENC_PUBLIC_KEY))
        assertNull("signPrivateKey must be null", securePrefs.getBytesOrNull(Constants.StorageKey.SIGN_PRIVATE_KEY))
        assertNull("signPublicKey must be null", securePrefs.getBytesOrNull(Constants.StorageKey.SIGN_PUBLIC_KEY))
        assertNull("contactsKey must be null", securePrefs.getBytesOrNull(Constants.StorageKey.CONTACTS_KEY))
        assertNull("deviceId must be null", securePrefs.getString(Constants.StorageKey.DEVICE_ID))
        assertNull("whisperId must be null", securePrefs.getString(Constants.StorageKey.WHISPER_ID))
        assertNull("fcmToken must be null", securePrefs.getString(Constants.StorageKey.FCM_TOKEN))
    }

    @Test
    fun force_logout_with_reason_logged() {
        // Just verify force logout doesn't throw with different reasons
        sessionManager.forceLogout(reason = "another_device_registered")
        sessionManager.forceLogout(reason = "user_manual_logout")
        sessionManager.forceLogout(reason = "auth_error")
        // No exceptions = pass
    }

    @Test
    fun session_manager_reports_logged_out_after_wipe() {
        securePrefs.sessionToken = "some_token"
        assertTrue("Should be logged in with token", sessionManager.isLoggedIn)

        sessionManager.forceLogout(reason = "test")

        assertFalse("Should be logged out after wipe", sessionManager.isLoggedIn)
        assertNull("Token should be null", sessionManager.sessionToken)
        assertNull("DeviceId should be null", sessionManager.deviceId)
    }

    @Test
    fun save_session_and_update_token() {
        sessionManager.saveSession(token = "initial_token", deviceId = "device-001")

        assertEquals("initial_token", sessionManager.sessionToken)
        assertEquals("device-001", sessionManager.deviceId)
        assertTrue(sessionManager.isLoggedIn)

        sessionManager.updateToken("refreshed_token")

        assertEquals("refreshed_token", sessionManager.sessionToken)
        assertEquals("device-001", sessionManager.deviceId) // deviceId unchanged
    }

    @Test
    fun soft_logout_keeps_keys() {
        securePrefs.encPrivateKey = ByteArray(32) { 0xAA.toByte() }
        securePrefs.signPrivateKey = ByteArray(64) { 0xBB.toByte() }
        securePrefs.sessionToken = "token"
        securePrefs.deviceId = "device"
        securePrefs.fcmToken = "fcm"

        sessionManager.softLogout()

        // Session/device/fcm cleared
        assertNull(securePrefs.sessionToken)
        assertNull(securePrefs.deviceId)
        assertNull(securePrefs.fcmToken)

        // Keys preserved
        assertNotNull("encPrivateKey should survive soft logout", securePrefs.encPrivateKey)
        assertNotNull("signPrivateKey should survive soft logout", securePrefs.signPrivateKey)
    }
}
