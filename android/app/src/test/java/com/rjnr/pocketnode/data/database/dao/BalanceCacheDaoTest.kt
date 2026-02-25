package com.rjnr.pocketnode.data.database.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.entity.BalanceCacheEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BalanceCacheDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: BalanceCacheDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.balanceCacheDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `upsert and getByNetwork returns cached balance`() = runTest {
        val entity = BalanceCacheEntity(
            network = "MAINNET",
            address = "ckb1qz...",
            capacity = "0x174876e800",
            capacityCkb = "1000.0",
            blockNumber = "0x1a4",
            cachedAt = System.currentTimeMillis()
        )
        dao.upsert(entity)

        val result = dao.getByNetwork("MAINNET")
        assertNotNull(result)
        assertEquals("0x174876e800", result!!.capacity)
    }

    @Test
    fun `upsert overwrites existing entry`() = runTest {
        dao.upsert(BalanceCacheEntity("MAINNET", "addr", "0x1", "1.0", "0x0", 100L))
        dao.upsert(BalanceCacheEntity("MAINNET", "addr", "0x2", "2.0", "0x1", 200L))

        val result = dao.getByNetwork("MAINNET")
        assertEquals("0x2", result!!.capacity)
    }

    @Test
    fun `getByNetwork returns null for missing network`() = runTest {
        assertNull(dao.getByNetwork("TESTNET"))
    }

    @Test
    fun `deleteByNetwork only removes matching network`() = runTest {
        dao.upsert(BalanceCacheEntity("MAINNET", "a", "0x1", "1", "0x0", 0L))
        dao.upsert(BalanceCacheEntity("TESTNET", "b", "0x2", "2", "0x0", 0L))

        dao.deleteByNetwork("MAINNET")

        assertNull(dao.getByNetwork("MAINNET"))
        assertNotNull(dao.getByNetwork("TESTNET"))
    }

    @Test
    fun `deleteAll removes everything`() = runTest {
        dao.upsert(BalanceCacheEntity("MAINNET", "a", "0x1", "1", "0x0", 0L))
        dao.upsert(BalanceCacheEntity("TESTNET", "b", "0x2", "2", "0x0", 0L))

        dao.deleteAll()

        assertNull(dao.getByNetwork("MAINNET"))
        assertNull(dao.getByNetwork("TESTNET"))
    }
}
