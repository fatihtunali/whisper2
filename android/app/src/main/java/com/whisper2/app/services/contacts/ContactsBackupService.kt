package com.whisper2.app.services.contacts

import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.whisper2.app.core.utils.Base64Strict
import com.whisper2.app.crypto.SecretBox
import com.whisper2.app.network.api.*
import com.whisper2.app.storage.db.dao.ContactDao
import com.whisper2.app.storage.db.entities.ContactBackupItem
import com.whisper2.app.storage.db.entities.ContactEntity
import java.security.SecureRandom
import java.util.UUID

/**
 * Contacts key provider for backup encryption
 */
fun interface ContactsKeyProvider {
    fun getContactsKey(): ByteArray?
}

/**
 * Nonce generator for testability
 */
fun interface NonceGenerator {
    fun generate(): ByteArray
}

/**
 * Default nonce generator using SecureRandom
 */
object DefaultNonceGenerator : NonceGenerator {
    override fun generate(): ByteArray {
        val nonce = ByteArray(24)
        SecureRandom().nextBytes(nonce)
        return nonce
    }
}

/**
 * Backup result
 */
sealed class BackupResult {
    data class Success(val sizeBytes: Int, val created: Boolean) : BackupResult()
    data class Error(val code: String, val message: String) : BackupResult()

    val isSuccess: Boolean get() = this is Success
}

/**
 * Restore result
 */
sealed class RestoreResult {
    data class Success(val contactCount: Int) : RestoreResult()
    data class NoBackup(val message: String) : RestoreResult()
    data class Error(val code: String, val message: String) : RestoreResult()

    val isSuccess: Boolean get() = this is Success
}

/**
 * SecretBox interface for testability
 */
