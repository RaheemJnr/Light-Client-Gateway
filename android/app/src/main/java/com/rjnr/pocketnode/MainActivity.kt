package com.rjnr.pocketnode

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.rememberNavController
import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.ui.navigation.CkbNavGraph
import com.rjnr.pocketnode.ui.navigation.Screen
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import com.rjnr.pocketnode.ui.theme.CkbWalletTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject
    lateinit var repository: GatewayRepository

    @Inject
    lateinit var pinManager: PinManager

    @Inject
    lateinit var walletPreferences: WalletPreferences

    private val _requireReauth = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val startDestination = when {
            !repository.hasWallet() -> Screen.Onboarding.route
            repository.needsMnemonicBackup() -> Screen.MnemonicBackup.route
            pinManager.hasPin() -> Screen.Auth.route
            else -> Screen.Main.route
        }

        setContent {
            val themeMode by walletPreferences.themeModeFlow.collectAsState()

            CkbWalletTheme(themeMode = themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()

                    val reauth = _requireReauth.value
                    LaunchedEffect(reauth) {
                        if (reauth) {
                            _requireReauth.value = false
                            val currentRoute =
                                navController.currentBackStackEntry?.destination?.route
                            if (currentRoute != Screen.Auth.route &&
                                currentRoute != Screen.PinEntry.route
                            ) {
                                navController.navigate(Screen.Auth.route) {
                                    popUpTo(Screen.Main.route) { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                        }
                    }

                    CkbNavGraph(
                        navController = navController,
                        startDestination = startDestination
                    )
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (repository.hasWallet() && pinManager.hasPin()) {
            _requireReauth.value = true
        }
    }
}
