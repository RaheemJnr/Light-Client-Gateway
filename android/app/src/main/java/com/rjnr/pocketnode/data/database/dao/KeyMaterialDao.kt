package com.rjnr.pocketnode.data.database.dao

import androidx.room.*
import com.rjnr.pocketnode.data.database.entity.KeyMaterialEntity

@Dao
interface KeyMaterialDao {

    @Query("SELECT * FROM key_material WHERE walletId = :walletId")
    suspend fun getByWalletId(walletId: String): KeyMaterialEntity?

    @Query("SELECT * FROM key_material")
    suspend fun getAll(): List<KeyMaterialEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: KeyMaterialEntity)

    @Query("DELETE FROM key_material WHERE walletId = :walletId")
    suspend fun delete(walletId: String)

    @Query("SELECT COUNT(*) FROM key_material")
    suspend fun count(): Int

    @Query("UPDATE key_material SET mnemonicBackedUp = :backedUp, updatedAt = :updatedAt WHERE walletId = :walletId")
    suspend fun updateMnemonicBackedUp(walletId: String, backedUp: Boolean, updatedAt: Long)
}
