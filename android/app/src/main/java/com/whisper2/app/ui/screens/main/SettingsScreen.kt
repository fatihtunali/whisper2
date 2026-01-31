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
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.whisper2.app.core.BiometricHelper
import com.whisper2.app.core.StorageHelper
import com.whisper2.app.ui.viewmodels.SettingsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onProfileClick: () -> Unit,
    onLogout: () -> Unit,
    onNavigateToBlockedUsers: () -> Unit = {},
    onNavigateToFontSize: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val whisperId by viewModel.whisperId.collectAsState()
    val deviceId by viewModel.deviceId.collectAsState()
    var showSeedPhrase by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showWipeDataDialog by remember { mutableStateOf(false) }
    var showProfileSheet by remember { mutableStateOf(false) }
    var showCopiedSnackbar by remember { mutableStateOf(false) }

    // Notification settings
    val notificationsEnabled by viewModel.notificationsEnabled.collectAsState()
    val messagePreview by viewModel.messagePreview.collectAsState()
    val notificationSound by viewModel.notificationSound.collectAsState()
    val notificationVibration by viewModel.notificationVibration.collectAsState()

    // Privacy settings
    val sendReadReceipts by viewModel.sendReadReceipts.collectAsState()
    val showTypingIndicator by viewModel.showTypingIndicator.collectAsState()
    val showOnlineStatus by viewModel.showOnlineStatus.collectAsState()

    // Auto-download settings
    val autoDownloadPhotos by viewModel.autoDownloadPhotos.collectAsState()
    val autoDownloadVideos by viewModel.autoDownloadVideos.collectAsState()
    val autoDownloadAudio by viewModel.autoDownloadAudio.collectAsState()

    // Storage
    val storageUsage by viewModel.storageUsage.collectAsState()
    val isLoadingStorage by viewModel.isLoadingStorage.collectAsState()
    val isClearingCache by viewModel.isClearingCache.collectAsState()

    // Biometric lock settings
    val biometricLockEnabled by viewModel.biometricLockEnabled.collectAsState()
    val lockTimeoutMinutes by viewModel.lockTimeoutMinutes.collectAsState()
    val isBiometricAvailable = remember { BiometricHelper.isBiometricAvailable(context) }
    val biometricStatus = remember { BiometricHelper.checkBiometricAvailability(context) }

    // Dialogs
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showClearMediaDialog by remember { mutableStateOf(false) }
    var showResetSettingsDialog by remember { mutableStateOf(false) }
    var showLockTimeoutPicker by remember { mutableStateOf(false) }
    var showBiometricNotAvailableDialog by remember { mutableStateOf(false) }

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

            // Notifications section
            item {
                Text("Notifications", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            item {
                SettingsToggleItem(
                    icon = Icons.Default.Notifications,
                    iconColor = Color(0xFF3B82F6),
                    title = "Notifications",
                    subtitle = "Enable or disable all notifications",
                    checked = notificationsEnabled,
                    onCheckedChange = { viewModel.setNotificationsEnabled(it) }
                )
            }
            item {
                SettingsToggleItem(
                    icon = Icons.Default.Visibility,
                    iconColor = Color(0xFF8B5CF6),
                    title = "Message Preview",
                    subtitle = "Show message content in notifications",
                    checked = messagePreview,
                    onCheckedChange = { viewModel.setMessagePreview(it) },
                    enabled = notificationsEnabled
                )
            }
            item {
                SettingsToggleItem(
                    icon = Icons.Default.VolumeUp,
                    iconColor = Color(0xFF10B981),
                    title = "Sound",
                    subtitle = "Play sound for new messages",
                    checked = notificationSound,
                    onCheckedChange = { viewModel.setNotificationSound(it) },
                    enabled = notificationsEnabled
                )
            }
            item {
                SettingsToggleItem(
                    icon = Icons.Default.Vibration,
                    iconColor = Color(0xFFF59E0B),
                    title = "Vibration",
                    subtitle = "Vibrate for new messages",
                    checked = notificationVibration,
                    onCheckedChange = { viewModel.setNotificationVibration(it) },
                    enabled = notificationsEnabled
                )
            }

            // Privacy section
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Privacy", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            item {
                SettingsToggleItem(
                    icon = Icons.Default.DoneAll,
                    iconColor = Color(0xFF3B82F6),
                    title = "Read Receipts",
                    subtitle = "Let others know when you've read their messages",
                    checked = sendReadReceipts,
                    onCheckedChange = { viewModel.setSendReadReceipts(it) }
                )
            }
            item {
                SettingsToggleItem(
                    icon = Icons.Default.Edit,
                    iconColor = Color(0xFF8B5CF6),
                    title = "Typing Indicators",
                    subtitle = "Show when you're typing a message",
                    checked = showTypingIndicator,
                    onCheckedChange = { viewModel.setShowTypingIndicator(it) }
                )
            }
            item {
                SettingsToggleItem(
                    icon = Icons.Default.Circle,
                    iconColor = Color(0xFF10B981),
                    title = "Online Status",
                    subtitle = "Show when you're online",
                    checked = showOnlineStatus,
                    onCheckedChange = { viewModel.setShowOnlineStatus(it) }
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Default.Block,
                    iconColor = Color(0xFFEF4444),
                    title = "Blocked Users",
                    subtitle = "Manage blocked contacts",
                    onClick = onNavigateToBlockedUsers
                )
            }

            // Appearance section
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Appearance", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            item {
                SettingsItem(
                    icon = Icons.Default.TextFields,
                    iconColor = Color(0xFF8B5CF6),
                    title = "Font Size",
                    subtitle = "Adjust text size for better readability",
                    onClick = onNavigateToFontSize
                )
            }

            // Security section
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Security", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            item {
                SettingsToggleItem(
                    icon = Icons.Default.Fingerprint,
                    iconColor = Color(0xFF3B82F6),
                    title = "Biometric Lock",
                    subtitle = if (isBiometricAvailable) "Require fingerprint or face to unlock" else BiometricHelper.getStatusDescription(biometricStatus),
                    checked = biometricLockEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled && !isBiometricAvailable) {
                            showBiometricNotAvailableDialog = true
                        } else {
                            viewModel.setBiometricLockEnabled(enabled)
                        }
                    },
                    enabled = isBiometricAvailable || biometricLockEnabled
                )
            }
            if (biometricLockEnabled) {
                item {
                    SettingsItem(
                        icon = Icons.Default.Timer,
                        iconColor = Color(0xFF8B5CF6),
                        title = "Lock Timeout",
                        subtitle = viewModel.getLockTimeoutLabel(lockTimeoutMinutes),
                        onClick = { showLockTimeoutPicker = true }
                    )
                }
            }
            item {
                SettingsItem(
                    icon = Icons.Default.Key,
                    iconColor = Color(0xFFF59E0B),
                    title = "View Seed Phrase",
                    onClick = { showSeedPhrase = true }
                )
            }

            // Storage & Data section
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Storage & Data", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            item {
                StorageUsageCard(
                    messagesSize = storageUsage.messagesSize,
                    mediaSize = storageUsage.mediaSize,
                    cacheSize = storageUsage.cacheSize,
                    totalSize = storageUsage.totalSize,
                    isLoading = isLoadingStorage,
                    onRefresh = { viewModel.refreshStorageUsage() }
                )
            }
            item {
                Text(
                    "Auto-Download",
                    color = Color.Gray,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            item {
                SettingsToggleItem(
                    icon = Icons.Default.Photo,
                    iconColor = Color(0xFF3B82F6),
                    title = "Photos",
                    subtitle = "Automatically download photos",
                    checked = autoDownloadPhotos,
                    onCheckedChange = { viewModel.setAutoDownloadPhotos(it) }
                )
            }
            item {
                SettingsToggleItem(
                    icon = Icons.Default.VideoFile,
                    iconColor = Color(0xFF8B5CF6),
                    title = "Videos",
                    subtitle = "Automatically download videos (uses more data)",
                    checked = autoDownloadVideos,
                    onCheckedChange = { viewModel.setAutoDownloadVideos(it) }
                )
            }
            item {
                SettingsToggleItem(
                    icon = Icons.Default.AudioFile,
                    iconColor = Color(0xFF10B981),
                    title = "Audio",
                    subtitle = "Automatically download audio messages",
                    checked = autoDownloadAudio,
                    onCheckedChange = { viewModel.setAutoDownloadAudio(it) }
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Default.CleaningServices,
                    iconColor = Color(0xFFF59E0B),
                    title = "Clear Cache",
                    subtitle = StorageHelper.formatSizeCompact(storageUsage.cacheSize),
                    onClick = { showClearCacheDialog = true }
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Default.FolderDelete,
                    iconColor = Color(0xFFEF4444),
                    title = "Clear Downloaded Media",
                    subtitle = StorageHelper.formatSizeCompact(storageUsage.mediaSize),
                    onClick = { showClearMediaDialog = true }
                )
            }

            // About section
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text("About", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            item {
                SettingsItem(
                    icon = Icons.Default.Info,
                    iconColor = Color(0xFF3B82F6),
                    title = "Version",
                    subtitle = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
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
                Spacer(modifier = Modifier.height(8.dp))
                Text("Danger Zone", color = Color(0xFFEF4444), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            item {
                SettingsItem(
                    icon = Icons.Default.RestartAlt,
                    iconColor = Color(0xFFF59E0B),
                    title = "Reset Settings",
                    subtitle = "Reset all settings to defaults (keeps account data)",
                    onClick = { showResetSettingsDialog = true }
                )
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
                    scope.launch {
                        viewModel.wipeAllData()
                        onLogout()
                    }
                }) {
                    Text("Wipe Everything", color = Color(0xFFEF4444))
                }
            },
            dismissButton = {
                TextButton(onClick = { showWipeDataDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Clear cache dialog
    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text("Clear Cache") },
            text = {
                Text("This will clear ${StorageHelper.formatSize(storageUsage.cacheSize)} of cached data. This may slow down the app temporarily as it rebuilds the cache.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearCacheDialog = false
                        viewModel.clearCache()
                    },
                    enabled = !isClearingCache
                ) {
                    if (isClearingCache) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFFF59E0B)
                        )
                    } else {
                        Text("Clear Cache", color = Color(0xFFF59E0B))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Clear media dialog
    if (showClearMediaDialog) {
        AlertDialog(
            onDismissRequest = { showClearMediaDialog = false },
            title = { Text("Clear Downloaded Media") },
            text = {
                Text("This will delete ${StorageHelper.formatSize(storageUsage.mediaSize)} of downloaded photos, videos, and audio files. You can re-download them from your conversations.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearMediaDialog = false
                        viewModel.clearMedia()
                    },
                    enabled = !isClearingCache
                ) {
                    if (isClearingCache) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFFEF4444)
                        )
                    } else {
                        Text("Clear Media", color = Color(0xFFEF4444))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearMediaDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Reset settings dialog
    if (showResetSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showResetSettingsDialog = false },
            title = { Text("Reset Settings") },
            text = {
                Text("This will reset all settings to their default values. Your account data, messages, and contacts will be preserved.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showResetSettingsDialog = false
                    viewModel.resetSettings()
                }) {
                    Text("Reset", color = Color(0xFFF59E0B))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetSettingsDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Lock timeout picker dialog
    if (showLockTimeoutPicker) {
        AlertDialog(
            onDismissRequest = { showLockTimeoutPicker = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Timer, null, tint = Color(0xFF8B5CF6))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Lock Timeout")
                }
            },
            text = {
                Column {
                    Text(
                        "Choose when to require authentication after the app goes to background.",
                        color = Color.Gray,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    viewModel.lockTimeoutOptions.forEach { (minutes, label) ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setLockTimeoutMinutes(minutes)
                                    showLockTimeoutPicker = false
                                },
                            shape = RoundedCornerShape(8.dp),
                            color = if (lockTimeoutMinutes == minutes) Color(0xFF3B82F6).copy(alpha = 0.2f) else Color.Transparent
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = lockTimeoutMinutes == minutes,
                                    onClick = {
                                        viewModel.setLockTimeoutMinutes(minutes)
                                        showLockTimeoutPicker = false
                                    },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = Color(0xFF3B82F6),
                                        unselectedColor = Color.Gray
                                    )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(label, color = Color.White)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showLockTimeoutPicker = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Biometric not available dialog
    if (showBiometricNotAvailableDialog) {
        AlertDialog(
            onDismissRequest = { showBiometricNotAvailableDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Fingerprint, null, tint = Color(0xFFF59E0B))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Biometric Not Available")
                }
            },
            text = {
                Text(BiometricHelper.getStatusDescription(biometricStatus))
            },
            confirmButton = {
                TextButton(onClick = { showBiometricNotAvailableDialog = false }) {
                    Text("OK", color = Color(0xFF3B82F6))
                }
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
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = titleColor)
                if (subtitle != null) {
                    Text(subtitle, color = Color.Gray, fontSize = 12.sp)
                }
            }
            if (onClick != null) {
                Icon(Icons.Default.ChevronRight, null, tint = Color.Gray)
            }
        }
    }
}

/**
 * Settings item with a toggle switch for boolean settings.
 */
@Composable
fun SettingsToggleItem(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    val contentAlpha = if (enabled) 1f else 0.5f

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.Gray.copy(alpha = 0.15f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = iconColor.copy(alpha = contentAlpha)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    color = Color.White.copy(alpha = contentAlpha),
                    fontWeight = FontWeight.Medium
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        color = Color.Gray.copy(alpha = contentAlpha),
                        fontSize = 12.sp
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF3B82F6),
                    uncheckedThumbColor = Color.Gray,
                    uncheckedTrackColor = Color.Gray.copy(alpha = 0.3f),
                    disabledCheckedThumbColor = Color.Gray,
                    disabledCheckedTrackColor = Color.Gray.copy(alpha = 0.3f),
                    disabledUncheckedThumbColor = Color.Gray.copy(alpha = 0.5f),
                    disabledUncheckedTrackColor = Color.Gray.copy(alpha = 0.15f)
                )
            )
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

/**
 * Storage usage card showing breakdown of app data usage.
 */
@Composable
fun StorageUsageCard(
    messagesSize: Long,
    mediaSize: Long,
    cacheSize: Long,
    totalSize: Long,
    isLoading: Boolean,
    onRefresh: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.Gray.copy(alpha = 0.15f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Storage,
                        contentDescription = null,
                        tint = Color(0xFF3B82F6)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            "Total Storage",
                            color = Color.White,
                            fontWeight = FontWeight.Medium
                        )
                        if (isLoading) {
                            Text(
                                "Calculating...",
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                        } else {
                            Text(
                                StorageHelper.formatSize(totalSize),
                                color = Color(0xFF3B82F6),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                IconButton(
                    onClick = onRefresh,
                    enabled = !isLoading
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF3B82F6)
                        )
                    } else {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = Color.Gray
                        )
                    }
                }
            }

            if (!isLoading) {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = Color.Gray.copy(alpha = 0.3f))
                Spacer(modifier = Modifier.height(12.dp))

                // Storage breakdown
                StorageBreakdownRow(
                    label = "Messages & Database",
                    size = messagesSize,
                    color = Color(0xFF3B82F6)
                )
                Spacer(modifier = Modifier.height(8.dp))
                StorageBreakdownRow(
                    label = "Media & Attachments",
                    size = mediaSize,
                    color = Color(0xFF8B5CF6)
                )
                Spacer(modifier = Modifier.height(8.dp))
                StorageBreakdownRow(
                    label = "Cache",
                    size = cacheSize,
                    color = Color(0xFFF59E0B)
                )

                // Storage bar
                if (totalSize > 0) {
                    Spacer(modifier = Modifier.height(16.dp))
                    StorageBar(
                        messagesSize = messagesSize,
                        mediaSize = mediaSize,
                        cacheSize = cacheSize,
                        totalSize = totalSize
                    )
                }
            }
        }
    }
}

