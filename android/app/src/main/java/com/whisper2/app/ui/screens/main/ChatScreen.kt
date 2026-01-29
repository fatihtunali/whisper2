package com.whisper2.app.ui.screens.main

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.android.gms.location.LocationServices
import com.whisper2.app.ui.components.AttachmentType
import com.whisper2.app.ui.components.MessageBubble
import com.whisper2.app.ui.components.MessageInputBar
import com.whisper2.app.ui.theme.*
import com.whisper2.app.ui.viewmodels.ChatViewModel
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    peerId: String,
    onBack: () -> Unit,
    onVoiceCall: () -> Unit,
    onVideoCall: () -> Unit,
    onNavigateToProfile: () -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages by viewModel.messages.collectAsState()
    val contactName by viewModel.contactName.collectAsState()
    val canSendMessages by viewModel.canSendMessages.collectAsState()
    val isTyping by viewModel.peerIsTyping.collectAsState()
    val error by viewModel.error.collectAsState()
    var messageText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Voice recording state
    var isRecording by remember { mutableStateOf(false) }
    var recordingStartTime by remember { mutableStateOf(0L) }
    var mediaRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var audioFilePath by remember { mutableStateOf<String?>(null) }

    // Pending attachment state (for preview before sending)
    var pendingAttachmentUri by remember { mutableStateOf<String?>(null) }
    var pendingAttachmentType by remember { mutableStateOf<String?>(null) }

    // Photo/Video picker - stores URI for preview instead of sending immediately
    val photoVideoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            pendingAttachmentUri = it.toString()
            pendingAttachmentType = "photo"
        }
    }

    // File picker - stores URI for preview
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            pendingAttachmentUri = it.toString()
            pendingAttachmentType = "file"
        }
    }

    // Location permission launcher
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // Get location and send
            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
            try {
                fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                    if (location != null) {
                        viewModel.sendLocationMessage(location.latitude, location.longitude)
                        Toast.makeText(context, "Sending location...", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Unable to get location", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: SecurityException) {
                Toast.makeText(context, "Location permission denied", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Location permission required", Toast.LENGTH_SHORT).show()
        }
    }

    // Audio permission launcher
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // Start recording
            startVoiceRecording(context) { recorder, path ->
                mediaRecorder = recorder
                audioFilePath = path
                recordingStartTime = System.currentTimeMillis()
                isRecording = true
            }
        } else {
            Toast.makeText(context, "Microphone permission required", Toast.LENGTH_SHORT).show()
        }
    }

    // Cleanup recorder on dispose
    DisposableEffect(Unit) {
        onDispose {
            mediaRecorder?.release()
        }
    }

    LaunchedEffect(peerId) {
        viewModel.loadConversation(peerId)
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(contactName, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        if (isTyping) {
                            Text("typing...", color = PrimaryBlue, fontSize = 12.sp)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = PrimaryBlue)
                    }
                },
                actions = {
                    IconButton(onClick = onVoiceCall, enabled = canSendMessages) {
                        Icon(Icons.Default.Phone, "Voice Call", tint = if (canSendMessages) CallAccept else TextDisabled)
                    }
                    IconButton(onClick = onVideoCall, enabled = canSendMessages) {
                        Icon(Icons.Default.Videocam, "Video Call", tint = if (canSendMessages) PrimaryBlue else TextDisabled)
                    }
                    // More options menu like iOS
                    var showMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, "More", tint = TextSecondary)
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            containerColor = MetalSlate
                        ) {
                            DropdownMenuItem(
                                text = { Text("View Profile", color = TextPrimary) },
                                onClick = {
                                    showMenu = false
                                    onNavigateToProfile()
                                },
                                leadingIcon = { Icon(Icons.Default.Person, null, tint = TextSecondary) }
                            )
                            DropdownMenuItem(
                                text = { Text("Search Messages", color = TextPrimary) },
                                onClick = { showMenu = false },
                                leadingIcon = { Icon(Icons.Default.Search, null, tint = TextSecondary) }
                            )
                            DropdownMenuItem(
                                text = { Text("Mute Notifications", color = TextPrimary) },
                                onClick = { showMenu = false },
                                leadingIcon = { Icon(Icons.Default.NotificationsOff, null, tint = TextSecondary) }
                            )
                            HorizontalDivider(color = BorderDefault)
                            DropdownMenuItem(
                                text = { Text("Clear Chat", color = StatusError) },
                                onClick = { showMenu = false },
                                leadingIcon = { Icon(Icons.Default.Delete, null, tint = StatusError) }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MetalNavy)
            )
        },
        containerColor = MetalDark
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()  // This makes the content adjust when keyboard appears
        ) {
            // Key missing warning
            if (!canSendMessages) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = StatusWarningMuted.copy(alpha = 0.3f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, null, tint = StatusWarning, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Scan contact's QR code to enable messaging",
                            color = StatusWarning,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { /* Scan QR */ }) {
                            Text("Scan", color = PrimaryBlue)
                        }
                    }
                }
            }

            // Error display
            error?.let { err ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = StatusErrorMuted.copy(alpha = 0.3f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Error, null, tint = StatusError, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(err, color = StatusError, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { viewModel.clearError() }) {
                            Text("Dismiss", color = TextTertiary)
                        }
                    }
                }
            }

            // Messages
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        onDelete = { forEveryone -> viewModel.deleteMessage(message.id, forEveryone) }
                    )
                }
            }

            // Typing indicator
            if (isTyping) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TypingIndicator()
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("$contactName is typing...", color = PrimaryBlue, fontSize = 12.sp)
                }
            }

            // Attachment preview (photo/video/file)
            pendingAttachmentUri?.let { uri ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MetalSurface1.copy(alpha = 0.95f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Preview thumbnail
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MetalSurface2),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (pendingAttachmentType == "photo") Icons.Default.Image else Icons.Default.AttachFile,
                                contentDescription = null,
                                tint = PrimaryBlue,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        // File info
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (pendingAttachmentType == "photo") "Photo/Video" else "File",
                                color = TextPrimary,
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "Ready to send",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }

                        // Cancel button
                        TextButton(
                            onClick = {
                                pendingAttachmentUri = null
                                pendingAttachmentType = null
                            }
                        ) {
                            Text("Cancel", color = TextTertiary)
                        }

                        // Send button
                        TextButton(
                            onClick = {
                                pendingAttachmentUri?.let { attachmentUri ->
                                    viewModel.sendAttachment(attachmentUri)
                                    Toast.makeText(context, "Sending attachment...", Toast.LENGTH_SHORT).show()
                                }
                                pendingAttachmentUri = null
                                pendingAttachmentType = null
                            }
                        ) {
                            Text("Send", color = PrimaryBlue, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Voice recording indicator
            if (isRecording) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = StatusError.copy(alpha = 0.2f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            null,
                            tint = StatusError,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            "Recording...",
                            color = StatusError,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        TextButton(
                            onClick = {
                                // Cancel recording
                                mediaRecorder?.stop()
                                mediaRecorder?.release()
                                mediaRecorder = null
                                audioFilePath?.let { File(it).delete() }
                                isRecording = false
                            }
                        ) {
                            Text("Cancel", color = TextTertiary)
                        }
                        TextButton(
                            onClick = {
                                // Stop and send recording
                                mediaRecorder?.stop()
                                mediaRecorder?.release()
                                mediaRecorder = null
                                isRecording = false
                                val duration = System.currentTimeMillis() - recordingStartTime
                                audioFilePath?.let { path ->
                                    viewModel.sendVoiceMessage(path, duration)
                                    Toast.makeText(context, "Sending voice message...", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Text("Send", color = PrimaryBlue, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Input bar
            MessageInputBar(
                text = messageText,
                onTextChange = {
                    messageText = it
                    viewModel.onTyping()
                },
                onSend = {
                    viewModel.sendMessage(messageText)
                    messageText = ""
                },
                onAttachment = { type ->
                    when (type) {
                        AttachmentType.PHOTO_VIDEO -> {
                            photoVideoPicker.launch("image/*")
                        }
                        AttachmentType.FILE -> {
                            filePicker.launch("*/*")
                        }
                    }
                },
                onVoiceMessage = {
                    if (isRecording) {
                        // Stop and send recording
                        mediaRecorder?.stop()
                        mediaRecorder?.release()
                        mediaRecorder = null
                        isRecording = false
                        val duration = System.currentTimeMillis() - recordingStartTime
                        audioFilePath?.let { path ->
                            viewModel.sendVoiceMessage(path, duration)
                            Toast.makeText(context, "Sending voice message...", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        // Check permission and start recording
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                            == PackageManager.PERMISSION_GRANTED) {
                            startVoiceRecording(context) { recorder, path ->
                                mediaRecorder = recorder
                                audioFilePath = path
                                recordingStartTime = System.currentTimeMillis()
                                isRecording = true
                            }
                        } else {
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                },
                onLocation = {
                    // Check permission and get location
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED) {
                        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
                        try {
                            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                                if (location != null) {
                                    viewModel.sendLocationMessage(location.latitude, location.longitude)
                                    Toast.makeText(context, "Sending location...", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Unable to get location. Try again.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (e: SecurityException) {
                            Toast.makeText(context, "Location permission denied", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                },
                isEnabled = canSendMessages && !isRecording
            )
        }
    }
}

@Composable
fun TypingIndicator() {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { index ->
            val alpha by remember { mutableFloatStateOf(0.4f + (index * 0.2f)) }
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(PrimaryBlue.copy(alpha = alpha), CircleShape)
            )
        }
    }
}

/**
 * Start voice recording using MediaRecorder.
 */
private fun startVoiceRecording(
    context: android.content.Context,
    onStarted: (MediaRecorder, String) -> Unit
) {
    try {
        val audioDir = File(context.cacheDir, "voice_messages")
        if (!audioDir.exists()) {
            audioDir.mkdirs()
        }
        val audioFile = File(audioDir, "voice_${System.currentTimeMillis()}.m4a")
        val audioPath = audioFile.absolutePath

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        recorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128000)
            setAudioSamplingRate(44100)
            setOutputFile(audioPath)
            prepare()
            start()
        }

        onStarted(recorder, audioPath)
    } catch (e: Exception) {
        Toast.makeText(context, "Failed to start recording: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
