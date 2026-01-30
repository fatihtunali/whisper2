package com.whisper2.app.services.contacts

import android.net.Uri
import com.whisper2.app.core.Logger
import com.whisper2.app.data.local.db.dao.ContactDao
import com.whisper2.app.data.local.db.entities.ContactEntity
import com.whisper2.app.data.local.prefs.SecureStorage
import com.whisper2.app.data.network.api.WhisperApi
import com.whisper2.app.data.network.ws.WsClientImpl
import javax.inject.Inject
import javax.inject.Singleton

/**
 * QR code format: whisper2://add?id=WSP-XXXX-XXXX-XXXX&key=base64encPubKey&skey=base64signPubKey
 */
data class QrCodeData(
    val whisperId: String,
    val encPublicKey: String,
    val signPublicKey: String? = null
)

sealed class QrParseResult {
    data class Success(val data: QrCodeData) : QrParseResult()
    data class Error(val message: String) : QrParseResult()
}

@Singleton
class ContactsService @Inject constructor(
    private val contactDao: ContactDao,
    private val wsClient: WsClientImpl,
    private val secureStorage: SecureStorage,
    private val whisperApi: WhisperApi
) {
    /**
     * Parse a QR code string into contact data.
     * Format: whisper2://add?id=WSP-XXXX-XXXX-XXXX&key=base64pubkey
     */
    fun parseQrCode(qrContent: String): QrParseResult {
        return try {
            val uri = Uri.parse(qrContent)

            // Validate scheme
            if (uri.scheme != "whisper2") {
                return QrParseResult.Error("Invalid QR code: wrong scheme")
            }

            // Validate host/path
            if (uri.host != "add" && uri.path != "/add") {
                return QrParseResult.Error("Invalid QR code: not an add contact code")
            }

            // Extract WhisperID
            val whisperId = uri.getQueryParameter("id")
                ?: return QrParseResult.Error("Invalid QR code: missing WhisperID")

            // Validate WhisperID format
            if (!whisperId.matches(Regex("^WSP-[A-Z2-7]{4}-[A-Z2-7]{4}-[A-Z2-7]{4}$"))) {
                return QrParseResult.Error("Invalid QR code: invalid WhisperID format")
            }

            // Check not adding self
            if (whisperId == secureStorage.whisperId) {
                return QrParseResult.Error("Cannot add yourself as a contact")
            }

            // Extract encryption public key
            val encPublicKey = uri.getQueryParameter("key")
                ?: return QrParseResult.Error("Invalid QR code: missing public key")

            // Extract signing public key (optional)
            val signPublicKey = uri.getQueryParameter("skey")

            Logger.d("[ContactsService] Parsed QR: id=$whisperId, key=${encPublicKey.take(10)}...")

            QrParseResult.Success(QrCodeData(
                whisperId = whisperId,
                encPublicKey = encPublicKey,
                signPublicKey = signPublicKey
            ))
        } catch (e: Exception) {
            Logger.e("[ContactsService] Failed to parse QR code", e)
            QrParseResult.Error("Failed to parse QR code: ${e.message}")
        }
    }

    /**
     * Add a contact from scanned QR code data.
     */
    suspend fun addContactFromQr(qrData: QrCodeData, nickname: String = ""): Result<Unit> {
        return try {
            // Check if contact already exists
            val existing = contactDao.getContactById(qrData.whisperId)
            if (existing != null) {
                // Update public key if needed
                if (existing.encPublicKey != qrData.encPublicKey) {
                    contactDao.updatePublicKey(qrData.whisperId, qrData.encPublicKey)
                    if (qrData.signPublicKey != null) {
                        contactDao.updateSignPublicKey(qrData.whisperId, qrData.signPublicKey)
                    }
                }
                return Result.success(Unit)
            }

            val contact = ContactEntity(
                whisperId = qrData.whisperId,
                displayName = nickname.ifEmpty { qrData.whisperId.takeLast(4) },
                encPublicKey = qrData.encPublicKey,
                signPublicKey = qrData.signPublicKey
            )
            contactDao.insert(contact)

            Logger.d("[ContactsService] Added contact from QR: ${qrData.whisperId}")
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e("[ContactsService] Failed to add contact from QR", e)
            Result.failure(e)
        }
    }

    suspend fun addContact(whisperId: String, nickname: String) {
        val contact = ContactEntity(
            whisperId = whisperId,
            displayName = nickname.ifEmpty { whisperId }
        )
        contactDao.insert(contact)

        // Request public key from server
        requestPublicKey(whisperId)
    }

    suspend fun updateContactPublicKey(whisperId: String, publicKey: String) {
        contactDao.updatePublicKey(whisperId, publicKey)
    }

    suspend fun blockContact(whisperId: String) {
        contactDao.setBlocked(whisperId, true)
    }

    suspend fun unblockContact(whisperId: String) {
        contactDao.setBlocked(whisperId, false)
    }

    suspend fun deleteContact(whisperId: String) {
        contactDao.deleteByWhisperId(whisperId)
    }

    private suspend fun requestPublicKey(whisperId: String) {
        try {
            val token = secureStorage.sessionToken
            if (token == null) {
                Logger.w("[ContactsService] Cannot fetch keys: no session token")
                return
            }

            val response = whisperApi.getUserKeys("Bearer $token", whisperId)
            contactDao.updatePublicKey(whisperId, response.encPublicKey)
            contactDao.updateSignPublicKey(whisperId, response.signPublicKey)
            Logger.i("[ContactsService] Fetched keys for $whisperId")
        } catch (e: Exception) {
            Logger.e("[ContactsService] Failed to fetch keys for $whisperId", e)
        }
    }
}
