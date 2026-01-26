package com.whisper2.app.http

import com.whisper2.app.core.utils.Base64Strict
import com.whisper2.app.network.api.*
import com.whisper2.app.services.contacts.*
import com.whisper2.app.storage.db.entities.ContactEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Gate 4: Contacts Backup Encrypt/Decrypt + PUT body validation
 *
 * Tests:
 * - contacts list → encrypt → PUT request body
 * - nonce base64 decode 24 bytes
 * - ciphertext base64 strict
 * - Mock server 201 → service success
 * - 256KB limit client-side block
 */
class ContactsBackupEncryptTest {

    private lateinit var mockApi: MockWhisperApi
    private lateinit var contactDao: InMemoryContactDao
    private lateinit var service: ContactsBackupService
    private var capturedRequest: ContactsBackupPutRequest? = null
    private var contactsKey: ByteArray? = ByteArray(32) { it.toByte() }

    // Fixed nonce for testing
    private val fixedNonce = ByteArray(24) { (it + 100).toByte() }

    @Before
    fun setup() {
        mockApi = MockWhisperApi(sessionTokenProvider = { "test_token" })
        contactDao = InMemoryContactDao()
        capturedRequest = null

        service = ContactsBackupService(
            api = mockApi,
            contactDao = contactDao,
            contactsKeyProvider = { contactsKey },
            nonceGenerator = { fixedNonce },
            encryptor = { plaintext, nonce, key ->
                // Simple mock: prepend nonce size marker + plaintext
                // In real code this would be SecretBox
                ByteArray(16) + plaintext // 16 byte MAC + plaintext
            },
            decryptor = { ciphertext, nonce, key ->
                // Remove 16 byte MAC
                ciphertext.copyOfRange(16, ciphertext.size)
            }
        )
    }

    // ==========================================================================
    // Gate 4: PUT request body validation
    // ==========================================================================

    @Test
    fun `gate4 backup sends nonce as base64`() = runBlocking {
        contactDao.insertAll(listOf(createContact("WSP-A")))

        mockApi.enqueuePutBackupResponse(
            ApiResult.Success(ContactsBackupPutResponse(
                success = true,
                created = true,
                sizeBytes = 100,
                updatedAt = System.currentTimeMillis()
            ))
        )

        service.backupContacts()

        val request = mockApi.lastPutBackupRequest
        assertNotNull("Request should be captured", request)

        // Verify nonce is valid base64
        assertTrue("Nonce should be valid base64", Base64Strict.isValid(request!!.nonce))

        // Decode and verify 24 bytes
        val nonceBytes = Base64Strict.decode(request.nonce)
        assertEquals("Nonce must be 24 bytes", 24, nonceBytes.size)
    }

    @Test
    fun `gate4 backup sends ciphertext as strict base64`() = runBlocking {
        contactDao.insertAll(listOf(createContact("WSP-A")))

        mockApi.enqueuePutBackupResponse(
            ApiResult.Success(ContactsBackupPutResponse(
                success = true,
                created = true,
                sizeBytes = 100,
                updatedAt = System.currentTimeMillis()
            ))
        )

        service.backupContacts()

        val request = mockApi.lastPutBackupRequest
        assertNotNull(request)

        // Verify ciphertext is valid base64
        assertTrue("Ciphertext should be valid base64", Base64Strict.isValid(request!!.ciphertext))
    }

    @Test
    fun `gate4 backup nonce decodes to exactly 24 bytes`() = runBlocking {
        contactDao.insertAll(listOf(createContact("WSP-A")))

        mockApi.enqueuePutBackupResponse(
            ApiResult.Success(ContactsBackupPutResponse(
                success = true,
                created = true,
                sizeBytes = 100,
                updatedAt = System.currentTimeMillis()
            ))
        )

        service.backupContacts()

        val request = mockApi.lastPutBackupRequest!!
        val nonceBytes = Base64Strict.decode(request.nonce)

        assertEquals(24, nonceBytes.size)
        // Verify it matches our fixed nonce
        assertArrayEquals(fixedNonce, nonceBytes)
    }

    // ==========================================================================
    // Gate 4: Server 201 → service success
    // ==========================================================================

    @Test
    fun `gate4 server 201 returns success`() = runBlocking {
        contactDao.insertAll(listOf(
            createContact("WSP-A"),
            createContact("WSP-B")
        ))

        mockApi.enqueuePutBackupResponse(
            ApiResult.Success(ContactsBackupPutResponse(
                success = true,
                created = true,
                sizeBytes = 200,
                updatedAt = 1700000000000L
            ))
        )

        val result = service.backupContacts()

        assertTrue("Backup should succeed", result.isSuccess)
        assertTrue(result is BackupResult.Success)
        assertEquals(200, (result as BackupResult.Success).sizeBytes)
        assertTrue(result.created)
    }

    @Test
    fun `gate4 server 200 update returns success`() = runBlocking {
        contactDao.insertAll(listOf(createContact("WSP-A")))

        mockApi.enqueuePutBackupResponse(
            ApiResult.Success(ContactsBackupPutResponse(
                success = true,
                created = false, // Update, not create
                sizeBytes = 150,
                updatedAt = 1700000000000L
            ))
        )

        val result = service.backupContacts()

        assertTrue(result.isSuccess)
        assertFalse((result as BackupResult.Success).created)
    }

    @Test
    fun `gate4 server error returns error result`() = runBlocking {
        contactDao.insertAll(listOf(createContact("WSP-A")))

        mockApi.enqueuePutBackupResponse(
            ApiResult.Error(ApiErrorResponse.SERVER_ERROR, "Internal error", 500)
        )

        val result = service.backupContacts()

        assertFalse(result.isSuccess)
        assertTrue(result is BackupResult.Error)
        assertEquals("SERVER_ERROR", (result as BackupResult.Error).code)
    }

