package com.rjnr.pocketnode.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.rjnr.pocketnode.data.database.dao.BalanceCacheDao
import com.rjnr.pocketnode.data.database.dao.DaoCellDao
import com.rjnr.pocketnode.data.database.dao.HeaderCacheDao
import com.rjnr.pocketnode.data.database.dao.TransactionDao
import com.rjnr.pocketnode.data.database.dao.WalletDao
import com.rjnr.pocketnode.data.database.entity.BalanceCacheEntity
import com.rjnr.pocketnode.data.database.entity.DaoCellEntity
import com.rjnr.pocketnode.data.database.entity.HeaderCacheEntity
import com.rjnr.pocketnode.data.database.entity.TransactionEntity
import com.rjnr.pocketnode.data.database.entity.WalletEntity

@Database(
    entities = [
        TransactionEntity::class,
        BalanceCacheEntity::class,
        HeaderCacheEntity::class,
        DaoCellEntity::class,
        WalletEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun balanceCacheDao(): BalanceCacheDao
    abstract fun headerCacheDao(): HeaderCacheDao
    abstract fun daoCellDao(): DaoCellDao
    abstract fun walletDao(): WalletDao
}
