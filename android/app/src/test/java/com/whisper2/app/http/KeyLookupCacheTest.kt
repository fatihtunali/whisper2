package com.whisper2.app.http

import com.whisper2.app.core.utils.Base64Strict
import com.whisper2.app.network.api.*
import com.whisper2.app.services.contacts.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Gate 2: Key Lookup Cache + Strict Base64 + Status
 *
 * Tests:
 * - Cache miss → HTTP 200 active → keys cached
 * - Cache hit → no HTTP call
 * - status=banned → reject + no cache
 * - encPublicKey != 32 bytes → reject
 * - signPublicKey != 32 bytes → reject
 */
class KeyLookupCacheTest {

    private lateinit var mockApi: MockWhisperApi
    private lateinit var cache: InMemoryKeyCache
    private lateinit var service: KeyLookupService

    private val validEnc32 = Base64Strict.encode(ByteArray(32) { it.toByte() })
    private val validSign32 = Base64Strict.encode(ByteArray(32) { (it + 50).toByte() })

    @Before
    fun setup() {
        mockApi = MockWhisperApi(
            sessionTokenProvider = { "test_token" }
        )
        cache = InMemoryKeyCache()
        service = KeyLookupService(mockApi, cache)
    }

    // ==========================================================================
    // Gate 2: Cache miss then hit
    // ==========================================================================

    @Test
    fun `gate2 cache miss then hit and strict keys`() = runBlocking {
        // Enqueue only ONE response
        mockApi.enqueueUserKeysResponse(
            ApiResult.Success(UserKeysResponse(
                whisperId = "WSP-TEST-TEST-TEST",
                encPublicKey = validEnc32,
                signPublicKey = validSign32,
                status = "active"
            ))
        )

        // First call - cache miss, HTTP request
        val k1 = service.getKeys("WSP-TEST-TEST-TEST")

        // Second call - cache hit, NO HTTP request
        val k2 = service.getKeys("WSP-TEST-TEST-TEST")

        // Verify: only 1 HTTP call
        assertEquals("Only 1 HTTP call should be made", 1, mockApi.getUserKeysCallCount)

        // Verify: both results are success
        assertTrue(k1.isSuccess)
        assertTrue(k2.isSuccess)
        assertNotNull(k1.getOrNull())
        assertNotNull(k2.getOrNull())
    }

    @Test
    fun `gate2 cache hit returns same keys`() = runBlocking {
        mockApi.enqueueUserKeysResponse(
            ApiResult.Success(UserKeysResponse(
                whisperId = "WSP-TEST-TEST-TEST",
                encPublicKey = validEnc32,
                signPublicKey = validSign32,
                status = "active"
            ))
        )

        val k1 = service.getKeys("WSP-TEST-TEST-TEST")
        val k2 = service.getKeys("WSP-TEST-TEST-TEST")

        assertEquals(k1.getOrNull(), k2.getOrNull())
    }

    @Test
    fun `gate2 different whisperIds make separate HTTP calls`() = runBlocking {
        mockApi.enqueueUserKeysResponse(
            ApiResult.Success(UserKeysResponse(
                whisperId = "WSP-AAAA-AAAA-AAAA",
                encPublicKey = validEnc32,
                signPublicKey = validSign32,
                status = "active"
            ))
        )
        mockApi.enqueueUserKeysResponse(
            ApiResult.Success(UserKeysResponse(
                whisperId = "WSP-BBBB-BBBB-BBBB",
                encPublicKey = validEnc32,
                signPublicKey = validSign32,
                status = "active"
            ))
        )

        service.getKeys("WSP-AAAA-AAAA-AAAA")
        service.getKeys("WSP-BBBB-BBBB-BBBB")

        assertEquals(2, mockApi.getUserKeysCallCount)
    }

    // ==========================================================================
    // Gate 2: status=banned rejection
    // ==========================================================================

