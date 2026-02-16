package com.rjnr.pocketnode.ui.screens.auth

import androidx.lifecycle.ViewModel
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.auth.PinManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class AuthUiState(
    val showBiometricButton: Boolean = false,
    val showPinFallback: Boolean = true,
    val authSuccess: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authManager: AuthManager,
    private val pinManager: PinManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        val biometricReady = authManager.isBiometricEnrolled() && authManager.isBiometricEnabled()
        _uiState.update {
            it.copy(
                showBiometricButton = biometricReady,
                showPinFallback = pinManager.hasPin()
            )
        }
    }

    fun shouldAutoTriggerBiometric(): Boolean = _uiState.value.showBiometricButton

    fun onBiometricSuccess() {
        _uiState.update { it.copy(authSuccess = true) }
    }

    fun onBiometricFailed(errorMessage: String) {
        _uiState.update { it.copy(error = errorMessage) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
