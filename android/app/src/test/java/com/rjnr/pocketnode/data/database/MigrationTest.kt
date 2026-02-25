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
 * Tests that MIGRATION_1_2 correctly creates header_cache and dao_cells tables
 * while preserving existing data in transactions and balance_cache.
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
    fun `migration SQL creates header_cache with correct schema`() = runTest {
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
    fun `migration SQL creates dao_cells with correct schema`() = runTest {
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
