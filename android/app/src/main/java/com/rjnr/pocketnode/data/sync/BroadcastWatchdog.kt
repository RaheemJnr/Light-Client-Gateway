package com.rjnr.pocketnode.data.sync

import android.util.Log
import com.rjnr.pocketnode.data.database.dao.PendingBroadcastDao
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.gateway.TipSource
import com.rjnr.pocketnode.data.gateway.TransactionStatusUpdater
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives `pending_broadcasts` rows to terminal states via the
 * Neuron-style Monitor pattern (#94) adapted to CKB.
 *
 *   - Primary trigger: light-client tip events ([TipSource.tipFlow]).
 *   - Fallback timer: 15s loop. Defensive — tip events stalling would
 *     otherwise leave rows stuck.
 *   - Both routes call [checkAll], internally idempotent (CAS UPDATEs
 *     guard against tip-event vs fallback-timer races).
 *
 * Foreground-gated: skips checkAll when not at least STARTED.
 * Phase A is foreground-only by design; cold-start recovery (Task 5)
 * handles process death.
 */
@Singleton
class BroadcastWatchdog(
    private val dao: PendingBroadcastDao,
    private val statusGateway: TransactionStatusGateway,
    private val cache: TransactionStatusUpdater,
    private val tipSource: TipSource,
    private val lifecycleProvider: LifecycleProvider,
    dispatcher: CoroutineDispatcher
) {
    /** Hilt entry point — uses [Dispatchers.IO]. Tests construct via the primary ctor. */
    @Inject constructor(
        dao: PendingBroadcastDao,
        statusGateway: TransactionStatusGateway,
        cache: TransactionStatusUpdater,
        tipSource: TipSource,
        lifecycleProvider: LifecycleProvider
    ) : this(dao, statusGateway, cache, tipSource, lifecycleProvider, Dispatchers.IO)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var tipJob: Job? = null
    private var fallbackJob: Job? = null

    fun start() {
        if (tipJob != null) return  // idempotent
        tipJob = scope.launch {
            tipSource.tipFlow.collect { tip ->
                if (!lifecycleProvider.isAtLeastStarted()) return@collect
                val (walletId, network) = tipSource.activeWalletAndNetworkOrNull() ?: return@collect
                checkAll(currentTip = tip, walletId = walletId, network = network)
            }
        }
        fallbackJob = scope.launch {
            while (true) {
                delay(FALLBACK_INTERVAL_MS)
                if (!lifecycleProvider.isAtLeastStarted()) continue
                runCatching {
                    val tip = tipSource.fetchAndPublishTip()
                    val (walletId, network) = tipSource.activeWalletAndNetworkOrNull()
                        ?: return@runCatching
                    checkAll(currentTip = tip, walletId = walletId, network = network)
                }.onFailure { Log.w(TAG, "fallback checkAll: ${it.message}") }
            }
        }
    }

    fun stop() {
        tipJob?.cancel(); tipJob = null
        fallbackJob?.cancel(); fallbackJob = null
    }

    /** Public for testability; not called directly by app code. */
    suspend fun checkAll(currentTip: Long, walletId: String, network: String) {
        val rows = dao.getActive(walletId, network)
        val now = System.currentTimeMillis()
        for (row in rows) {
            when (val result = statusGateway.fetch(row.txHash)) {
                is TxFetchResult.OnChain -> {
                    val ok = dao.compareAndUpdateState(
                        hash = row.txHash, expected = row.state,
                        next = "CONFIRMED", now = now
                    )
                    if (ok == 1) {
                        cache.updateTransactionStatus(row.txHash, "CONFIRMED")
                        dao.delete(row.txHash)
                    }
                }
                TxFetchResult.InPool -> {
                    if (row.state == "BROADCASTING") {
                        dao.compareAndUpdateState(row.txHash, "BROADCASTING", "BROADCAST", now)
                    }
                    if (row.nullCount != 0) {
                        dao.updateNullCount(row.txHash, 0, now)
                    }
                }
                TxFetchResult.NotFound -> {
                    val newCount = row.nullCount + 1
                    dao.updateNullCount(row.txHash, newCount, now)
                    if (newCount >= NULL_THRESHOLD &&
                        currentTip >= row.submittedAtTipBlock + BLOCK_TIMEOUT
                    ) {
                        val ok = dao.compareAndUpdateState(
                            hash = row.txHash, expected = row.state,
                            next = "FAILED", now = now
                        )
                        if (ok == 1) {
                            cache.updateTransactionStatus(row.txHash, "FAILED")
                        }
                    }
                }
                TxFetchResult.Exception -> {
                    Log.w(TAG, "fetch exception for ${row.txHash}; no state change")
                }
            }
        }
    }

    companion object {
        private const val TAG = "BroadcastWatchdog"
        const val NULL_THRESHOLD = 3
        const val BLOCK_TIMEOUT = 25L
        const val FALLBACK_INTERVAL_MS = 15_000L
    }
}

sealed class TxFetchResult {
    data class OnChain(val blockHash: String) : TxFetchResult()
    data object InPool : TxFetchResult()
    data object NotFound : TxFetchResult()
    data object Exception : TxFetchResult()
}

/** Indirection over `GatewayRepository.getTransactionStatus` for testability. */
fun interface TransactionStatusGateway {
    suspend fun fetch(hash: String): TxFetchResult
}

/** Lifecycle-state indirection. Real impl reads ProcessLifecycleOwner. */
fun interface LifecycleProvider {
    fun isAtLeastStarted(): Boolean
}

/**
 * Real adapter wrapping [GatewayRepository.getTransactionStatus]. Maps
 * `TransactionStatusResponse` to the watchdog's narrower [TxFetchResult]
 * vocabulary so the watchdog never has to re-parse JNI JSON.
 */
class RepositoryTransactionStatusGateway @Inject constructor(
    private val gateway: GatewayRepository
) : TransactionStatusGateway {
    override suspend fun fetch(hash: String): TxFetchResult = runCatching {
        val resp = gateway.getTransactionStatus(hash).getOrNull()
            ?: return TxFetchResult.NotFound
        when {
            resp.status == "unknown" -> TxFetchResult.NotFound
            resp.blockHash != null -> TxFetchResult.OnChain(resp.blockHash!!)
            else -> TxFetchResult.InPool
        }
    }.getOrElse { TxFetchResult.Exception }
}

/** Real lifecycle provider reading [androidx.lifecycle.ProcessLifecycleOwner]. */
class ProcessLifecycleProvider @Inject constructor() : LifecycleProvider {
    override fun isAtLeastStarted(): Boolean =
        androidx.lifecycle.ProcessLifecycleOwner.get()
            .lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
}