    // ==========================================================================
    // Gate 4: 256KB client-side limit
    // ==========================================================================

    @Test
    fun `gate4 256KB limit blocks large backup`() = runBlocking {
        // Create many contacts to exceed 256KB
        // Each contact JSON is roughly 200-300 bytes
        // Need ~1000 contacts to exceed 256KB
        val contacts = (1..1500).map { i ->
            createContact(
                whisperId = "WSP-${String.format("%04d", i)}-LONG-NAME-HERE",
                displayName = "Contact Number $i with a very long display name to increase size"
            )
        }
        contactDao.insertAll(contacts)

        val result = service.backupContacts()

        assertFalse("Large backup should be blocked", result.isSuccess)
        assertTrue(result is BackupResult.Error)
        assertEquals("SIZE_LIMIT", (result as BackupResult.Error).code)
    }

    @Test
    fun `gate4 under 256KB limit succeeds`() = runBlocking {
        // Create a reasonable number of contacts (under limit)
        val contacts = (1..10).map { i ->
            createContact("WSP-$i")
        }
        contactDao.insertAll(contacts)

        mockApi.enqueuePutBackupResponse(
            ApiResult.Success(ContactsBackupPutResponse(
                success = true,
                created = true,
                sizeBytes = 500,
                updatedAt = System.currentTimeMillis()
            ))
        )

        val result = service.backupContacts()

        assertTrue("Under limit should succeed", result.isSuccess)
    }

    // ==========================================================================
    // Gate 4: No contacts key
    // ==========================================================================

    @Test
    fun `gate4 no contacts key returns error`() = runBlocking {
        contactsKey = null
        contactDao.insertAll(listOf(createContact("WSP-A")))

        val result = service.backupContacts()

        assertFalse(result.isSuccess)
        assertTrue(result is BackupResult.Error)
        assertEquals("NO_KEY", (result as BackupResult.Error).code)
    }

    // ==========================================================================
    // Gate 4: Empty contacts backup
    // ==========================================================================

    @Test
    fun `gate4 empty contacts backup succeeds`() = runBlocking {
        // No contacts in DAO

        mockApi.enqueuePutBackupResponse(
            ApiResult.Success(ContactsBackupPutResponse(
                success = true,
                created = true,
                sizeBytes = 50,
                updatedAt = System.currentTimeMillis()
            ))
        )

        val result = service.backupContacts()

        assertTrue("Empty backup should succeed", result.isSuccess)
    }

    // ==========================================================================
    // Gate 4: Contacts are sorted by whisperId
    // ==========================================================================

    @Test
    fun `gate4 contacts sorted by whisperId in backup`() = runBlocking {
        // Insert in random order
        contactDao.insertAll(listOf(
            createContact("WSP-C"),
            createContact("WSP-A"),
            createContact("WSP-B")
        ))

        mockApi.enqueuePutBackupResponse(
            ApiResult.Success(ContactsBackupPutResponse(
                success = true,
                created = true,
                sizeBytes = 100,
                updatedAt = System.currentTimeMillis()
            ))
        )

        // Use a real-ish encryptor that preserves order in ciphertext
        val capturedPlaintext = mutableListOf<ByteArray>()
        val sortedService = ContactsBackupService(
            api = mockApi,
            contactDao = contactDao,
            contactsKeyProvider = { contactsKey },
            nonceGenerator = { fixedNonce },
            encryptor = { plaintext, _, _ ->
                capturedPlaintext.add(plaintext)
                ByteArray(16) + plaintext
            },
            decryptor = { ciphertext, _, _ ->
                ciphertext.copyOfRange(16, ciphertext.size)
            }
        )

        sortedService.backupContacts()

        // Check that A comes before B comes before C in JSON
        val json = String(capturedPlaintext[0], Charsets.UTF_8)
        val indexA = json.indexOf("WSP-A")
        val indexB = json.indexOf("WSP-B")
        val indexC = json.indexOf("WSP-C")

        assertTrue("A should come before B", indexA < indexB)
        assertTrue("B should come before C", indexB < indexC)
    }

    // ==========================================================================
    // Gate 4: Request body validation helper
    // ==========================================================================

    @Test
    fun `gate4 validateBackupRequest accepts valid request`() = runBlocking {
        val validRequest = ContactsBackupPutRequest(
            nonce = Base64Strict.encode(ByteArray(24)),
            ciphertext = Base64Strict.encode(ByteArray(100))
        )

        assertTrue(service.validateBackupRequest(validRequest))
    }

    @Test
    fun `gate4 validateBackupRequest rejects invalid nonce size`() = runBlocking {
        val invalidRequest = ContactsBackupPutRequest(
            nonce = Base64Strict.encode(ByteArray(16)), // Wrong size
            ciphertext = Base64Strict.encode(ByteArray(100))
        )

        assertFalse(service.validateBackupRequest(invalidRequest))
    }

    @Test
    fun `gate4 validateBackupRequest rejects invalid base64`() = runBlocking {
        val invalidRequest = ContactsBackupPutRequest(
            nonce = "not_valid_base64!!!",
            ciphertext = Base64Strict.encode(ByteArray(100))
        )

        assertFalse(service.validateBackupRequest(invalidRequest))
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
            encPublicKeyB64 = Base64Strict.encode(ByteArray(32)),
            signPublicKeyB64 = Base64Strict.encode(ByteArray(32)),
            addedAt = System.currentTimeMillis(),
            keysUpdatedAt = System.currentTimeMillis(),
            isBlocked = false,
            isFavorite = false
        )
    }
}
