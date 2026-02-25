package com.rjnr.pocketnode.data.gateway

import android.util.Log
import com.rjnr.pocketnode.data.database.dao.DaoCellDao
import com.rjnr.pocketnode.data.database.dao.HeaderCacheDao
import com.rjnr.pocketnode.data.database.entity.DaoCellEntity
import com.rjnr.pocketnode.data.database.entity.HeaderCacheEntity
import com.rjnr.pocketnode.data.gateway.models.JniHeaderView
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DaoSyncManager @Inject constructor(
    private val headerCacheDao: HeaderCacheDao,
    private val daoCellDao: DaoCellDao
) {
    // --- Header cache (permanent — block headers are immutable) ---

    suspend fun getCachedHeader(blockHash: String): HeaderCacheEntity? {
        return try {
            headerCacheDao.getByBlockHash(blockHash)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read header cache: ${e.message}")
            null
        }
    }

    suspend fun cacheHeader(header: JniHeaderView, network: String) {
        try {
            headerCacheDao.upsert(HeaderCacheEntity.from(header, network))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write header cache: ${e.message}")
        }
    }

    // --- DAO cell lifecycle ---

    suspend fun getActiveDeposits(network: String): List<DaoCellEntity> {
        return try {
            daoCellDao.getActiveByNetwork(network)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read active deposits: ${e.message}")
            emptyList()
        }
    }

    suspend fun getCompletedDeposits(network: String): List<DaoCellEntity> {
        return try {
            daoCellDao.getCompletedByNetwork(network)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read completed deposits: ${e.message}")
            emptyList()
        }
    }

    suspend fun getByOutPoint(txHash: String, index: String): DaoCellEntity? {
        return try {
            daoCellDao.getByOutPoint(txHash, index)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read DAO cell: ${e.message}")
            null
        }
    }

    suspend fun upsertDaoCell(entity: DaoCellEntity) {
        try {
            daoCellDao.upsert(entity)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to upsert DAO cell: ${e.message}")
        }
    }

    suspend fun upsertDaoCells(entities: List<DaoCellEntity>) {
        try {
            daoCellDao.upsertAll(entities)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to upsert DAO cells: ${e.message}")
        }
    }

    suspend fun updateStatus(txHash: String, index: String, status: String) {
        try {
            daoCellDao.updateStatus(txHash, index, status)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update DAO cell status: ${e.message}")
        }
    }

    suspend fun insertPendingDeposit(txHash: String, capacity: Long, network: String) {
        try {
            daoCellDao.upsert(
                DaoCellEntity(
                    txHash = txHash,
                    index = "0x0",
                    capacity = capacity,
                    status = "DEPOSITING",
                    depositBlockNumber = 0L,
                    depositBlockHash = "",
                    depositEpochHex = null,
                    withdrawBlockNumber = null,
                    withdrawBlockHash = null,
                    withdrawEpochHex = null,
                    compensation = 0L,
                    unlockEpochHex = null,
                    depositTimestamp = System.currentTimeMillis(),
                    network = network,
                    lastUpdatedAt = System.currentTimeMillis()
                )
            )
            Log.d(TAG, "Pending DAO deposit cached: $txHash")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache pending deposit: ${e.message}")
        }
    }

    // --- Cleanup ---

    suspend fun clearForNetwork(network: String) {
        try {
            headerCacheDao.deleteByNetwork(network)
            daoCellDao.deleteByNetwork(network)
            Log.d(TAG, "DAO caches cleared for $network")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear DAO caches: ${e.message}")
        }
    }

    suspend fun clearAll() {
        try {
            headerCacheDao.deleteAll()
            daoCellDao.deleteAll()
            Log.d(TAG, "All DAO caches cleared")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear DAO caches: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "DaoSyncManager"
    }
}
