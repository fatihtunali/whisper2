package com.whisper2.app.ui.screens.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.whisper2.app.ui.viewmodels.AuthViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateAccountScreen(
    onBack: () -> Unit,
    onContinue: (String) -> Unit,
    viewModel: AuthViewModel = hiltViewModel()
) {
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !isLoading) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(padding)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(40.dp))

                // Header
                Icon(
                    imageVector = Icons.Default.Key,
                    contentDescription = null,
                    modifier = Modifier.size(50.dp),
                    tint = Color(0xFF3B82F6)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Create Your Account",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "We will generate a secure seed phrase for you.\nThis is the only way to recover your account.",
                    fontSize = 14.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )

                Spacer(modifier = Modifier.weight(0.5f))

                // Info cards
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    InfoCard(
                        icon = Icons.Default.Lock,
                        title = "End-to-End Encrypted",
                        description = "Your messages are encrypted on your device"
                    )
                    InfoCard(
                        icon = Icons.Default.Key,
                        title = "You Own Your Keys",
                        description = "Only you can access your account"
                    )
                    InfoCard(
                        icon = Icons.Default.Warning,
                        title = "Backup Required",
                        description = "Write down your seed phrase securely"
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Generate button
                Button(
                    onClick = {
                        isLoading = true
                        try {
                            val mnemonic = viewModel.generateMnemonic()
                            isLoading = false
                            onContinue(mnemonic)
                        } catch (e: Exception) {
                            isLoading = false
                            error = e.message ?: "Failed to generate seed phrase"
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp)
                        .padding(bottom = 32.dp)
                        .height(56.dp),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isLoading,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6))
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = Color.White
                        )
                    } else {
                        Text("Generate Seed Phrase", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // Error dialog
            if (error != null) {
                AlertDialog(
                    onDismissRequest = { error = null },
                    title = { Text("Error") },
                    text = { Text(error!!) },
                    confirmButton = {
                        TextButton(onClick = { error = null }) { Text("OK") }
                    }
                )
            }
        }
    }
}

@Composable
fun InfoCard(icon: ImageVector, title: String, description: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color.Gray.copy(alpha = 0.2f)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = Color(0xFF3B82F6)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(title, fontWeight = FontWeight.SemiBold, color = Color.White)
                Text(description, fontSize = 12.sp, color = Color.Gray)
            }
        }
    }
}
