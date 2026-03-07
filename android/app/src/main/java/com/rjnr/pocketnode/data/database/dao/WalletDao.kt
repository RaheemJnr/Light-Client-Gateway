package com.rjnr.pocketnode.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WalletDao {

    @Query("SELECT * FROM wallets WHERE isActive = 1 LIMIT 1")
    fun getActiveWallet(): Flow<WalletEntity?>

    @Query("SELECT * FROM wallets ORDER BY createdAt ASC")
    fun getAllWallets(): Flow<List<WalletEntity>>

    @Query("SELECT * FROM wallets WHERE parentWalletId = :parentId ORDER BY accountIndex ASC")
    fun getSubAccounts(parentId: String): Flow<List<WalletEntity>>

    @Query("SELECT * FROM wallets WHERE walletId = :walletId")
    suspend fun getById(walletId: String): WalletEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(wallet: WalletEntity)

    @Query("UPDATE wallets SET isActive = 0 WHERE isActive = 1")
    suspend fun deactivateAll()

    @Query("UPDATE wallets SET isActive = 1 WHERE walletId = :walletId")
    suspend fun activate(walletId: String)

    @Query("UPDATE wallets SET name = :name WHERE walletId = :walletId")
    suspend fun rename(walletId: String, name: String)

    @Query("DELETE FROM wallets WHERE walletId = :walletId")
    suspend fun delete(walletId: String)

    @Query("SELECT COUNT(*) FROM wallets")
    suspend fun count(): Int
}
