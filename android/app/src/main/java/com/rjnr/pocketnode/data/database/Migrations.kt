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

        // 3. Recreate balance_cache with composite PK (walletId, network)
        // SQLite can't alter primary keys, so we must recreate the table
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `balance_cache_new` (
                `walletId` TEXT NOT NULL DEFAULT '',
                `network` TEXT NOT NULL,
                `address` TEXT NOT NULL DEFAULT '',
                `capacity` TEXT NOT NULL DEFAULT '0',
                `capacityCkb` TEXT NOT NULL DEFAULT '0',
                `blockNumber` TEXT NOT NULL DEFAULT '0',
                `cachedAt` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`walletId`, `network`)
            )
            """.trimIndent()
        )
        db.execSQL("INSERT INTO `balance_cache_new` SELECT '', `network`, `address`, `capacity`, `capacityCkb`, `blockNumber`, `cachedAt` FROM `balance_cache`")
        db.execSQL("DROP TABLE `balance_cache`")
        db.execSQL("ALTER TABLE `balance_cache_new` RENAME TO `balance_cache`")

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

/**
 * v3 → v4: Fix balance_cache PK for users who ran the broken v2→v3 migration,
 * and add Phase 2 columns (lastActiveAt, colorIndex) to wallets table.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. Fix balance_cache PK for users who ran broken v2→v3
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `balance_cache_new` (
                `walletId` TEXT NOT NULL DEFAULT '',
                `network` TEXT NOT NULL,
                `address` TEXT NOT NULL DEFAULT '',
                `capacity` TEXT NOT NULL DEFAULT '0',
                `capacityCkb` TEXT NOT NULL DEFAULT '0',
                `blockNumber` TEXT NOT NULL DEFAULT '0',
                `cachedAt` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`walletId`, `network`)
            )
            """.trimIndent()
        )
        db.execSQL("INSERT OR IGNORE INTO `balance_cache_new` SELECT `walletId`, `network`, `address`, `capacity`, `capacityCkb`, `blockNumber`, `cachedAt` FROM `balance_cache`")
        db.execSQL("DROP TABLE `balance_cache`")
        db.execSQL("ALTER TABLE `balance_cache_new` RENAME TO `balance_cache`")

        // 2. Add Phase 2 columns to wallets
        db.execSQL("ALTER TABLE `wallets` ADD COLUMN `lastActiveAt` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `wallets` ADD COLUMN `colorIndex` INTEGER NOT NULL DEFAULT 0")
    }
}
