package com.rjnr.pocketnode.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 → v2: Add header_cache and dao_cells tables (Phase 2 of Issue #40).
 * Purely additive — existing transactions and balance_cache tables are untouched.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `header_cache` (
                `blockHash` TEXT NOT NULL,
                `number` TEXT NOT NULL,
                `epoch` TEXT NOT NULL,
                `timestamp` TEXT NOT NULL,
                `dao` TEXT NOT NULL,
                `network` TEXT NOT NULL,
                `cachedAt` INTEGER NOT NULL,
                PRIMARY KEY(`blockHash`)
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `dao_cells` (
                `txHash` TEXT NOT NULL,
                `index` TEXT NOT NULL,
                `capacity` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                `depositBlockNumber` INTEGER NOT NULL,
                `depositBlockHash` TEXT NOT NULL,
                `depositEpochHex` TEXT,
                `withdrawBlockNumber` INTEGER,
                `withdrawBlockHash` TEXT,
                `withdrawEpochHex` TEXT,
                `compensation` INTEGER NOT NULL,
                `unlockEpochHex` TEXT,
                `depositTimestamp` INTEGER NOT NULL,
                `network` TEXT NOT NULL,
                `lastUpdatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`txHash`, `index`)
            )
            """.trimIndent()
        )
    }
}

/**
 * v2 -> v3: Add wallets table and walletId column to existing tables for multi-wallet support (M3).
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. Create wallets table
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `wallets` (
                `walletId` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `derivationPath` TEXT,
                `parentWalletId` TEXT,
                `accountIndex` INTEGER NOT NULL DEFAULT 0,
                `mainnetAddress` TEXT NOT NULL DEFAULT '',
                `testnetAddress` TEXT NOT NULL DEFAULT '',
                `isActive` INTEGER NOT NULL DEFAULT 0,
                `createdAt` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`walletId`)
            )
            """.trimIndent()
        )

        // 2. Add walletId column to transactions
        db.execSQL(
            "ALTER TABLE `transactions` ADD COLUMN `walletId` TEXT NOT NULL DEFAULT ''"
        )
        // Create the index Room expects
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_tx_wallet_network_time` ON `transactions` (`walletId`, `network`, `timestamp` DESC)"
        )

        // 3. Add walletId column to balance_cache
        db.execSQL(
            "ALTER TABLE `balance_cache` ADD COLUMN `walletId` TEXT NOT NULL DEFAULT ''"
        )

        // 4. Add walletId column to dao_cells
        db.execSQL(
            "ALTER TABLE `dao_cells` ADD COLUMN `walletId` TEXT NOT NULL DEFAULT ''"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_dao_wallet_network` ON `dao_cells` (`walletId`, `network`)"
        )

        // 5. Add index to header_cache (entity annotation added in M3 but never created in v1→v2)
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_header_network_number` ON `header_cache` (`network`, `number`)"
        )
    }
}
