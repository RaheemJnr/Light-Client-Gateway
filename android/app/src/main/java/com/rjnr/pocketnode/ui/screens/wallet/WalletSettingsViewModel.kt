package com.rjnr.pocketnode.ui.screens.wallet

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.database.dao.DaoCellDao
import com.rjnr.pocketnode.data.database.dao.TransactionDao
import com.rjnr.pocketnode.data.database.dao.WalletDao
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import com.rjnr.pocketnode.data.wallet.WalletRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

private const val TAG = "WalletSettingsVM"

@HiltViewModel
class WalletSettingsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val walletRepository: WalletRepository,
    private val walletDao: WalletDao,
    private val keyManager: KeyManager,
    private val pinManager: PinManager,
    private val daoCellDao: DaoCellDao,
    private val transactionDao: TransactionDao,
    private val walletPreferences: WalletPreferences
) : ViewModel() {

    private val walletId: String = savedStateHandle["walletId"] ?: ""

    private val _uiState = MutableStateFlow(WalletSettingsUiState())
    val uiState: StateFlow<WalletSettingsUiState> = _uiState.asStateFlow()

    init {
        loadWallet()
        observeSubAccounts()
    }

    private fun loadWallet() {
        viewModelScope.launch {
            val wallet = walletRepository.getById(walletId)
            val isBackedUp = if (wallet != null) {
                keyManager.hasMnemonicBackupForWallet(walletId)
            } else false
            _uiState.update {
                it.copy(
                    wallet = wallet,
                    editName = wallet?.name ?: "",
                    isBackedUp = isBackedUp
                )
            }
        }
    }

    private fun observeSubAccounts() {
        viewModelScope.launch {
            walletDao.getSubAccounts(walletId).collect { subAccounts ->
                _uiState.update { it.copy(subAccounts = subAccounts) }
            }
        }
    }

    // -- Name editing --

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

    // -- Delete --

    fun requestDelete() {
        val wallet = _uiState.value.wallet ?: return
        if (wallet.isActive) {
            _uiState.update { it.copy(error = "Cannot delete the active wallet. Switch to another wallet first.") }
            return
        }
        viewModelScope.launch {
            val count = walletRepository.walletCount()
            if (count <= 1) {
                _uiState.update { it.copy(error = "Cannot delete the last wallet. Create another wallet first.") }
                return@launch
            }

            // Check for active DAO deposits
            val network = walletPreferences.getSelectedNetwork().name
            val daoDeposits = daoCellDao.getActiveByWalletAndNetwork(walletId, network)
            val hasDaoDeposits = daoDeposits.isNotEmpty()
            val daoAmount = if (hasDaoDeposits) {
                val totalShannons = daoDeposits.sumOf { it.capacity }
                String.format(Locale.US, "%,.2f", totalShannons / 100_000_000.0)
            } else ""

            // Check for pending transactions
            val pendingTxs = transactionDao.getPendingByWallet(walletId, network)
            val pendingTxCount = pendingTxs.size

            _uiState.update {
                it.copy(
                    showDeleteConfirm = true,
                    hasDaoDeposits = hasDaoDeposits,
                    daoDepositAmount = daoAmount,
                    pendingTxCount = pendingTxCount
                )
            }
        }
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
                _uiState.update {
                    it.copy(showDeleteConfirm = false, error = "Delete failed: ${e.message}")
                }
            }
        }
    }

    // -- Wallet type helpers --

    fun hasMnemonic(): Boolean {
        val wallet = _uiState.value.wallet ?: return false
        return wallet.type == KeyManager.WALLET_TYPE_MNEMONIC && wallet.parentWalletId == null
    }

    fun isRawKey(): Boolean {
        val wallet = _uiState.value.wallet ?: return false
        return wallet.type == KeyManager.WALLET_TYPE_RAW_KEY
    }

    fun isSubAccount(): Boolean {
        val wallet = _uiState.value.wallet ?: return false
        return wallet.parentWalletId != null
    }

    fun getPrivateKeyHex(): String? {
        return try {
            keyManager.getPrivateKeyForWallet(walletId)?.let { bytes ->
                bytes.joinToString("") { "%02x".format(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get private key", e)
            null
        }
    }

    fun requiresPinForSeedPhrase(): Boolean = pinManager.hasPin()

    fun onPinVerified() {
        _uiState.update { it.copy(seedPhraseUnlocked = true) }
    }

    fun lockSeedPhrase() {
        _uiState.update { it.copy(seedPhraseUnlocked = false) }
    }

    fun getMnemonic(): List<String>? {
        return try {
            keyManager.getMnemonicForWallet(walletId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get mnemonic", e)
            null
        }
    }

    // -- Sub-accounts --

    fun addSubAccount(name: String) {
        viewModelScope.launch {
            try {
                walletRepository.createSubAccount(walletId, name)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create sub-account", e)
                _uiState.update { it.copy(error = "Failed to create account: ${e.message}") }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

data class WalletSettingsUiState(
    val wallet: WalletEntity? = null,
    val subAccounts: List<WalletEntity> = emptyList(),
    val isEditing: Boolean = false,
    val editName: String = "",
    val isBackedUp: Boolean = false,
    val showDeleteConfirm: Boolean = false,
    val hasDaoDeposits: Boolean = false,
    val daoDepositAmount: String = "",
    val pendingTxCount: Int = 0,
    val deleted: Boolean = false,
    val error: String? = null,
    val seedPhraseUnlocked: Boolean = false
)
