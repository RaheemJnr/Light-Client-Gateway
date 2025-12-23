package com.example.ckbwallet.data.gateway

import android.util.Log
import com.example.ckbwallet.data.gateway.models.*
import com.example.ckbwallet.data.wallet.KeyManager
import com.example.ckbwallet.data.wallet.WalletInfo
import com.example.ckbwallet.data.wallet.WalletPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GatewayRepository @Inject constructor(
    private val api: GatewayApi,
    private val keyManager: KeyManager,
    private val walletPreferences: WalletPreferences
) {
    private val _walletInfo = MutableStateFlow<WalletInfo?>(null)
    val walletInfo: StateFlow<WalletInfo?> = _walletInfo.asStateFlow()

    private val _balance = MutableStateFlow<BalanceResponse?>(null)
    val balance: StateFlow<BalanceResponse?> = _balance.asStateFlow()

    private val _isRegistered = MutableStateFlow(false)
    val isRegistered: StateFlow<Boolean> = _isRegistered.asStateFlow()

    val network: NetworkType = NetworkType.TESTNET

    suspend fun initializeWallet(): Result<WalletInfo> = runCatching {
        val info = if (keyManager.hasWallet()) {
            keyManager.getWalletInfo()
        } else {
            keyManager.generateWallet()
        }
        _walletInfo.value = info
        info
    }

    suspend fun importWallet(privateKeyHex: String): Result<WalletInfo> = runCatching {
        val info = keyManager.importWallet(privateKeyHex)
        _walletInfo.value = info
        _isRegistered.value = false
        info
    }

    /**
     * Get the saved sync mode from preferences
     */
    fun getSavedSyncMode(): SyncMode = walletPreferences.getSyncMode()

    /**
     * Get the saved custom block height from preferences
     */
    fun getSavedCustomBlockHeight(): Long? = walletPreferences.getCustomBlockHeight()

    /**
     * Register account with the light client using the specified sync mode.
     *
     * @param syncMode The sync mode to use:
     *   - NEW_WALLET: Sync from current tip (no history, fastest)
     *   - RECENT: Sync ~30 days of history (default, good balance)
     *   - FULL_HISTORY: Sync from genesis (complete history, slowest)
     *   - CUSTOM: Sync from a specific block height
     * @param customBlockHeight Block height to start from (only used with CUSTOM mode)
     * @param savePreference Whether to save the sync mode to preferences (default true)
     */
    suspend fun registerAccount(
        syncMode: SyncMode = SyncMode.RECENT,
        customBlockHeight: Long? = null,
        savePreference: Boolean = true
    ): Result<Unit> {
        val info = _walletInfo.value ?: return Result.failure(Exception("Wallet not initialized"))
        val address = when (network) {
            NetworkType.TESTNET -> info.testnetAddress
            NetworkType.MAINNET -> info.mainnetAddress
        }

        val fromBlock = syncMode.toFromBlock(customBlockHeight)

        Log.d("GatewayRepository", "Registering account with address: $address")
        Log.d("GatewayRepository", "Sync mode: $syncMode, fromBlock: $fromBlock")
        Log.d("GatewayRepository", "Script codeHash: ${info.script.codeHash}")
        Log.d("GatewayRepository", "Script hashType: ${info.script.hashType}")
        Log.d("GatewayRepository", "Script args: ${info.script.args}")

        return api.registerAccount(
            RegisterAccountRequest(
                address = address,
                script = info.script,
                fromBlock = fromBlock
            )
        ).onFailure { error ->
            Log.e("GatewayRepository", "Registration failed", error)
        }.map {
            Log.d("GatewayRepository", "Registration successful with sync mode: $syncMode")
            _isRegistered.value = true

            // Save the sync preferences if requested
            if (savePreference) {
                walletPreferences.setSyncMode(syncMode)
                if (syncMode == SyncMode.CUSTOM) {
                    walletPreferences.setCustomBlockHeight(customBlockHeight)
                }
                walletPreferences.setInitialSyncCompleted(true)
            }
        }
    }

    /**
     * Re-register account with a different sync mode.
     * Useful when user wants to load more historical transactions.
     */
    suspend fun resyncAccount(
        syncMode: SyncMode,
        customBlockHeight: Long? = null
    ): Result<Unit> {
        _isRegistered.value = false
        return registerAccount(syncMode, customBlockHeight, savePreference = true)
    }

    /**
     * Check if user has completed initial sync setup
     */
    fun hasCompletedInitialSync(): Boolean = walletPreferences.hasCompletedInitialSync()

    suspend fun refreshBalance(address: String? = null): Result<BalanceResponse> {
        val addr = address ?: getCurrentAddress() ?: return Result.failure(Exception("Wallet not initialized"))
        return api.getBalance(addr).onSuccess {
            _balance.value = it
        }
    }

    suspend fun getAccountStatus(): Result<AccountStatusResponse> {
        val address = getCurrentAddress() ?: return Result.failure(Exception("Wallet not initialized"))
        return api.getAccountStatus(address)
    }

    suspend fun getCells(address: String? = null, limit: Int = 100, cursor: String? = null): Result<CellsResponse> {
        val addr = address ?: getCurrentAddress() ?: return Result.failure(Exception("Wallet not initialized"))
        return api.getCells(addr, limit, cursor)
    }

    suspend fun sendTransaction(transaction: Transaction): Result<String> {
        return api.sendTransaction(SendTransactionRequest(transaction)).map { it.txHash }
    }

    suspend fun getTransactions(limit: Int = 50, cursor: String? = null): Result<TransactionsResponse> {
        val address = getCurrentAddress() ?: return Result.failure(Exception("Wallet not initialized"))
        return api.getTransactions(address, limit, cursor)
    }

    suspend fun getTransactionStatus(txHash: String): Result<TransactionStatusResponse> {
        return api.getTransactionStatus(txHash)
    }

    suspend fun getGatewayStatus(): Result<StatusResponse> = api.getStatus()

    fun getCurrentAddress(): String? {
        val info = _walletInfo.value ?: return null
        return when (network) {
            NetworkType.TESTNET -> info.testnetAddress
            NetworkType.MAINNET -> info.mainnetAddress
        }
    }

    fun hasWallet(): Boolean = keyManager.hasWallet()
}
