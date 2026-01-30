package com.whisper2.app.ui.screens.media

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * Full-screen image viewer with pinch-to-zoom, pan, and double-tap to zoom.
 * Features a dark background with floating action buttons for close, save, and share.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    imagePath: String,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Transform state for zoom and pan
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var imageSize by remember { mutableStateOf(IntSize.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // Controls visibility (tap to toggle)
    var showControls by remember { mutableStateOf(true) }

    // Convert path to Uri
    val imageUri = remember(imagePath) {
        when {
            imagePath.startsWith("content://") -> Uri.parse(imagePath)
            File(imagePath).exists() -> Uri.fromFile(File(imagePath))
            else -> null
        }
    }

    // Calculate bounds for panning
    fun calculateBounds(): Pair<Float, Float> {
        if (scale <= 1f) return Pair(0f, 0f)
        val maxX = (imageSize.width * scale - containerSize.width).coerceAtLeast(0f) / 2f
        val maxY = (imageSize.height * scale - containerSize.height).coerceAtLeast(0f) / 2f
        return Pair(maxX, maxY)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { containerSize = it }
    ) {
        if (imageUri != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(imageUri)
                    .crossfade(true)
                    .build(),
                contentDescription = "Image",
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { showControls = !showControls },
                            onDoubleTap = { tapOffset ->
                                // Double tap to zoom in/out
                                if (scale > 1f) {
                                    // Reset to original
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    // Zoom to 2.5x centered on tap point
                                    scale = 2.5f
                                    // Calculate offset to center on tap point
                                    val centerX = containerSize.width / 2f
                                    val centerY = containerSize.height / 2f
                                    offset = Offset(
                                        x = (centerX - tapOffset.x) * (scale - 1),
                                        y = (centerY - tapOffset.y) * (scale - 1)
                                    )
                                }
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            // Update scale with limits
                            val newScale = (scale * zoom).coerceIn(0.5f, 5f)
                            scale = newScale

                            // Update offset with pan
                            val (maxX, maxY) = calculateBounds()
                            offset = Offset(
                                x = (offset.x + pan.x).coerceIn(-maxX, maxX),
                                y = (offset.y + pan.y).coerceIn(-maxY, maxY)
                            )
                        }
                    }
                    .onSizeChanged { imageSize = it },
                contentScale = ContentScale.Fit
            )
        } else {
            // Error state
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.BrokenImage,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Unable to load image",
                        color = Color.Gray
                    )
                }
            }
        }

        // Controls overlay
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Top bar with close button
                TopAppBar(
                    title = { },
                    navigationIcon = {
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier
                                .padding(8.dp)
                                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close",
                                tint = Color.White
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    )
                )

                // Bottom action bar
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 32.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // Save to gallery button
                    IconButton(
                        onClick = {
                            scope.launch {
                                saveImageToGallery(context, imagePath)
                            }
                        }
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = "Save to Gallery",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                            Text(
                                "Save",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }

                    // Share button
                    IconButton(
                        onClick = {
                            shareImage(context, imagePath)
                        }
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = "Share",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                            Text(
                                "Share",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }

        // Zoom indicator
        if (scale != 1f) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .padding(top = 56.dp),
                color = Color.Black.copy(alpha = 0.6f),
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = "${(scale * 100).toInt()}%",
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

/**
 * Save image to device gallery.
 */
private fun saveImageToGallery(context: Context, imagePath: String) {
    try {
        val sourceFile = when {
            imagePath.startsWith("content://") -> {
                // Copy content URI to temp file first
                val inputStream = context.contentResolver.openInputStream(Uri.parse(imagePath))
                val tempFile = File(context.cacheDir, "temp_save_${System.currentTimeMillis()}.jpg")
                inputStream?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                tempFile
            }
            else -> File(imagePath)
        }

        if (!sourceFile.exists()) {
            Toast.makeText(context, "Image file not found", Toast.LENGTH_SHORT).show()
            return
        }

        val fileName = "Whisper2_${System.currentTimeMillis()}.jpg"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ - use MediaStore
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Whisper2")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )

            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { outputStream ->
                    FileInputStream(sourceFile).use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }

                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(it, contentValues, null, null)

                Toast.makeText(context, "Image saved to gallery", Toast.LENGTH_SHORT).show()
            } ?: run {
                Toast.makeText(context, "Failed to save image", Toast.LENGTH_SHORT).show()
            }
        } else {
            // Older Android versions
            @Suppress("DEPRECATION")
            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val whisperDir = File(picturesDir, "Whisper2")
            if (!whisperDir.exists()) {
                whisperDir.mkdirs()
            }

            val destFile = File(whisperDir, fileName)
            sourceFile.copyTo(destFile, overwrite = true)

            // Notify gallery about new image
            val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            mediaScanIntent.data = Uri.fromFile(destFile)
            context.sendBroadcast(mediaScanIntent)

            Toast.makeText(context, "Image saved to gallery", Toast.LENGTH_SHORT).show()
        }
    } catch (e: IOException) {
        Toast.makeText(context, "Failed to save image: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Share image via Android share sheet.
 */
private fun shareImage(context: Context, imagePath: String) {
    try {
        val uri = when {
            imagePath.startsWith("content://") -> Uri.parse(imagePath)
            else -> {
                val file = File(imagePath)
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
            }
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(Intent.createChooser(shareIntent, "Share Image"))
    } catch (e: Exception) {
        Toast.makeText(context, "Failed to share image: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
