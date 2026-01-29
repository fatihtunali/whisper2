package com.whisper2.app.ui.navigation

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.google.gson.Gson
import com.whisper2.app.data.network.ws.WsConnectionState
import com.whisper2.app.services.auth.AuthState
import com.whisper2.app.services.contacts.QrCodeData
import com.whisper2.app.services.contacts.QrParseResult
import com.whisper2.app.ui.screens.auth.WelcomeScreen
import com.whisper2.app.ui.screens.auth.CreateAccountScreen
import com.whisper2.app.ui.screens.auth.SeedPhraseScreen
import com.whisper2.app.ui.screens.contacts.AddContactScreen
import com.whisper2.app.ui.screens.contacts.ContactProfileScreen
import com.whisper2.app.ui.screens.contacts.ScanQrScreen
import com.whisper2.app.ui.screens.main.MainScreen
import com.whisper2.app.ui.screens.main.ChatScreen
import com.whisper2.app.ui.screens.main.GroupChatScreen
import com.whisper2.app.ui.screens.calls.CallScreen
import com.whisper2.app.ui.NotificationData
import com.whisper2.app.ui.viewmodels.AddContactState
import com.whisper2.app.ui.viewmodels.MainViewModel
import com.whisper2.app.ui.viewmodels.AuthViewModel
import com.whisper2.app.ui.viewmodels.ContactsViewModel

sealed class Screen(val route: String) {
    object Welcome : Screen("welcome")
    object CreateAccount : Screen("create_account")
    object SeedPhrase : Screen("seed_phrase/{mnemonic}") {
        fun createRoute(mnemonic: String) = "seed_phrase/${mnemonic.replace(" ", "_")}"
    }
    object RecoverAccount : Screen("recover_account")
    object Main : Screen("main")
    object Chat : Screen("chat/{peerId}") {
        fun createRoute(peerId: String) = "chat/$peerId"
    }
    object GroupChat : Screen("group_chat/{groupId}") {
        fun createRoute(groupId: String) = "group_chat/$groupId"
    }
    object ScanQr : Screen("scan_qr")
    object AddContact : Screen("add_contact/{qrDataJson}") {
        fun createRoute(qrData: QrCodeData): String {
            val json = Gson().toJson(qrData)
            return "add_contact/${Uri.encode(json)}"
        }
    }
    object ContactProfile : Screen("contact_profile/{peerId}") {
        fun createRoute(peerId: String) = "contact_profile/$peerId"
    }
    object Call : Screen("call/{peerId}/{isVideo}") {
        fun createRoute(peerId: String, isVideo: Boolean) = "call/$peerId/$isVideo"
    }
    object IncomingCall : Screen("incoming_call/{callerName}/{callerId}/{isVideo}") {
        fun createRoute(callerName: String, callerId: String, isVideo: Boolean) =
            "incoming_call/${Uri.encode(callerName)}/$callerId/$isVideo"
    }
}

@Composable
fun WhisperNavigation(
    authState: AuthState,
    connectionState: WsConnectionState,
    notificationData: NotificationData? = null,
    onNotificationHandled: () -> Unit = {}
) {
    when (authState) {
        is AuthState.Unauthenticated -> AuthNavigation()
        is AuthState.Authenticating -> LoadingScreen()
        is AuthState.Authenticated -> MainNavigation(
            notificationData = notificationData,
            onNotificationHandled = onNotificationHandled
        )
        is AuthState.Error -> ErrorScreen(authState.message)
    }
}

@Composable
fun AuthNavigation() {
    val navController = rememberNavController()
    val authViewModel: AuthViewModel = hiltViewModel()

    NavHost(navController = navController, startDestination = Screen.Welcome.route) {
        composable(Screen.Welcome.route) {
            WelcomeScreen(
                onCreateAccount = {
                    // Generate mnemonic immediately and go to seed phrase screen
                    val mnemonic = authViewModel.generateMnemonic()
                    navController.navigate(Screen.SeedPhrase.createRoute(mnemonic))
                },
                onRecoverAccount = { navController.navigate(Screen.RecoverAccount.route) }
            )
        }

        composable(
            route = Screen.SeedPhrase.route,
            arguments = listOf(navArgument("mnemonic") { type = NavType.StringType })
        ) { backStackEntry ->
            val mnemonic = backStackEntry.arguments?.getString("mnemonic")?.replace("_", " ") ?: ""
            var isLoading by remember { mutableStateOf(false) }

            SeedPhraseScreen(
                mnemonic = mnemonic,
                isNewAccount = true,
                isLoading = isLoading,
                onBack = { navController.popBackStack() },
                onConfirm = {
                    isLoading = true
                    authViewModel.createAccount(mnemonic) { result ->
                        isLoading = false
                        // Auth state will automatically change and navigate to main
                    }
                }
            )
        }

        composable(Screen.RecoverAccount.route) {
            RecoverAccountScreen(
                onBack = { navController.popBackStack() },
                onRecover = { mnemonic ->
                    authViewModel.recoverAccount(mnemonic) { result ->
                        // Auth state will automatically change and navigate to main
                    }
                }
            )
        }
    }
}

