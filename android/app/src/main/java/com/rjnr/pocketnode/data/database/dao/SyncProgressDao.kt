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

    /**
     * Atomic update of progress only (preserves lightStartBlockNumber).
     * Returns rows affected (0 = no row, caller falls back to upsert).
     * Closes the get→upsert race window in setWalletSyncBlock vs concurrent
     * inserts from setScriptsAndRecord during initial registration.
     */
    @Query("UPDATE sync_progress SET localSavedBlockNumber = :block, updatedAt = :ts WHERE walletId = :walletId AND network = :network")
    suspend fun updateLocalSaved(walletId: String, network: String, block: Long, ts: Long): Int

    /**
     * Atomic update of registration metadata only (preserves localSavedBlockNumber).
     * Returns rows affected (0 = no row, caller falls back to upsert).
     * Closes the get→upsert race window in setScriptsAndRecord vs concurrent
     * setWalletSyncBlock writes from the sync poll.
     */
    @Query("UPDATE sync_progress SET lightStartBlockNumber = :lightStart, updatedAt = :ts WHERE walletId = :walletId AND network = :network")
    suspend fun updateLightStart(walletId: String, network: String, lightStart: Long, ts: Long): Int

    @Query("DELETE FROM sync_progress WHERE walletId = :walletId")
    suspend fun deleteForWallet(walletId: String)
}
