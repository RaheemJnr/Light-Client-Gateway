package com.rjnr.pocketnode.ui.screens.settings

import androidx.lifecycle.ViewModel
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.auth.PinManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

enum class PendingSecurityAction {
    REMOVE_PIN,
    ENABLE_BIOMETRIC,
    DISABLE_BIOMETRIC
}

data class SecuritySettingsUiState(
    val isBiometricAvailable: Boolean = false,
    val isBiometricEnabled: Boolean = false,
    val hasPin: Boolean = false,
    val biometricStatusText: String = "",
    val error: String? = null
)

@HiltViewModel
class SecuritySettingsViewModel @Inject constructor(
    private val authManager: AuthManager,
    private val pinManager: PinManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SecuritySettingsUiState())
    val uiState: StateFlow<SecuritySettingsUiState> = _uiState.asStateFlow()

    var pendingAction: PendingSecurityAction? = null
        private set

    fun setPendingAction(action: PendingSecurityAction) {
        pendingAction = action
    }

    fun executePendingAction() {
        when (pendingAction) {
            PendingSecurityAction.REMOVE_PIN -> removePin()
            PendingSecurityAction.ENABLE_BIOMETRIC -> toggleBiometric(true)
            PendingSecurityAction.DISABLE_BIOMETRIC -> toggleBiometric(false)
            null -> {}
        }
        pendingAction = null
    }

    init {
        refreshState()
    }

    fun refreshState() {
        val biometricStatus = authManager.isBiometricAvailable()
        _uiState.update {
            it.copy(
                isBiometricAvailable = biometricStatus == AuthManager.BiometricStatus.AVAILABLE,
                isBiometricEnabled = authManager.isBiometricEnabled(),
                hasPin = pinManager.hasPin(),
                biometricStatusText = when (biometricStatus) {
                    AuthManager.BiometricStatus.AVAILABLE -> "Fingerprint hardware available"
                    AuthManager.BiometricStatus.NO_HARDWARE -> "No biometric hardware detected"
                    AuthManager.BiometricStatus.NOT_ENROLLED -> "No fingerprints enrolled in device settings"
                    AuthManager.BiometricStatus.UNAVAILABLE -> "Biometric authentication unavailable"
                },
                error = null
            )
        }
    }

    fun toggleBiometric(enabled: Boolean) {
        if (enabled && !pinManager.hasPin()) {
            _uiState.update { it.copy(error = "Set a PIN first to enable biometric unlock") }
            return
        }
        authManager.setBiometricEnabled(enabled)
        _uiState.update { it.copy(isBiometricEnabled = enabled, error = null) }
    }

    fun removePin() {
        pinManager.removePin()
        authManager.setBiometricEnabled(false)
        _uiState.update {
            it.copy(hasPin = false, isBiometricEnabled = false, error = null)
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
