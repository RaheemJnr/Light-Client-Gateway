package com.rjnr.pocketnode.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.rjnr.pocketnode.ui.screens.auth.AuthScreen
import com.rjnr.pocketnode.ui.screens.auth.PinEntryScreen
import com.rjnr.pocketnode.ui.screens.auth.PinMode
import com.rjnr.pocketnode.ui.screens.home.HomeScreen
import com.rjnr.pocketnode.ui.screens.onboarding.MnemonicBackupScreen
import com.rjnr.pocketnode.ui.screens.onboarding.MnemonicImportScreen
import com.rjnr.pocketnode.ui.screens.receive.ReceiveScreen
import com.rjnr.pocketnode.ui.screens.scanner.QrScannerScreen
import com.rjnr.pocketnode.ui.screens.send.SendScreen
import com.rjnr.pocketnode.ui.screens.settings.SecuritySettingsScreen

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Send : Screen("send")
    object Receive : Screen("receive")
    object Scanner : Screen("scanner")
    object NodeStatus : Screen("node_status")
    object Onboarding : Screen("onboarding")
    object MnemonicBackup : Screen("mnemonic_backup")
    object MnemonicImport : Screen("mnemonic_import")
    object Auth : Screen("auth")
    object PinEntry : Screen("pin_entry/{mode}") {
        fun createRoute(mode: String) = "pin_entry/$mode"
    }
    object SecuritySettings : Screen("security_settings")
}

@Composable
fun CkbNavGraph(
    navController: NavHostController,
    startDestination: String = Screen.Home.route
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Onboarding.route) {
            com.rjnr.pocketnode.ui.screens.onboarding.OnboardingScreen(
                onNavigateToHome = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                },
                onNavigateToBackup = {
                    navController.navigate(Screen.MnemonicBackup.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                },
                onNavigateToImport = {
                    navController.navigate(Screen.MnemonicImport.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.MnemonicBackup.route) {
            MnemonicBackupScreen(
                onNavigateToHome = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.MnemonicBackup.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.MnemonicImport.route) {
            MnemonicImportScreen(
                onNavigateToHome = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.MnemonicImport.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Auth.route) {
            AuthScreen(
                onAuthSuccess = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Auth.route) { inclusive = true }
                    }
                },
                onNavigateToPinVerify = {
                    navController.navigate(Screen.PinEntry.createRoute("verify"))
                }
            )
        }

        composable(
            route = Screen.PinEntry.route,
            arguments = listOf(navArgument("mode") { type = NavType.StringType })
        ) { backStackEntry ->
            val modeString = backStackEntry.arguments?.getString("mode") ?: "verify"
            val mode = PinMode.valueOf(modeString.uppercase())
            val setupPin = navController.previousBackStackEntry
                ?.savedStateHandle
                ?.get<String>("setup_pin")

            PinEntryScreen(
                mode = mode,
                setupPin = setupPin,
                onPinComplete = { enteredPin ->
                    when (mode) {
                        PinMode.SETUP -> {
                            navController.currentBackStackEntry
                                ?.savedStateHandle
                                ?.set("setup_pin", enteredPin)
                            navController.navigate(Screen.PinEntry.createRoute("confirm"))
                        }
                        PinMode.CONFIRM -> {
                            navController.popBackStack(
                                Screen.SecuritySettings.route,
                                inclusive = false
                            )
                        }
                        PinMode.VERIFY -> {
                            navController.navigate(Screen.Home.route) {
                                popUpTo(Screen.Auth.route) { inclusive = true }
                            }
                        }
                    }
                },
                onForgotPin = {
                    navController.navigate(Screen.MnemonicImport.route)
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.SecuritySettings.route) {
            SecuritySettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToPinSetup = {
                    navController.navigate(Screen.PinEntry.createRoute("setup"))
                }
            )
        }

        composable(Screen.Home.route) {
            HomeScreen(
                onNavigateToSend = { navController.navigate(Screen.Send.route) },
                onNavigateToReceive = { navController.navigate(Screen.Receive.route) },
                onNavigateToStatus = { navController.navigate(Screen.NodeStatus.route) },
                onNavigateToBackup = { navController.navigate(Screen.MnemonicBackup.route) },
                onNavigateToSecuritySettings = {
                    navController.navigate(Screen.SecuritySettings.route)
                }
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
            com.rjnr.pocketnode.ui.screens.status.NodeStatusScreen(
                navController = navController
            )
        }
    }
}
