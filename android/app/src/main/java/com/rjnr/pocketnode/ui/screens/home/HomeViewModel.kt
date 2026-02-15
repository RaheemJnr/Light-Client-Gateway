package com.rjnr.pocketnode.ui.screens.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.gateway.models.BalanceResponse
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import com.rjnr.pocketnode.data.gateway.models.TransactionRecord
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.WalletInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "HomeViewModel"

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: GatewayRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var syncPollingJob: Job? = null

    init {
        checkBackupStatus()

        viewModelScope.launch {
            initializeWallet()
        }

        viewModelScope.launch {
            repository.walletInfo.collect { info ->
                _uiState.update { 
                    it.copy(
                        walletInfo = info,
                        address = repository.getCurrentAddress() ?: ""
                    ) 
                }
            }
        }

        viewModelScope.launch {
            repository.balance.collect { balance ->
                _uiState.update {
                    it.copy(balanceCkb = balance?.capacityAsCkb() ?: 0.0)
                }
            }
        }
    }

    private suspend fun initializeWallet() {
        _uiState.update { it.copy(isLoading = true) }

        repository.initializeWallet()
            .onSuccess { info ->
                Log.d(TAG, "Wallet initialized: ${info.testnetAddress}")
                _uiState.update { it.copy(walletInfo = info, isLoading = false) }
                registerAndRefresh()
            }
            .onFailure { error ->
                Log.e(TAG, "Wallet initialization failed", error)
                _uiState.update {
                    it.copy(error = error.message, isLoading = false)
                }
            }
    }

    private suspend fun registerAndRefresh() {
        // Load saved sync preferences
        val savedSyncMode = repository.getSavedSyncMode()
        val savedCustomBlockHeight = repository.getSavedCustomBlockHeight()
        val hasCompletedInitialSync = repository.hasCompletedInitialSync()

        Log.d(TAG, "Loading saved sync preferences: mode=$savedSyncMode, customBlock=$savedCustomBlockHeight, completedSync=$hasCompletedInitialSync")

        _uiState.update { it.copy(currentSyncMode = savedSyncMode) }

        // Use saved settings, or default to RECENT for first-time users
        val syncMode = if (hasCompletedInitialSync) savedSyncMode else SyncMode.RECENT
        val customBlockHeight = if (syncMode == SyncMode.CUSTOM) savedCustomBlockHeight else null

        Log.d(TAG, "Registering account with sync mode: $syncMode")

        repository.registerAccount(
            syncMode = syncMode,
            customBlockHeight = customBlockHeight,
            savePreference = !hasCompletedInitialSync // Only save if first time
        )
            .onSuccess {
                Log.d(TAG, "Account registered successfully with sync mode: $syncMode")
                // Check sync status before fetching transactions
                checkSyncStatusAndRefresh()
            }
            .onFailure { error ->
                Log.e(TAG, "Registration failed", error)
                _uiState.update { it.copy(error = "Registration failed: ${error.message}") }
            }
    }

    private suspend fun checkSyncStatusAndRefresh() {
        // Check sync status
        repository.getAccountStatus()
            .onSuccess { status ->
                Log.d(TAG, "Account sync status: synced=${status.isSynced}, progress=${status.syncProgress}")
                _uiState.update {
                    it.copy(
                        syncProgress = status.syncProgress,
                        isSyncing = !status.isSynced
                    )
                }

                if (!status.isSynced) {
                    Log.d(TAG, "Account still syncing (${(status.syncProgress * 100).toInt()}%), starting fast poll")
                }
                startSyncPolling()
            }
            .onFailure { error ->
                Log.e(TAG, "Failed to get account status", error)
            }

        // Refresh data regardless of sync status
        refresh()
    }

    private fun startSyncPolling() {
        if (syncPollingJob?.isActive == true) return
        
        syncPollingJob = viewModelScope.launch {
            Log.d(TAG, "🚀 Starting indefinite sync polling")
            var isFullySynced = false

            while (true) {
                val delayMs = if (isFullySynced) 30_000L else 5_000L
                delay(delayMs)

                repository.getAccountStatus()
                    .onSuccess { status ->
                        val wasSynced = isFullySynced
                        isFullySynced = status.isSynced
                        
                        _uiState.update {
                            it.copy(
                                syncProgress = status.syncProgress,
                                isSyncing = !status.isSynced
                            )
                        }

                        if (isFullySynced && !wasSynced) {
                            Log.d(TAG, "✨ Account just reached full sync! Refreshing all data...")
                            refresh()
                        } else if (isFullySynced) {
                            // Periodically check for new transactions even when synced
                            Log.d(TAG, "📡 Periodic background refresh (Synced)")
                            refresh()
                        } else {
                            Log.d(TAG, "📈 Sync progress: ${(status.syncProgress * 100).toInt()}%")
                            // Refresh transactions periodically during active sync
                            refreshTransactionsOnly(silent = true)
                        }
                    }
                    .onFailure {
                        Log.e(TAG, "❌ Failed to check sync status during polling", it)
                    }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = null) }

            Log.d(TAG, "Refreshing balance...")
            repository.refreshBalance()
                .onSuccess { balance ->
                    Log.d(TAG, "Balance: ${balance.capacityCkb} CKB")
                }
                .onFailure { error ->
                    Log.e(TAG, "Failed to refresh balance", error)
                }

            refreshTransactionsOnly()

            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    private suspend fun refreshTransactionsOnly(silent: Boolean = false) {
        Log.d(TAG, "Fetching transactions (limit=50)...")
        repository.getTransactions(limit = 50)
            .onSuccess { response ->
                Log.d(TAG, "Fetched ${response.items.size} transactions")
                response.items.forEachIndexed { index, tx ->
                    Log.d(TAG, "  [$index] ${tx.txHash.take(16)}... dir=${tx.direction} amount=${tx.balanceChange} conf=${tx.confirmations}")
                }
                _uiState.update {
                    it.copy(transactions = response.items)
                }
            }
            .onFailure { error ->
                Log.e(TAG, "Failed to fetch transactions", error)
                if (!silent) {
                    _uiState.update {
                        it.copy(error = "Failed to load transactions: ${error.message}")
                    }
                }
            }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Change sync mode and resync the account.
     * This will re-register with the light client using the new sync settings.
     */
    fun changeSyncMode(syncMode: SyncMode, customBlockHeight: Long? = null) {
        viewModelScope.launch {
            Log.d(TAG, "Changing sync mode to: $syncMode, customBlock: $customBlockHeight")

            // Cancel any existing sync polling
            syncPollingJob?.cancel()

            _uiState.update {
                it.copy(
                    isLoading = true,
                    isSyncing = true,
                    syncProgress = 0.0,
                    transactions = emptyList(),
                    currentSyncMode = syncMode
                )
            }

            repository.resyncAccount(syncMode, customBlockHeight)
                .onSuccess {
                    Log.d(TAG, "Resync initiated successfully")
                    _uiState.update { it.copy(isLoading = false) }
                    checkSyncStatusAndRefresh()
                }
                .onFailure { error ->
                    Log.e(TAG, "Resync failed", error)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isSyncing = false,
                            error = "Failed to change sync mode: ${error.message}"
                        )
                    }
                }
        }
    }

    /**
     * Show the sync options dialog
     */
    fun showSyncOptions() {
        _uiState.update { it.copy(showSyncOptionsDialog = true) }
    }

    /**
     * Hide the sync options dialog
     */
    fun hideSyncOptions() {
        _uiState.update { it.copy(showSyncOptionsDialog = false) }
    }

    /**
     * Show the backup wallet dialog with the private key (raw key wallets only).
     * For mnemonic wallets, HomeScreen navigates to MnemonicBackupScreen directly.
     */
    fun showBackup() {
        if (isMnemonicWallet()) return // handled by navigation in HomeScreen
        try {
            val privateKey = repository.getPrivateKey()
            val hex = org.nervos.ckb.utils.Numeric.toHexStringNoPrefix(privateKey)
            _uiState.update { it.copy(privateKeyHex = hex, showBackupDialog = true) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get private key for backup", e)
            _uiState.update { it.copy(error = "Failed to access wallet keys") }
        }
    }

    private fun checkBackupStatus() {
        val type = repository.getWalletType()
        val needsBackup = type == KeyManager.WALLET_TYPE_MNEMONIC && !repository.hasMnemonicBackup()
        _uiState.update { it.copy(walletType = type, showBackupReminder = needsBackup) }
    }

    fun dismissBackupReminder() {
        _uiState.update { it.copy(showBackupReminder = false) }
    }

    fun isMnemonicWallet(): Boolean = _uiState.value.walletType == KeyManager.WALLET_TYPE_MNEMONIC

    /**
     * Hide the backup wallet dialog
     */
    fun hideBackup() {
        _uiState.update { it.copy(showBackupDialog = false, privateKeyHex = null) }
    }

    /**
     * Show the import wallet dialog
     */
    fun showImport() {
        _uiState.update { it.copy(showImportDialog = true) }
    }

    /**
     * Hide the import wallet dialog
     */
    fun hideImport() {
        _uiState.update { it.copy(showImportDialog = false) }
    }

    /**
     * Dismiss the sync reminder
     */
    fun dismissSyncReminder() {
        _uiState.update { it.copy(showImportSyncReminder = false) }
    }

    /**
     * Import a wallet using a private key
     */
    fun importWallet(privateKeyHex: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, showImportDialog = false) }
            
            repository.importWallet(privateKeyHex)
                .onSuccess { info ->
                    Log.d(TAG, "Wallet imported successfully: ${info.testnetAddress}")
                    _uiState.update { it.copy(
                        walletInfo = info, 
                        isLoading = false,
                        showImportSyncReminder = true // Show reminder for imported wallet
                    ) }
                    registerAndRefresh()
                }
                .onFailure { error ->
                    Log.e(TAG, "Wallet import failed", error)
                    _uiState.update { 
                        it.copy(
                            isLoading = false, 
                            error = "Import failed: ${error.message}"
                        ) 
                    }
                }
        }
    }

    override fun onCleared() {
        super.onCleared()
        syncPollingJob?.cancel()
    }
}

data class HomeUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isSyncing: Boolean = false,
    val syncProgress: Double = 0.0,
    val walletInfo: WalletInfo? = null,
    val address: String = "",
    val balanceCkb: Double = 0.0,
    val balance: BalanceResponse? = null,
    val transactions: List<TransactionRecord> = emptyList(),
    val error: String? = null,
    val currentSyncMode: SyncMode = SyncMode.RECENT,
    val showSyncOptionsDialog: Boolean = false,
    val showBackupDialog: Boolean = false,
    val privateKeyHex: String? = null,
    val showImportDialog: Boolean = false,
    val showImportSyncReminder: Boolean = false,
    val showBackupReminder: Boolean = false,
    val walletType: String = KeyManager.WALLET_TYPE_RAW_KEY
)
