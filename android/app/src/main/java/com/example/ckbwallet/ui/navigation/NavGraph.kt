package com.example.ckbwallet.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.ckbwallet.ui.screens.home.HomeScreen
import com.example.ckbwallet.ui.screens.receive.ReceiveScreen
import com.example.ckbwallet.ui.screens.scanner.QrScannerScreen
import com.example.ckbwallet.ui.screens.send.SendScreen

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Send : Screen("send")
    object Receive : Screen("receive")
    object Scanner : Screen("scanner")
    object NodeStatus : Screen("node_status")
}

@Composable
fun CkbNavGraph(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                onNavigateToSend = { navController.navigate(Screen.Send.route) },
                onNavigateToReceive = { navController.navigate(Screen.Receive.route) },
                onNavigateToStatus = { navController.navigate(Screen.NodeStatus.route) }
            )
        }

        composable(Screen.Send.route) {
            SendScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToScanner = { navController.navigate(Screen.Scanner.route) },
                scannedAddress = navController.currentBackStackEntry
                    ?.savedStateHandle
                    ?.get<String>("scanned_address")
            )
        }

        composable(Screen.Receive.route) {
            ReceiveScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Scanner.route) {
            QrScannerScreen(
                onScanResult = { address ->
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("scanned_address", address)
                    navController.popBackStack()
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.NodeStatus.route) {
            com.example.ckbwallet.ui.screens.status.NodeStatusScreen(
                navController = navController
            )
        }
    }
}
