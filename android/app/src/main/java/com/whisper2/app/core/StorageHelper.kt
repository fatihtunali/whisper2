package com.whisper2.app.core

import android.content.Context
import com.whisper2.app.data.local.db.WhisperDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Storage usage breakdown for display in settings.
 */
data class StorageUsage(
    val messagesSize: Long,
    val mediaSize: Long,
    val cacheSize: Long,
    val totalSize: Long
) {
    companion object {
        val EMPTY = StorageUsage(0L, 0L, 0L, 0L)
    }
}

/**
 * Helper utility for calculating and managing storage usage.
 */
@Singleton
class StorageHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: WhisperDatabase
) {
    /**
     * Calculate total storage usage including database, media, and cache.
     */
    suspend fun calculateStorageUsage(): StorageUsage = withContext(Dispatchers.IO) {
        val messagesSize = calculateDatabaseSize()
        val mediaSize = calculateMediaFolderSize()
        val cacheSize = calculateCacheSize()
        val totalSize = messagesSize + mediaSize + cacheSize

        StorageUsage(
            messagesSize = messagesSize,
            mediaSize = mediaSize,
            cacheSize = cacheSize,
            totalSize = totalSize
        )
    }

    /**
     * Calculate the size of the Room database files.
     */
    suspend fun calculateDatabaseSize(): Long = withContext(Dispatchers.IO) {
        try {
            val dbPath = context.getDatabasePath(Constants.DATABASE_NAME)
            val dbDir = dbPath.parentFile ?: return@withContext 0L

            var totalSize = 0L

            // Main database file
            if (dbPath.exists()) {
                totalSize += dbPath.length()
            }

            // WAL (Write-Ahead Log) file
            val walFile = File(dbDir, "${Constants.DATABASE_NAME}-wal")
            if (walFile.exists()) {
                totalSize += walFile.length()
            }

            // SHM (Shared Memory) file
            val shmFile = File(dbDir, "${Constants.DATABASE_NAME}-shm")
            if (shmFile.exists()) {
                totalSize += shmFile.length()
            }

            totalSize
        } catch (e: Exception) {
            Logger.e("[StorageHelper] Error calculating database size", e)
            0L
        }
    }

    /**
     * Calculate the size of the media/attachments folder.
     */
    suspend fun calculateMediaFolderSize(): Long = withContext(Dispatchers.IO) {
        try {
            val attachmentsDir = File(context.filesDir, "attachments")
            calculateFolderSize(attachmentsDir)
        } catch (e: Exception) {
            Logger.e("[StorageHelper] Error calculating media folder size", e)
            0L
        }
    }

    /**
     * Calculate the size of all cache directories.
     */
    suspend fun calculateCacheSize(): Long = withContext(Dispatchers.IO) {
        try {
            var totalSize = 0L

            // Internal cache directory
            context.cacheDir?.let { cacheDir ->
                totalSize += calculateFolderSize(cacheDir)
            }

            // External cache directory (if available)
            context.externalCacheDir?.let { extCacheDir ->
                totalSize += calculateFolderSize(extCacheDir)
            }

            // Code cache directory
            context.codeCacheDir?.let { codeCacheDir ->
                totalSize += calculateFolderSize(codeCacheDir)
            }

            totalSize
        } catch (e: Exception) {
            Logger.e("[StorageHelper] Error calculating cache size", e)
            0L
        }
    }

    /**
     * Clear all cache directories.
     * Returns the amount of space freed in bytes.
     */
    suspend fun clearCache(): Long = withContext(Dispatchers.IO) {
        val previousSize = calculateCacheSize()

        try {
            // Clear internal cache
            context.cacheDir?.let { cacheDir ->
                deleteDirectory(cacheDir, deleteRoot = false)
            }

            // Clear external cache
            context.externalCacheDir?.let { extCacheDir ->
                deleteDirectory(extCacheDir, deleteRoot = false)
            }

            Logger.d("[StorageHelper] Cache cleared, freed ${formatSize(previousSize)}")
        } catch (e: Exception) {
            Logger.e("[StorageHelper] Error clearing cache", e)
        }

        previousSize
    }

    /**
     * Clear all downloaded media/attachments.
     * Returns the amount of space freed in bytes.
     */
    suspend fun clearMedia(): Long = withContext(Dispatchers.IO) {
        val previousSize = calculateMediaFolderSize()

        try {
            val attachmentsDir = File(context.filesDir, "attachments")
            if (attachmentsDir.exists()) {
                deleteDirectory(attachmentsDir, deleteRoot = false)
            }

            Logger.d("[StorageHelper] Media cleared, freed ${formatSize(previousSize)}")
        } catch (e: Exception) {
            Logger.e("[StorageHelper] Error clearing media", e)
        }

        previousSize
    }

    /**
     * Recursively calculate the size of a directory.
     */
    private fun calculateFolderSize(directory: File): Long {
        if (!directory.exists()) return 0L
        if (directory.isFile) return directory.length()

        var size = 0L
        try {
            directory.listFiles()?.forEach { file ->
                size += if (file.isDirectory) {
                    calculateFolderSize(file)
                } else {
                    file.length()
                }
            }
        } catch (e: Exception) {
            Logger.e("[StorageHelper] Error calculating folder size for ${directory.path}", e)
        }
        return size
    }

    /**
     * Recursively delete directory contents.
     * @param directory The directory to delete
     * @param deleteRoot If true, also delete the directory itself; if false, only delete contents
     */
    private fun deleteDirectory(directory: File, deleteRoot: Boolean = true): Boolean {
        if (!directory.exists()) return true

        if (directory.isDirectory) {
            directory.listFiles()?.forEach { file ->
                deleteDirectory(file, deleteRoot = true)
            }
        }

        return if (deleteRoot) {
            directory.delete()
        } else {
            true
        }
    }

    companion object {
        private const val KB = 1024L
        private const val MB = KB * 1024L
        private const val GB = MB * 1024L

        /**
         * Format a size in bytes to a human-readable string (KB, MB, GB).
         */
        fun formatSize(bytes: Long): String {
            return when {
                bytes >= GB -> String.format("%.2f GB", bytes.toDouble() / GB)
                bytes >= MB -> String.format("%.2f MB", bytes.toDouble() / MB)
                bytes >= KB -> String.format("%.2f KB", bytes.toDouble() / KB)
                else -> "$bytes B"
            }
        }

        /**
         * Format a size in bytes to a compact human-readable string.
         */
        fun formatSizeCompact(bytes: Long): String {
            return when {
                bytes >= GB -> String.format("%.1f GB", bytes.toDouble() / GB)
                bytes >= MB -> String.format("%.1f MB", bytes.toDouble() / MB)
                bytes >= KB -> String.format("%.0f KB", bytes.toDouble() / KB)
                else -> "$bytes B"
            }
        }
    }
}