    @Test
    fun `gate2 banned status is rejected`() = runBlocking {
        mockApi.enqueueUserKeysResponse(
            ApiResult.Success(UserKeysResponse(
                whisperId = "WSP-BANNED-USR-XXX",
                encPublicKey = validEnc32,
                signPublicKey = validSign32,
                status = "banned"
            ))
        )

        val result = service.getKeys("WSP-BANNED-USR-XXX")

        assertFalse("Banned user should be rejected", result.isSuccess)
    }

    @Test
    fun `gate2 banned status not cached`() = runBlocking {
        mockApi.enqueueUserKeysResponse(
            ApiResult.Success(UserKeysResponse(
                whisperId = "WSP-BANNED-USR-XXX",
                encPublicKey = validEnc32,
                signPublicKey = validSign32,
                status = "banned"
            ))
        )

        service.getKeys("WSP-BANNED-USR-XXX")

        assertNull("Banned user should not be in cache", cache.get("WSP-BANNED-USR-XXX"))
        assertEquals(0, cache.size())
    }

    // ==========================================================================
    // Gate 2: Invalid key sizes
    // ==========================================================================

    @Test
    fun `gate2 encPublicKey not 32 bytes is rejected`() = runBlocking {
        val invalidEnc = Base64Strict.encode(ByteArray(16)) // 16 bytes instead of 32

        mockApi.enqueueUserKeysResponse(
            ApiResult.Success(UserKeysResponse(
                whisperId = "WSP-BADKEY-XXX-XXX",
                encPublicKey = invalidEnc,
                signPublicKey = validSign32,
                status = "active"
            ))
        )

        val result = service.getKeys("WSP-BADKEY-XXX-XXX")

        assertFalse("Invalid encPublicKey should be rejected", result.isSuccess)
    }

    @Test
    fun `gate2 signPublicKey not 32 bytes is rejected`() = runBlocking {
        val invalidSign = Base64Strict.encode(ByteArray(64)) // 64 bytes instead of 32

        mockApi.enqueueUserKeysResponse(
            ApiResult.Success(UserKeysResponse(
                whisperId = "WSP-BADKEY-XXX-XXX",
                encPublicKey = validEnc32,
                signPublicKey = invalidSign,
                status = "active"
            ))
        )

        val result = service.getKeys("WSP-BADKEY-XXX-XXX")

        assertFalse("Invalid signPublicKey should be rejected", result.isSuccess)
    }

    @Test
    fun `gate2 invalid key not cached`() = runBlocking {
        val invalidEnc = Base64Strict.encode(ByteArray(16))

        mockApi.enqueueUserKeysResponse(
            ApiResult.Success(UserKeysResponse(
                whisperId = "WSP-BADKEY-XXX-XXX",
                encPublicKey = invalidEnc,
                signPublicKey = validSign32,
                status = "active"
            ))
        )

        service.getKeys("WSP-BADKEY-XXX-XXX")

        assertNull("Invalid key should not be cached", cache.get("WSP-BADKEY-XXX-XXX"))
    }

    // ==========================================================================
    // Gate 2: Invalid base64
    // ==========================================================================

    @Test
    fun `gate2 invalid base64 encPublicKey is rejected`() = runBlocking {
        mockApi.enqueueUserKeysResponse(
            ApiResult.Success(UserKeysResponse(
                whisperId = "WSP-BADB64-XXX-XXX",
                encPublicKey = "not_valid_base64!!!",
                signPublicKey = validSign32,
                status = "active"
            ))
        )

        val result = service.getKeys("WSP-BADB64-XXX-XXX")

        assertFalse("Invalid base64 should be rejected", result.isSuccess)
    }

    @Test
    fun `gate2 invalid base64 signPublicKey is rejected`() = runBlocking {
        mockApi.enqueueUserKeysResponse(
            ApiResult.Success(UserKeysResponse(
                whisperId = "WSP-BADB64-XXX-XXX",
                encPublicKey = validEnc32,
                signPublicKey = "not_valid_base64!!!",
                status = "active"
            ))
        )

        val result = service.getKeys("WSP-BADB64-XXX-XXX")

        assertFalse("Invalid base64 should be rejected", result.isSuccess)
    }

