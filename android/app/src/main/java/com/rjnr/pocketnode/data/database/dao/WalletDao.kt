package com.rjnr.pocketnode.data.database.dao

import androidx.room.*
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WalletDao {

    @Query("SELECT * FROM wallets ORDER BY createdAt ASC")
    fun getAllFlow(): Flow<List<WalletEntity>>

    @Query("SELECT * FROM wallets ORDER BY createdAt ASC")
    suspend fun getAll(): List<WalletEntity>

    @Query("SELECT * FROM wallets WHERE walletId = :walletId")
    suspend fun getById(walletId: String): WalletEntity?

    @Query("SELECT * FROM wallets WHERE isActive = 1 LIMIT 1")
    suspend fun getActive(): WalletEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(wallet: WalletEntity)

    @Update
    suspend fun update(wallet: WalletEntity)

    @Query("UPDATE wallets SET isActive = 0")
    suspend fun deactivateAll()

    @Query("UPDATE wallets SET isActive = 1 WHERE walletId = :walletId")
    suspend fun activate(walletId: String)

    @Query("UPDATE wallets SET name = :name WHERE walletId = :walletId")
    suspend fun updateName(walletId: String, name: String)

    @Query("DELETE FROM wallets WHERE walletId = :walletId")
    suspend fun delete(walletId: String)

    @Query("SELECT COUNT(*) FROM wallets")
    suspend fun count(): Int

    // -- Flow-based & wallet-scoped queries --

    @Query("SELECT * FROM wallets WHERE isActive = 1 LIMIT 1")
    fun getActiveWallet(): Flow<WalletEntity?>

    @Query("SELECT * FROM wallets ORDER BY createdAt ASC")
    fun getAllWallets(): Flow<List<WalletEntity>>

    @Query("SELECT * FROM wallets WHERE parentWalletId = :parentId ORDER BY accountIndex ASC")
    fun getSubAccounts(parentId: String): Flow<List<WalletEntity>>

    @Query("SELECT * FROM wallets WHERE parentWalletId = :parentId ORDER BY accountIndex ASC")
    suspend fun getSubAccountsList(parentId: String): List<WalletEntity>

    @Query("UPDATE wallets SET name = :name WHERE walletId = :walletId")
    suspend fun rename(walletId: String, name: String)

    @Query("UPDATE wallets SET lastActiveAt = :timestamp WHERE walletId = :walletId")
    suspend fun updateLastActiveAt(walletId: String, timestamp: Long)
}
