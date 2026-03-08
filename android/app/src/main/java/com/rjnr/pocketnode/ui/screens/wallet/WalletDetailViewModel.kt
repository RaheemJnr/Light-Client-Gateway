package com.rjnr.pocketnode.ui.screens.wallet

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.data.database.dao.WalletDao
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.WalletRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WalletDetailUiState(
    val wallet: WalletEntity? = null,
    val isEditing: Boolean = false,
    val editName: String = "",
    val showDeleteConfirm: Boolean = false,
    val error: String? = null,
    val deleted: Boolean = false
)

@HiltViewModel
class WalletDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val walletDao: WalletDao,
    private val walletRepository: WalletRepository,
    private val keyManager: KeyManager
) : ViewModel() {

    private val walletId: String = savedStateHandle["walletId"]
        ?: throw IllegalArgumentException("walletId required")

    private val _uiState = MutableStateFlow(WalletDetailUiState())
    val uiState: StateFlow<WalletDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val wallet = walletDao.getById(walletId)
            _uiState.update { it.copy(wallet = wallet, editName = wallet?.name ?: "") }
        }
    }

    fun startEditing() {
        _uiState.update { it.copy(isEditing = true, editName = it.wallet?.name ?: "") }
    }

    fun updateEditName(name: String) {
        _uiState.update { it.copy(editName = name) }
    }

    fun saveName() {
        val name = _uiState.value.editName.trim()
        if (name.isBlank()) return

        viewModelScope.launch {
            walletRepository.renameWallet(walletId, name)
            val updated = walletDao.getById(walletId)
            _uiState.update { it.copy(wallet = updated, isEditing = false) }
        }
    }

    fun cancelEditing() {
        _uiState.update { it.copy(isEditing = false, editName = it.wallet?.name ?: "") }
    }

    fun requestDelete() {
        _uiState.update { it.copy(showDeleteConfirm = true) }
    }

    fun cancelDelete() {
        _uiState.update { it.copy(showDeleteConfirm = false) }
    }

    fun confirmDelete() {
        viewModelScope.launch {
            runCatching {
                walletRepository.deleteWallet(walletId)
            }.onSuccess {
                _uiState.update { it.copy(showDeleteConfirm = false, deleted = true) }
            }.onFailure { error ->
                _uiState.update { it.copy(showDeleteConfirm = false, error = error.message) }
            }
        }
    }

    fun hasMnemonic(): Boolean {
        return _uiState.value.wallet?.type == KeyManager.WALLET_TYPE_MNEMONIC
                && _uiState.value.wallet?.parentWalletId == null
    }

    fun getMnemonic(): List<String>? {
        return keyManager.getMnemonicForWallet(walletId)
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
