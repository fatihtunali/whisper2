package com.whisper2.app.services.contacts

import android.util.Log
import com.whisper2.app.core.utils.Base64Strict
import com.whisper2.app.network.api.ApiResult
import com.whisper2.app.network.api.UserKeysResponse
import com.whisper2.app.network.api.WhisperApi

/**
 * Peer keys for encryption and signature verification
 */
data class PeerKeys(
    val whisperId: String,
    val encPublicKey: ByteArray,   // 32 bytes X25519
    val signPublicKey: ByteArray   // 32 bytes Ed25519
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PeerKeys
        return whisperId == other.whisperId &&
                encPublicKey.contentEquals(other.encPublicKey) &&
                signPublicKey.contentEquals(other.signPublicKey)
    }

    override fun hashCode(): Int {
        var result = whisperId.hashCode()
        result = 31 * result + encPublicKey.contentHashCode()
        result = 31 * result + signPublicKey.contentHashCode()
        return result
    }
}

/**
 * Key lookup result
 */
sealed class KeyLookupResult {
    data class Success(val keys: PeerKeys) : KeyLookupResult()
    data class Error(val code: String, val message: String) : KeyLookupResult()

    val isSuccess: Boolean get() = this is Success
    fun getOrNull(): PeerKeys? = (this as? Success)?.keys
}

/**
 * Key cache interface for testability
 */
interface KeyCache {
    fun get(whisperId: String): PeerKeys?
    fun put(whisperId: String, keys: PeerKeys)
    fun remove(whisperId: String)
    fun clear()
}

/**
 * In-memory key cache implementation
 */
class InMemoryKeyCache : KeyCache {
    private val cache = mutableMapOf<String, PeerKeys>()

    override fun get(whisperId: String): PeerKeys? = cache[whisperId]

    override fun put(whisperId: String, keys: PeerKeys) {
        cache[whisperId] = keys
    }

    override fun remove(whisperId: String) {
        cache.remove(whisperId)
    }

    override fun clear() {
        cache.clear()
    }

    fun size(): Int = cache.size
}

/**
 * Key Lookup Service
 *
 * Looks up peer public keys with caching.
 * - Cache hit → no HTTP
 * - Cache miss → HTTP GET /users/{id}/keys
 *
 * Validation:
 * - status must be "active"
 * - encPublicKey must decode to 32 bytes
 * - signPublicKey must decode to 32 bytes
 */
class KeyLookupService(
    private val api: WhisperApi,
    private val cache: KeyCache = InMemoryKeyCache()
) {
    companion object {
        private const val TAG = "KeyLookupService"
        private const val KEY_SIZE = 32
    }

    /**
     * Get peer keys by WhisperID
     *
     * @param whisperId Target user's WhisperID
     * @return PeerKeys or null if lookup failed
     */
    suspend fun getKeys(whisperId: String): KeyLookupResult {
        // 1. Check cache
        val cached = cache.get(whisperId)
        if (cached != null) {
            Log.d(TAG, "Cache hit for $whisperId")
            return KeyLookupResult.Success(cached)
        }

        Log.d(TAG, "Cache miss for $whisperId, fetching from server")

        // 2. Fetch from server
        return when (val result = api.getUserKeys(whisperId)) {
            is ApiResult.Success -> {
                val response = result.data
                validateAndCache(response)
            }
            is ApiResult.Error -> {
                Log.w(TAG, "Key lookup failed for $whisperId: ${result.code} - ${result.message}")
                KeyLookupResult.Error(result.code, result.message)
            }
        }
    }

    /**
     * Validate response and cache if valid
     */
    private fun validateAndCache(response: UserKeysResponse): KeyLookupResult {
        // 1. Check status
        if (!response.isActive) {
            Log.w(TAG, "User ${response.whisperId} is banned, not caching")
            return KeyLookupResult.Error("FORBIDDEN", "User is banned")
        }

        // 2. Decode and validate encPublicKey
        val encKey: ByteArray
        try {
            if (!Base64Strict.isValid(response.encPublicKey)) {
                Log.w(TAG, "Invalid base64 for encPublicKey: ${response.whisperId}")
                return KeyLookupResult.Error("INVALID_KEY", "Invalid encPublicKey base64")
            }
            encKey = Base64Strict.decode(response.encPublicKey)
            if (encKey.size != KEY_SIZE) {
                Log.w(TAG, "Invalid encPublicKey size: ${encKey.size} for ${response.whisperId}")
                return KeyLookupResult.Error("INVALID_KEY", "encPublicKey must be $KEY_SIZE bytes")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode encPublicKey for ${response.whisperId}: ${e.message}")
            return KeyLookupResult.Error("INVALID_KEY", "Failed to decode encPublicKey")
        }

        // 3. Decode and validate signPublicKey
        val signKey: ByteArray
        try {
            if (!Base64Strict.isValid(response.signPublicKey)) {
                Log.w(TAG, "Invalid base64 for signPublicKey: ${response.whisperId}")
                return KeyLookupResult.Error("INVALID_KEY", "Invalid signPublicKey base64")
            }
            signKey = Base64Strict.decode(response.signPublicKey)
            if (signKey.size != KEY_SIZE) {
                Log.w(TAG, "Invalid signPublicKey size: ${signKey.size} for ${response.whisperId}")
                return KeyLookupResult.Error("INVALID_KEY", "signPublicKey must be $KEY_SIZE bytes")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode signPublicKey for ${response.whisperId}: ${e.message}")
            return KeyLookupResult.Error("INVALID_KEY", "Failed to decode signPublicKey")
        }

        // 4. Create PeerKeys and cache
        val peerKeys = PeerKeys(
            whisperId = response.whisperId,
            encPublicKey = encKey,
            signPublicKey = signKey
        )

        cache.put(response.whisperId, peerKeys)
        Log.d(TAG, "Cached keys for ${response.whisperId}")

        return KeyLookupResult.Success(peerKeys)
    }

    /**
     * Invalidate cached keys for a user
     */
    fun invalidate(whisperId: String) {
        cache.remove(whisperId)
        Log.d(TAG, "Invalidated cache for $whisperId")
    }

    /**
     * Clear all cached keys
     */
    fun clearCache() {
        cache.clear()
        Log.d(TAG, "Cache cleared")
    }
}
