package com.rjnr.pocketnode.data.database

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for database migrations.
 *
 * - In-memory DB tests verify the v3 schema (tables and DAOs work correctly).
 * - [Migration2To3Test] verifies the v2→v3 migration SQL (data preservation, new columns, indices).
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun teardown() { db.close() }

    @Test
    fun `v3 database has all five tables accessible`() = runTest {
        assertNull(db.balanceCacheDao().getByNetwork("MAINNET"))
        assertEquals(0, db.transactionDao().getPending("MAINNET").size)
        assertNull(db.headerCacheDao().getByBlockHash("0xtest"))
        assertEquals(0, db.daoCellDao().getActiveByNetwork("MAINNET").size)
        assertEquals(0, db.walletDao().count())
    }

    @Test
    fun `v3 header_cache table has correct schema`() = runTest {
        val entity = com.rjnr.pocketnode.data.database.entity.HeaderCacheEntity(
            blockHash = "0xmigtest",
            number = "0x100",
            epoch = "0x7080291000032",
            timestamp = "0x18c8d0a7a00",
            dao = "0x40b4d9a3ddc9e730",
            network = "MAINNET",
            cachedAt = System.currentTimeMillis()
        )
        db.headerCacheDao().upsert(entity)

        val result = db.headerCacheDao().getByBlockHash("0xmigtest")
        assertNotNull(result)
        assertEquals("0x100", result!!.number)
    }

    @Test
    fun `v3 dao_cells table has correct schema with walletId`() = runTest {
        val entity = com.rjnr.pocketnode.data.database.entity.DaoCellEntity(
            txHash = "0xmigtest",
            index = "0x0",
            capacity = 10_200_000_000L,
            status = "DEPOSITED",
            depositBlockNumber = 100L,
            depositBlockHash = "0xblockhash",
            depositEpochHex = "0x7080291000032",
            withdrawBlockNumber = null,
            withdrawBlockHash = null,
            withdrawEpochHex = null,
            compensation = 500_000L,
            unlockEpochHex = null,
            depositTimestamp = 1700000000000L,
            network = "MAINNET",
            lastUpdatedAt = System.currentTimeMillis()
        )
        db.daoCellDao().upsert(entity)

        val result = db.daoCellDao().getByOutPoint("0xmigtest", "0x0")
        assertNotNull(result)
        assertEquals("DEPOSITED", result!!.status)
        assertEquals("", result.walletId)
    }
}

/**
 * Tests the actual v2→v3 migration SQL by creating a raw v2 database,
 * inserting data, running the migration, and verifying results.
 */
@RunWith(RobolectricTestRunner::class)
class Migration2To3Test {

    private var db: SupportSQLiteDatabase? = null

