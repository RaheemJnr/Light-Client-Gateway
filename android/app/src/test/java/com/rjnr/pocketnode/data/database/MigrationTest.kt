package com.rjnr.pocketnode.data.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies that the v2 database schema is correct and all four tables
 * (transactions, balance_cache, header_cache, dao_cells) are accessible.
 *
 * NOTE: These tests use an in-memory DB which creates a fresh v2 schema,
 * so MIGRATION_1_2 SQL is not actually exercised here. For true migration
 * path testing (v1 → v2 with data preservation), use MigrationTestHelper
 * in an instrumented test (src/androidTest/) with exported schemas.
 * TODO: Add instrumented migration test with MigrationTestHelper.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addMigrations(MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun teardown() { db.close() }

    @Test
    fun `v2 database has all four tables accessible`() = runTest {
        // Phase 1 tables still work
        assertNull(db.balanceCacheDao().getByNetwork("MAINNET"))
        assertEquals(0, db.transactionDao().getPending("MAINNET").size)

        // Phase 2 tables work
        assertNull(db.headerCacheDao().getByBlockHash("0xtest"))
        assertEquals(0, db.daoCellDao().getActiveByNetwork("MAINNET").size)
    }

    @Test
    fun `v2 header_cache table has correct schema`() = runTest {
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
    fun `v2 dao_cells table has correct schema`() = runTest {
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
        assertEquals(10_200_000_000L, result.capacity)
    }
}
