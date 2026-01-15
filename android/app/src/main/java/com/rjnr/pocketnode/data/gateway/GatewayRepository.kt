package com.rjnr.pocketnode.data.gateway

import android.content.Context
import android.util.Log
import com.rjnr.pocketnode.data.gateway.models.*
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.WalletInfo
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import com.nervosnetwork.ckblightclient.LightClientNative
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
            Log.d(TAG, "🚀 Initializing embedded node...")
            val configName = "mainnet.toml"
            val configFile = File(context.filesDir, configName)
            
            // For debugging: Always overwrite config to ensure it's up to date
            Log.d(TAG, "📁 Copying config from assets to: ${configFile.absolutePath}")
            try {
                context.assets.open(configName).use { input ->
                    configFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to copy $configName from assets", e)
                nodeReady.complete(false)
                return
            }
            
            // Update paths in config
            val configContent = configFile.readText()
            val dataDir = File(context.filesDir, "data")
            if (!dataDir.exists()) {
                Log.d(TAG, "📂 Creating data directory: ${dataDir.absolutePath}")
                if (!dataDir.mkdirs()) {
                    Log.e(TAG, "❌ Failed to create data directory")
                    nodeReady.complete(false)
                    return
                }
            }
            
            val newConfig = configContent
                .replace("path = \"data/store\"", "path = \"${File(dataDir, "store").absolutePath}\"")
                .replace("path = \"data/network\"", "path = \"${File(dataDir, "network").absolutePath}\"")
            configFile.writeText(newConfig)
            Log.d(TAG, "📝 Config updated with absolute paths")
            Log.d(TAG, "📄 Final config content:\n$newConfig")

            // Init JNI
            Log.d(TAG, "⚙️ Calling LightClientNative.nativeInit...")
            val initResult = LightClientNative.nativeInit(
                configFile.absolutePath,
                object : LightClientNative.StatusCallback {
                    override fun onStatusChange(status: String, data: String) {
                        Log.d(TAG, "📡 Native Status Change: $status")
                        _nodeStatus.value = status
                    }
                }
            )

            if (initResult) {
                Log.d(TAG, "✅ Native init successful, starting node...")
                val startResult = LightClientNative.nativeStart()
                if (startResult) {
                    Log.d(TAG, "🚀 Node started successfully")
                    nodeReady.complete(true)
                } else {
                    Log.e(TAG, "❌ Node failed to start (nativeStart returned false)")
                    nodeReady.complete(false)
                }
            } else {
                Log.e(TAG, "❌ Failed to init node (nativeInit returned false)")
                // If init fails, maybe it's because of existing data? 
                // We could try to clear it, but let's log first.
                nodeReady.complete(false)
            }

        } catch (e: Exception) {
            Log.e(TAG, "💥 Setup error during node initialization", e)
            if (!nodeReady.isCompleted) {
                nodeReady.complete(false)
            }
        }
    }

    /**
     * Initialize wallet by loading existing one. 
     * Does NOT auto-generate a new one anymore (Onboarding flow handles that).
     */
    suspend fun initializeWallet(): Result<WalletInfo> = runCatching {
        if (keyManager.hasWallet()) {
            val info = keyManager.getWalletInfo()
            _walletInfo.value = info
            info
        } else {
            throw Exception("No wallet found")
        }
    }

    /**
     * Checks if a wallet is already configured
     */
    fun hasWallet(): Boolean = keyManager.hasWallet()

    /**
     * Create a brand new wallet and register with optimized sync
     */
    suspend fun createNewWallet(): Result<WalletInfo> = runCatching {
        Log.d(TAG, "🆕 Creating brand new wallet...")
        val info = keyManager.generateWallet()
        _walletInfo.value = info
        
        // For new wallets, use NEW_WALLET sync mode (starts from checkpoint)
        registerAccount(syncMode = SyncMode.NEW_WALLET)
        info
    }

    /**
     * Import an existing wallet
     */
    suspend fun importExistingWallet(privateKeyHex: String, syncMode: SyncMode = SyncMode.RECENT): Result<WalletInfo> = runCatching {
        Log.d(TAG, "📥 Importing existing wallet...")
        val info = keyManager.importWallet(privateKeyHex)
        _walletInfo.value = info
        _isRegistered.value = false
        
        // For imported wallets, we use the provided sync mode (default RECENT)
        registerAccount(syncMode = syncMode)
        info
    }

    suspend fun importWallet(privateKeyHex: String): Result<WalletInfo> = runCatching {
        importExistingWallet(privateKeyHex).getOrThrow()
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
        // Also use HARDCODED_CHECKPOINT as a fallback if tip is 0.
        var finalBlockNum = blockNum
        val blockNumLong = blockNum.toLongOrNull() ?: 0L
        
        if (blockNumLong > tipHeight && tipHeight > 0) {
            val recentBlock = (tipHeight - 200_000).coerceAtLeast(0L)
            Log.w(TAG, "⚠️ Detected future block number ($blockNumLong > $tipHeight). " +
                    "Resetting to RECENT height: $recentBlock")
            finalBlockNum = recentBlock.toString()
        } else if (blockNumLong == 0L && syncMode != SyncMode.FULL_HISTORY) {
            // If it resolved to 0 but we aren't doing full history, use checkpoint
            Log.d(TAG, "📍 Block resolved to 0 but mode is $syncMode. Using checkpoint $HARDCODED_CHECKPOINT")
            finalBlockNum = HARDCODED_CHECKPOINT.toString()
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
        var capacityVal = cap.capacity.removePrefix("0x").toLong(16)

        // The light client's nativeGetCellsCapacity may include spent cells
        // We need to calculate the true balance by getting live cells only
        Log.d(TAG, "🔍 Calculating true balance by filtering out spent cells...")

        try {
            // Get all transactions to find spent outpoints (inputs)
            val txJson = LightClientNative.nativeGetTransactions(
                json.encodeToString(searchKey),
                "desc",
                100,
                null
            )

            // Build a set of spent outpoints (cells used as inputs)
            val spentOutpoints = mutableSetOf<String>()
            if (txJson != null) {
                val txPag = json.decodeFromString<JniPagination<JniTxWithCell>>(txJson)
                txPag.objects.forEach { txWithCell ->
                    // For each transaction, collect all inputs as spent outpoints
                    txWithCell.transaction.inputs.forEach { input ->
                        val outpointKey = "${input.previousOutput.txHash}:${input.previousOutput.index}"
                        spentOutpoints.add(outpointKey)
                    }
                }
                Log.d(TAG, "📋 Found ${spentOutpoints.size} spent outpoints from ${txPag.objects.size} transactions")
            }

            // Get all cells and filter out spent ones
            val cellsJson = LightClientNative.nativeGetCells(
                json.encodeToString(searchKey),
                "desc",
                100,
                null
            )

            if (cellsJson != null) {
                val cellsPag = json.decodeFromString<JniPagination<JniCell>>(cellsJson)
                var liveCapacity = 0L
                var liveCellCount = 0

                cellsPag.objects.forEach { cell ->
                    val outpointKey = "${cell.outPoint.txHash}:${cell.outPoint.index}"
                    if (outpointKey !in spentOutpoints) {
                        val cellCapacity = cell.output.capacity.removePrefix("0x").toLong(16)
                        liveCapacity += cellCapacity
                        liveCellCount++
                        Log.d(TAG, "✅ Live cell: $outpointKey = $cellCapacity shannons")
                    } else {
                        Log.d(TAG, "❌ Spent cell: $outpointKey (filtered out)")
                    }
                }

                Log.d(TAG, "💰 Live balance: $liveCellCount cells, $liveCapacity shannons")
                capacityVal = liveCapacity

                // If we have 0 live cells but transactions exist, trigger rescan
                if (liveCapacity == 0L && txJson != null) {
                    val txPag = json.decodeFromString<JniPagination<JniTxWithCell>>(txJson)
                    if (txPag.objects.isNotEmpty()) {
                        Log.w(TAG, "🔄 Have ${txPag.objects.size} transactions but 0 live cells - triggering rescan")
                        val earliestBlock = txPag.objects.minOfOrNull {
                            it.blockNumber.removePrefix("0x").toLong(16)
                        } ?: 0L
                        val rescanFrom = (earliestBlock - 100).coerceAtLeast(0L)
                        Log.d(TAG, "🔄 Rescan from block $rescanFrom (earliest tx at $earliestBlock)")

                        val scriptStatus = JniScriptStatus(
                            script = info.script,
                            scriptType = "lock",
                            blockNumber = "0x${rescanFrom.toString(16)}"
                        )
                        val jsonStr = json.encodeToString(listOf(scriptStatus))
                        LightClientNative.nativeSetScripts(jsonStr, LightClientNative.CMD_SET_SCRIPTS_ALL)
                        walletPreferences.setLastSyncedBlock(rescanFrom)
                        Log.d(TAG, "✅ Rescan triggered - balance should update on next refresh")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to calculate live balance: ${e.message}")
            // Fall back to the raw capacity value if filtering fails
        }

        val ckbVal = capacityVal / 100_000_000.0

        Log.d(TAG, "💰 Final balance: $capacityVal shannons = $ckbVal CKB (at block ${cap.blockNumber})")

        val resp = BalanceResponse(
            address = addr,
            capacity = "0x${capacityVal.toString(16)}",
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

        // First, get all transactions to find spent outpoints
        val txJson = LightClientNative.nativeGetTransactions(
            json.encodeToString(searchKey),
            "desc",
            100,
            null
        )

        val spentOutpoints = mutableSetOf<String>()
        if (txJson != null) {
            val txPag = json.decodeFromString<JniPagination<JniTxWithCell>>(txJson)
            txPag.objects.forEach { txWithCell ->
                txWithCell.transaction.inputs.forEach { input ->
                    val outpointKey = "${input.previousOutput.txHash}:${input.previousOutput.index}"
                    spentOutpoints.add(outpointKey)
                }
            }
            Log.d(TAG, "📋 getCells: Found ${spentOutpoints.size} spent outpoints")
        }

        val resultJson = LightClientNative.nativeGetCells(
            json.encodeToString(searchKey),
            "desc",
            limit,
            cursor
        ) ?: throw Exception("Failed to get cells - native returned null")

        Log.d(TAG, "📦 getCells: Raw response length: ${resultJson.length}")

        // Parse as JniCell and filter out spent cells
        val pag = json.decodeFromString<JniPagination<JniCell>>(resultJson)
        val liveCells = pag.objects.filter { cell ->
            val outpointKey = "${cell.outPoint.txHash}:${cell.outPoint.index}"
            val isLive = outpointKey !in spentOutpoints
            if (!isLive) {
                Log.d(TAG, "❌ getCells: Filtering out spent cell: $outpointKey")
            }
            isLive
        }.map { it.toCell() }

        Log.d(TAG, "✅ getCells: ${liveCells.size} live cells (filtered from ${pag.objects.size} total)")
        liveCells.forEachIndexed { index, cell ->
            Log.d(TAG, "  Cell[$index]: capacity=${cell.capacity}, outPoint=${cell.outPoint.txHash.take(20)}...")
        }

        CellsResponse(liveCells, pag.lastCursor)
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

        // After sending a transaction, we need to re-register the script from a few blocks back
        // to ensure the light client scans the block containing our new change output.
        // This is necessary because the change output is created when the tx is confirmed,
        // and the light client needs to scan that block to index the new cell.
        scope.launch {
            try {
                delay(5000) // Wait a bit for tx to propagate
                val tipStr = LightClientNative.nativeGetTipHeader()
                if (tipStr != null) {
                    val tip = json.decodeFromString<JniHeaderView>(tipStr)
                    val tipNumber = tip.number.removePrefix("0x").toLong(16)
                    // Re-register from a few blocks back to catch the tx when it confirms
                    val rescanFrom = (tipNumber - 10).coerceAtLeast(0L)
                    Log.d(TAG, "🔄 Re-registering script from block $rescanFrom to catch change output")

                    val info = _walletInfo.value
                    if (info != null) {
                        val scriptStatus = JniScriptStatus(
                            script = info.script,
                            scriptType = "lock",
                            blockNumber = "0x${rescanFrom.toString(16)}"
                        )
                        val jsonStr = json.encodeToString(listOf(scriptStatus))
                        LightClientNative.nativeSetScripts(jsonStr, LightClientNative.CMD_SET_SCRIPTS_ALL)

                        // Update saved progress to rescan from here
                        walletPreferences.setLastSyncedBlock(rescanFrom)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to re-register script after send: ${e.message}")
            }
        }

        txHash
    }

    suspend fun getTransactions(limit: Int = 50, cursor: String? = null): Result<TransactionsResponse> = runCatching {
        val info = _walletInfo.value ?: throw Exception("No wallet")
        val searchKey = JniSearchKey(script = info.script)
        val myScript = info.script

        val resultJson = LightClientNative.nativeGetTransactions(
            json.encodeToString(searchKey),
            "desc",
            limit,
            cursor
        ) ?: throw Exception("Failed to get transactions")

        Log.d(TAG, "📡 getTransactions raw JSON length: ${resultJson.length}")

        val pag = json.decodeFromString<JniPagination<JniTxWithCell>>(resultJson)

        // Group by transaction hash to show a clean "one entry per transaction" UI
        val groupedTransactions = pag.objects.groupBy { it.transaction.hash }

        val items = groupedTransactions.map { (txHash, cellInteractions) ->
            val firstInteraction = cellInteractions.first()
            val tx = firstInteraction.transaction

            // Net balance change = Sum(Outputs to us) - Sum(Inputs from us)
            var netChangeShannons = 0L
            cellInteractions.forEach { interaction ->
                val cap = interaction.ioCapacity.removePrefix("0x").toLong(16)
                if (interaction.ioType == "output") {
                    netChangeShannons += cap
                } else {
                    netChangeShannons -= cap
                }
            }

            val direction = when {
                netChangeShannons > 0 -> "in"
                netChangeShannons < 0 -> "out"
                else -> "self"
            }

            // For display, we show the absolute value as the amount
            val amount = if (netChangeShannons < 0) -netChangeShannons else netChangeShannons

            TransactionRecord(
                txHash = txHash,
                blockNumber = firstInteraction.blockNumber,
                blockHash = "0x0",
                timestamp = 0L,
                balanceChange = "0x${amount.toString(16)}",
                direction = direction,
                fee = "0x0", // Fee calculation could be added if needed: Sum(Inputs) - Sum(Outputs)
                confirmations = 10 // Placeholder
            )
        }

        TransactionsResponse(items, pag.lastCursor)
    }

    suspend fun getTransactionStatus(txHash: String): Result<TransactionStatusResponse> = runCatching {
        Log.d(TAG, "🔍 getTransactionStatus: Checking status for $txHash")

        val resJson = LightClientNative.nativeGetTransaction(txHash)
        if (resJson == null) {
            Log.w(TAG, "⚠️ getTransactionStatus: Native returned null for $txHash")
            // Return unknown status instead of throwing - tx might still be in network mempool
            return@runCatching TransactionStatusResponse(
                txHash = txHash,
                status = "unknown",
                confirmations = 0,
                blockHash = null
            )
        }

        Log.d(TAG, "📦 getTransactionStatus: Response: ${resJson.take(500)}")
        val txWithStatus = json.decodeFromString<JniTransactionWithStatus>(resJson)

        val status = txWithStatus.txStatus.status
        Log.d(TAG, "📊 getTransactionStatus: Raw status='$status', blockHash=${txWithStatus.txStatus.blockHash}")

        // Calculate confirmations if committed
        val confirmations = if (status == "committed" && txWithStatus.txStatus.blockHash != null) {
            // Get tip to calculate confirmations
            val tipJson = LightClientNative.nativeGetTipHeader()
            if (tipJson != null) {
                val tip = json.decodeFromString<JniHeaderView>(tipJson)
                val tipNumber = tip.number.removePrefix("0x").toLong(16)

                // Try to get block number from transaction's header
                // For committed transactions, we know it's confirmed - use at least 1
                // The light client should have synced the block, so it's confirmed
                val estimatedConfirmations = 3 // Safe assumption for committed tx
                Log.d(TAG, "📈 Tip: $tipNumber, estimated confirmations: $estimatedConfirmations")
                estimatedConfirmations
            } else {
                1 // At least 1 confirmation if committed
            }
        } else {
            0
        }

        Log.d(TAG, "✅ getTransactionStatus: status=$status, confirmations=$confirmations")

        TransactionStatusResponse(
            txHash = txHash,
            status = status,
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

    fun getPrivateKey(): ByteArray = keyManager.getPrivateKey()

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
