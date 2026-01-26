package com.whisper2.app.http

import com.whisper2.app.core.utils.Base64Strict
import com.whisper2.app.messaging.InMemoryMessageDao
import com.whisper2.app.network.api.*
import com.whisper2.app.services.contacts.*
import com.whisper2.app.storage.db.entities.ContactEntity
import com.whisper2.app.storage.db.entities.MessageEntity
import com.whisper2.app.storage.db.entities.MessageType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Gate 5: Contacts Restore Replaces Table + Messages Untouched Policy
 *
 * Tests:
 * - DB has 3 contacts, restore response decrypt → 2 contacts
 * - Restore sonrası: contacts count = 2, eski 3 contact tamamen gitmiş (replace)
 * - Messages table restore'dan etkilenmiyor (policy)
 */
class ContactsRestoreTest {

    private lateinit var mockApi: MockWhisperApi
    private lateinit var contactDao: InMemoryContactDao
    private lateinit var messageDao: InMemoryMessageDao
    private lateinit var service: ContactsBackupService
    private var contactsKey: ByteArray? = ByteArray(32) { it.toByte() }

    private val fixedNonce = ByteArray(24) { (it + 100).toByte() }

    @Before
    fun setup() {
        mockApi = MockWhisperApi(sessionTokenProvider = { "test_token" })
        contactDao = InMemoryContactDao()
        messageDao = InMemoryMessageDao()

        service = ContactsBackupService(
            api = mockApi,
            contactDao = contactDao,
            contactsKeyProvider = { contactsKey },
            nonceGenerator = { fixedNonce },
            encryptor = { plaintext, _, _ ->
                // Simple mock: 16 byte MAC + plaintext
                ByteArray(16) + plaintext
            },
            decryptor = { ciphertext, _, _ ->
                // Remove 16 byte MAC
                ciphertext.copyOfRange(16, ciphertext.size)
            }
        )
    }

    // ==========================================================================
    // Gate 5: Restore replaces all contacts
    // ==========================================================================

    @Test
    fun `gate5 restore replaces all contacts`() = runBlocking {
        // Setup: DB has 3 contacts
        contactDao.insertAll(listOf(
            createContact("WSP-OLD-1", "Old Contact 1"),
            createContact("WSP-OLD-2", "Old Contact 2"),
            createContact("WSP-OLD-3", "Old Contact 3")
        ))
        assertEquals("Initial contacts", 3, contactDao.count())

        // Prepare restore response with 2 contacts
        val backupJson = """
            [
                {"whisperId":"WSP-NEW-1","displayName":"New Contact 1","encPublicKey":"${b64_32bytes()}","signPublicKey":"${b64_32bytes()}","isBlocked":false,"isFavorite":false},
                {"whisperId":"WSP-NEW-2","displayName":"New Contact 2","encPublicKey":"${b64_32bytes()}","signPublicKey":"${b64_32bytes()}","isBlocked":false,"isFavorite":true}
            ]
        """.trimIndent()

        val ciphertext = ByteArray(16) + backupJson.toByteArray(Charsets.UTF_8)

        mockApi.enqueueGetBackupResponse(
            ApiResult.Success(ContactsBackupGetResponse(
                nonce = Base64Strict.encode(fixedNonce),
                ciphertext = Base64Strict.encode(ciphertext),
                sizeBytes = ciphertext.size,
                updatedAt = System.currentTimeMillis()
            ))
        )

        // Execute restore
        val result = service.restoreContacts()

        // Verify result
        assertTrue("Restore should succeed", result.isSuccess)
        assertEquals(2, (result as RestoreResult.Success).contactCount)

        // Verify contacts replaced
        assertEquals("Contacts count should be 2", 2, contactDao.count())

        // Verify old contacts are gone
        assertNull("Old contact 1 should be gone", contactDao.getByWhisperId("WSP-OLD-1"))
        assertNull("Old contact 2 should be gone", contactDao.getByWhisperId("WSP-OLD-2"))
        assertNull("Old contact 3 should be gone", contactDao.getByWhisperId("WSP-OLD-3"))

        // Verify new contacts exist
        assertNotNull("New contact 1 should exist", contactDao.getByWhisperId("WSP-NEW-1"))
        assertNotNull("New contact 2 should exist", contactDao.getByWhisperId("WSP-NEW-2"))
    }

