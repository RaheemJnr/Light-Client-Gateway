package com.rjnr.pocketnode.ui.screens.wallet

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

private const val TAG = "WalletDetailVM"

@HiltViewModel
class WalletDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val walletRepository: WalletRepository,
    private val keyManager: KeyManager
) : ViewModel() {

    private val walletId: String = savedStateHandle["walletId"] ?: ""

    private val _uiState = MutableStateFlow(WalletDetailUiState())
    val uiState: StateFlow<WalletDetailUiState> = _uiState.asStateFlow()

    init {
        loadWallet()
    }

    private fun loadWallet() {
        viewModelScope.launch {
            val wallet = walletRepository.getById(walletId)
            _uiState.update { it.copy(wallet = wallet, editName = wallet?.name ?: "") }
        }
    }

    fun startEditing() {
        _uiState.update { it.copy(isEditing = true, editName = it.wallet?.name ?: "") }
    }

    fun cancelEditing() {
        _uiState.update { it.copy(isEditing = false, editName = it.wallet?.name ?: "") }
    }

    fun updateEditName(name: String) {
        _uiState.update { it.copy(editName = name) }
    }

    fun saveName() {
        val name = _uiState.value.editName.trim()
        if (name.isBlank()) return
        viewModelScope.launch {
            try {
                walletRepository.renameWallet(walletId, name)
                loadWallet()
                _uiState.update { it.copy(isEditing = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to rename: ${e.message}") }
            }
        }
    }

    fun requestDelete() {
        _uiState.update { it.copy(showDeleteConfirm = true) }
    }

    fun cancelDelete() {
        _uiState.update { it.copy(showDeleteConfirm = false) }
    }

    fun confirmDelete() {
        viewModelScope.launch {
            try {
                walletRepository.deleteWallet(walletId)
                _uiState.update { it.copy(showDeleteConfirm = false, deleted = true) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete wallet", e)
                _uiState.update { it.copy(showDeleteConfirm = false, error = "Delete failed: ${e.message}") }
            }
        }
    }

    fun hasMnemonic(): Boolean {
        val wallet = _uiState.value.wallet ?: return false
        return wallet.type == KeyManager.WALLET_TYPE_MNEMONIC && wallet.parentWalletId == null
    }

    fun getMnemonic(): List<String>? {
        return try {
            keyManager.getMnemonicForWallet(walletId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get mnemonic", e)
            null
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

data class WalletDetailUiState(
    val wallet: WalletEntity? = null,
    val isEditing: Boolean = false,
    val editName: String = "",
    val showDeleteConfirm: Boolean = false,
    val deleted: Boolean = false,
    val error: String? = null
)
