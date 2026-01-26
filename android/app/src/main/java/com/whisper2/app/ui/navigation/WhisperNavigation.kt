package com.whisper2.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.whisper2.app.ui.screens.auth.AuthScreen
import com.whisper2.app.ui.screens.chat.ChatScreen
import com.whisper2.app.ui.screens.conversations.ConversationsScreen
import com.whisper2.app.ui.screens.settings.SettingsScreen

/**
 * Navigation routes
 */
object Routes {
    const val AUTH = "auth"
    const val CONVERSATIONS = "conversations"
    const val CHAT = "chat/{conversationId}"
    const val SETTINGS = "settings"

    fun chat(conversationId: String) = "chat/$conversationId"
}

/**
 * Main navigation host
 * Start destination depends on auth state
 */
@Composable
fun WhisperNavHost(
    navController: NavHostController,
    startDestination: String
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        // Auth screen (register/recover)
        composable(Routes.AUTH) {
            AuthScreen(
                onAuthSuccess = {
                    navController.navigate(Routes.CONVERSATIONS) {
                        popUpTo(Routes.AUTH) { inclusive = true }
                    }
                }
            )
        }

        // Conversation list
        composable(Routes.CONVERSATIONS) {
            ConversationsScreen(
                onConversationClick = { conversationId ->
                    navController.navigate(Routes.chat(conversationId))
                },
                onSettingsClick = {
                    navController.navigate(Routes.SETTINGS)
                }
            )
        }

        // Chat screen
        composable(
            route = Routes.CHAT,
            arguments = listOf(
                navArgument("conversationId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val conversationId = backStackEntry.arguments?.getString("conversationId") ?: ""
            ChatScreen(
                conversationId = conversationId,
                onBackClick = { navController.popBackStack() }
            )
        }

        // Settings
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBackClick = { navController.popBackStack() },
                onLogout = {
                    navController.navigate(Routes.AUTH) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
    }
}
