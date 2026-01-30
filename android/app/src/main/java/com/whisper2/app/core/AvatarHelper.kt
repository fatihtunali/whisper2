package com.whisper2.app.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Helper class for managing contact avatar images.
 * Handles saving, loading, deleting, and compressing avatar images.
 */
object AvatarHelper {

    private const val AVATAR_DIR = "avatars"
    private const val MAX_AVATAR_SIZE = 512 // Max dimension in pixels
    private const val COMPRESSION_QUALITY = 85 // JPEG quality (0-100)

    /**
     * Get the avatars directory, creating it if necessary.
     */
    private fun getAvatarsDir(context: Context): File {
        val dir = File(context.filesDir, AVATAR_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Save an avatar image from a URI.
     * Compresses and resizes the image before saving.
     *
     * @param context Application context
     * @param whisperId The contact's WhisperID (used for filename)
     * @param imageUri URI of the source image
     * @return Path to the saved avatar file, or null if save failed
     */
    fun saveAvatarFromUri(context: Context, whisperId: String, imageUri: Uri): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(imageUri) ?: return null
            saveAvatarFromStream(context, whisperId, inputStream, imageUri)
        } catch (e: Exception) {
            Logger.e("[AvatarHelper] Failed to save avatar from URI", e)
            null
        }
    }

    /**
     * Save an avatar image from an InputStream.
     */
    private fun saveAvatarFromStream(
        context: Context,
        whisperId: String,
        inputStream: InputStream,
        originalUri: Uri? = null
    ): String? {
        return try {
            // Decode the image
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            if (originalBitmap == null) {
                Logger.e("[AvatarHelper] Failed to decode bitmap")
                return null
            }

            // Get EXIF orientation and rotate if needed
            val rotatedBitmap = originalUri?.let { uri ->
                try {
                    context.contentResolver.openInputStream(uri)?.use { exifStream ->
                        val exif = ExifInterface(exifStream)
                        val orientation = exif.getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL
                        )
                        rotateBitmapIfNeeded(originalBitmap, orientation)
                    } ?: originalBitmap
                } catch (e: Exception) {
                    originalBitmap
                }
            } ?: originalBitmap

            // Resize and compress
            val resizedBitmap = resizeBitmap(rotatedBitmap, MAX_AVATAR_SIZE)

            // Delete old avatar if exists
            deleteAvatar(context, whisperId)

            // Save new avatar
            val avatarFile = File(getAvatarsDir(context), "${whisperId}.jpg")
            FileOutputStream(avatarFile).use { out ->
                resizedBitmap.compress(Bitmap.CompressFormat.JPEG, COMPRESSION_QUALITY, out)
            }

            // Cleanup bitmaps
            if (rotatedBitmap != originalBitmap) {
                originalBitmap.recycle()
            }
            if (resizedBitmap != rotatedBitmap) {
                rotatedBitmap.recycle()
            }
            resizedBitmap.recycle()

            Logger.d("[AvatarHelper] Saved avatar for $whisperId: ${avatarFile.absolutePath}")
            avatarFile.absolutePath
        } catch (e: Exception) {
            Logger.e("[AvatarHelper] Failed to save avatar", e)
            null
        }
    }

    /**
     * Save an avatar from a file path (e.g., from camera).
     */
    fun saveAvatarFromFile(context: Context, whisperId: String, filePath: String): String? {
        return try {
            val file = File(filePath)
            if (!file.exists()) {
                Logger.e("[AvatarHelper] Source file does not exist: $filePath")
                return null
            }

            val bitmap = BitmapFactory.decodeFile(filePath)
            if (bitmap == null) {
                Logger.e("[AvatarHelper] Failed to decode bitmap from file")
                return null
            }

            // Get EXIF orientation
            val exif = ExifInterface(filePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            val rotatedBitmap = rotateBitmapIfNeeded(bitmap, orientation)

            // Resize
            val resizedBitmap = resizeBitmap(rotatedBitmap, MAX_AVATAR_SIZE)

            // Delete old avatar
            deleteAvatar(context, whisperId)

            // Save new avatar
            val avatarFile = File(getAvatarsDir(context), "${whisperId}.jpg")
            FileOutputStream(avatarFile).use { out ->
                resizedBitmap.compress(Bitmap.CompressFormat.JPEG, COMPRESSION_QUALITY, out)
            }

            // Cleanup
            if (rotatedBitmap != bitmap) {
                bitmap.recycle()
            }
            if (resizedBitmap != rotatedBitmap) {
                rotatedBitmap.recycle()
            }
            resizedBitmap.recycle()

            // Delete source file if it's in cache
            if (filePath.contains("cache")) {
                file.delete()
            }

            Logger.d("[AvatarHelper] Saved avatar from file for $whisperId")
            avatarFile.absolutePath
        } catch (e: Exception) {
            Logger.e("[AvatarHelper] Failed to save avatar from file", e)
            null
        }
    }

    /**
     * Delete an avatar for a contact.
     */
    fun deleteAvatar(context: Context, whisperId: String): Boolean {
        return try {
            val avatarFile = File(getAvatarsDir(context), "${whisperId}.jpg")
            if (avatarFile.exists()) {
                val deleted = avatarFile.delete()
                Logger.d("[AvatarHelper] Deleted avatar for $whisperId: $deleted")
                deleted
            } else {
                true
            }
        } catch (e: Exception) {
            Logger.e("[AvatarHelper] Failed to delete avatar", e)
            false
        }
    }

    /**
     * Check if an avatar file exists for a contact.
     */
    fun avatarExists(context: Context, whisperId: String): Boolean {
        val avatarFile = File(getAvatarsDir(context), "${whisperId}.jpg")
        return avatarFile.exists()
    }

    /**
     * Get the avatar file for a contact if it exists.
     */
    fun getAvatarFile(context: Context, whisperId: String): File? {
        val avatarFile = File(getAvatarsDir(context), "${whisperId}.jpg")
        return if (avatarFile.exists()) avatarFile else null
    }

    /**
     * Check if an avatar path is valid (file exists).
     */
    fun isAvatarPathValid(avatarPath: String?): Boolean {
        if (avatarPath.isNullOrEmpty()) return false
        return File(avatarPath).exists()
    }

    /**
     * Resize a bitmap to fit within the given max size while maintaining aspect ratio.
     */
    private fun resizeBitmap(bitmap: Bitmap, maxSize: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxSize && height <= maxSize) {
            return bitmap
        }

        val ratio = minOf(maxSize.toFloat() / width, maxSize.toFloat() / height)
        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * Rotate bitmap based on EXIF orientation.
     */
    private fun rotateBitmapIfNeeded(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()

        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(-90f)
                matrix.preScale(-1f, 1f)
            }
            else -> return bitmap
        }

        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: Exception) {
            Logger.e("[AvatarHelper] Failed to rotate bitmap", e)
            bitmap
        }
    }

    /**
     * Create a temporary file for camera capture.
     */
    fun createTempImageFile(context: Context): File {
        val tempDir = File(context.cacheDir, "temp_images")
        if (!tempDir.exists()) {
            tempDir.mkdirs()
        }
        return File(tempDir, "temp_avatar_${System.currentTimeMillis()}.jpg")
    }

    /**
     * Clean up temp image files.
     */
    fun cleanupTempFiles(context: Context) {
        try {
            val tempDir = File(context.cacheDir, "temp_images")
            if (tempDir.exists()) {
                tempDir.listFiles()?.forEach { file ->
                    if (file.name.startsWith("temp_avatar_")) {
                        file.delete()
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e("[AvatarHelper] Failed to cleanup temp files", e)
        }
    }
}