    @Test
    fun `gate5 restore with empty list clears all contacts`() = runBlocking {
        // Setup: DB has 3 contacts
        contactDao.insertAll(listOf(
            createContact("WSP-OLD-1"),
            createContact("WSP-OLD-2"),
            createContact("WSP-OLD-3")
        ))
        assertEquals(3, contactDao.count())

        // Prepare empty restore response
        val backupJson = "[]"
        val ciphertext = ByteArray(16) + backupJson.toByteArray(Charsets.UTF_8)

        mockApi.enqueueGetBackupResponse(
            ApiResult.Success(ContactsBackupGetResponse(
                nonce = Base64Strict.encode(fixedNonce),
                ciphertext = Base64Strict.encode(ciphertext),
                sizeBytes = ciphertext.size,
                updatedAt = System.currentTimeMillis()
            ))
        )

        val result = service.restoreContacts()

        assertTrue(result.isSuccess)
        assertEquals(0, (result as RestoreResult.Success).contactCount)
        assertEquals("All contacts should be cleared", 0, contactDao.count())
    }

    // ==========================================================================
    // Gate 5: Messages table untouched
    // ==========================================================================

    @Test
    fun `gate5 restore does not affect messages table`() = runBlocking {
        // Setup: DB has contacts AND messages
        contactDao.insertAll(listOf(
            createContact("WSP-OLD-1"),
            createContact("WSP-OLD-2")
        ))

        // Insert messages
        messageDao.insert(createMessage("msg-1", "WSP-OLD-1"))
        messageDao.insert(createMessage("msg-2", "WSP-OLD-1"))
        messageDao.insert(createMessage("msg-3", "WSP-OLD-2"))

        assertEquals(2, contactDao.count())
        assertEquals(3, messageDao.count())

        // Prepare restore response that replaces contacts
        val backupJson = """
            [
                {"whisperId":"WSP-NEW-1","displayName":"New Contact","encPublicKey":"${b64_32bytes()}","signPublicKey":"${b64_32bytes()}","isBlocked":false,"isFavorite":false}
            ]
        """.trimIndent()

        val ciphertext = ByteArray(16) + backupJson.toByteArray(Charsets.UTF_8)

        mockApi.enqueueGetBackupResponse(
            ApiResult.Success(ContactsBackupGetResponse(
                nonce = Base64Strict.encode(fixedNonce),
                ciphertext = Base64Strict.encode(ciphertext),
                sizeBytes = ciphertext.size,
                updatedAt = System.currentTimeMillis()
            ))
        )

        // Execute restore
        service.restoreContacts()

        // Verify contacts replaced
        assertEquals(1, contactDao.count())

        // CRITICAL: Messages table should be UNTOUCHED
        assertEquals("Messages should be untouched", 3, messageDao.count())
        assertNotNull("Message 1 should still exist", messageDao.getById("msg-1"))
        assertNotNull("Message 2 should still exist", messageDao.getById("msg-2"))
        assertNotNull("Message 3 should still exist", messageDao.getById("msg-3"))
    }

    // ==========================================================================
    // Gate 5: Restore error handling
    // ==========================================================================

    @Test
    fun `gate5 no backup returns NoBackup result`() = runBlocking {
        mockApi.enqueueGetBackupResponse(
            ApiResult.Error(ApiErrorResponse.NOT_FOUND, "No backup found", 404)
        )

        val result = service.restoreContacts()

        assertTrue(result is RestoreResult.NoBackup)
    }

    @Test
    fun `gate5 server error returns Error result`() = runBlocking {
        mockApi.enqueueGetBackupResponse(
            ApiResult.Error(ApiErrorResponse.SERVER_ERROR, "Internal error", 500)
        )

        val result = service.restoreContacts()

        assertTrue(result is RestoreResult.Error)
        assertEquals("SERVER_ERROR", (result as RestoreResult.Error).code)
    }

    @Test
    fun `gate5 no contacts key returns error`() = runBlocking {
        contactsKey = null

        val result = service.restoreContacts()

        assertTrue(result is RestoreResult.Error)
        assertEquals("NO_KEY", (result as RestoreResult.Error).code)
    }

    @Test
    fun `gate5 invalid nonce base64 returns error`() = runBlocking {
        mockApi.enqueueGetBackupResponse(
            ApiResult.Success(ContactsBackupGetResponse(
                nonce = "not_valid_base64!!!",
                ciphertext = Base64Strict.encode(ByteArray(100)),
                sizeBytes = 100,
                updatedAt = System.currentTimeMillis()
            ))
        )

        val result = service.restoreContacts()

        assertTrue(result is RestoreResult.Error)
        assertEquals("INVALID_DATA", (result as RestoreResult.Error).code)
    }

    @Test
    fun `gate5 invalid nonce size returns error`() = runBlocking {
        mockApi.enqueueGetBackupResponse(
            ApiResult.Success(ContactsBackupGetResponse(
                nonce = Base64Strict.encode(ByteArray(16)), // Wrong size
                ciphertext = Base64Strict.encode(ByteArray(100)),
                sizeBytes = 100,
                updatedAt = System.currentTimeMillis()
            ))
        )

        val result = service.restoreContacts()

        assertTrue(result is RestoreResult.Error)
        assertEquals("INVALID_DATA", (result as RestoreResult.Error).code)
    }

