package com.rjnr.pocketnode.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OnboardingUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val isWalletCreated: Boolean = false
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val repository: GatewayRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    /**
     * Starts a wallet creation operation and updates the onboarding UI state accordingly.
     *
     * Sets `isLoading` to true and clears `error` before starting. On success, sets `isWalletCreated` to true and `isLoading` to false.
     * On failure, sets `error` to the failure message and `isLoading` to false.
     */
    fun createNewWallet() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            repository.createWalletWithMnemonic()
                .onSuccess {
                    _uiState.update { it.copy(isLoading = false, isWalletCreated = true) }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isLoading = false, error = error.message) }
                }
        }
    }

    /**
     * Clears the error stored in the onboarding UI state.
     *
     * Sets the state's `error` property to `null`.
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}