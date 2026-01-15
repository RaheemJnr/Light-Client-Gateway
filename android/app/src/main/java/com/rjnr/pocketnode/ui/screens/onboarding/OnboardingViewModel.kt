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
    val isWalletCreated: Boolean = false,
    val showImportDialog: Boolean = false
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val repository: GatewayRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    fun createNewWallet() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            repository.createNewWallet()
                .onSuccess {
                    _uiState.update { it.copy(isLoading = false, isWalletCreated = true) }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isLoading = false, error = error.message) }
                }
        }
    }

    fun importWallet(privateKey: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, showImportDialog = false) }
            repository.importExistingWallet(privateKey)
                .onSuccess {
                    _uiState.update { it.copy(isLoading = false, isWalletCreated = true) }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isLoading = false, error = error.message) }
                }
        }
    }

    fun showImport() {
        _uiState.update { it.copy(showImportDialog = true) }
    }

    fun hideImport() {
        _uiState.update { it.copy(showImportDialog = false) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
