package com.rjnr.pocketnode.data.database.dao

import androidx.room.*
import com.rjnr.pocketnode.data.database.entity.HeaderCacheEntity

@Dao
interface HeaderCacheDao {

    @Query("SELECT * FROM header_cache WHERE blockHash = :blockHash")
    suspend fun getByBlockHash(blockHash: String): HeaderCacheEntity?

    @Query("SELECT * FROM header_cache WHERE network = :network")
    suspend fun getByNetwork(network: String): List<HeaderCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: HeaderCacheEntity)

    @Query("DELETE FROM header_cache WHERE network = :network")
    suspend fun deleteByNetwork(network: String)

    @Query("DELETE FROM header_cache")
    suspend fun deleteAll()
}
