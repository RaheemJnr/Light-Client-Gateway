package com.rjnr.pocketnode.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.SyncMode
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
        val currentNetwork: NetworkType = NetworkType.MAINNET,
        val showSyncDialog: Boolean = false,
        val showNetworkSwitchDialog: Boolean = false,
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
                currentNetwork = repository.currentNetwork
            )
        }
    }

    fun showSyncDialog() {
        _uiState.update { it.copy(showSyncDialog = true) }
    }

    fun hideSyncDialog() {
        _uiState.update { it.copy(showSyncDialog = false) }
    }

    fun setSyncMode(mode: SyncMode, customBlockHeight: Long? = null) {
        _uiState.update { it.copy(syncMode = mode, showSyncDialog = false) }
        viewModelScope.launch {
            repository.resyncAccount(mode, customBlockHeight)
                .onFailure { e ->
                    _uiState.update { it.copy(error = "Sync mode change failed: ${e.message}") }
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

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
