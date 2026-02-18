package com.rjnr.pocketnode.ui.screens.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import com.rjnr.pocketnode.data.gateway.models.TransactionRecord
import com.rjnr.pocketnode.data.price.PriceRepository
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
    private val repository: GatewayRepository,
    private val priceRepository: PriceRepository
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
                val ckb = balance?.capacityAsCkb() ?: 0.0
                _uiState.update { current ->
                    val price = current.ckbUsdPrice
                    val fiat = if (price != null) "≈ $%.2f USD".format(ckb * price) else null
                    current.copy(
                        balanceCkb = ckb,
                        fiatBalance = fiat ?: current.fiatBalance
                    )
                }
            }
        }

        viewModelScope.launch {
            repository.network.collect { network ->
                _uiState.update { it.copy(currentNetwork = network) }
            }
        }

        viewModelScope.launch {
            repository.isSwitchingNetwork.collect { switching ->
                _uiState.update { it.copy(isSwitchingNetwork = switching) }
            }
        }
    }

    private suspend fun initializeWallet() {
        _uiState.update { it.copy(isLoading = true) }

        repository.initializeWallet()
            .onSuccess { info ->
                Log.d(TAG, "Wallet initialized: ${info.testnetAddress}")
                _uiState.update { it.copy(walletInfo = info, isLoading = false) }
                fetchPrice()
                registerAndRefresh()
            }
            .onFailure { error ->
                Log.e(TAG, "Wallet initialization failed", error)
                _uiState.update {
                    it.copy(error = error.message, isLoading = false)
                }
            }
    }

    /**
     * Fetches the CKB/USD spot price and computes the fiat equivalent of the current balance.
     * Failures are silent — the UI falls back to "≈ — USD".
     */
    private suspend fun fetchPrice() {
        priceRepository.getCkbUsdPrice()
            .onSuccess { price ->
                val balanceCkb = _uiState.value.balanceCkb
                val fiat = balanceCkb * price
                val formatted = "≈ $%.2f USD".format(fiat)
                _uiState.update { it.copy(fiatBalance = formatted, ckbUsdPrice = price) }
                Log.d(TAG, "CKB price: $$price, fiat balance: $formatted")
            }
            .onFailure { error ->
                Log.w(TAG, "Price fetch failed (non-critical): ${error.message}")
                // Leave fiatBalance as-is; UI shows "≈ — USD" when null
            }
    }

    private suspend fun registerAndRefresh() {
        // Load saved sync preferences
        val savedSyncMode = repository.getSavedSyncMode()
        val savedCustomBlockHeight = repository.getSavedCustomBlockHeight()
        val hasCompletedInitialSync = repository.hasCompletedInitialSync()

        Log.d(TAG, "Loading saved sync preferences: mode=$savedSyncMode, customBlock=$savedCustomBlockHeight, completedSync=$hasCompletedInitialSync")

        _uiState.update { it.copy(currentSyncMode = savedSyncMode) }

        // Use saved settings, or network-appropriate default for first-time users.
        // Testnet defaults to NEW_WALLET (start from tip — testnet is small, no need for history).
        // Mainnet defaults to RECENT (~30 days of history).
        val firstTimeSyncMode = if (repository.currentNetwork == NetworkType.TESTNET)
            SyncMode.NEW_WALLET else SyncMode.RECENT
        val syncMode = if (hasCompletedInitialSync) savedSyncMode else firstTimeSyncMode
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
                        isSyncing = !status.isSynced,
                        syncedToBlock = status.syncedToBlock,
                        tipBlockNumber = status.tipNumber
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
                                isSyncing = !status.isSynced,
                                syncedToBlock = status.syncedToBlock,
                                tipBlockNumber = status.tipNumber
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
                    // Recompute fiat with the cached price if available
                    val price = _uiState.value.ckbUsdPrice
                    if (price != null) {
                        val fiat = balance.capacityAsCkb() * price
                        _uiState.update { it.copy(fiatBalance = "≈ $%.2f USD".format(fiat)) }
                    }
                }
                .onFailure { error ->
                    Log.e(TAG, "Failed to refresh balance", error)
                }

            // Refresh peer count (best-effort: parse array size from JSON)
            try {
                val peersJson = repository.getPeers()
                if (peersJson != null) {
                    // Count top-level array elements by counting "peer_id" occurrences.
                    // Each peer has exactly one peer_id and it won't appear nested.
                    val count = peersJson.split("\"peer_id\"").size - 1
                    _uiState.update { it.copy(peerCount = count.coerceAtLeast(0)) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to refresh peer count", e)
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

    fun requestNetworkSwitch(target: NetworkType) {
        _uiState.update { it.copy(showNetworkSwitchDialog = true, pendingNetworkSwitch = target) }
    }

    fun cancelNetworkSwitch() {
        _uiState.update { it.copy(showNetworkSwitchDialog = false, pendingNetworkSwitch = null) }
    }

    fun confirmNetworkSwitch() {
        val target = _uiState.value.pendingNetworkSwitch ?: return
        _uiState.update { it.copy(showNetworkSwitchDialog = false, pendingNetworkSwitch = null) }

        viewModelScope.launch {
            // Cancel active polling before switching
            syncPollingJob?.cancel()
            syncPollingJob = null

            // Clear UI state for fresh network
            _uiState.update {
                it.copy(
                    balanceCkb = 0.0,
                    fiatBalance = null,
                    ckbUsdPrice = null,
                    transactions = emptyList(),
                    syncProgress = 0.0,
                    syncedToBlock = null,
                    tipBlockNumber = "",
                    peerCount = 0,
                    isSyncing = true,
                    error = null
                )
            }

            repository.switchNetwork(target)
                .onSuccess {
                    Log.d(TAG, "Network switched to ${target.name}")
                    _uiState.update {
                        it.copy(address = repository.getCurrentAddress() ?: "")
                    }
                    fetchPrice()
                    checkSyncStatusAndRefresh()
                }
                .onFailure { error ->
                    Log.e(TAG, "Network switch failed", error)
                    _uiState.update {
                        it.copy(
                            isSyncing = false,
                            error = "Network switch failed: ${error.message}"
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
    val syncedToBlock: String? = null,
    val tipBlockNumber: String = "",
    val walletInfo: WalletInfo? = null,
    val address: String = "",
    val balanceCkb: Double = 0.0,
    val fiatBalance: String? = null,
    val ckbUsdPrice: Double? = null,
    val peerCount: Int = 0,
    val transactions: List<TransactionRecord> = emptyList(),
    val error: String? = null,
    val currentSyncMode: SyncMode = SyncMode.RECENT,
    val showSyncOptionsDialog: Boolean = false,
    val showBackupDialog: Boolean = false,
    val privateKeyHex: String? = null,
    val showImportDialog: Boolean = false,
    val showImportSyncReminder: Boolean = false,
    val showBackupReminder: Boolean = false,
    val walletType: String = KeyManager.WALLET_TYPE_RAW_KEY,
    val currentNetwork: NetworkType = NetworkType.MAINNET,
    val isSwitchingNetwork: Boolean = false,
    val showNetworkSwitchDialog: Boolean = false,
    val pendingNetworkSwitch: NetworkType? = null
)
