package com.rjnr.pocketnode.data.database.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.entity.TransactionEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TransactionDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: TransactionDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.transactionDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun makeTx(
        hash: String,
        network: String = "MAINNET",
        status: String = "CONFIRMED",
        isLocal: Boolean = false,
        confirmations: Int = 5,
        timestamp: Long = 1700000000000L
    ) = TransactionEntity(
        txHash = hash,
        blockNumber = "0x1a4",
        blockHash = "0xdef",
        timestamp = timestamp,
        balanceChange = "0x174876e800",
        direction = "out",
        fee = "0x186a0",
        confirmations = confirmations,
        blockTimestampHex = null,
        network = network,
        status = status,
        isLocal = isLocal,
        cachedAt = System.currentTimeMillis()
    )

    @Test
    fun `insert and getByNetwork returns correct results`() = runTest {
        dao.insert(makeTx("0x1"))
        dao.insert(makeTx("0x2", network = "TESTNET"))

        val mainnet = dao.getByNetwork("MAINNET")
        assertEquals(1, mainnet.size)
        assertEquals("0x1", mainnet[0].txHash)
    }

    @Test
    fun `getPending returns only pending transactions`() = runTest {
        dao.insert(makeTx("0x1", status = "PENDING", isLocal = true))
        dao.insert(makeTx("0x2", status = "CONFIRMED"))

        val pending = dao.getPending("MAINNET")
        assertEquals(1, pending.size)
        assertEquals("0x1", pending[0].txHash)
    }

    @Test
    fun `updateStatus changes status and clears isLocal`() = runTest {
        dao.insert(makeTx("0x1", status = "PENDING", isLocal = true, confirmations = 0))

        dao.updateStatus("0x1", "CONFIRMED", 3, "0x100", "0xblockhash")

        val updated = dao.getByTxHash("0x1")!!
        assertEquals("CONFIRMED", updated.status)
        assertEquals(3, updated.confirmations)
        assertFalse(updated.isLocal)
    }

    @Test
    fun `deleteByNetwork only removes matching network`() = runTest {
        dao.insert(makeTx("0x1", network = "MAINNET"))
        dao.insert(makeTx("0x2", network = "TESTNET"))

        dao.deleteByNetwork("MAINNET")

        assertTrue(dao.getByNetwork("MAINNET").isEmpty())
        assertEquals(1, dao.getByNetwork("TESTNET").size)
    }

    @Test
    fun `pending transactions sort before confirmed`() = runTest {
        dao.insert(makeTx("0xconfirmed", status = "CONFIRMED", timestamp = 1700000000000L))
        dao.insert(makeTx("0xpending", status = "PENDING", timestamp = 1700000001000L))

        val results = dao.getByNetwork("MAINNET")
        assertEquals("0xpending", results[0].txHash)
    }

    @Test
    fun `insertAll replaces existing entries`() = runTest {
        dao.insert(makeTx("0x1", confirmations = 0, status = "PENDING"))

        val updated = makeTx("0x1", confirmations = 5, status = "CONFIRMED")
        dao.insertAll(listOf(updated))

        val result = dao.getByTxHash("0x1")!!
        assertEquals("CONFIRMED", result.status)
        assertEquals(5, result.confirmations)
    }
}