    private fun createV2Database(): SupportSQLiteDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("migration-test")

        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("migration-test")
            .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS transactions (
                            txHash TEXT NOT NULL,
                            blockNumber TEXT NOT NULL,
                            blockHash TEXT NOT NULL,
                            timestamp INTEGER NOT NULL,
                            balanceChange TEXT NOT NULL,
                            direction TEXT NOT NULL,
                            fee TEXT NOT NULL,
                            confirmations INTEGER NOT NULL,
                            blockTimestampHex TEXT,
                            network TEXT NOT NULL,
                            status TEXT NOT NULL,
                            isLocal INTEGER NOT NULL,
                            cachedAt INTEGER NOT NULL,
                            PRIMARY KEY(txHash)
                        )
                    """)
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS balance_cache (
                            network TEXT NOT NULL,
                            address TEXT NOT NULL,
                            capacity TEXT NOT NULL,
                            capacityCkb TEXT NOT NULL,
                            blockNumber TEXT NOT NULL,
                            cachedAt INTEGER NOT NULL,
                            PRIMARY KEY(network)
                        )
                    """)
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS header_cache (
                            blockHash TEXT NOT NULL,
                            number TEXT NOT NULL,
                            epoch TEXT NOT NULL,
                            timestamp TEXT NOT NULL,
                            dao TEXT NOT NULL,
                            network TEXT NOT NULL,
                            cachedAt INTEGER NOT NULL,
                            PRIMARY KEY(blockHash)
                        )
                    """)
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS dao_cells (
                            txHash TEXT NOT NULL,
                            `index` TEXT NOT NULL,
                            capacity INTEGER NOT NULL,
                            status TEXT NOT NULL,
                            depositBlockNumber INTEGER NOT NULL,
                            depositBlockHash TEXT NOT NULL,
                            depositEpochHex TEXT,
                            withdrawBlockNumber INTEGER,
                            withdrawBlockHash TEXT,
                            withdrawEpochHex TEXT,
                            compensation INTEGER NOT NULL,
                            unlockEpochHex TEXT,
                            depositTimestamp INTEGER NOT NULL,
                            network TEXT NOT NULL,
                            lastUpdatedAt INTEGER NOT NULL,
                            PRIMARY KEY(txHash, `index`)
                        )
                    """)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        return helper.writableDatabase
    }

    @After
    fun tearDown() {
        db?.let { if (it.isOpen) it.close() }
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("migration-test")
    }

    @Test
    fun `migrate 2 to 3 creates wallets table`() {
        db = createV2Database()
        MIGRATION_2_3.migrate(db!!)

        val cursor = db!!.query("SELECT * FROM wallets")
        assertEquals(0, cursor.count)
        val columns = (0 until cursor.columnCount).map { cursor.getColumnName(it) }.toSet()
        assertTrue(columns.contains("walletId"))
        assertTrue(columns.contains("name"))
        assertTrue(columns.contains("type"))
        assertTrue(columns.contains("isActive"))
        assertTrue(columns.contains("createdAt"))
        cursor.close()
    }

    @Test
    fun `migrate 2 to 3 adds walletId to transactions`() {
        db = createV2Database()
        db!!.execSQL("""
            INSERT INTO transactions (txHash, blockNumber, blockHash, timestamp, balanceChange, direction, fee, confirmations, blockTimestampHex, network, status, isLocal, cachedAt)
            VALUES ('0xabc', '1000', '0xhash', 1709337600000, '100', 'in', '0x186a0', 10, null, 'MAINNET', 'CONFIRMED', 0, 1709337600000)
        """)

        MIGRATION_2_3.migrate(db!!)

        val cursor = db!!.query("SELECT walletId FROM transactions WHERE txHash = '0xabc'")
        assertTrue(cursor.moveToFirst())
        assertEquals("", cursor.getString(0))
        cursor.close()
    }

    @Test
    fun `migrate 2 to 3 recreates balance_cache with composite PK`() {
        db = createV2Database()
        db!!.execSQL("""
            INSERT INTO balance_cache (network, address, capacity, capacityCkb, blockNumber, cachedAt)
            VALUES ('MAINNET', 'ckb1test', '100000000', '1.0', '1000', 1709337600000)
        """)

        MIGRATION_2_3.migrate(db!!)

        val cursor = db!!.query("SELECT walletId, network, address FROM balance_cache")
        assertTrue(cursor.moveToFirst())
        assertEquals("", cursor.getString(0))
        assertEquals("MAINNET", cursor.getString(1))
        assertEquals("ckb1test", cursor.getString(2))
        cursor.close()
    }

    @Test
    fun `migrate 2 to 3 creates composite indices`() {
        db = createV2Database()
        MIGRATION_2_3.migrate(db!!)

        val cursor = db!!.query("SELECT name FROM sqlite_master WHERE type='index' AND name='idx_tx_wallet_network_time'")
        assertTrue(cursor.moveToFirst())
        cursor.close()

        val cursor2 = db!!.query("SELECT name FROM sqlite_master WHERE type='index' AND name='idx_dao_wallet_network'")
        assertTrue(cursor2.moveToFirst())
        cursor2.close()

        val cursor3 = db!!.query("SELECT name FROM sqlite_master WHERE type='index' AND name='idx_header_network_number'")
        assertTrue(cursor3.moveToFirst())
        cursor3.close()
    }
}
