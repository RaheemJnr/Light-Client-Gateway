package com.example.ckbwallet.data.gateway

import android.content.Context
import android.util.Log
import com.example.ckbwallet.data.gateway.models.*
import com.example.ckbwallet.data.wallet.KeyManager
import com.example.ckbwallet.data.wallet.WalletInfo
import com.example.ckbwallet.data.wallet.WalletPreferences
import com.nervosnetwork.ckblightclient.LightClientNative
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GatewayRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyManager: KeyManager,
    private val walletPreferences: WalletPreferences,
    private val json: Json
) {
    private val _walletInfo = MutableStateFlow<WalletInfo?>(null)
    val walletInfo: StateFlow<WalletInfo?> = _walletInfo.asStateFlow()

    private val _balance = MutableStateFlow<BalanceResponse?>(null)
    val balance: StateFlow<BalanceResponse?> = _balance.asStateFlow()

    private val _isRegistered = MutableStateFlow(false)
    val isRegistered: StateFlow<Boolean> = _isRegistered.asStateFlow()

    private val _nodeStatus = MutableStateFlow("Stopped")
    val nodeStatus: StateFlow<String> = _nodeStatus.asStateFlow()

    val network: NetworkType = NetworkType.MAINNET
    private val scope = CoroutineScope(Dispatchers.IO)
    private val nodeReady = CompletableDeferred<Boolean>()

    init {
        // Initialize the embedded node
        scope.launch {
            initializeNode()
        }
    }

    private fun initializeNode() {
        try {
            val configName = "mainnet.toml" // Using mainnet config template as per PR, but we might point to testnet
            // Copy config
            val configFile = File(context.filesDir, configName)
            if (!configFile.exists()) {
                context.assets.open(configName).use { input ->
                    configFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                // Update paths in config
                val configContent = configFile.readText()
                val dataDir = File(context.filesDir, "data")
                dataDir.mkdirs()
                val newConfig = configContent
                    .replace("path = \"data/store\"", "path = \"${File(dataDir, "store").absolutePath}\"")
                    .replace("path = \"data/network\"", "path = \"${File(dataDir, "network").absolutePath}\"")
                configFile.writeText(newConfig)
            }

            // Init JNI
            val initResult = LightClientNative.nativeInit(
                configFile.absolutePath,
                object : LightClientNative.StatusCallback {
                    override fun onStatusChange(status: String, data: String) {
                        Log.d("LightClient", "Status: $status")
                        _nodeStatus.value = status
                    }
                }
            )

            if (initResult) {
                LightClientNative.nativeStart()
                Log.d("LightClient", "Node started")
                nodeReady.complete(true)
            } else {
                Log.e("LightClient", "Failed to init node")
                nodeReady.complete(false)
            }

        } catch (e: Exception) {
            Log.e("LightClient", "Setup error", e)
            nodeReady.complete(false)
        }
    }

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
        _isRegistered.value = false // Need to re-register filter for new wallet
        registerAccount() // Auto register
        info
    }

    fun getSavedSyncMode(): SyncMode = walletPreferences.getSyncMode()
    fun getSavedCustomBlockHeight(): Long? = walletPreferences.getCustomBlockHeight()

    suspend fun registerAccount(
        syncMode: SyncMode = SyncMode.RECENT,
        customBlockHeight: Long? = null,
        savePreference: Boolean = true,
        forceResync: Boolean = false
    ): Result<Unit> = runCatching {
        // Wait for node to be ready
        if (!nodeReady.await()) {
             throw Exception("Node initialization failed")
        }

        val info = _walletInfo.value ?: throw Exception("Wallet not initialized")
        val address = when (network) {
            NetworkType.TESTNET -> info.testnetAddress
            NetworkType.MAINNET -> info.mainnetAddress
        }

        val tipStr = LightClientNative.nativeGetTipHeader()
        val tipHeight = if (tipStr != null) {
            val tip = json.decodeFromString<JniHeaderView>(tipStr)
            tip.number.removePrefix("0x").toLong(16)
        } else 0L

        // Check for existing sync progress to resume from
        val savedBlock = walletPreferences.getLastSyncedBlock()
        val existingScriptBlock = getExistingScriptBlock()

        val blockNum: String = when {
            // If force resync requested, recalculate from sync mode
            forceResync -> {
                Log.d(TAG, "🔄 Force resync requested, recalculating from sync mode")
                syncMode.toFromBlock(customBlockHeight, tipHeight)
            }
            // Resume from saved progress if available (use the higher value)
            savedBlock > 0 || existingScriptBlock > 0 -> {
                val resumeBlock = maxOf(savedBlock, existingScriptBlock)
                Log.d(TAG, "📍 Resuming sync from saved block: $resumeBlock (saved=$savedBlock, existing=$existingScriptBlock)")
                resumeBlock.toString()
            }
            // First time: calculate based on sync mode
            else -> {
                Log.d(TAG, "🆕 First time sync, calculating from mode: $syncMode")
                syncMode.toFromBlock(customBlockHeight, tipHeight)
            }
        }

        // Safety check: if blockNum is in the future, reset to a RECENT block height
        // to ensure we don't just scan from the exact tip and miss history.
        var finalBlockNum = blockNum
        val blockNumLong = blockNum.toLongOrNull() ?: 0L
        if (blockNumLong > tipHeight && tipHeight > 0) {
            val recentBlock = (tipHeight - 200_000).coerceAtLeast(0L)
            Log.w(TAG, "⚠️ Detected future block number ($blockNumLong > $tipHeight). " +
                    "Resetting to RECENT height: $recentBlock")
            finalBlockNum = recentBlock.toString()
        }

        Log.d(TAG, "🔄 Sync mode $syncMode: tip=$tipHeight, targetBlock=$finalBlockNum")

        val scriptStatus = JniScriptStatus(
            script = info.script,
            scriptType = "lock",
            blockNumber = "0x${finalBlockNum.toLongOrNull()?.toString(16) ?: "0"}"
        )
        
        Log.d(TAG, "📝 Registering script with status: ${json.encodeToString(scriptStatus)}")

        val list = listOf(scriptStatus)
        val jsonStr = json.encodeToString(list)
        
        Log.d(TAG, "📡 Calling nativeSetScripts with: $jsonStr")
        val result = LightClientNative.nativeSetScripts(jsonStr, LightClientNative.CMD_SET_SCRIPTS_ALL)
        if (!result) throw Exception("Failed to set scripts")

        _isRegistered.value = true
        if (savePreference) {
            walletPreferences.setSyncMode(syncMode)
            if (syncMode == SyncMode.CUSTOM) {
                walletPreferences.setCustomBlockHeight(customBlockHeight)
            }
            walletPreferences.setInitialSyncCompleted(true)
        }
    }

    suspend fun resyncAccount(
        syncMode: SyncMode,
        customBlockHeight: Long? = null
    ): Result<Unit> {
        _isRegistered.value = false
        // Clear saved sync progress when explicitly resyncing
        walletPreferences.setLastSyncedBlock(0L)
        return registerAccount(syncMode, customBlockHeight, savePreference = true, forceResync = true)
    }

    fun hasCompletedInitialSync(): Boolean = walletPreferences.hasCompletedInitialSync()
    
    suspend fun forceResetSync(): Result<Unit> = runCatching {
        Log.w(TAG, "♻️ Forcing sync reset...")
        walletPreferences.clear()
        _isRegistered.value = false
        _balance.value = null
        registerAccount(SyncMode.RECENT)
        Log.i(TAG, "♻️ Sync reset complete. Registered as RECENT.")
    }

    suspend fun refreshBalance(address: String? = null): Result<BalanceResponse> = runCatching {
        val addr = address ?: getCurrentAddress() ?: throw Exception("Wallet not initialized")
        val info = _walletInfo.value ?: throw Exception("No wallet")
        
        val searchKey = JniSearchKey(script = info.script)
        Log.d(TAG, "🔍 Fetching balance for script: ${json.encodeToString(searchKey)}")
        
        val responseJson = LightClientNative.nativeGetCellsCapacity(json.encodeToString(searchKey)) 
            ?: throw Exception("Failed to get capacity - null response")
        
        Log.d(TAG, "📊 Raw capacity response: $responseJson")
            
        val cap = json.decodeFromString<JniCellsCapacity>(responseJson)
        
        // Convert to balance response
        val capacityVal = cap.capacity.removePrefix("0x").toLong(16)
        val ckbVal = capacityVal / 100_000_000.0
        
        Log.d(TAG, "💰 Parsed capacity: ${cap.capacity} = $ckbVal CKB (at block ${cap.blockNumber})")
        
        val resp = BalanceResponse(
            address = addr,
            capacity = cap.capacity,
            capacityCkb = ckbVal.toString(),
            asOfBlock = cap.blockNumber
        )
        _balance.value = resp
        resp
    }

    // Simplified Account Status - JNI doesn't give sync progress easily
    suspend fun getAccountStatus(): Result<AccountStatusResponse> = runCatching {
        val addr = getCurrentAddress() ?: throw Exception("No wallet")
        
        // Fetch tip header
        val tipJson = LightClientNative.nativeGetTipHeader()
        val tipNumber = if (tipJson != null) {
            val tip = json.decodeFromString<JniHeaderView>(tipJson)
            tip.number.removePrefix("0x").toLong(16)
        } else {
            0L
        }

        // Fetch script status
        val scriptsJson = LightClientNative.nativeGetScripts()
        val scriptBlockNumber = if (scriptsJson != null) {
            val scripts = json.decodeFromString<List<JniScriptStatus>>(scriptsJson)
            // Assuming we only trust one script (our wallet lock script)
            scripts.firstOrNull()?.blockNumber?.removePrefix("0x")?.toLong(16) ?: 0L
        } else {
            0L
        }

        // Save sync progress if we've made progress
        if (scriptBlockNumber > walletPreferences.getLastSyncedBlock()) {
            walletPreferences.setLastSyncedBlock(scriptBlockNumber)
            Log.d(TAG, "💾 Saved sync progress: block $scriptBlockNumber")
        }

        // Log sync progress for debugging
        Log.d(TAG, "📈 SYNC STATUS: tip=$tipNumber, scriptBlock=$scriptBlockNumber, " +
                "behind=${tipNumber - scriptBlockNumber} blocks")

        // Calculate progress
        // Note: Light client might be syncing headers, so tipNumber increases over time.
        // Script block number catches up to tipNumber.
        
        val progress = if (tipNumber > 0) {
            scriptBlockNumber.toDouble() / tipNumber.toDouble()
        } else {
            0.0
        }
        
        val isSynced = tipNumber > 0 && 
                scriptBlockNumber >= tipNumber - 10 && 
                scriptBlockNumber <= tipNumber + 10 // Handle slight mismatches safely
        
        Log.d(TAG, "📊 SYNC PROGRESS: ${(progress * 100).toInt()}% synced, isSynced=$isSynced")

        AccountStatusResponse(
            address = addr,
            isRegistered = _isRegistered.value,
            tipNumber = tipNumber.toString(),
            syncedToBlock = scriptBlockNumber.toString(),
            syncProgress = progress.coerceIn(0.0, 1.0),
            isSynced = isSynced
        )
    }

    suspend fun getCells(address: String? = null, limit: Int = 100, cursor: String? = null): Result<CellsResponse> = runCatching {
        val info = _walletInfo.value ?: throw Exception("No wallet")
        val searchKey = JniSearchKey(script = info.script)

        Log.d(TAG, "🔍 getCells: Fetching cells for script: ${json.encodeToString(searchKey)}")

        val resultJson = LightClientNative.nativeGetCells(
            json.encodeToString(searchKey),
            "desc",
            limit,
            cursor
        ) ?: throw Exception("Failed to get cells - native returned null")

        Log.d(TAG, "📦 getCells: Raw response length: ${resultJson.length}")
        Log.d(TAG, "📦 getCells: Raw response preview: ${resultJson.take(500)}")

        // Parse as JniCell (which has nested output structure) and convert to Cell
        val pag = json.decodeFromString<JniPagination<JniCell>>(resultJson)
        val cells = pag.objects.map { it.toCell() }

        Log.d(TAG, "✅ getCells: Parsed ${cells.size} cells successfully")
        cells.forEachIndexed { index, cell ->
            Log.d(TAG, "  Cell[$index]: capacity=${cell.capacity}, outPoint=${cell.outPoint.txHash.take(20)}...")
        }

        CellsResponse(cells, pag.lastCursor)
    }

    suspend fun sendTransaction(transaction: Transaction): Result<String> = runCatching {
        Log.d(TAG, "📤 sendTransaction: Building transaction JSON...")
        Log.d(TAG, "  Inputs: ${transaction.cellInputs.size}, Outputs: ${transaction.cellOutputs.size}")

        val txJson = json.encodeToString(transaction)
        Log.d(TAG, "📤 sendTransaction: JSON length=${txJson.length}")
        Log.d(TAG, "📤 sendTransaction: JSON preview: ${txJson.take(300)}...")

        val rawResult = LightClientNative.nativeSendTransaction(txJson)
            ?: throw Exception("Send failed - native returned null")

        // The Rust JNI returns the tx hash as a JSON string (with quotes), so we need to parse it
        val txHash = rawResult.trim('"')
        Log.d(TAG, "✅ sendTransaction: Success! txHash=$txHash (raw: $rawResult)")
        txHash
    }

    suspend fun getTransactions(limit: Int = 50, cursor: String? = null): Result<TransactionsResponse> = runCatching {
        val info = _walletInfo.value ?: throw Exception("No wallet")
        val searchKey = JniSearchKey(script = info.script)
        
        val resultJson = LightClientNative.nativeGetTransactions(
            json.encodeToString(searchKey),
            "desc",
            limit,
            cursor
        ) ?: throw Exception("Failed to get transactions")

        Log.d(TAG, "📡 getTransactions raw JSON length: ${resultJson.length}")
        
        val pag = json.decodeFromString<JniPagination<JniTxWithCell>>(resultJson)
        
        // Map to TransactionRecord
        val items = pag.objects.map { txWithCell ->
            val tx = txWithCell.transaction
            val ioType = txWithCell.ioType
            val ioIndex = txWithCell.ioIndex.removePrefix("0x").toInt(16)
            
            // For outputs, we have the capacity directly
            // For inputs, we'd need to fetch the previous cell to get the capacity.
            // For now, we'll show the output capacity if it's an "in" tx (representing the spent cell)
            // if we can find it, or just 0.
            
            var amount = 0L
            val direction = if (ioType == "output") "in" else "out"
            
            if (ioType == "output") {
                amount = tx.outputs[ioIndex].capacity.removePrefix("0x").toLong(16)
            } else {
                // For "out", the ioIndex refers to the input index.
                // We don't have the capacity of the spent cell here easily.
                // Log it for now.
                Log.d(TAG, "🔍 Mapping 'out' transaction ${tx.hash}, input index $ioIndex")
            }
            
            TransactionRecord(
                txHash = tx.hash,
                blockNumber = txWithCell.blockNumber,
                blockHash = "0x0", 
                timestamp = 0L, 
                balanceChange = "0x${amount.toString(16)}",
                direction = direction,
                fee = "0x0",
                confirmations = 10 // Assumption
            )
        }
        
        TransactionsResponse(items, pag.lastCursor)
    }

    suspend fun getTransactionStatus(txHash: String): Result<TransactionStatusResponse> = runCatching {
        Log.d(TAG, "🔍 getTransactionStatus: Checking status for $txHash")

        val resJson = LightClientNative.nativeGetTransaction(txHash)
        if (resJson == null) {
            Log.w(TAG, "⚠️ getTransactionStatus: Native returned null for $txHash")
            throw Exception("Tx not found")
        }

        Log.d(TAG, "📦 getTransactionStatus: Response: ${resJson.take(200)}")
        val txWithStatus = json.decodeFromString<JniTransactionWithStatus>(resJson)

        // Calculate confirmations if committed
        val confirmations = if (txWithStatus.txStatus.status == "committed" && txWithStatus.txStatus.blockHash != null) {
            // Get tip to calculate confirmations
            val tipJson = LightClientNative.nativeGetTipHeader()
            if (tipJson != null) {
                val tip = json.decodeFromString<JniHeaderView>(tipJson)
                val tipNumber = tip.number.removePrefix("0x").toLong(16)
                // We'd need block number from the tx, but for now estimate based on time
                // For simplicity, if committed, assume at least 1 confirmation
                maxOf(1, 3) // Placeholder - needs block number from tx
            } else {
                1
            }
        } else {
            0
        }

        Log.d(TAG, "✅ getTransactionStatus: status=${txWithStatus.txStatus.status}, confirmations=$confirmations")

        TransactionStatusResponse(
            txHash = txHash,
            status = txWithStatus.txStatus.status,
            confirmations = confirmations,
            blockHash = txWithStatus.txStatus.blockHash
        )
    }

    suspend fun getGatewayStatus(): Result<StatusResponse> = runCatching {
        StatusResponse("testnet", "0x0", "0x0", 0, false, true)
    }

    fun getCurrentAddress(): String? {
        val info = _walletInfo.value ?: return null
        return when (network) {
            NetworkType.TESTNET -> info.testnetAddress
            NetworkType.MAINNET -> info.mainnetAddress
        }
    }

    fun hasWallet(): Boolean = keyManager.hasWallet()

    /**
     * Get the current block number from the registered script in the light client.
     * This represents how far the light client has synced for our wallet.
     */
    private fun getExistingScriptBlock(): Long {
        return try {
            val scriptsJson = LightClientNative.nativeGetScripts() ?: return 0L
            val scripts = json.decodeFromString<List<JniScriptStatus>>(scriptsJson)
            scripts.firstOrNull()?.blockNumber?.removePrefix("0x")?.toLong(16) ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get existing script block: ${e.message}")
            0L
        }
    }

    // Status UI methods
    suspend fun getPeers(): String? = withContext(Dispatchers.IO) {
        LightClientNative.nativeGetPeers()
    }

    suspend fun getTipHeader(): String? = withContext(Dispatchers.IO) {
        LightClientNative.nativeGetTipHeader()
    }

    suspend fun getScripts(): String? = withContext(Dispatchers.IO) {
        LightClientNative.nativeGetScripts()
    }

    suspend fun callRpc(method: String): String? = withContext(Dispatchers.IO) {
        LightClientNative.callRpc(method)
    }

    companion object {
        private const val TAG = "GatewayRepository"
    }
}
