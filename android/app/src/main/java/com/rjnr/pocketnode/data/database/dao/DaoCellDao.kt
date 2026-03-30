package com.rjnr.pocketnode.data.database.dao

import androidx.room.*
import com.rjnr.pocketnode.data.database.entity.DaoCellEntity

@Dao
interface DaoCellDao {

    @Query("SELECT * FROM dao_cells WHERE network = :network AND status NOT IN ('COMPLETED')")
    suspend fun getActiveByNetwork(network: String): List<DaoCellEntity>

    @Query("SELECT * FROM dao_cells WHERE network = :network AND status = 'COMPLETED'")
    suspend fun getCompletedByNetwork(network: String): List<DaoCellEntity>

    @Query("SELECT * FROM dao_cells WHERE txHash = :txHash AND `index` = :index")
    suspend fun getByOutPoint(txHash: String, index: String): DaoCellEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DaoCellEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<DaoCellEntity>)

    @Query("UPDATE dao_cells SET status = :status, lastUpdatedAt = :updatedAt WHERE txHash = :txHash AND `index` = :index")
    suspend fun updateStatus(txHash: String, index: String, status: String, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM dao_cells WHERE network = :network")
    suspend fun deleteByNetwork(network: String)

    @Query("DELETE FROM dao_cells")
    suspend fun deleteAll()
}
