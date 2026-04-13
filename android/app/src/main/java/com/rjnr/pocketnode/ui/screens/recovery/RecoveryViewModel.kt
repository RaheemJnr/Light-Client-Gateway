package com.rjnr.pocketnode.ui.screens.recovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.data.wallet.KeyBackupManager
import com.rjnr.pocketnode.data.wallet.KeyMaterial
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class RecoveryStage {
    PIN_ENTRY,
    MNEMONIC_ENTRY,
    SUCCESS,
    ERROR
}

data class RecoveryUiState(
    val stage: RecoveryStage = RecoveryStage.PIN_ENTRY,
    val failedAttempts: Int = 0,
    val recoveredWallets: List<RecoveredWallet> = emptyList(),
    val failedWalletIds: List<String> = emptyList(),
    val error: String? = null
)

data class RecoveredWallet(
    val walletId: String,
    val material: KeyMaterial
)

@HiltViewModel
class RecoveryViewModel @Inject constructor(
    private val backupManager: KeyBackupManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecoveryUiState())
    val uiState: StateFlow<RecoveryUiState> = _uiState

    init {
        if (!backupManager.hasAnyBackups()) {
            _uiState.update { it.copy(stage = RecoveryStage.MNEMONIC_ENTRY) }
        }
    }

    fun attemptPinRecovery(pin: CharArray) {
        viewModelScope.launch {
            val walletIds = backupManager.listBackupWalletIds()
            val recovered = mutableListOf<RecoveredWallet>()
            val failed = mutableListOf<String>()

            for (id in walletIds) {
                val material = backupManager.readBackup(id, pin)
                if (material != null) {
                    recovered.add(RecoveredWallet(id, material))
                } else {
                    failed.add(id)
                }
            }

            if (recovered.isNotEmpty()) {
                _uiState.update {
                    it.copy(
                        stage = RecoveryStage.SUCCESS,
                        recoveredWallets = recovered,
                        failedWalletIds = failed
                    )
                }
            } else {
                val newAttempts = _uiState.value.failedAttempts + 1
                _uiState.update {
                    it.copy(
                        failedAttempts = newAttempts,
                        stage = if (newAttempts >= MAX_PIN_ATTEMPTS) RecoveryStage.MNEMONIC_ENTRY else RecoveryStage.PIN_ENTRY,
                        error = if (newAttempts >= MAX_PIN_ATTEMPTS) "Too many failed attempts. Please enter your recovery phrase."
                        else "Incorrect PIN. ${MAX_PIN_ATTEMPTS - newAttempts} attempts remaining."
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    companion object {
        const val MAX_PIN_ATTEMPTS = 3
    }
}