    // ==========================================================================
    // Gate 2: HTTP errors
    // ==========================================================================

    @Test
    fun `gate2 HTTP 404 returns error`() = runBlocking {
        mockApi.enqueueUserKeysResponse(
            ApiResult.Error(ApiErrorResponse.NOT_FOUND, "User not found", 404)
        )

        val result = service.getKeys("WSP-NOTFOUND-XXXX")

        assertFalse(result.isSuccess)
        assertEquals("NOT_FOUND", (result as KeyLookupResult.Error).code)
    }

    @Test
    fun `gate2 HTTP 403 returns error`() = runBlocking {
        mockApi.enqueueUserKeysResponse(
            ApiResult.Error(ApiErrorResponse.FORBIDDEN, "User is banned", 403)
        )

        val result = service.getKeys("WSP-FORBIDDEN-XXX")

        assertFalse(result.isSuccess)
        assertEquals("FORBIDDEN", (result as KeyLookupResult.Error).code)
    }

    @Test
    fun `gate2 HTTP error not cached`() = runBlocking {
        mockApi.enqueueUserKeysResponse(
            ApiResult.Error(ApiErrorResponse.NOT_FOUND, "User not found", 404)
        )

        service.getKeys("WSP-NOTFOUND-XXXX")

        assertNull(cache.get("WSP-NOTFOUND-XXXX"))
    }

    // ==========================================================================
    // Gate 2: Cache invalidation
    // ==========================================================================

    @Test
    fun `gate2 invalidate removes from cache`() = runBlocking {
        mockApi.enqueueUserKeysResponse(
            ApiResult.Success(UserKeysResponse(
                whisperId = "WSP-TEST-TEST-TEST",
                encPublicKey = validEnc32,
                signPublicKey = validSign32,
                status = "active"
            ))
        )

        service.getKeys("WSP-TEST-TEST-TEST")
        assertEquals(1, cache.size())

        service.invalidate("WSP-TEST-TEST-TEST")

        assertNull(cache.get("WSP-TEST-TEST-TEST"))
        assertEquals(0, cache.size())
    }

    @Test
    fun `gate2 clearCache removes all`() = runBlocking {
        mockApi.enqueueUserKeysResponse(
            ApiResult.Success(UserKeysResponse("WSP-A", validEnc32, validSign32, "active"))
        )
        mockApi.enqueueUserKeysResponse(
            ApiResult.Success(UserKeysResponse("WSP-B", validEnc32, validSign32, "active"))
        )

        service.getKeys("WSP-A")
        service.getKeys("WSP-B")
        assertEquals(2, cache.size())

        service.clearCache()

        assertEquals(0, cache.size())
    }

    // ==========================================================================
    // Gate 2: Valid keys are correct
    // ==========================================================================

    @Test
    fun `gate2 valid keys have correct content`() = runBlocking {
        val encBytes = ByteArray(32) { (it + 10).toByte() }
        val signBytes = ByteArray(32) { (it + 50).toByte() }

        mockApi.enqueueUserKeysResponse(
            ApiResult.Success(UserKeysResponse(
                whisperId = "WSP-VALID-KEY-TEST",
                encPublicKey = Base64Strict.encode(encBytes),
                signPublicKey = Base64Strict.encode(signBytes),
                status = "active"
            ))
        )

        val result = service.getKeys("WSP-VALID-KEY-TEST")
        val keys = result.getOrNull()!!

        assertEquals("WSP-VALID-KEY-TEST", keys.whisperId)
        assertArrayEquals(encBytes, keys.encPublicKey)
        assertArrayEquals(signBytes, keys.signPublicKey)
    }
}
