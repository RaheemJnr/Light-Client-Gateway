package com.rjnr.pocketnode.data.database.dao

import androidx.room.*
import com.rjnr.pocketnode.data.database.entity.BalanceCacheEntity

@Dao
interface BalanceCacheDao {

    @Query("SELECT * FROM balance_cache WHERE network = :network")
    suspend fun getByNetwork(network: String): BalanceCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BalanceCacheEntity)

    @Query("DELETE FROM balance_cache WHERE network = :network")
    suspend fun deleteByNetwork(network: String)

    @Query("DELETE FROM balance_cache")
    suspend fun deleteAll()

    // -- Wallet-scoped queries --

    @Query("SELECT * FROM balance_cache WHERE walletId = :walletId AND network = :network LIMIT 1")
    suspend fun getByWalletAndNetwork(walletId: String, network: String): BalanceCacheEntity?

    @Query("DELETE FROM balance_cache WHERE walletId = :walletId AND network = :network")
    suspend fun deleteByWalletAndNetwork(walletId: String, network: String)
}
