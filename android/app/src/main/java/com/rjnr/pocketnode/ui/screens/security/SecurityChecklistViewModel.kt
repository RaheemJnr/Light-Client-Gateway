package com.rjnr.pocketnode.ui.screens.security

import androidx.lifecycle.ViewModel
import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.wallet.KeyManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class SecurityChecklistUiState(
    val hasPinOrBiometrics: Boolean = false,
    val hasMnemonicBackup: Boolean = false,
    val isMnemonicWallet: Boolean = false
)

@HiltViewModel
class SecurityChecklistViewModel @Inject constructor(
    private val pinManager: PinManager,
    private val repository: GatewayRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SecurityChecklistUiState())
    val uiState: StateFlow<SecurityChecklistUiState> = _uiState.asStateFlow()

    init {
        refreshState()
    }

    fun refreshState() {
        _uiState.update {
            it.copy(
                hasPinOrBiometrics = pinManager.hasPin(),
                hasMnemonicBackup = repository.hasMnemonicBackup(),
                isMnemonicWallet = repository.getWalletType() == KeyManager.WALLET_TYPE_MNEMONIC
            )
        }
    }
}