fun interface SecretBoxEncryptor {
    fun seal(plaintext: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray
}

fun interface SecretBoxDecryptor {
    fun open(ciphertext: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray
}

/**
 * Default SecretBox implementation
 */
object DefaultSecretBoxEncryptor : SecretBoxEncryptor {
    override fun seal(plaintext: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        return SecretBox.seal(plaintext, nonce, key)
    }
}

object DefaultSecretBoxDecryptor : SecretBoxDecryptor {
    override fun open(ciphertext: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        return SecretBox.open(ciphertext, nonce, key)
    }
}

/**
 * Contacts Backup Service
 *
 * Handles encrypted backup and restore of contacts.
 * Zero-knowledge: server cannot decrypt the blob.
 *
 * Backup format:
 * - JSON list of ContactBackupItem (stable order by whisperId)
 * - Encrypted with SecretBox (XSalsa20-Poly1305)
 * - PUT /backup/contacts with nonce + ciphertext
 *
 * Restore:
 * - GET /backup/contacts
 * - Decrypt ciphertext
 * - Parse JSON
 * - Replace all contacts in DB
 */
class ContactsBackupService(
    private val api: WhisperApi,
    private val contactDao: ContactDao,
    private val contactsKeyProvider: ContactsKeyProvider,
    private val nonceGenerator: NonceGenerator = DefaultNonceGenerator,
    private val encryptor: SecretBoxEncryptor = DefaultSecretBoxEncryptor,
    private val decryptor: SecretBoxDecryptor = DefaultSecretBoxDecryptor
) {
    companion object {
        private const val TAG = "ContactsBackupService"
        private const val NONCE_SIZE = 24
        private const val MAX_BACKUP_SIZE = 256 * 1024 // 256KB
    }

    // Stable JSON serialization (sorted keys)
    private val gson: Gson = GsonBuilder()
        .disableHtmlEscaping()
        .create()

    /**
     * Backup contacts to server
     *
     * @return BackupResult
     */
    suspend fun backupContacts(): BackupResult {
        val contactsKey = contactsKeyProvider.getContactsKey()
        if (contactsKey == null) {
            Log.e(TAG, "No contacts key available")
            return BackupResult.Error("NO_KEY", "No contacts key available")
        }

        // 1. Get all contacts
        val contacts = contactDao.getAll()
        Log.d(TAG, "Backing up ${contacts.size} contacts")

        // 2. Convert to backup format (sorted by whisperId for determinism)
        val backupItems = contacts
            .sortedBy { it.whisperId }
            .map { contact ->
                ContactBackupItem(
                    whisperId = contact.whisperId,
                    displayName = contact.displayName,
                    encPublicKey = contact.encPublicKeyB64,
                    signPublicKey = contact.signPublicKeyB64,
                    isBlocked = contact.isBlocked,
                    isFavorite = contact.isFavorite
                )
            }

        // 3. Serialize to JSON
        val jsonBytes = gson.toJson(backupItems).toByteArray(Charsets.UTF_8)
        Log.d(TAG, "JSON size: ${jsonBytes.size} bytes")

        // 4. Check size limit (client-side)
        if (jsonBytes.size > MAX_BACKUP_SIZE) {
            Log.e(TAG, "Backup too large: ${jsonBytes.size} bytes > $MAX_BACKUP_SIZE")
            return BackupResult.Error("SIZE_LIMIT", "Backup exceeds ${MAX_BACKUP_SIZE / 1024}KB limit")
        }

        // 5. Generate nonce and encrypt
        val nonce = nonceGenerator.generate()
        val ciphertext: ByteArray
        try {
            ciphertext = encryptor.seal(jsonBytes, nonce, contactsKey)
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed: ${e.message}")
            return BackupResult.Error("ENCRYPTION_FAILED", "Failed to encrypt backup")
        }

        // 6. Prepare request
        val nonceB64 = Base64Strict.encode(nonce)
        val ciphertextB64 = Base64Strict.encode(ciphertext)

        val request = ContactsBackupPutRequest(
            nonce = nonceB64,
            ciphertext = ciphertextB64
        )

        // 7. Upload to server
        return when (val result = api.putContactsBackup(request)) {
            is ApiResult.Success -> {
                Log.d(TAG, "Backup uploaded: ${result.data.sizeBytes} bytes, created=${result.data.created}")
                BackupResult.Success(result.data.sizeBytes, result.data.created)
            }
            is ApiResult.Error -> {
                Log.e(TAG, "Backup upload failed: ${result.code} - ${result.message}")
                BackupResult.Error(result.code, result.message)
            }
        }
    }

    /**
     * Restore contacts from server
     *
     * @return RestoreResult
     */
    suspend fun restoreContacts(): RestoreResult {
        val contactsKey = contactsKeyProvider.getContactsKey()
        if (contactsKey == null) {
            Log.e(TAG, "No contacts key available")
            return RestoreResult.Error("NO_KEY", "No contacts key available")
        }

        // 1. Download from server
        val response = when (val result = api.getContactsBackup()) {
            is ApiResult.Success -> result.data
            is ApiResult.Error -> {
                if (result.code == ApiErrorResponse.NOT_FOUND) {
                    Log.d(TAG, "No backup found on server")
                    return RestoreResult.NoBackup("No backup found")
                }
                Log.e(TAG, "Backup download failed: ${result.code} - ${result.message}")
                return RestoreResult.Error(result.code, result.message)
            }
        }

        Log.d(TAG, "Downloaded backup: ${response.sizeBytes} bytes")

        // 2. Validate and decode nonce
        if (!Base64Strict.isValid(response.nonce)) {
            Log.e(TAG, "Invalid nonce base64")
            return RestoreResult.Error("INVALID_DATA", "Invalid nonce base64")
        }
        val nonce = Base64Strict.decode(response.nonce)
        if (nonce.size != NONCE_SIZE) {
            Log.e(TAG, "Invalid nonce size: ${nonce.size}")
            return RestoreResult.Error("INVALID_DATA", "Nonce must be $NONCE_SIZE bytes")
        }

        // 3. Validate and decode ciphertext
        if (!Base64Strict.isValid(response.ciphertext)) {
            Log.e(TAG, "Invalid ciphertext base64")
            return RestoreResult.Error("INVALID_DATA", "Invalid ciphertext base64")
        }
        val ciphertext = Base64Strict.decode(response.ciphertext)

        // 4. Decrypt
        val plaintext: ByteArray
        try {
            plaintext = decryptor.open(ciphertext, nonce, contactsKey)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed: ${e.message}")
            return RestoreResult.Error("DECRYPTION_FAILED", "Failed to decrypt backup")
        }

        // 5. Parse JSON
        val backupItems: List<ContactBackupItem>
        try {
            val json = String(plaintext, Charsets.UTF_8)
            val type = object : TypeToken<List<ContactBackupItem>>() {}.type
            backupItems = gson.fromJson(json, type)
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse failed: ${e.message}")
            return RestoreResult.Error("PARSE_FAILED", "Failed to parse backup JSON")
        }

        Log.d(TAG, "Parsed ${backupItems.size} contacts from backup")

        // 6. Convert to entities
        val now = System.currentTimeMillis()
        val entities = backupItems.map { item ->
            ContactEntity(
                id = UUID.randomUUID().toString(),
                whisperId = item.whisperId,
                displayName = item.displayName,
                encPublicKeyB64 = item.encPublicKey,
                signPublicKeyB64 = item.signPublicKey,
                addedAt = now,
                keysUpdatedAt = now,
                isBlocked = item.isBlocked,
                isFavorite = item.isFavorite
            )
        }

        // 7. Replace all contacts in DB
        contactDao.replaceAll(entities)
        Log.d(TAG, "Restored ${entities.size} contacts")

        return RestoreResult.Success(entities.size)
    }

    /**
     * Validate backup request body (for testing)
     */
    fun validateBackupRequest(request: ContactsBackupPutRequest): Boolean {
        // Validate nonce
        if (!Base64Strict.isValid(request.nonce)) return false
        val nonce = Base64Strict.decode(request.nonce)
        if (nonce.size != NONCE_SIZE) return false

        // Validate ciphertext
        if (!Base64Strict.isValid(request.ciphertext)) return false

        return true
    }
}
