package com.whisper2.app.ui.screens.contacts

import android.Manifest
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.whisper2.app.core.Logger
import com.whisper2.app.services.contacts.QrCodeData
import com.whisper2.app.services.contacts.QrParseResult
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanQrScreen(
    onBack: () -> Unit,
    onQrScanned: (QrCodeData) -> Unit,
    parseQrCode: (String) -> QrParseResult
) {
    val context = LocalContext.current
    var hasCameraPermission by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isScanning by remember { mutableStateOf(true) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    // Gallery launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            isScanning = false
            processGalleryImage(context, uri, parseQrCode) { result ->
                when (result) {
                    is QrParseResult.Success -> {
                        onQrScanned(result.data)
                    }
                    is QrParseResult.Error -> {
                        errorMessage = result.message
                        isScanning = true
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan QR Code") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { galleryLauncher.launch("image/*") }) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = "Choose from gallery")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (hasCameraPermission && isScanning) {
                QrCameraPreview(
                    onQrCodeScanned = { qrContent ->
                        if (isScanning) {
                            isScanning = false
                            when (val result = parseQrCode(qrContent)) {
                                is QrParseResult.Success -> onQrScanned(result.data)
                                is QrParseResult.Error -> {
                                    errorMessage = result.message
                                    isScanning = true
                                }
                            }
                        }
                    }
                )

                // Scanning overlay
                ScanningOverlay()
            } else if (!hasCameraPermission) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        "Camera permission is required to scan QR codes",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Grant Permission")
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    OutlinedButton(onClick = { galleryLauncher.launch("image/*") }) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Choose from Gallery")
                    }
                }
            }

            // Error snackbar
            errorMessage?.let { error ->
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    action = {
                        TextButton(onClick = { errorMessage = null }) {
                            Text("Dismiss")
                        }
                    }
                ) {
                    Text(error)
                }
            }
        }
    }
}

@Composable
private fun ScanningOverlay() {
    Box(modifier = Modifier.fillMaxSize()) {
        // Dim the area outside the scanning zone
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
        )

        // Clear scanning zone
        Box(
            modifier = Modifier
                .size(280.dp)
                .align(Alignment.Center)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Transparent)
        )

        // Instruction text
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 100.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Point camera at a Whisper QR code",
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Or choose an image from gallery",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun QrCameraPreview(onQrCodeScanned: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)

            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder()
                    .build()
                    .also { it.surfaceProvider = previewView.surfaceProvider }

                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                            @androidx.camera.core.ExperimentalGetImage
                            val mediaImage = imageProxy.image
                            if (mediaImage != null) {
                                val image = InputImage.fromMediaImage(
                                    mediaImage,
                                    imageProxy.imageInfo.rotationDegrees
                                )

                                val scanner = BarcodeScanning.getClient()
                                scanner.process(image)
                                    .addOnSuccessListener { barcodes ->
                                        for (barcode in barcodes) {
                                            if (barcode.valueType == Barcode.TYPE_URL ||
                                                barcode.valueType == Barcode.TYPE_TEXT) {
                                                barcode.rawValue?.let { value ->
                                                    if (value.startsWith("whisper2://")) {
                                                        onQrCodeScanned(value)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    .addOnCompleteListener {
                                        imageProxy.close()
                                    }
                            } else {
                                imageProxy.close()
                            }
                        }
                    }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalyzer
                    )
                } catch (e: Exception) {
                    Logger.e("[ScanQrScreen] Camera bind failed", e)
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}

private fun processGalleryImage(
    context: Context,
    uri: Uri,
    parseQrCode: (String) -> QrParseResult,
    onResult: (QrParseResult) -> Unit
) {
    try {
        val inputStream = context.contentResolver.openInputStream(uri)
        val bitmap = BitmapFactory.decodeStream(inputStream)
        inputStream?.close()

        if (bitmap == null) {
            onResult(QrParseResult.Error("Could not load image"))
            return
        }

        // Try ZXing first for static image
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val source = RGBLuminanceSource(width, height, pixels)
        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))

        try {
            val reader = MultiFormatReader()
            val hints = mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true
            )
            val result = reader.decode(binaryBitmap, hints)
            val qrContent = result.text

            if (qrContent.startsWith("whisper2://")) {
                onResult(parseQrCode(qrContent))
            } else {
                onResult(QrParseResult.Error("Not a valid Whisper QR code"))
            }
        } catch (e: NotFoundException) {
            // Try with ML Kit as fallback
            val image = InputImage.fromBitmap(bitmap, 0)
            val scanner = BarcodeScanning.getClient()
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    val whisperBarcode = barcodes.firstOrNull { barcode ->
                        barcode.rawValue?.startsWith("whisper2://") == true
                    }

                    if (whisperBarcode != null) {
                        onResult(parseQrCode(whisperBarcode.rawValue!!))
                    } else if (barcodes.isNotEmpty()) {
                        onResult(QrParseResult.Error("Not a valid Whisper QR code"))
                    } else {
                        onResult(QrParseResult.Error("No QR code found in image"))
                    }
                }
                .addOnFailureListener {
                    onResult(QrParseResult.Error("Failed to scan image: ${it.message}"))
                }
        }
    } catch (e: Exception) {
        Logger.e("[ScanQrScreen] Failed to process gallery image", e)
        onResult(QrParseResult.Error("Failed to process image: ${e.message}"))
    }
}
