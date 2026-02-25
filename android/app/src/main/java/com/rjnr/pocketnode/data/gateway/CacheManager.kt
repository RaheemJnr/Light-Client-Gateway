package com.rjnr.pocketnode.data.gateway

import android.util.Log
import com.rjnr.pocketnode.data.database.dao.BalanceCacheDao
import com.rjnr.pocketnode.data.database.dao.TransactionDao
import com.rjnr.pocketnode.data.database.entity.BalanceCacheEntity
import com.rjnr.pocketnode.data.database.entity.TransactionEntity
import com.rjnr.pocketnode.data.gateway.models.BalanceResponse
import com.rjnr.pocketnode.data.gateway.models.TransactionRecord
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CacheManager @Inject constructor(
    private val transactionDao: TransactionDao,
    private val balanceCacheDao: BalanceCacheDao
) {
    // --- Balance cache ---

    suspend fun getCachedBalance(network: String): BalanceResponse? {
        return try {
            balanceCacheDao.getByNetwork(network)?.toBalanceResponse()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read balance cache", e)
            null
        }
    }

    suspend fun cacheBalance(response: BalanceResponse, network: String) {
        try {
            balanceCacheDao.upsert(BalanceCacheEntity.from(response, network))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write balance cache", e)
        }
    }

    // --- Transaction cache ---

    suspend fun cacheTransactions(records: List<TransactionRecord>, network: String) {
        try {
            val entities = records.map { record ->
                TransactionEntity.fromTransactionRecord(
                    txHash = record.txHash,
                    blockNumber = record.blockNumber,
                    blockHash = record.blockHash,
                    timestamp = record.timestamp,
                    balanceChange = record.balanceChange,
                    direction = record.direction,
                    fee = record.fee,
                    confirmations = record.confirmations,
                    blockTimestampHex = record.blockTimestampHex,
                    network = network
                )
            }
            transactionDao.insertAll(entities)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write transaction cache", e)
        }
    }

    suspend fun insertPendingTransaction(txHash: String, network: String) {
        try {
            transactionDao.insert(
                TransactionEntity(
                    txHash = txHash,
                    blockNumber = "",
                    blockHash = "",
                    timestamp = System.currentTimeMillis(),
                    balanceChange = "0x0",
                    direction = "out",
                    fee = "0x186a0",
                    confirmations = 0,
                    blockTimestampHex = null,
                    network = network,
                    status = "PENDING",
                    isLocal = true,
                    cachedAt = System.currentTimeMillis()
                )
            )
            Log.d(TAG, "Pending transaction cached in Room: $txHash")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache pending tx", e)
        }
    }

    suspend fun getPendingNotIn(network: String, excludeHashes: Set<String>): List<TransactionRecord> {
        return try {
            transactionDao.getPending(network)
                .filter { it.isLocal && it.txHash !in excludeHashes }
                .map { it.toTransactionRecord() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read pending transactions", e)
            emptyList()
        }
    }

    // --- Cleanup ---

    suspend fun clearAll() {
        try {
            transactionDao.deleteAll()
            balanceCacheDao.deleteAll()
            Log.d(TAG, "All caches cleared")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear caches", e)
        }
    }

    companion object {
        private const val TAG = "CacheManager"
    }
}