    @Test
    fun `gate5 invalid ciphertext base64 returns error`() = runBlocking {
        mockApi.enqueueGetBackupResponse(
            ApiResult.Success(ContactsBackupGetResponse(
                nonce = Base64Strict.encode(fixedNonce),
                ciphertext = "not_valid_base64!!!",
                sizeBytes = 100,
                updatedAt = System.currentTimeMillis()
            ))
        )

        val result = service.restoreContacts()

        assertTrue(result is RestoreResult.Error)
        assertEquals("INVALID_DATA", (result as RestoreResult.Error).code)
    }

    @Test
    fun `gate5 decryption failure returns error`() = runBlocking {
        // Use a decryptor that throws
        val failingService = ContactsBackupService(
            api = mockApi,
            contactDao = contactDao,
            contactsKeyProvider = { contactsKey },
            nonceGenerator = { fixedNonce },
            encryptor = { plaintext, _, _ -> ByteArray(16) + plaintext },
            decryptor = { _, _, _ -> throw RuntimeException("Decryption failed") }
        )

        mockApi.enqueueGetBackupResponse(
            ApiResult.Success(ContactsBackupGetResponse(
                nonce = Base64Strict.encode(fixedNonce),
                ciphertext = Base64Strict.encode(ByteArray(100)),
                sizeBytes = 100,
                updatedAt = System.currentTimeMillis()
            ))
        )

        val result = failingService.restoreContacts()

        assertTrue(result is RestoreResult.Error)
        assertEquals("DECRYPTION_FAILED", (result as RestoreResult.Error).code)
    }

    @Test
    fun `gate5 invalid JSON returns error`() = runBlocking {
        val invalidJson = "not valid json at all"
        val ciphertext = ByteArray(16) + invalidJson.toByteArray(Charsets.UTF_8)

        mockApi.enqueueGetBackupResponse(
            ApiResult.Success(ContactsBackupGetResponse(
                nonce = Base64Strict.encode(fixedNonce),
                ciphertext = Base64Strict.encode(ciphertext),
                sizeBytes = ciphertext.size,
                updatedAt = System.currentTimeMillis()
            ))
        )

        val result = service.restoreContacts()

        assertTrue(result is RestoreResult.Error)
        assertEquals("PARSE_FAILED", (result as RestoreResult.Error).code)
    }

    // ==========================================================================
    // Gate 5: Restored contact data integrity
    // ==========================================================================

    @Test
    fun `gate5 restored contacts have correct data`() = runBlocking {
        val backupJson = """
            [
                {"whisperId":"WSP-TEST-USER","displayName":"Test User","encPublicKey":"${b64_32bytes()}","signPublicKey":"${b64_32bytes()}","isBlocked":true,"isFavorite":true}
            ]
        """.trimIndent()

        val ciphertext = ByteArray(16) + backupJson.toByteArray(Charsets.UTF_8)

        mockApi.enqueueGetBackupResponse(
            ApiResult.Success(ContactsBackupGetResponse(
                nonce = Base64Strict.encode(fixedNonce),
                ciphertext = Base64Strict.encode(ciphertext),
                sizeBytes = ciphertext.size,
                updatedAt = System.currentTimeMillis()
            ))
        )

        service.restoreContacts()

        val contact = contactDao.getByWhisperId("WSP-TEST-USER")
        assertNotNull(contact)
        assertEquals("Test User", contact!!.displayName)
        assertTrue("isBlocked should be true", contact.isBlocked)
        assertTrue("isFavorite should be true", contact.isFavorite)
    }

    // ==========================================================================
    // Helper
    // ==========================================================================

    private fun createContact(
        whisperId: String,
        displayName: String = "Test Contact"
    ): ContactEntity {
        return ContactEntity(
            id = java.util.UUID.randomUUID().toString(),
            whisperId = whisperId,
            displayName = displayName,
            encPublicKeyB64 = b64_32bytes(),
            signPublicKeyB64 = b64_32bytes(),
            addedAt = System.currentTimeMillis(),
            keysUpdatedAt = System.currentTimeMillis(),
            isBlocked = false,
            isFavorite = false
        )
    }

    private fun createMessage(messageId: String, peerId: String): MessageEntity {
        return MessageEntity(
            messageId = messageId,
            conversationId = "conv-$peerId",
            from = peerId,
            to = "WSP-MY-ID",
            msgType = MessageType.TEXT,
            timestamp = System.currentTimeMillis(),
            nonceB64 = Base64Strict.encode(ByteArray(24)),
            ciphertextB64 = Base64Strict.encode(ByteArray(100)),
            sigB64 = Base64Strict.encode(ByteArray(64)),
            text = "Test message",
            status = "delivered",
            isOutgoing = false
        )
    }

    private fun b64_32bytes(): String {
        return Base64Strict.encode(ByteArray(32) { it.toByte() })
    }
}
