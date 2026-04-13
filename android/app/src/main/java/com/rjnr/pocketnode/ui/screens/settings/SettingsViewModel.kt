package com.rjnr.pocketnode.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import com.rjnr.pocketnode.data.wallet.SyncStrategy
import com.rjnr.pocketnode.data.wallet.ThemeMode
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: GatewayRepository,
    private val walletPrefs: WalletPreferences,
    private val pinManager: PinManager
) : ViewModel() {

    data class UiState(
        val isPinEnabled: Boolean = false,
        val syncMode: SyncMode = SyncMode.RECENT,
        val syncStrategy: SyncStrategy = SyncStrategy.ALL_WALLETS,
        val currentNetwork: NetworkType = NetworkType.MAINNET,
        val themeMode: ThemeMode = ThemeMode.SYSTEM,
        val isBackgroundSyncEnabled: Boolean = true,
        val showSyncDialog: Boolean = false,
        val showSyncStrategyDialog: Boolean = false,
        val showNetworkSwitchDialog: Boolean = false,
        val showThemeDialog: Boolean = false,
        val pendingNetworkSwitch: NetworkType? = null,
        val error: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        loadState()

        // Keep network in sync with repository's live StateFlow
        viewModelScope.launch {
            repository.network.collect { network ->
                _uiState.update { it.copy(currentNetwork = network) }
            }
        }
    }

    private fun loadState() {
        _uiState.update {
            it.copy(
                isPinEnabled = pinManager.hasPin(),
                syncMode = walletPrefs.getSyncMode(),
                syncStrategy = walletPrefs.getSyncStrategy(),
                currentNetwork = repository.currentNetwork,
                themeMode = walletPrefs.getThemeMode(),
                isBackgroundSyncEnabled = walletPrefs.isBackgroundSyncEnabled()
            )
        }
    }

    fun showSyncDialog() {
        _uiState.update { it.copy(showSyncDialog = true) }
    }

    fun hideSyncDialog() {
        _uiState.update { it.copy(showSyncDialog = false) }
    }

    fun showSyncStrategyDialog() {
        _uiState.update { it.copy(showSyncStrategyDialog = true) }
    }

    fun hideSyncStrategyDialog() {
        _uiState.update { it.copy(showSyncStrategyDialog = false) }
    }

    fun setSyncStrategy(strategy: SyncStrategy) {
        walletPrefs.setSyncStrategy(strategy)
        _uiState.update { it.copy(syncStrategy = strategy, showSyncStrategyDialog = false) }
    }

    fun setSyncMode(mode: SyncMode, customBlockHeight: Long? = null) {
        val previousMode = _uiState.value.syncMode
        _uiState.update { it.copy(syncMode = mode, showSyncDialog = false) }
        viewModelScope.launch {
            repository.resyncAccount(mode, customBlockHeight)
                .onFailure { e ->
                    _uiState.update { it.copy(syncMode = previousMode, error = "Sync mode change failed: ${e.message}") }
                }
        }
    }

    fun requestNetworkSwitch(target: NetworkType) {
        if (target == _uiState.value.currentNetwork) return
        _uiState.update {
            it.copy(showNetworkSwitchDialog = true, pendingNetworkSwitch = target)
        }
    }

    fun cancelNetworkSwitch() {
        _uiState.update { it.copy(showNetworkSwitchDialog = false, pendingNetworkSwitch = null) }
    }

    fun confirmNetworkSwitch() {
        val target = _uiState.value.pendingNetworkSwitch ?: return
        _uiState.update { it.copy(showNetworkSwitchDialog = false, pendingNetworkSwitch = null) }
        viewModelScope.launch {
            repository.switchNetwork(target)
                .onFailure { e ->
                    _uiState.update { it.copy(error = "Network switch failed: ${e.message}") }
                }
        }
    }

    fun showThemeDialog() {
        _uiState.update { it.copy(showThemeDialog = true) }
    }

    fun hideThemeDialog() {
        _uiState.update { it.copy(showThemeDialog = false) }
    }

    fun setThemeMode(mode: ThemeMode) {
        walletPrefs.setThemeMode(mode)
        _uiState.update { it.copy(themeMode = mode, showThemeDialog = false) }
    }

    fun toggleBackgroundSync(enabled: Boolean) {
        walletPrefs.setBackgroundSyncEnabled(enabled)
        _uiState.update { it.copy(isBackgroundSyncEnabled = enabled) }
        if (enabled) {
            repository.startBackgroundSync()
        } else {
            repository.stopBackgroundSync()
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
