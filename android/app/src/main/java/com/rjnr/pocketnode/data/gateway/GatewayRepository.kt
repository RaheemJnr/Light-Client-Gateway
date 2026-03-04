package com.rjnr.pocketnode.data.gateway

import android.content.Context
import android.util.Log
import com.rjnr.pocketnode.data.gateway.models.*
import com.rjnr.pocketnode.data.transaction.TransactionBuilder
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.WalletInfo
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import com.nervosnetwork.ckblightclient.LightClientNative
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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
    private val json: Json,
    private val transactionBuilder: TransactionBuilder,
    private val cacheManager: CacheManager,
    private val daoSyncManager: DaoSyncManager
) {
    private val _walletInfo = MutableStateFlow<WalletInfo?>(null)
    val walletInfo: StateFlow<WalletInfo?> = _walletInfo.asStateFlow()

    private val _balance = MutableStateFlow<BalanceResponse?>(null)
    val balance: StateFlow<BalanceResponse?> = _balance.asStateFlow()

    private val _isRegistered = MutableStateFlow(false)
    val isRegistered: StateFlow<Boolean> = _isRegistered.asStateFlow()

    private val _nodeStatus = MutableStateFlow("Stopped")
    val nodeStatus: StateFlow<String> = _nodeStatus.asStateFlow()

    private val _network = MutableStateFlow(walletPreferences.getSelectedNetwork())
    val network: StateFlow<NetworkType> = _network.asStateFlow()
    val currentNetwork: NetworkType get() = _network.value

    private val _isSwitchingNetwork = MutableStateFlow(false)
    val isSwitchingNetwork: StateFlow<Boolean> = _isSwitchingNetwork.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO)
    private val _nodeReady = MutableStateFlow<Boolean?>(null)

    init {
        // Migrate old flat data/ directory to data/mainnet/ on first run
        migrateDataDirectoryIfNeeded()

        // Initialize the embedded node for the persisted network
        scope.launch {
            initializeNode(currentNetwork)
        }
    }

    /**
     * Suspends until the node is ready. Returns true if init succeeded, false if it failed.
     */
    private suspend fun awaitNodeReady(): Boolean {
        return _nodeReady.filterNotNull().first()
    }

    /**
     * One-time migration: moves old flat data/ layout (store.db, network/) into data/mainnet/.
     * Existing users upgrading from pre-testnet have data directly in data/ — this moves it
     * so each network gets its own isolated subdirectory.
     */
    private fun migrateDataDirectoryIfNeeded() {
        val dataDir = File(context.filesDir, "data")
        val mainnetDir = File(dataDir, "mainnet")
        val storeDb = File(dataDir, "store.db")
        val networkDir = File(dataDir, "network")

        // If mainnet subdir already exists or there's nothing to migrate, skip
        if (mainnetDir.exists() || (!storeDb.exists() && !networkDir.exists())) return

        Log.d(TAG, "Migrating data directory to per-network layout...")
        if (!mainnetDir.mkdirs() && !mainnetDir.exists()) {
            Log.e(TAG, "Failed to create mainnet directory, skipping migration")
            return
        }

        var migrationOk = true
        if (storeDb.exists()) {
            if (storeDb.renameTo(File(mainnetDir, "store.db"))) {
                Log.d(TAG, "Moved store.db -> mainnet/store.db")
            } else {
                Log.e(TAG, "Failed to move store.db to mainnet/store.db")
                migrationOk = false
            }
        }
        if (networkDir.exists()) {
            if (networkDir.renameTo(File(mainnetDir, "network"))) {
                Log.d(TAG, "Moved network/ -> mainnet/network/")
            } else {
                Log.e(TAG, "Failed to move network/ to mainnet/network/")
                migrationOk = false
            }
        }
        if (!migrationOk) {
            Log.e(TAG, "Migration incomplete — manual intervention may be needed")
        }
    }

    private suspend fun initializeNode(targetNetwork: NetworkType) {
        try {
            _nodeReady.value = null // Reset for re-initialization
            Log.d(TAG, "Initializing embedded node for ${targetNetwork.name}...")

            val configName = "${targetNetwork.name.lowercase()}.toml"
            val configFile = File(context.filesDir, configName)

            // Copy config from assets (deterministic, no retry needed)
            Log.d(TAG, "Copying config from assets: $configName")
            try {
                context.assets.open(configName).use { input ->
                    configFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy $configName from assets", e)
                _nodeReady.value = false
                return
            }

            // Update paths in config — each network gets its own data subdirectory
            val configContent = configFile.readText()
            val dataDir = File(context.filesDir, "data/${targetNetwork.name.lowercase()}")
            if (!dataDir.exists()) {
                Log.d(TAG, "Creating data directory: ${dataDir.absolutePath}")
                if (!dataDir.mkdirs()) {
                    Log.e(TAG, "Failed to create data directory")
                    _nodeReady.value = false
                    return
                }
            }

            val newConfig = configContent
                .replace("path = \"data/store\"", "path = \"${File(dataDir, "store.db").absolutePath}\"")
                .replace("path = \"data/network\"", "path = \"${File(dataDir, "network").absolutePath}\"")
            configFile.writeText(newConfig)
            Log.d(TAG, "Config updated with absolute paths for ${targetNetwork.name}")

            // Init and start JNI with retry (transient failures can occur)
            val maxRetries = 3
            val backoffMs = longArrayOf(2_000, 4_000, 8_000)

            for (attempt in 1..maxRetries) {
                Log.d(TAG, "JNI init attempt $attempt/$maxRetries...")

                val initResult = LightClientNative.nativeInit(
                    configFile.absolutePath,
                    object : LightClientNative.StatusCallback {
                        override fun onStatusChange(status: String, data: String) {
                            Log.d(TAG, "Native Status Change: $status")
                            _nodeStatus.value = status
                        }
                    }
                )

                if (!initResult) {
                    Log.e(TAG, "nativeInit returned false (attempt $attempt)")
                    if (attempt < maxRetries) {
                        delay(backoffMs[attempt - 1])
                        continue
                    }
                    _nodeReady.value = false
                    return
                }

                val startResult = LightClientNative.nativeStart()
                if (startResult) {
                    Log.d(TAG, "Node started successfully on ${targetNetwork.name} (attempt $attempt)")
                    _nodeReady.value = true
                    return
                }

                Log.e(TAG, "nativeStart returned false (attempt $attempt)")
                if (attempt < maxRetries) {
                    delay(backoffMs[attempt - 1])
                } else {
                    _nodeReady.value = false
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Setup error during node initialization", e)
            _nodeReady.value = false
        }
    }

    /**
     * Switches to a different network by persisting the selection and restarting the process.
     *
     * The JNI light client does not support in-process re-initialization: nativeStop() blocks
     * indefinitely while peers are connected, and nativeInit() rejects calls when already
     * initialized. Restarting the process gives a clean JNI state at zero engineering cost.
     *
     * Process death safety: setSelectedNetwork() uses commit() (synchronous) so the preference
     * is guaranteed on disk before killProcess(). On restart, initializeNode() reads the new
     * network from WalletPreferences. Data directories are isolated per network.
     */
    suspend fun switchNetwork(target: NetworkType): Result<Unit> = runCatching {
        if (target == currentNetwork) return@runCatching
        if (_isSwitchingNetwork.value) throw Exception("Network switch already in progress")

        _isSwitchingNetwork.value = true
        try {
            Log.d(TAG, "Switching network: ${currentNetwork.name} -> ${target.name}")

            // The JNI light client does not support re-initialization in the same process lifetime:
            // nativeStop() blocks indefinitely (peer disconnection loop) and nativeInit() rejects
            // calls while already initialized ("Already initialized!"). The only reliable path is
            // to persist the selection and restart the process — Android will relaunch the app and
            // initializeNode() will pick up the new network from WalletPreferences.

            // Clear Room caches before process restart
            cacheManager.clearAll()
            daoSyncManager.clearAll()

            walletPreferences.setSelectedNetwork(target) // uses commit() — synchronous flush
            Log.d(TAG, "Persisted ${target.name}, restarting process for clean JNI init")
            android.os.Process.killProcess(android.os.Process.myPid())
            // Process is terminated above; code below is unreachable but satisfies the compiler
        } catch (e: Exception) {
            _isSwitchingNetwork.value = false
            throw e
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

    /**
     * Create a new wallet with BIP39 mnemonic.
     * Returns wallet info and the 12/24 mnemonic words for backup.
     */
    suspend fun createWalletWithMnemonic(): Result<Pair<WalletInfo, List<String>>> = runCatching {
        Log.d(TAG, "Creating mnemonic wallet...")
        val (info, words) = keyManager.generateWalletWithMnemonic()
        _walletInfo.value = info
        registerAccount(syncMode = SyncMode.NEW_WALLET)
        Pair(info, words)
    }

    /**
     * Import wallet from BIP39 mnemonic words.
     */
    suspend fun importFromMnemonic(
        words: List<String>,
        passphrase: String = "",
        syncMode: SyncMode = SyncMode.RECENT
    ): Result<WalletInfo> = runCatching {
        Log.d(TAG, "Importing wallet from mnemonic...")
        val info = keyManager.importWalletFromMnemonic(words, passphrase)
        _walletInfo.value = info
        _isRegistered.value = false
        registerAccount(syncMode = syncMode)
        info
    }

    fun getWalletType(): String = keyManager.getWalletType()
    fun getMnemonic(): List<String>? = keyManager.getMnemonic()
    fun hasMnemonicBackup(): Boolean = keyManager.hasMnemonicBackup()
    fun setMnemonicBackedUp(backedUp: Boolean) = keyManager.setMnemonicBackedUp(backedUp)

    fun getSavedSyncMode(): SyncMode = walletPreferences.getSyncMode()
    fun getSavedCustomBlockHeight(): Long? = walletPreferences.getCustomBlockHeight()

    suspend fun registerAccount(
        syncMode: SyncMode = SyncMode.RECENT,
        customBlockHeight: Long? = null,
        savePreference: Boolean = true,
        forceResync: Boolean = false
    ): Result<Unit> = runCatching {
        // Wait for node to be ready
        if (!awaitNodeReady()) {
             throw Exception("Node initialization failed")
        }

        val info = _walletInfo.value ?: throw Exception("Wallet not initialized")

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
                Log.d(TAG, "Force resync requested, recalculating from sync mode")
                syncMode.toFromBlock(customBlockHeight, tipHeight, currentNetwork)
            }
            // Resume from saved progress if available (use the higher value)
            savedBlock > 0 || existingScriptBlock > 0 -> {
                val resumeBlock = maxOf(savedBlock, existingScriptBlock)
                Log.d(TAG, "Resuming sync from saved block: $resumeBlock (saved=$savedBlock, existing=$existingScriptBlock)")
                resumeBlock.toString()
            }
            // First time: calculate based on sync mode
            else -> {
                Log.d(TAG, "First time sync, calculating from mode: $syncMode")
                syncMode.toFromBlock(customBlockHeight, tipHeight, currentNetwork)
            }
        }

        // Safety check: if blockNum is in the future, reset to a RECENT block height.
        // Use network-aware checkpoint as a fallback if tip is 0.
        val checkpoint = getCheckpoint(currentNetwork)
        var finalBlockNum = blockNum
        val blockNumLong = blockNum.toLongOrNull() ?: 0L

        if (blockNumLong > tipHeight && tipHeight > 0) {
            val recentBlock = (tipHeight - 200_000).coerceAtLeast(0L)
            Log.w(TAG, "Detected future block number ($blockNumLong > $tipHeight). " +
                    "Resetting to RECENT height: $recentBlock")
            finalBlockNum = recentBlock.toString()
        } else if (blockNumLong == 0L && syncMode != SyncMode.FULL_HISTORY && checkpoint > 0) {
            // If it resolved to 0 but we aren't doing full history, use checkpoint
            Log.d(TAG, "Block resolved to 0 but mode is $syncMode. Using checkpoint $checkpoint")
            finalBlockNum = checkpoint.toString()
        }

        Log.d(TAG, "🔄 Sync mode $syncMode: tip=$tipHeight, targetBlock=$finalBlockNum")

        val blockNumberHex = "0x${finalBlockNum.toLongOrNull()?.toString(16) ?: "0"}"
        val jsonStr = buildScriptStatusList(info.script, blockNumberHex)

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

        // --- Cache-first: emit cached balance immediately ---
        cacheManager.getCachedBalance(currentNetwork.name)?.let {
            _balance.value = it
        }

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
                        // Exclude cells with type scripts (DAO cells, etc.) from available balance
                        // Like Neuron: typeHash IS NULL AND hasData = false
                        if (cell.output.type != null) {
                            val cellCapacity = cell.output.capacity.removePrefix("0x").toLong(16)
                            Log.d(TAG, "🔒 DAO/typed cell excluded from balance: $outpointKey = $cellCapacity shannons")
                        } else {
                            val cellCapacity = cell.output.capacity.removePrefix("0x").toLong(16)
                            liveCapacity += cellCapacity
                            liveCellCount++
                            Log.d(TAG, "✅ Live cell: $outpointKey = $cellCapacity shannons")
                        }
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

                        val blockNumberHex = "0x${rescanFrom.toString(16)}"
                        val lockStatus = JniScriptStatus(
                            script = info.script,
                            scriptType = "lock",
                            blockNumber = blockNumberHex
                        )
                        val jsonStr = json.encodeToString(listOf(lockStatus))
                        LightClientNative.nativeSetScripts(jsonStr, LightClientNative.CMD_SET_SCRIPTS_PARTIAL)
                        walletPreferences.setLastSyncedBlock(rescanFrom)
                        Log.d(TAG, "✅ Rescan triggered (partial) - balance should update on next refresh")
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

        // --- Cache write ---
        cacheManager.cacheBalance(resp, currentNetwork.name)

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
            // Also exclude cells with type scripts (DAO cells) — they can't be spent as regular inputs
            val hasTypeScript = cell.output.type != null
            if (hasTypeScript && isLive) {
                Log.d(TAG, "🔒 getCells: Excluding typed cell (DAO): $outpointKey")
            }
            isLive && !hasTypeScript
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

        // Pre-flight checks (defense-in-depth, TransactionBuilder also validates)
        require(transaction.cellInputs.isNotEmpty()) { "Transaction has no inputs" }
        require(transaction.cellOutputs.isNotEmpty()) { "Transaction has no outputs" }
        for (output in transaction.cellOutputs) {
            val capacity = output.capacity.removePrefix("0x").toLong(16)
            require(capacity >= TransactionBuilder.MIN_CELL_CAPACITY) {
                "Output capacity ${capacity / 100_000_000.0} CKB is below minimum 61 CKB"
            }
        }

        val txJson = json.encodeToString(transaction)
        Log.d(TAG, "📤 sendTransaction: JSON length=${txJson.length}")
        Log.d(TAG, "📤 sendTransaction: JSON preview: ${txJson.take(300)}...")

        val rawResult = LightClientNative.nativeSendTransaction(txJson)
            ?: throw Exception("Send failed - native returned null")

        // The Rust JNI returns the tx hash as a JSON string (with quotes), so we need to parse it
        val txHash = rawResult.trim('"')
        Log.d(TAG, "✅ sendTransaction: Success! txHash=$txHash (raw: $rawResult)")

        // Cache pending transaction in Room
        cacheManager.insertPendingTransaction(txHash, currentNetwork.name)

        // After sending, nudge the light client to rescan from a few blocks back
        // so it picks up the new change output when the tx confirms.
        scope.launch {
            try {
                delay(5000) // Wait a bit for tx to propagate
                val tipStr = LightClientNative.nativeGetTipHeader()
                if (tipStr != null) {
                    val tip = json.decodeFromString<JniHeaderView>(tipStr)
                    val tipNumber = tip.number.removePrefix("0x").toLong(16)
                    val rescanFrom = (tipNumber - 10).coerceAtLeast(0L)
                    Log.d(TAG, "🔄 Partial re-register from block $rescanFrom to catch change output")

                    val info = _walletInfo.value
                    if (info != null) {
                        val blockNumberHex = "0x${rescanFrom.toString(16)}"
                        // Only register lock script (not DAO type) with PARTIAL mode
                        val lockStatus = JniScriptStatus(
                            script = info.script,
                            scriptType = "lock",
                            blockNumber = blockNumberHex
                        )
                        val jsonStr = json.encodeToString(listOf(lockStatus))
                        LightClientNative.nativeSetScripts(jsonStr, LightClientNative.CMD_SET_SCRIPTS_PARTIAL)
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

        // Fetch tip height once for confirmation calculations (avoid per-tx JNI calls)
        val tipHeight = runCatching {
            LightClientNative.nativeGetTipHeader()
                ?.let { json.decodeFromString<JniHeaderView>(it) }
                ?.number?.removePrefix("0x")?.toLongOrNull(16)
        }.getOrNull() ?: 0L

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

            // Attempt to fetch block header to get real timestamp and block hash.
            // nativeGetTransaction gives us the blockHash, then nativeGetHeader gives the header.
            data class HeaderInfo(val timestampHex: String?, val hash: String?)
            val headerInfo: HeaderInfo = runCatching {
                val txWithStatus = LightClientNative.nativeGetTransaction(txHash)
                    ?.let { json.decodeFromString<JniTransactionWithStatus>(it) }
                val blockHashFromStatus = txWithStatus?.txStatus?.blockHash
                if (blockHashFromStatus != null) {
                    // Try local lookup first, then trigger a fetch if not cached
                    val headerJson = LightClientNative.nativeGetHeader(blockHashFromStatus)
                    val header = headerJson?.let { json.decodeFromString<JniHeaderView>(it) }
                    if (header != null) {
                        HeaderInfo(timestampHex = header.timestamp, hash = header.hash)
                    } else {
                        // Header not cached locally — ask light client to fetch it
                        val fetchJson = LightClientNative.nativeFetchHeader(blockHashFromStatus)
                        val fetchResult = fetchJson?.let { json.decodeFromString<JniFetchHeaderResponse>(it) }
                        if (fetchResult?.status == "fetched" && fetchResult.data != null) {
                            HeaderInfo(timestampHex = fetchResult.data.timestamp, hash = fetchResult.data.hash)
                        } else {
                            HeaderInfo(null, null)
                        }
                    }
                } else HeaderInfo(null, null)
            }.onFailure { e ->
                Log.w(TAG, "getTransactions: failed to fetch header for $txHash: ${e.message}")
            }.getOrElse { HeaderInfo(null, null) }

            // Derive confirmations from tip block height vs transaction block number
            val txBlockNum = firstInteraction.blockNumber.removePrefix("0x")
                .toLongOrNull(16) ?: 0L
            val confirmations = if (tipHeight > 0L && txBlockNum > 0L) {
                (tipHeight - txBlockNum).coerceAtLeast(0L).toInt()
            } else {
                0  // unknown = treat as pending
            }

            // Detect DAO operation type from output type scripts and header deps:
            //   Deposit:  DAO output + no header deps
            //   Withdraw: DAO output + 1 header dep (deposit block)
            //   Unlock:   no DAO output + 2 header deps (deposit + withdraw blocks)
            val hasDaoOutput = tx.outputs.any { output ->
                output.type?.codeHash == DaoConstants.DAO_CODE_HASH
            }

            val (finalDirection, finalAmount) = if (hasDaoOutput) {
                val daoOutputCapacity = tx.outputs
                    .first { it.type?.codeHash == DaoConstants.DAO_CODE_HASH }
                    .capacity.removePrefix("0x").toLong(16)
                if (tx.headerDeps.isEmpty()) {
                    "dao_deposit" to daoOutputCapacity
                } else {
                    "dao_withdraw" to daoOutputCapacity
                }
            } else if (tx.headerDeps.size >= 2) {
                // Unlock: show total CKB returned (deposit + compensation)
                val totalOutput = cellInteractions
                    .filter { it.ioType == "output" }
                    .sumOf { it.ioCapacity.removePrefix("0x").toLong(16) }
                "dao_unlock" to totalOutput
            } else {
                direction to amount
            }

            TransactionRecord(
                txHash = txHash,
                blockNumber = firstInteraction.blockNumber,
                blockHash = headerInfo.hash ?: "0x0",
                timestamp = 0L,
                balanceChange = "0x${finalAmount.toString(16)}",
                direction = finalDirection,
                fee = "0x0",
                confirmations = confirmations,
                blockTimestampHex = headerInfo.timestampHex,
                isDaoRelated = hasDaoOutput || tx.headerDeps.size >= 2
            )
        }

        // --- Cache write: upsert JNI results into Room ---
        cacheManager.cacheTransactions(items, currentNetwork.name)

        // Merge: include pending local txs not yet returned by JNI
        val jniTxHashes = items.map { it.txHash }.toSet()
        val pendingLocal = cacheManager.getPendingNotIn(currentNetwork.name, jniTxHashes)
        val mergedItems = pendingLocal + items

        TransactionsResponse(mergedItems, pag.lastCursor)
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
        StatusResponse(currentNetwork.name.lowercase(), "0x0", "0x0", 0, false, true)
    }

    fun getCurrentAddress(): String? {
        val info = _walletInfo.value ?: return null
        return when (currentNetwork) {
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

    // ========================================
    // DAO Operations
    // ========================================

    suspend fun getCurrentEpoch(): Result<EpochInfo> = runCatching {
        val headerJson = LightClientNative.nativeGetTipHeader()
            ?: throw Exception("Failed to get tip header")
        val header = json.decodeFromString<JniHeaderView>(headerJson)
        EpochInfo.fromHex(header.epoch)
    }

    /**
     * Helper: get block hash for a cell by looking up its transaction.
     * The light client's get_transaction often returns null block_hash,
     * so we fall back to fetch_transaction which requests from peers.
     */
    private suspend fun getBlockHashForCell(txHash: String): String? {
        // Try local cache first
        val txJson = LightClientNative.nativeGetTransaction(txHash)
        if (txJson != null) {
            val txWithStatus = json.decodeFromString<JniTransactionWithStatus>(txJson)
            if (txWithStatus.txStatus.blockHash != null) {
                return txWithStatus.txStatus.blockHash
            }
            Log.d(TAG, "  get_transaction found tx but no block_hash, trying fetch_transaction...")
        } else {
            Log.d(TAG, "  get_transaction returned null, trying fetch_transaction...")
        }

        // Fallback: fetch from peers (may need retries as it's async)
        for (attempt in 1..3) {
            val fetchJson = LightClientNative.nativeFetchTransaction(txHash)
            if (fetchJson == null) {
                Log.w(TAG, "  fetch_transaction returned null on attempt $attempt")
                break
            }
            val fetchResp = json.decodeFromString<JniFetchTransactionResponse>(fetchJson)
            Log.d(TAG, "  fetch_transaction attempt $attempt: status=${fetchResp.status}")
            if (fetchResp.status == "fetched" && fetchResp.data != null) {
                return fetchResp.data.txStatus.blockHash
            }
            // "fetching" or "added" — wait and retry
            if (fetchResp.status == "fetching" || fetchResp.status == "added") {
                delay(2000)
            } else {
                break // unknown status, don't retry
            }
        }
        return null
    }

    /**
     * Fetch a block header from peers via nativeFetchHeader with retries.
     * The light client only stores headers for blocks it has processed locally;
     * for older blocks we need to request them from the peer network.
     */
    private suspend fun fetchHeaderWithRetry(blockHash: String): JniHeaderView? {
        for (attempt in 1..3) {
            val fetchJson = LightClientNative.nativeFetchHeader(blockHash)
            if (fetchJson == null) {
                Log.w(TAG, "  fetch_header returned null on attempt $attempt")
                break
            }
            val fetchResp = json.decodeFromString<JniFetchHeaderResponse>(fetchJson)
            Log.d(TAG, "  fetch_header attempt $attempt: status=${fetchResp.status}")
            if (fetchResp.status == "fetched" && fetchResp.data != null) {
                return fetchResp.data
            }
            if (fetchResp.status == "fetching" || fetchResp.status == "added") {
                delay(2000)
            } else {
                break
            }
        }
        return null
    }

    /**
     * Cache-first header lookup: Room DB → local JNI → peer fetch.
     * Block headers are immutable, so cached results are always valid.
     */
    private suspend fun getOrFetchHeader(blockHash: String): JniHeaderView? {
        // 1. Check Room cache
        val cached = daoSyncManager.getCachedHeader(blockHash)
        if (cached != null) {
            return cached.toJniHeaderView()
        }

        // 2. Try local JNI (light client may have it in memory)
        val localJson = LightClientNative.nativeGetHeader(blockHash)
        if (localJson != null) {
            val header = json.decodeFromString<JniHeaderView>(localJson)
            daoSyncManager.cacheHeader(header, currentNetwork.name)
            return header
        }

        // 3. Fetch from peers
        val fetched = fetchHeaderWithRetry(blockHash)
        if (fetched != null) {
            daoSyncManager.cacheHeader(fetched, currentNetwork.name)
        }
        return fetched
    }

    suspend fun getDaoDeposits(): Result<List<DaoDeposit>> = runCatching {
        val info = _walletInfo.value ?: throw Exception("No wallet")

        // Query ALL cells by lock script (like Neuron), then filter locally for DAO type
        val searchKey = JniSearchKey(
            script = info.script,
            scriptType = "lock",
            withData = true
        )
        val searchKeyJson = json.encodeToString(searchKey)

        // Paginate all cells by lock script
        val allCellObjects = mutableListOf<JniCell>()
        var cellsCursor: String? = null
        do {
            val pageJson = LightClientNative.nativeGetCells(searchKeyJson, "desc", 100, cellsCursor)
                ?: break
            val page = json.decodeFromString<JniPagination<JniCell>>(pageJson)
            allCellObjects += page.objects
            cellsCursor = page.lastCursor?.takeUnless { it == cellsCursor }
        } while (cellsCursor != null)

        // Filter locally: only cells whose type script matches DAO code hash
        val daoCells = allCellObjects.filter { cell ->
            cell.output.type?.codeHash == DaoConstants.DAO_CODE_HASH
        }
        Log.d(TAG, "📋 getDaoDeposits: ${daoCells.size} DAO cells out of ${allCellObjects.size} total")

        // Paginate transactions to find spent outpoints
        val spentOutpoints = mutableSetOf<String>()
        var txCursor: String? = null
        do {
            val txJson = LightClientNative.nativeGetTransactions(searchKeyJson, "desc", 100, txCursor)
                ?: break
            val txPag = json.decodeFromString<JniPagination<JniTxWithCell>>(txJson)
            txPag.objects.forEach { txWithCell ->
                txWithCell.transaction.inputs.forEach { input ->
                    spentOutpoints.add("${input.previousOutput.txHash}:${input.previousOutput.index}")
                }
            }
            txCursor = txPag.lastCursor?.takeUnless { it == txCursor }
        } while (txCursor != null)

        val liveDaoCells = daoCells.filter { cell ->
            val key = "${cell.outPoint.txHash}:${cell.outPoint.index}"
            key !in spentOutpoints
        }
        Log.d(TAG, "📋 getDaoDeposits: ${liveDaoCells.size} live DAO cells")

        val pagination = JniPagination(objects = liveDaoCells, lastCursor = null)

        val currentEpoch = getCurrentEpoch().getOrNull()

        val deposits = mutableListOf<DaoDeposit>()
        for (jniCell in pagination.objects) {
            val cell = jniCell.toCell()
            val data = cell.data.removePrefix("0x")
            val cellId = "${cell.outPoint.txHash.take(20)}...:${cell.outPoint.index}"
            val capacityShannons = cell.capacity.removePrefix("0x").toLong(16)

            // Determine if deposit or withdrawing cell
            val isWithdrawing = data.length == 16 && data != "0000000000000000"
            Log.d(TAG, "🏦 Processing DAO cell: $cellId, data=$data, isWithdrawing=$isWithdrawing, capacity=$capacityShannons")

            var depositBlockNumber = cell.blockNumber.removePrefix("0x").toLong(16)
            var depositBlockHash = "" // may remain empty if header unavailable
            var depositEpoch: EpochInfo? = null
            var withdrawBlockNumber: Long? = null
            var withdrawBlockHash: String? = null
            var withdrawEpoch: EpochInfo? = null
            var compensation = 0L
            var unlockEpoch: EpochInfo? = null
            var lockRemainingHours: Int? = null
            var depositTimestampMs = 0L
            var apc = 0.0

            // Get block header for compensation & epoch data (cache-first).
            val blockHash = getBlockHashForCell(cell.outPoint.txHash)
            val cellBlockHeader = if (blockHash != null) {
                getOrFetchHeader(blockHash)
            } else null

            if (cellBlockHeader != null) {
                val cellBlockEpoch = EpochInfo.fromHex(cellBlockHeader.epoch)
                depositBlockHash = cellBlockHeader.hash
                depositEpoch = cellBlockEpoch
                // Default timestamp from this cell's block; overridden for withdrawing cells below
                depositTimestampMs = cellBlockHeader.timestamp.removePrefix("0x").toLong(16)

                if (isWithdrawing) {
                    // Cell data contains deposit block number as 8-byte LE
                    val depositBlockNum = data.chunked(2)
                        .map { it.toInt(16).toByte() }
                        .toByteArray()
                        .let { bytes ->
                            var num = 0L
                            for (i in bytes.indices) {
                                num = num or ((bytes[i].toLong() and 0xFF) shl (i * 8))
                            }
                            num
                        }

                    withdrawBlockNumber = cell.blockNumber.removePrefix("0x").toLong(16)
                    withdrawBlockHash = cellBlockHeader.hash
                    withdrawEpoch = cellBlockEpoch
                    depositBlockNumber = depositBlockNum

                    // Get original deposit header for compensation (cache-first)
                    val withdrawTxJson = LightClientNative.nativeGetTransaction(cell.outPoint.txHash)
                    val withdrawTx = withdrawTxJson?.let { json.decodeFromString<JniTransactionWithStatus>(it) }
                    val origDepositBlockHash = withdrawTx?.transaction?.headerDeps?.firstOrNull()
                    // Preserve the hash even if full header fetch fails
                    if (origDepositBlockHash != null) {
                        depositBlockHash = origDepositBlockHash
                    }
                    val origDepositHeader = origDepositBlockHash?.let { h ->
                        getOrFetchHeader(h)
                    }

                    if (origDepositHeader != null) {
                        depositBlockHash = origDepositHeader.hash
                        depositEpoch = EpochInfo.fromHex(origDepositHeader.epoch)
                        depositTimestampMs = origDepositHeader.timestamp.removePrefix("0x").toLong(16)
                        val maxWithdraw = LightClientNative.nativeCalculateMaxWithdraw(
                            origDepositHeader.dao, cellBlockHeader.dao, capacityShannons, 61_00000000L
                        )
                        compensation = maxWithdraw - capacityShannons
                        val sinceHex = LightClientNative.nativeCalculateUnlockEpoch(
                            origDepositHeader.epoch, cellBlockHeader.epoch
                        )
                        if (sinceHex != null) {
                            val epochVal = sinceHex.removePrefix("0x").toLong(16) and 0x00FF_FFFF_FFFF_FFFFL
                            unlockEpoch = EpochInfo.fromHex("0x${epochVal.toString(16)}")
                            if (currentEpoch != null && unlockEpoch!!.value > currentEpoch.value) {
                                lockRemainingHours = ((unlockEpoch!!.value - currentEpoch.value) * DaoConstants.HOURS_PER_EPOCH).toInt()
                            }
                        }
                    }
                } else {
                    // Deposited cell — calculate compensation using current tip header
                    val tipJson = LightClientNative.nativeGetTipHeader()
                    if (tipJson != null) {
                        val tipHeader = json.decodeFromString<JniHeaderView>(tipJson)
                        val maxWithdraw = LightClientNative.nativeCalculateMaxWithdraw(
                            cellBlockHeader.dao, tipHeader.dao, capacityShannons, 61_00000000L
                        )
                        compensation = maxWithdraw - capacityShannons
                    }
                }
            } else {
                Log.d(TAG, "  No header available for $cellId — showing deposit with basic info")
                if (isWithdrawing) {
                    val depositBlockNum = data.chunked(2)
                        .map { it.toInt(16).toByte() }
                        .toByteArray()
                        .let { bytes ->
                            var num = 0L
                            for (i in bytes.indices) {
                                num = num or ((bytes[i].toLong() and 0xFF) shl (i * 8))
                            }
                            num
                        }
                    withdrawBlockNumber = cell.blockNumber.removePrefix("0x").toLong(16)
                    depositBlockNumber = depositBlockNum
                }
            }

            // Compute per-deposit APC when enough time has elapsed
            if (compensation > 0 && depositTimestampMs > 0 && capacityShannons > 0) {
                val elapsedDays = (System.currentTimeMillis() - depositTimestampMs) / 86_400_000.0
                if (elapsedDays >= 1.0) {
                    apc = (compensation.toDouble() / capacityShannons) / (elapsedDays / 365.25) * 100.0
                }
            }

            // Calculate cycle progress (best-effort with available epoch data)
            val depositedEpochs = if (currentEpoch != null && depositEpoch != null) {
                (currentEpoch.value - depositEpoch.value).coerceAtLeast(0.0)
            } else 0.0
            val cycleProgress = ((depositedEpochs % DaoConstants.WITHDRAW_EPOCHS) / DaoConstants.WITHDRAW_EPOCHS).toFloat()

            val status = determineDaoStatus(
                isWithdrawingCell = isWithdrawing,
                hasPendingWithdraw = false,
                hasPendingUnlock = false,
                hasPendingDeposit = false,
                currentEpoch = currentEpoch,
                unlockEpoch = unlockEpoch
            )

            deposits.add(DaoDeposit(
                outPoint = cell.outPoint,
                capacity = capacityShannons,
                status = status,
                depositBlockNumber = depositBlockNumber,
                depositBlockHash = depositBlockHash,
                depositEpoch = depositEpoch,
                withdrawBlockNumber = withdrawBlockNumber,
                withdrawBlockHash = withdrawBlockHash,
                withdrawEpoch = withdrawEpoch,
                compensation = compensation.coerceAtLeast(0L),
                unlockEpoch = unlockEpoch,
                lockRemainingHours = lockRemainingHours,
                compensationCycleProgress = cycleProgress,
                cyclePhase = cyclePhaseFromProgress(cycleProgress),
                depositTimestamp = depositTimestampMs,
                apc = apc
            ))
            Log.d(TAG, "✅ DAO deposit added: $cellId, status=$status, capacity=$capacityShannons")
        }
        deposits
    }

    suspend fun getDaoOverview(): Result<DaoOverview> = runCatching {
        val deposits = getDaoDeposits().getOrThrow()
        val active = deposits.filter { it.status != DaoCellStatus.COMPLETED }
        val completed = deposits.filter { it.status == DaoCellStatus.COMPLETED }

        // Capacity-weighted average APC from deposits that have APC data
        val depositsWithApc = active.filter { it.apc > 0.0 }
        val weightedApc = if (depositsWithApc.isNotEmpty()) {
            val totalCap = depositsWithApc.sumOf { it.capacity }.toDouble()
            depositsWithApc.sumOf { it.apc * it.capacity } / totalCap
        } else 2.47 // fallback until headers are available

        DaoOverview(
            totalLocked = active.sumOf { it.capacity },
            totalCompensation = deposits.sumOf { it.compensation },
            currentApc = weightedApc,
            activeCount = active.size,
            completedCount = completed.size
        )
    }

    suspend fun depositToDao(amountShannons: Long): Result<String> = runCatching {
        val info = _walletInfo.value ?: throw Exception("No wallet")
        val net = _network.value

        require(amountShannons >= DaoConstants.MIN_DEPOSIT_SHANNONS) {
            "Minimum deposit is ${DaoConstants.MIN_DEPOSIT_SHANNONS / 100_000_000} CKB"
        }

        val cellsResponse = getCells().getOrThrow()
        val privateKey = keyManager.getPrivateKey()

        val tx = transactionBuilder.buildDaoDeposit(
            amountShannons = amountShannons,
            availableCells = cellsResponse.items,
            senderScript = info.script,
            privateKey = privateKey,
            network = net
        )

        val txHash = sendTransaction(tx).getOrThrow()
        Log.d(TAG, "DAO deposit sent: $txHash")

        // Track pending deposit in Room so UI shows it before JNI confirms
        daoSyncManager.insertPendingDeposit(txHash, amountShannons, net.name)

        txHash
    }

    suspend fun withdrawFromDao(depositOutPoint: OutPoint): Result<String> = runCatching {
        val info = _walletInfo.value ?: throw Exception("No wallet")
        val net = _network.value

        // Find the deposit cell
        val deposits = getDaoDeposits().getOrThrow()
        val deposit = deposits.find { it.outPoint == depositOutPoint }
            ?: throw Exception("Deposit not found")

        val privateKey = keyManager.getPrivateKey()

        require(deposit.depositBlockHash.isNotBlank()) {
            "Deposit block hash unavailable. Please retry after sync."
        }

        // Build a Cell from the deposit for the transaction builder
        val depositCell = Cell(
            outPoint = deposit.outPoint,
            capacity = "0x${deposit.capacity.toString(16)}",
            blockNumber = "0x${deposit.depositBlockNumber.toString(16)}",
            lock = info.script,
            type = DaoConstants.DAO_TYPE_SCRIPT,
            data = "0x" + DaoConstants.DAO_DEPOSIT_DATA.joinToString("") { "%02x".format(it) }
        )

        val tx = transactionBuilder.buildDaoWithdraw(
            depositCell = depositCell,
            depositBlockNumber = deposit.depositBlockNumber,
            depositBlockHash = deposit.depositBlockHash,
            senderScript = info.script,
            privateKey = privateKey,
            network = net
        )

        val txHash = sendTransaction(tx).getOrThrow()
        Log.d(TAG, "DAO withdraw (phase 1) sent: $txHash")
        txHash
    }

    suspend fun unlockDao(
        withdrawingOutPoint: OutPoint
    ): Result<String> = runCatching {
        val info = _walletInfo.value ?: throw Exception("No wallet")
        val net = _network.value

        val deposits = getDaoDeposits().getOrThrow()
        val deposit = deposits.find { it.outPoint == withdrawingOutPoint }
            ?: throw Exception("Withdrawing cell not found")

        require(deposit.status == DaoCellStatus.UNLOCKABLE) {
            "Cell is not unlockable yet (status: ${deposit.status})"
        }

        // Use the deposit object's hashes — it is the single source of truth
        val depositBlockHash = deposit.depositBlockHash
        require(depositBlockHash.isNotBlank()) {
            "Deposit block hash unavailable. Please retry after sync."
        }
        val withdrawBlockHash = deposit.withdrawBlockHash
            ?: throw Exception("Withdraw block hash unavailable. Please retry after sync.")

        val privateKey = keyManager.getPrivateKey()

        // Get headers for max withdraw calculation (cache-first)
        val depositHeader = getOrFetchHeader(depositBlockHash)
            ?: throw Exception("Failed to get deposit header")

        val withdrawHeader = getOrFetchHeader(withdrawBlockHash)
            ?: throw Exception("Failed to get withdraw header")

        val maxWithdraw = LightClientNative.nativeCalculateMaxWithdraw(
            depositHeader.dao,
            withdrawHeader.dao,
            deposit.capacity,
            61_00000000L
        )

        val sinceValue = LightClientNative.nativeCalculateUnlockEpoch(
            depositHeader.epoch,
            withdrawHeader.epoch
        ) ?: throw Exception("Failed to calculate unlock epoch")

        val withdrawingCell = Cell(
            outPoint = deposit.outPoint,
            capacity = "0x${deposit.capacity.toString(16)}",
            blockNumber = "0x${(deposit.withdrawBlockNumber ?: throw Exception("No withdraw block")).toString(16)}",
            lock = info.script,
            type = DaoConstants.DAO_TYPE_SCRIPT
        )

        val tx = transactionBuilder.buildDaoUnlock(
            withdrawingCell = withdrawingCell,
            maxWithdraw = maxWithdraw,
            sinceValue = sinceValue,
            depositBlockHash = depositBlockHash,
            withdrawBlockHash = withdrawBlockHash,
            senderScript = info.script,
            privateKey = privateKey,
            network = net
        )

        val txHash = sendTransaction(tx).getOrThrow()
        Log.d(TAG, "DAO unlock (phase 2) sent: $txHash")
        txHash
    }

    /**
     * Build the script status list for registration (lock script only).
     * DAO cells are discovered via lock script query and filtered locally by type hash,
     * following the same pattern as Neuron wallet.
     */
    private fun buildScriptStatusList(lockScript: Script, blockNumberHex: String): String {
        val lockStatus = JniScriptStatus(
            script = lockScript,
            scriptType = "lock",
            blockNumber = blockNumberHex
        )
        return json.encodeToString(listOf(lockStatus))
    }

    companion object {
        private const val TAG = "GatewayRepository"
    }
}