@Composable
fun MainNavigation(
    notificationData: NotificationData? = null,
    onNotificationHandled: () -> Unit = {}
) {
    val navController = rememberNavController()
    val authViewModel: AuthViewModel = hiltViewModel()
    val contactsViewModel: ContactsViewModel = hiltViewModel()
    val mainViewModel: MainViewModel = hiltViewModel()  // This ensures CallService is created
    val gson = remember { Gson() }

    // Call state is managed by Telecom - no navigation needed for incoming calls
    // The Telecom system UI handles answer/decline, WebRTC runs in background

    // Handle notification click - navigate to appropriate screen
    LaunchedEffect(notificationData) {
        notificationData?.let { data ->
            when {
                data.isIncomingCall -> {
                    // Incoming calls are handled by Telecom - no navigation needed
                }
                data.conversationId != null -> {
                    // Navigate to chat screen
                    navController.navigate(Screen.Chat.createRoute(data.conversationId))
                }
            }
            onNotificationHandled()
        }
    }

    NavHost(navController = navController, startDestination = Screen.Main.route) {
        composable(Screen.Main.route) {
            MainScreen(
                onNavigateToChat = { peerId ->
                    navController.navigate(Screen.Chat.createRoute(peerId))
                },
                onNavigateToGroup = { groupId ->
                    navController.navigate(Screen.GroupChat.createRoute(groupId))
                },
                onNavigateToProfile = {
                    // Navigate to profile/settings
                },
                onNavigateToScanQr = {
                    navController.navigate(Screen.ScanQr.route)
                },
                onNavigateToContactProfile = { peerId ->
                    navController.navigate(Screen.ContactProfile.createRoute(peerId))
                },
                onLogout = {
                    authViewModel.logout()
                }
            )
        }

        composable(Screen.ScanQr.route) {
            ScanQrScreen(
                onBack = { navController.popBackStack() },
                onQrScanned = { qrData ->
                    navController.navigate(Screen.AddContact.createRoute(qrData)) {
                        popUpTo(Screen.ScanQr.route) { inclusive = true }
                    }
                },
                parseQrCode = { qrContent -> contactsViewModel.parseQrCode(qrContent) }
            )
        }

        composable(
            route = Screen.AddContact.route,
            arguments = listOf(navArgument("qrDataJson") { type = NavType.StringType })
        ) { backStackEntry ->
            val qrDataJson = backStackEntry.arguments?.getString("qrDataJson") ?: ""
            val qrData = try {
                gson.fromJson(Uri.decode(qrDataJson), QrCodeData::class.java)
            } catch (e: Exception) {
                null
            }

            val addContactState by contactsViewModel.addContactState.collectAsState()

            LaunchedEffect(addContactState) {
                if (addContactState is AddContactState.Success) {
                    contactsViewModel.clearScannedQrData()
                    navController.popBackStack(Screen.Main.route, inclusive = false)
                }
            }

            if (qrData != null) {
                AddContactScreen(
                    qrData = qrData,
                    onBack = {
                        contactsViewModel.clearScannedQrData()
                        navController.popBackStack()
                    },
                    onConfirm = { nickname ->
                        contactsViewModel.addContactFromQr(qrData, nickname)
                    },
                    isLoading = addContactState is AddContactState.Loading
                )
            } else {
                // Invalid QR data, go back
                LaunchedEffect(Unit) {
                    navController.popBackStack()
                }
            }
        }

        composable(
            route = Screen.ContactProfile.route,
            arguments = listOf(navArgument("peerId") { type = NavType.StringType })
        ) { backStackEntry ->
            val peerId = backStackEntry.arguments?.getString("peerId") ?: ""
            ContactProfileScreen(
                peerId = peerId,
                onBack = { navController.popBackStack() },
                onNavigateToChat = {
                    navController.navigate(Screen.Chat.createRoute(peerId)) {
                        popUpTo(Screen.ContactProfile.route) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Screen.Chat.route,
            arguments = listOf(navArgument("peerId") { type = NavType.StringType })
        ) { backStackEntry ->
            val peerId = backStackEntry.arguments?.getString("peerId") ?: ""
            ChatScreen(
                peerId = peerId,
                onBack = { navController.popBackStack() },
                onVoiceCall = {
                    navController.navigate(Screen.Call.createRoute(peerId, false))
                },
                onVideoCall = {
                    navController.navigate(Screen.Call.createRoute(peerId, true))
                },
                onNavigateToProfile = {
                    navController.navigate(Screen.ContactProfile.createRoute(peerId))
                }
            )
        }

        composable(
            route = Screen.GroupChat.route,
            arguments = listOf(navArgument("groupId") { type = NavType.StringType })
        ) { backStackEntry ->
            val groupId = backStackEntry.arguments?.getString("groupId") ?: ""
            GroupChatScreen(
                groupId = groupId,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.Call.route,
            arguments = listOf(
                navArgument("peerId") { type = NavType.StringType },
                navArgument("isVideo") { type = NavType.BoolType }
            )
        ) { backStackEntry ->
            val peerId = backStackEntry.arguments?.getString("peerId") ?: ""
            val isVideo = backStackEntry.arguments?.getBoolean("isVideo") ?: false

            CallScreen(
                peerId = peerId,
                isVideo = isVideo,
                isOutgoing = true,
                onCallEnded = { navController.popBackStack() }
            )
        }

        // Incoming calls are handled by Telecom system UI
        // No custom IncomingCall screen needed
    }
}

@Composable
fun RecoverAccountScreen(
    onBack: () -> Unit,
    onRecover: (String) -> Unit
) {
    var mnemonic by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Recover Account", style = MaterialTheme.typography.headlineLarge)
        Spacer(modifier = Modifier.height(16.dp))
        Text("Enter your 12 or 24 word recovery phrase", style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = mnemonic,
            onValueChange = { mnemonic = it },
            label = { Text("Seed Phrase") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { onRecover(mnemonic) },
            modifier = Modifier.fillMaxWidth(),
            enabled = mnemonic.split(" ").size >= 12
        ) {
            Text("Recover")
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(onClick = onBack) {
            Text("Go Back")
        }
    }
}

@Composable
fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun ErrorScreen(message: String) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text("Error: $message", color = MaterialTheme.colorScheme.error)
    }
}
