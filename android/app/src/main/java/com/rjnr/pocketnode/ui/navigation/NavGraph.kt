package com.rjnr.pocketnode.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.hilt.navigation.compose.hiltViewModel
import com.rjnr.pocketnode.ui.MainScreen
import com.rjnr.pocketnode.ui.screens.auth.AuthScreen
import com.rjnr.pocketnode.ui.screens.auth.PinEntryScreen
import com.rjnr.pocketnode.ui.screens.auth.PinMode
import com.rjnr.pocketnode.ui.screens.onboarding.MnemonicBackupScreen
import com.rjnr.pocketnode.ui.screens.onboarding.MnemonicImportScreen
import com.rjnr.pocketnode.ui.screens.receive.ReceiveScreen
import com.rjnr.pocketnode.ui.screens.scanner.QrScannerScreen
import com.rjnr.pocketnode.ui.screens.send.SendScreen
import com.rjnr.pocketnode.ui.screens.settings.SecuritySettingsScreen
import com.rjnr.pocketnode.ui.screens.settings.SecuritySettingsViewModel
import com.rjnr.pocketnode.ui.screens.recovery.RecoveryScreen
import androidx.compose.runtime.collectAsState
import com.rjnr.pocketnode.ui.screens.security.SecurityChecklistScreen
import com.rjnr.pocketnode.ui.screens.security.SecurityChecklistViewModel
import com.rjnr.pocketnode.ui.screens.security.MnemonicVerifyScreen
import com.rjnr.pocketnode.ui.screens.wallet.AddWalletScreen
import com.rjnr.pocketnode.ui.screens.wallet.WalletDetailScreen
import com.rjnr.pocketnode.ui.screens.wallet.WalletManagerScreen

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Main : Screen("main")
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
    object Recovery : Screen("recovery")
    object SecurityChecklist : Screen("security_checklist")
    object MnemonicVerify : Screen("mnemonic_verify")
    object WalletManager : Screen("wallet_manager")
    object WalletDetail : Screen("wallet_detail/{walletId}") {
        fun createRoute(walletId: String) = "wallet_detail/$walletId"
    }
    object AddWallet : Screen("add_wallet")
}

sealed class BottomTab(val route: String, val label: String) {
    object Home     : BottomTab("tab_home",     "Wallet")
    object Activity : BottomTab("tab_activity", "Activity")
    object DAO      : BottomTab("tab_dao",      "DAO")
    object Settings : BottomTab("tab_settings", "Settings")

    companion object {
        val entries = listOf(Home, Activity, DAO, Settings)
    }
}

