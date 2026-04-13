package com.rjnr.pocketnode.ui.screens.wallet

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.wallet.WalletRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "WalletManagerVM"

@HiltViewModel
class WalletManagerViewModel @Inject constructor(
    private val walletRepository: WalletRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(WalletManagerUiState())
    val uiState: StateFlow<WalletManagerUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            walletRepository.walletsFlow.collect { wallets ->
                _uiState.update { it.copy(wallets = wallets) }
            }
        }
    }

    fun switchWallet(walletId: String) {
        viewModelScope.launch {
            try {
                walletRepository.switchActiveWallet(walletId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to switch wallet", e)
                _uiState.update { it.copy(error = "Failed to switch wallet: ${e.message}") }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

data class WalletManagerUiState(
    val wallets: List<WalletEntity> = emptyList(),
    val error: String? = null
)
