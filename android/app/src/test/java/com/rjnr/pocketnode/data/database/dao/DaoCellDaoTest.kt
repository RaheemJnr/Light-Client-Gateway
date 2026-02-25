package com.rjnr.pocketnode.data.database.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.entity.DaoCellEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DaoCellDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: DaoCellDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.daoCellDao()
    }

    @After
    fun teardown() { db.close() }

    private fun makeCell(
        txHash: String = "0xdep1",
        index: String = "0x0",
        status: String = "DEPOSITED",
        network: String = "MAINNET"
    ) = DaoCellEntity(
        txHash = txHash,
        index = index,
        capacity = 10_200_000_000L,
        status = status,
        depositBlockNumber = 100L,
        depositBlockHash = "0xblockhash",
        depositEpochHex = "0x7080291000032",
        withdrawBlockNumber = null,
        withdrawBlockHash = null,
        withdrawEpochHex = null,
        compensation = 500_000L,
        unlockEpochHex = null,
        depositTimestamp = 1700000000000L,
        network = network,
        lastUpdatedAt = System.currentTimeMillis()
    )

    @Test
    fun `upsert and getByOutPoint`() = runTest {
        dao.upsert(makeCell("0xdep1", "0x0"))
        val result = dao.getByOutPoint("0xdep1", "0x0")
        assertNotNull(result)
        assertEquals(10_200_000_000L, result!!.capacity)
    }

    @Test
    fun `getActiveByNetwork excludes COMPLETED`() = runTest {
        dao.upsert(makeCell("0x1", status = "DEPOSITED"))
        dao.upsert(makeCell("0x2", status = "LOCKED"))
        dao.upsert(makeCell("0x3", status = "COMPLETED"))

        val active = dao.getActiveByNetwork("MAINNET")
        assertEquals(2, active.size)
        assertTrue(active.none { it.status == "COMPLETED" })
    }

    @Test
    fun `getCompletedByNetwork returns only COMPLETED`() = runTest {
        dao.upsert(makeCell("0x1", status = "DEPOSITED"))
        dao.upsert(makeCell("0x2", status = "COMPLETED"))

        val completed = dao.getCompletedByNetwork("MAINNET")
        assertEquals(1, completed.size)
        assertEquals("0x2", completed[0].txHash)
    }

    @Test
    fun `updateStatus changes status`() = runTest {
        dao.upsert(makeCell("0xdep1", status = "DEPOSITED"))
        dao.updateStatus("0xdep1", "0x0", "LOCKED")

        val updated = dao.getByOutPoint("0xdep1", "0x0")
        assertEquals("LOCKED", updated!!.status)
    }

    @Test
    fun `deleteByNetwork only removes matching network`() = runTest {
        dao.upsert(makeCell("0x1", network = "MAINNET"))
        dao.upsert(makeCell("0x2", network = "TESTNET"))

        dao.deleteByNetwork("MAINNET")

        assertTrue(dao.getActiveByNetwork("MAINNET").isEmpty())
        assertEquals(1, dao.getActiveByNetwork("TESTNET").size)
    }

    @Test
    fun `upsert replaces existing entry`() = runTest {
        dao.upsert(makeCell("0xdep1", status = "DEPOSITING"))
        dao.upsert(makeCell("0xdep1", status = "DEPOSITED"))

        val result = dao.getByOutPoint("0xdep1", "0x0")
        assertEquals("DEPOSITED", result!!.status)
    }
}