@Composable
private fun StorageBreakdownRow(
    label: String,
    size: Long,
    color: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color, CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                label,
                color = Color.Gray,
                fontSize = 13.sp
            )
        }
        Text(
            StorageHelper.formatSizeCompact(size),
            color = Color.White,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun StorageBar(
    messagesSize: Long,
    mediaSize: Long,
    cacheSize: Long,
    totalSize: Long
) {
    val messagesPercent = if (totalSize > 0) messagesSize.toFloat() / totalSize else 0f
    val mediaPercent = if (totalSize > 0) mediaSize.toFloat() / totalSize else 0f
    val cachePercent = if (totalSize > 0) cacheSize.toFloat() / totalSize else 0f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .background(Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
    ) {
        if (messagesPercent > 0) {
            Box(
                modifier = Modifier
                    .weight(messagesPercent.coerceAtLeast(0.01f))
                    .fillMaxHeight()
                    .background(
                        Color(0xFF3B82F6),
                        RoundedCornerShape(
                            topStart = 4.dp,
                            bottomStart = 4.dp,
                            topEnd = if (mediaPercent == 0f && cachePercent == 0f) 4.dp else 0.dp,
                            bottomEnd = if (mediaPercent == 0f && cachePercent == 0f) 4.dp else 0.dp
                        )
                    )
            )
        }
        if (mediaPercent > 0) {
            Box(
                modifier = Modifier
                    .weight(mediaPercent.coerceAtLeast(0.01f))
                    .fillMaxHeight()
                    .background(
                        Color(0xFF8B5CF6),
                        RoundedCornerShape(
                            topStart = if (messagesPercent == 0f) 4.dp else 0.dp,
                            bottomStart = if (messagesPercent == 0f) 4.dp else 0.dp,
                            topEnd = if (cachePercent == 0f) 4.dp else 0.dp,
                            bottomEnd = if (cachePercent == 0f) 4.dp else 0.dp
                        )
                    )
            )
        }
        if (cachePercent > 0) {
            Box(
                modifier = Modifier
                    .weight(cachePercent.coerceAtLeast(0.01f))
                    .fillMaxHeight()
                    .background(
                        Color(0xFFF59E0B),
                        RoundedCornerShape(
                            topStart = if (messagesPercent == 0f && mediaPercent == 0f) 4.dp else 0.dp,
                            bottomStart = if (messagesPercent == 0f && mediaPercent == 0f) 4.dp else 0.dp,
                            topEnd = 4.dp,
                            bottomEnd = 4.dp
                        )
                    )
            )
        }
    }
}
