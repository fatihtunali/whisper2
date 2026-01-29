package com.whisper2.app.ui.screens.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import com.whisper2.app.BuildConfig
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.whisper2.app.ui.viewmodels.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onProfileClick: () -> Unit,
    onLogout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val whisperId by viewModel.whisperId.collectAsState()
    val deviceId by viewModel.deviceId.collectAsState()
    var showSeedPhrase by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showWipeDataDialog by remember { mutableStateOf(false) }
    var showProfileSheet by remember { mutableStateOf(false) }
    var showCopiedSnackbar by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", color = Color.White, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
            )
        },
        snackbarHost = {
            if (showCopiedSnackbar) {
                Snackbar(
                    modifier = Modifier.padding(16.dp),
                    action = {
                        TextButton(onClick = { showCopiedSnackbar = false }) {
                            Text("OK")
                        }
                    }
                ) {
                    Text("Whisper ID copied to clipboard")
                }
            }
        },
        containerColor = Color.Black
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Profile section
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Gray.copy(alpha = 0.15f),
                    modifier = Modifier.clickable { showProfileSheet = true }
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .background(
                                    Brush.linearGradient(listOf(Color(0xFF3B82F6), Color(0xFF8B5CF6))),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Person, null, tint = Color.White, modifier = Modifier.size(32.dp))
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("My Profile", fontWeight = FontWeight.SemiBold, color = Color.White)
                            Text(
                                whisperId ?: "Not registered",
                                color = Color.Gray,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = Color.Gray)
                    }
                }
            }

            // Security section
            item {
                Text("Security", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            item {
                SettingsItem(
                    icon = Icons.Default.Key,
                    iconColor = Color(0xFFF59E0B),
                    title = "View Seed Phrase",
                    onClick = { showSeedPhrase = true }
                )
            }

            // About section
            item {
                Text("About", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            item {
                SettingsItem(
                    icon = Icons.Default.Info,
                    iconColor = Color(0xFF3B82F6),
                    title = "Version",
                    subtitle = BuildConfig.VERSION_NAME
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Default.Language,
                    iconColor = Color(0xFF3B82F6),
                    title = "Website",
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://whisper2.aiakademiturkiye.com"))
                        context.startActivity(intent)
                    }
                )
            }

            // Danger zone
            item {
                Text("Danger Zone", color = Color(0xFFEF4444), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            item {
                SettingsItem(
                    icon = Icons.AutoMirrored.Filled.Logout,
                    iconColor = Color(0xFFF59E0B),
                    title = "Logout",
                    titleColor = Color(0xFFF59E0B),
                    onClick = { showLogoutDialog = true }
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Default.DeleteForever,
                    iconColor = Color(0xFFEF4444),
                    title = "Wipe All Data",
                    titleColor = Color(0xFFEF4444),
                    onClick = { showWipeDataDialog = true }
                )
            }
        }
    }

    // Profile sheet
    if (showProfileSheet) {
        ProfileBottomSheet(
            whisperId = whisperId,
            deviceId = deviceId,
            qrCodeData = viewModel.getQrCodeData(),
            onDismiss = { showProfileSheet = false },
            onCopyId = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Whisper ID", whisperId))
                showCopiedSnackbar = true
            }
        )
    }

    // Seed phrase dialog
    if (showSeedPhrase) {
        SeedPhraseRevealDialog(
            onDismiss = { showSeedPhrase = false },
            viewModel = viewModel
        )
    }

    // Logout dialog
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Logout") },
            text = { Text("Are you sure you want to logout? Make sure you have backed up your seed phrase.") },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    viewModel.logout()
                    onLogout()
                }) {
                    Text("Logout", color = Color(0xFFEF4444))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Wipe data dialog
    if (showWipeDataDialog) {
        AlertDialog(
            onDismissRequest = { showWipeDataDialog = false },
            title = { Text("Wipe All Data") },
            text = {
                Text("This will permanently delete ALL local data including messages, contacts, call history, and your account keys. This action cannot be undone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showWipeDataDialog = false
                    viewModel.wipeAllData()
                    onLogout()
                }) {
                    Text("Wipe Everything", color = Color(0xFFEF4444))
                }
            },
            dismissButton = {
                TextButton(onClick = { showWipeDataDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Snackbar auto-dismiss
    LaunchedEffect(showCopiedSnackbar) {
        if (showCopiedSnackbar) {
            kotlinx.coroutines.delay(2000)
            showCopiedSnackbar = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileBottomSheet(
    whisperId: String?,
    deviceId: String?,
    qrCodeData: String?,
    onDismiss: () -> Unit,
    onCopyId: () -> Unit
) {
    var showFullScreenQr by remember { mutableStateOf(false) }

    if (showFullScreenQr && qrCodeData != null) {
        FullScreenQrView(
            qrCodeData = qrCodeData,
            whisperId = whisperId,
            onBack = { showFullScreenQr = false }
        )
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            containerColor = Color(0xFF1A1A1A)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Avatar
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .background(
                            Brush.linearGradient(listOf(Color(0xFF3B82F6), Color(0xFF8B5CF6))),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Person, null, tint = Color.White, modifier = Modifier.size(48.dp))
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Whisper ID
                Text(
                    whisperId ?: "Not registered",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Copy button
                OutlinedButton(
                    onClick = onCopyId,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF3B82F6))
                ) {
                    Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Copy ID")
                }

                Spacer(modifier = Modifier.height(24.dp))

                // QR Code (clickable for full screen)
                qrCodeData?.let { data ->
                    Text(
                        "Tap QR to enlarge",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier.clickable { showFullScreenQr = true }
                    ) {
                        QrCodeImage(data = data, size = 200)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Device ID
                deviceId?.let {
                    Text(
                        "Device: ${it.take(8)}...",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
fun FullScreenQrView(
    qrCodeData: String,
    whisperId: String?,
    onBack: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Back button
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .size(48.dp)
                .background(Color.White.copy(alpha = 0.1f), CircleShape)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White
            )
        }

        // QR Code centered
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "My QR Code",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                "Let others scan this to add you",
                color = Color.Gray,
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Large QR Code
            QrCodeImage(data = qrCodeData, size = 300)

            Spacer(modifier = Modifier.height(24.dp))

            // Whisper ID
            whisperId?.let {
                Text(
                    it,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
fun QrCodeImage(data: String, size: Int) {
    val qrBitmap = remember(data) {
        try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(data, BarcodeFormat.QR_CODE, size, size)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    qrBitmap?.let {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color.White
        ) {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "QR Code",
                modifier = Modifier
                    .size(size.dp)
                    .padding(8.dp)
            )
        }
    }
}

@Composable
fun SettingsItem(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    titleColor: Color = Color.White,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.Gray.copy(alpha = 0.15f),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = iconColor)
            Spacer(modifier = Modifier.width(16.dp))
            Text(title, color = titleColor, modifier = Modifier.weight(1f))
            if (subtitle != null) {
                Text(subtitle, color = Color.Gray)
            } else if (onClick != null) {
                Icon(Icons.Default.ChevronRight, null, tint = Color.Gray)
            }
        }
    }
}

@Composable
fun SeedPhraseRevealDialog(
    onDismiss: () -> Unit,
    viewModel: SettingsViewModel
) {
    var isRevealed by remember { mutableStateOf(false) }
    val seedPhrase by viewModel.seedPhrase.collectAsState()

    AlertDialog(
        onDismissRequest = {
            viewModel.hideSeedPhrase()
            onDismiss()
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, null, tint = Color(0xFFEF4444))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Seed Phrase")
            }
        },
        text = {
            Column {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFEF4444).copy(alpha = 0.1f)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Shield,
                            null,
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Never share your seed phrase with anyone!",
                            color = Color(0xFFEF4444),
                            fontSize = 12.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (isRevealed && seedPhrase != null) {
                    val words = seedPhrase!!.split(" ")
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.height(200.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(words) { index, word ->
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color.Gray.copy(alpha = 0.2f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "${index + 1}.",
                                        color = Color.Gray,
                                        fontSize = 10.sp,
                                        modifier = Modifier.width(18.dp)
                                    )
                                    Text(
                                        word,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .background(Color.Gray.copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.VisibilityOff,
                                null,
                                tint = Color.Gray,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Tap Reveal to show", color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (isRevealed) {
                    viewModel.hideSeedPhrase()
                    isRevealed = false
                } else {
                    viewModel.loadSeedPhrase()
                    isRevealed = true
                }
            }) {
                Text(
                    if (isRevealed) "Hide" else "Reveal",
                    color = if (isRevealed) Color.Gray else Color(0xFFEF4444)
                )
            }
        },
        dismissButton = {
            TextButton(onClick = {
                viewModel.hideSeedPhrase()
                onDismiss()
            }) {
                Text("Done")
            }
        }
    )
}
