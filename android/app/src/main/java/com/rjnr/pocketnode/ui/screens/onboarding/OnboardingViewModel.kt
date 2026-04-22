package com.rjnr.pocketnode.ui.screens.onboarding

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.wallet.WalletRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "OnboardingViewModel"

data class OnboardingUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val isWalletCreated: Boolean = false,
    val wasCorrupted: Boolean = false
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val repository: GatewayRepository,
    private val walletRepository: WalletRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    init {
        _uiState.update { it.copy(wasCorrupted = repository.wasResetDueToCorruption()) }
    }

    fun createNewWallet() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                // Create wallet via WalletRepository so a Room entity is created
                val (entity, _) = walletRepository.createWallet("My Wallet")
                Log.d(TAG, "Created wallet entity: ${entity.walletId}")
                // Initialize the gateway repository with the new wallet's keys
                repository.onActiveWalletChanged(entity)
                _uiState.update { it.copy(isLoading = false, isWalletCreated = true) }
            } catch (e: Exception) {
                Log.e(TAG, "Wallet creation failed", e)
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
