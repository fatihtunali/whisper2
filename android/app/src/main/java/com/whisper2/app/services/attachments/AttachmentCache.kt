package com.whisper2.app.services.attachments

/**
 * Step 10: Attachment Cache
 *
 * Caches decrypted attachment data to avoid re-downloading.
 * Key: objectKey
 * Value: decrypted bytes
 */
interface AttachmentCache {

    /**
     * Get cached attachment if present
     *
     * @param objectKey The S3/Spaces object key
     * @return Decrypted bytes or null if not cached
     */
    fun getIfPresent(objectKey: String): ByteArray?

    /**
     * Store decrypted attachment in cache
     *
     * @param objectKey The S3/Spaces object key
     * @param data Decrypted attachment bytes
     */
    fun put(objectKey: String, data: ByteArray)

    /**
     * Remove attachment from cache
     *
     * @param objectKey The S3/Spaces object key
     */
    fun invalidate(objectKey: String)

    /**
     * Clear all cached attachments
     */
    fun clear()

    /**
     * Get current cache size (number of entries)
     */
    fun size(): Int
}

/**
 * In-memory implementation for testing
 */
class InMemoryAttachmentCache : AttachmentCache {

    private val cache = mutableMapOf<String, ByteArray>()

    override fun getIfPresent(objectKey: String): ByteArray? {
        return cache[objectKey]
    }

    override fun put(objectKey: String, data: ByteArray) {
        cache[objectKey] = data
    }

    override fun invalidate(objectKey: String) {
        cache.remove(objectKey)
    }

    override fun clear() {
        cache.clear()
    }

    override fun size(): Int = cache.size
}
