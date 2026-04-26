package com.rjnr.pocketnode.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.rjnr.pocketnode.data.database.entity.SyncProgressEntity

@Dao
interface SyncProgressDao {

    @Query("SELECT * FROM sync_progress WHERE walletId = :walletId AND network = :network LIMIT 1")
    suspend fun get(walletId: String, network: String): SyncProgressEntity?

    @Query("SELECT * FROM sync_progress WHERE network = :network")
    suspend fun getAllForNetwork(network: String): List<SyncProgressEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SyncProgressEntity)

    @Query("UPDATE sync_progress SET localSavedBlockNumber = :block, updatedAt = :ts WHERE walletId = :walletId AND network = :network")
    suspend fun updateLocalSaved(walletId: String, network: String, block: Long, ts: Long)

    @Query("DELETE FROM sync_progress WHERE walletId = :walletId")
    suspend fun deleteForWallet(walletId: String)
}
