package com.whisper2.app.ui.screens.main

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

sealed class MainTab(val route: String, val title: String, val icon: ImageVector) {
    object Chats : MainTab("chats", "Chats", Icons.AutoMirrored.Filled.Chat)
    object Groups : MainTab("groups", "Groups", Icons.Default.Groups)
    object Contacts : MainTab("contacts", "Contacts", Icons.Default.Contacts)
    object Settings : MainTab("settings", "Settings", Icons.Default.Settings)
}

@Composable
fun MainScreen(
    onNavigateToChat: (String) -> Unit,
    onNavigateToGroup: (String) -> Unit,
    onNavigateToProfile: () -> Unit,
    onNavigateToScanQr: () -> Unit,
    onNavigateToContactProfile: (String) -> Unit,
    onLogout: () -> Unit
) {
    val navController = rememberNavController()
    val tabs = listOf(MainTab.Chats, MainTab.Groups, MainTab.Contacts, MainTab.Settings)

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF1A1A1A)) {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                tabs.forEach { tab ->
                    NavigationBarItem(
                        icon = { Icon(tab.icon, contentDescription = tab.title) },
                        label = { Text(tab.title) },
                        selected = currentRoute == tab.route,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF3B82F6),
                            selectedTextColor = Color(0xFF3B82F6),
                            unselectedIconColor = Color.Gray,
                            unselectedTextColor = Color.Gray,
                            indicatorColor = Color(0xFF3B82F6).copy(alpha = 0.1f)
                        )
                    )
                }
            }
        },
        containerColor = Color.Black
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = MainTab.Chats.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(MainTab.Chats.route) {
                ChatsListScreen(onChatClick = onNavigateToChat)
            }
            composable(MainTab.Groups.route) {
                GroupsListScreen(onGroupClick = onNavigateToGroup)
            }
            composable(MainTab.Contacts.route) {
                ContactsListScreen(
                    onContactClick = onNavigateToChat,
                    onScanQrClick = onNavigateToScanQr,
                    onContactProfileClick = onNavigateToContactProfile
                )
            }
            composable(MainTab.Settings.route) {
                SettingsScreen(
                    onProfileClick = onNavigateToProfile,
                    onLogout = onLogout
                )
            }
        }
    }
}
