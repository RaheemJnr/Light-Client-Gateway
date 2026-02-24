package com.rjnr.pocketnode.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.rjnr.pocketnode.data.database.dao.BalanceCacheDao
import com.rjnr.pocketnode.data.database.dao.TransactionDao
import com.rjnr.pocketnode.data.database.entity.BalanceCacheEntity
import com.rjnr.pocketnode.data.database.entity.TransactionEntity

@Database(
    entities = [TransactionEntity::class, BalanceCacheEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun balanceCacheDao(): BalanceCacheDao
}