@Composable
fun CkbNavGraph(
    navController: NavHostController,
    startDestination: String = Screen.Onboarding.route
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Onboarding.route) {
            com.rjnr.pocketnode.ui.screens.onboarding.OnboardingScreen(
                onNavigateToHome = {
                    navController.navigate(Screen.Main.route) {
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
                    navController.navigate(Screen.Main.route) {
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
                    navController.navigate(Screen.Main.route) {
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
                    navController.navigate(Screen.Main.route) {
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
            val mode = runCatching { PinMode.valueOf(modeString.uppercase()) }
                .getOrDefault(PinMode.VERIFY)
            val setupPin = navController.previousBackStackEntry
                ?.savedStateHandle
                ?.get<String>("setup_pin")

            // If CONFIRM mode but setupPin was lost (e.g. process death), go back to SETUP
            if (mode == PinMode.CONFIRM && setupPin == null) {
                LaunchedEffect(Unit) { navController.popBackStack() }
                return@composable
            }

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
                            val previousRoute = navController.previousBackStackEntry
                                ?.destination?.route
                            when (previousRoute) {
                                Screen.SecuritySettings.route -> {
                                    navController.previousBackStackEntry
                                        ?.savedStateHandle
                                        ?.set("pin_verified", true)
                                    navController.popBackStack()
                                }
                                Screen.Send.route -> {
                                    navController.previousBackStackEntry
                                        ?.savedStateHandle
                                        ?.set("send_pin_verified", true)
                                    navController.popBackStack()
                                }
                                Screen.Main.route -> {
                                    navController.previousBackStackEntry
                                        ?.savedStateHandle
                                        ?.set("dao_pin_verified", true)
                                    navController.popBackStack()
                                }
                                else -> {
                                    navController.navigate(Screen.Main.route) {
                                        popUpTo(Screen.Auth.route) { inclusive = true }
                                    }
                                }
                            }
                        }
                    }
                },
                onForgotPin = {
                    navController.navigate(Screen.MnemonicImport.route) {
                        popUpTo(Screen.Auth.route) { inclusive = true }
                    }
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.SecuritySettings.route) { backStackEntry ->
            val viewModel: SecuritySettingsViewModel = hiltViewModel()

            DisposableEffect(backStackEntry) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        viewModel.refreshState()
                    }
                }
                backStackEntry.lifecycle.addObserver(observer)
                onDispose { backStackEntry.lifecycle.removeObserver(observer) }
            }

            // Observe pin_verified result from PinEntry screen
            val pinVerified = backStackEntry.savedStateHandle
                .get<Boolean>("pin_verified") == true
            if (pinVerified) {
                LaunchedEffect(Unit) {
                    backStackEntry.savedStateHandle.remove<Boolean>("pin_verified")
                    viewModel.executePendingAction()
                }
            }

            SecuritySettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToPinSetup = {
                    navController.navigate(Screen.PinEntry.createRoute("setup"))
                },
                onNavigateToPinVerify = {
                    navController.navigate(Screen.PinEntry.createRoute("verify"))
                },
                viewModel = viewModel
            )
        }

        composable(Screen.Main.route) { backStackEntry ->
            val daoPinVerified = backStackEntry.savedStateHandle
                .get<Boolean>("dao_pin_verified") == true
            if (daoPinVerified) {
                LaunchedEffect(Unit) {
                    backStackEntry.savedStateHandle.remove<Boolean>("dao_pin_verified")
                }
            }

            MainScreen(
                onNavigateToSend = { navController.navigate(Screen.Send.route) },
                onNavigateToReceive = { navController.navigate(Screen.Receive.route) },
                onNavigateToNodeStatus = { navController.navigate(Screen.NodeStatus.route) },
                onNavigateToBackup = { navController.navigate(Screen.MnemonicBackup.route) },
                onNavigateToSecuritySettings = { navController.navigate(Screen.SecuritySettings.route) },
                onNavigateToImport = { navController.navigate(Screen.MnemonicImport.route) },
                onNavigateToPinVerify = {
                    navController.navigate(Screen.PinEntry.createRoute("verify"))
                },
                onNavigateToSecurityChecklist = {
                    navController.navigate(Screen.SecurityChecklist.route)
                },
                onNavigateToWalletManager = {
                    navController.navigate(Screen.WalletManager.route)
                },
                daoPinVerified = daoPinVerified,
            )
        }

        composable(Screen.Send.route) { backStackEntry ->
            val sendPinVerified = backStackEntry.savedStateHandle
                .get<Boolean>("send_pin_verified") == true
            if (sendPinVerified) {
                LaunchedEffect(Unit) {
                    backStackEntry.savedStateHandle.remove<Boolean>("send_pin_verified")
                }
            }

            SendScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToScanner = { navController.navigate(Screen.Scanner.route) },
                onNavigateToPinVerify = {
                    navController.navigate(Screen.PinEntry.createRoute("verify"))
                },
                scannedAddress = backStackEntry.savedStateHandle
                    .get<String>("scanned_address"),
                sendAuthVerified = sendPinVerified,
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

        composable(Screen.Recovery.route) {
            RecoveryScreen(
                onRecoveryComplete = { recoveredWallets ->
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.Recovery.route) { inclusive = true }
                    }
                },
                onMnemonicRestore = {
                    navController.navigate(Screen.Onboarding.route) {
                        popUpTo(Screen.Recovery.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.SecurityChecklist.route) {
            val viewModel: SecurityChecklistViewModel = hiltViewModel()
            val state = viewModel.uiState.collectAsState().value

            // Refresh state when returning from PIN setup or mnemonic backup
            DisposableEffect(Unit) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        viewModel.refreshState()
                    }
                }
                val lifecycle = it.lifecycle
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }

            SecurityChecklistScreen(
                hasPinOrBiometrics = state.hasPinOrBiometrics,
                hasMnemonicBackup = state.hasMnemonicBackup,
                isMnemonicWallet = state.isMnemonicWallet,
                onSetupPin = {
                    navController.navigate(Screen.PinEntry.createRoute("setup"))
                },
                onBackupMnemonic = {
                    navController.navigate(Screen.MnemonicBackup.route)
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.MnemonicVerify.route) {
            MnemonicVerifyScreen(
                mnemonicWords = emptyList(), // TODO: wire from KeyManager via ViewModel
                onVerified = {
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.WalletManager.route) {
            WalletManagerScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToAddWallet = { navController.navigate(Screen.AddWallet.route) },
                onNavigateToWalletDetail = { walletId ->
                    navController.navigate(Screen.WalletDetail.createRoute(walletId))
                }
            )
        }

        composable(
            route = Screen.WalletDetail.route,
            arguments = listOf(navArgument("walletId") { type = NavType.StringType })
        ) {
            WalletDetailScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.AddWallet.route) {
            AddWalletScreen(
                onNavigateBack = { navController.popBackStack() },
                onWalletCreated = {
                    navController.popBackStack(Screen.Main.route, inclusive = false)
                }
            )
        }
    }
}
