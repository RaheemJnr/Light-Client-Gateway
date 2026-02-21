package com.rjnr.pocketnode.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.composables.icons.lucide.Activity
import com.composables.icons.lucide.Circle
import com.composables.icons.lucide.Lock
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Wallet
import com.rjnr.pocketnode.ui.navigation.BottomTab
import com.rjnr.pocketnode.ui.screens.activity.ActivityScreen
import com.rjnr.pocketnode.ui.screens.dao.DaoScreen
import com.rjnr.pocketnode.ui.screens.home.HomeScreen
import com.rjnr.pocketnode.ui.screens.settings.SettingsScreen
import com.rjnr.pocketnode.ui.theme.CkbWalletTheme

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
    ) { paddingValues ->
        NavHost(
            navController = innerNav,
            startDestination = BottomTab.Home.route,
            modifier = Modifier
                .padding(bottom = paddingValues.calculateBottomPadding())
        ) {
            composable(BottomTab.Home.route) {
                HomeScreen(
                    onNavigateToSend = onNavigateToSend,
                    onNavigateToReceive = onNavigateToReceive,
                    onNavigateToBackup = onNavigateToBackup,
                    onNavigateToSettings = {
                        innerNav.navigate(BottomTab.Settings.route) {
                            popUpTo(innerNav.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
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
    BottomTab.Home -> Lucide.Wallet
    BottomTab.Activity -> Lucide.Activity
    BottomTab.DAO -> Lucide.Lock
    BottomTab.Settings -> Lucide.Settings
    else -> Lucide.Circle
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    CkbWalletTheme {
        MainScreen(
            onNavigateToSend = {},
            onNavigateToReceive = {},
            onNavigateToNodeStatus = {},
            onNavigateToBackup = {},
            onNavigateToSecuritySettings = {},
            onNavigateToImport = {}
        )
    }
}
