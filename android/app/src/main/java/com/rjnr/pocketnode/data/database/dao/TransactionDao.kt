package com.rjnr.pocketnode.data.database.dao

import androidx.room.*
import com.rjnr.pocketnode.data.database.entity.TransactionEntity

@Dao
interface TransactionDao {

    @Query("SELECT * FROM transactions WHERE network = :network ORDER BY CASE WHEN status = 'PENDING' THEN 0 ELSE 1 END, timestamp DESC LIMIT :limit")
    suspend fun getByNetwork(network: String, limit: Int = 50): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE network = :network AND status = 'PENDING'")
    suspend fun getPending(network: String): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE txHash = :txHash")
    suspend fun getByTxHash(txHash: String): TransactionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TransactionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<TransactionEntity>)

    @Query("UPDATE transactions SET status = :status, confirmations = :confirmations, blockNumber = :blockNumber, blockHash = :blockHash, isLocal = 0, cachedAt = :cachedAt WHERE txHash = :txHash")
    suspend fun updateStatus(txHash: String, status: String, confirmations: Int, blockNumber: String, blockHash: String, cachedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM transactions WHERE network = :network")
    suspend fun deleteByNetwork(network: String)

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()
}
