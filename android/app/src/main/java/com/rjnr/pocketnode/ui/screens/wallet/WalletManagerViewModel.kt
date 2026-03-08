package com.rjnr.pocketnode.ui.screens.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.wallet.WalletRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WalletManagerUiState(
    val wallets: List<WalletEntity> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class WalletManagerViewModel @Inject constructor(
    private val walletRepository: WalletRepository,
    private val gatewayRepository: GatewayRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(WalletManagerUiState())
    val uiState: StateFlow<WalletManagerUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            walletRepository.getAllWallets().collect { wallets ->
                _uiState.update { it.copy(wallets = wallets) }
            }
        }
    }

    fun switchWallet(walletId: String) {
        viewModelScope.launch {
            runCatching {
                walletRepository.switchActiveWallet(walletId)
                val wallet = _uiState.value.wallets.find { it.walletId == walletId }
                if (wallet != null) {
                    gatewayRepository.onActiveWalletChanged(wallet)
                }
            }.onFailure { error ->
                _uiState.update { it.copy(error = error.message) }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
