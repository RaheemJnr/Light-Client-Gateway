package com.rjnr.pocketnode.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.rjnr.pocketnode.ui.navigation.BottomTab
import com.rjnr.pocketnode.ui.screens.activity.ActivityScreen
import com.rjnr.pocketnode.ui.screens.dao.DaoScreen
import com.rjnr.pocketnode.ui.screens.home.HomeScreen
import com.rjnr.pocketnode.ui.screens.settings.SettingsScreen

@Composable
fun MainScreen(
    onNavigateToSend: () -> Unit,
    onNavigateToReceive: () -> Unit,
    onNavigateToNodeStatus: () -> Unit,
    onNavigateToBackup: () -> Unit,
    onNavigateToSecuritySettings: () -> Unit,
    onNavigateToImport: () -> Unit,
) {
    val innerNav = rememberNavController()
    val navBackStackEntry by innerNav.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                BottomTab.entries.forEach { tab ->
                    val selected = currentRoute == tab.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            innerNav.navigate(tab.route) {
                                popUpTo(innerNav.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = tabIcon(tab, selected),
                                contentDescription = tab.label
                            )
                        },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = innerNav,
            startDestination = BottomTab.Home.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(BottomTab.Home.route) {
                HomeScreen(
                    onNavigateToSend = onNavigateToSend,
                    onNavigateToReceive = onNavigateToReceive,
                )
            }
            composable(BottomTab.Activity.route) {
                ActivityScreen()
            }
            composable(BottomTab.DAO.route) {
                DaoScreen()
            }
            composable(BottomTab.Settings.route) {
                SettingsScreen(
                    onNavigateToNodeStatus = onNavigateToNodeStatus,
                    onNavigateToBackup = onNavigateToBackup,
                    onNavigateToSecuritySettings = onNavigateToSecuritySettings,
                    onNavigateToImport = onNavigateToImport,
                )
            }
        }
    }
}

private fun tabIcon(tab: BottomTab, selected: Boolean): ImageVector = when (tab) {
    BottomTab.Home     -> if (selected) Icons.Filled.AccountBalanceWallet else Icons.Outlined.AccountBalanceWallet
    BottomTab.Activity -> if (selected) Icons.Filled.History               else Icons.Outlined.History
    BottomTab.DAO      -> if (selected) Icons.Filled.Lock                  else Icons.Outlined.Lock
    BottomTab.Settings -> if (selected) Icons.Filled.Settings              else Icons.Outlined.Settings
    else               -> Icons.Outlined.Circle
}
