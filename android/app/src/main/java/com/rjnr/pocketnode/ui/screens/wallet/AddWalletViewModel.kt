package com.rjnr.pocketnode.ui.screens.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.wallet.MnemonicManager
import com.rjnr.pocketnode.data.wallet.WalletRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AddWalletUiState(
    val isLoading: Boolean = false,
    val name: String = "",
    val importMnemonic: String = "",
    val importPrivateKey: String = "",
    val parentWallets: List<WalletEntity> = emptyList(),
    val selectedParentId: String? = null,
    val createdWallet: WalletEntity? = null,
    val error: String? = null
)

@HiltViewModel
class AddWalletViewModel @Inject constructor(
    private val walletRepository: WalletRepository,
    private val gatewayRepository: GatewayRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddWalletUiState())
    val uiState: StateFlow<AddWalletUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            walletRepository.getAllWallets().collect { wallets ->
                val mnemonicWallets = wallets.filter {
                    it.type == "mnemonic" && it.parentWalletId == null
                }
                _uiState.update { it.copy(parentWallets = mnemonicWallets) }
            }
        }
    }

    fun updateName(name: String) {
        _uiState.update { it.copy(name = name) }
    }

    fun updateImportMnemonic(mnemonic: String) {
        _uiState.update { it.copy(importMnemonic = mnemonic) }
    }

    fun updateImportPrivateKey(key: String) {
        _uiState.update { it.copy(importPrivateKey = key) }
    }

    fun selectParent(walletId: String) {
        _uiState.update { it.copy(selectedParentId = walletId) }
    }

    fun createNewWallet() {
        val name = _uiState.value.name.trim()
        if (name.isBlank()) {
            _uiState.update { it.copy(error = "Please enter a wallet name") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching {
                val wallet = walletRepository.createWallet(name)
                gatewayRepository.onActiveWalletChanged(wallet)
                wallet
            }.onSuccess { wallet ->
                _uiState.update { it.copy(isLoading = false, createdWallet = wallet) }
            }.onFailure { error ->
                _uiState.update { it.copy(isLoading = false, error = error.message) }
            }
        }
    }

    fun importMnemonic() {
        val name = _uiState.value.name.trim()
        val words = _uiState.value.importMnemonic.trim().split("\\s+".toRegex())

        if (name.isBlank()) {
            _uiState.update { it.copy(error = "Please enter a wallet name") }
            return
        }
        if (words.size !in listOf(12, 15, 18, 21, 24)) {
            _uiState.update { it.copy(error = "Mnemonic must be 12, 15, 18, 21, or 24 words") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching {
                val wallet = walletRepository.importWallet(name, words)
                gatewayRepository.onActiveWalletChanged(wallet)
                wallet
            }.onSuccess { wallet ->
                _uiState.update { it.copy(isLoading = false, createdWallet = wallet) }
            }.onFailure { error ->
                _uiState.update { it.copy(isLoading = false, error = error.message) }
            }
        }
    }

    fun importRawKey() {
        val name = _uiState.value.name.trim()
        val key = _uiState.value.importPrivateKey.trim()

        if (name.isBlank()) {
            _uiState.update { it.copy(error = "Please enter a wallet name") }
            return
        }
        if (key.removePrefix("0x").length != 64) {
            _uiState.update { it.copy(error = "Private key must be 32 bytes (64 hex characters)") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching {
                val wallet = walletRepository.importRawKey(name, key)
                gatewayRepository.onActiveWalletChanged(wallet)
                wallet
            }.onSuccess { wallet ->
                _uiState.update { it.copy(isLoading = false, createdWallet = wallet) }
            }.onFailure { error ->
                _uiState.update { it.copy(isLoading = false, error = error.message) }
            }
        }
    }

    fun createSubAccount() {
        val name = _uiState.value.name.trim()
        val parentId = _uiState.value.selectedParentId

        if (name.isBlank()) {
            _uiState.update { it.copy(error = "Please enter a wallet name") }
            return
        }
        if (parentId == null) {
            _uiState.update { it.copy(error = "Please select a parent wallet") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching {
                val wallet = walletRepository.createSubAccount(parentId, name)
                gatewayRepository.onActiveWalletChanged(wallet)
                wallet
            }.onSuccess { wallet ->
                _uiState.update { it.copy(isLoading = false, createdWallet = wallet) }
            }.onFailure { error ->
                _uiState.update { it.copy(isLoading = false, error = error.message) }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
