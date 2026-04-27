package com.rjnr.pocketnode.data.database.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.entity.PendingBroadcastEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PendingBroadcastDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: PendingBroadcastDao

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.pendingBroadcastDao()
    }

    @After fun teardown() = db.close()

    private fun row(
        hash: String = "0xaa",
        wallet: String = "w1",
        net: String = "TESTNET",
        state: String = "BROADCASTING",
        nullCount: Int = 0
    ) = PendingBroadcastEntity(
        txHash = hash, walletId = wallet, network = net,
        signedTxJson = "{}", reservedInputs = "[]",
        state = state, submittedAtTipBlock = 100L, nullCount = nullCount,
        createdAt = 0L, lastCheckedAt = 0L
    )

    @Test
    fun `insert and getActive returns row`() = runTest {
        dao.insert(row())
        val active = dao.getActive("w1", "TESTNET")
        assertEquals(1, active.size)
        assertEquals("0xaa", active[0].txHash)
    }

    @Test
    fun `getActive filters out terminal states`() = runTest {
        dao.insert(row(hash = "0xaa", state = "BROADCASTING"))
        dao.insert(row(hash = "0xbb", state = "BROADCAST"))
        dao.insert(row(hash = "0xcc", state = "CONFIRMED"))
        dao.insert(row(hash = "0xdd", state = "FAILED"))
        val active = dao.getActive("w1", "TESTNET")
        assertEquals(2, active.size)
        assertEquals(setOf("0xaa", "0xbb"), active.map { it.txHash }.toSet())
    }

    @Test
    fun `getActive scopes to walletId and network`() = runTest {
        dao.insert(row(hash = "0xaa", wallet = "w1", net = "TESTNET"))
        dao.insert(row(hash = "0xbb", wallet = "w2", net = "TESTNET"))
        dao.insert(row(hash = "0xcc", wallet = "w1", net = "MAINNET"))
        val active = dao.getActive("w1", "TESTNET")
        assertEquals(1, active.size)
        assertEquals("0xaa", active[0].txHash)
    }

    @Test
    fun `compareAndUpdateState succeeds when expected matches`() = runTest {
        dao.insert(row(state = "BROADCASTING"))
        val rows = dao.compareAndUpdateState("0xaa", expected = "BROADCASTING", next = "BROADCAST", now = 5L)
        assertEquals(1, rows)
        val updated = dao.getActive("w1", "TESTNET")[0]
        assertEquals("BROADCAST", updated.state)
        assertEquals(5L, updated.lastCheckedAt)
    }

    @Test
    fun `compareAndUpdateState fails when expected mismatches`() = runTest {
        dao.insert(row(state = "BROADCAST"))
        val rows = dao.compareAndUpdateState("0xaa", expected = "BROADCASTING", next = "FAILED", now = 5L)
        assertEquals(0, rows)
        val unchanged = dao.getActive("w1", "TESTNET")[0]
        assertEquals("BROADCAST", unchanged.state)
    }

    @Test
    fun `updateNullCount writes count and timestamp`() = runTest {
        dao.insert(row(nullCount = 0))
        dao.updateNullCount("0xaa", count = 2, now = 10L)
        val r = dao.getActive("w1", "TESTNET")[0]
        assertEquals(2, r.nullCount)
        assertEquals(10L, r.lastCheckedAt)
    }

    @Test
    fun `delete removes row`() = runTest {
        dao.insert(row())
        dao.delete("0xaa")
        assertEquals(0, dao.getActive("w1", "TESTNET").size)
    }

    @Test
    fun `observeActive emits current snapshot then updates`() = runTest {
        dao.insert(row(hash = "0xaa", state = "BROADCASTING"))
        val first = dao.observeActive("w1", "TESTNET").first()
        assertEquals(1, first.size)

        dao.compareAndUpdateState("0xaa", "BROADCASTING", "CONFIRMED", now = 0L)
        val afterTerminal = dao.observeActive("w1", "TESTNET").first()
        assertEquals(0, afterTerminal.size)
    }

    @Test
    fun `observeFailed emits FAILED rows only`() = runTest {
        dao.insert(row(hash = "0xaa", state = "FAILED"))
        dao.insert(row(hash = "0xbb", state = "BROADCASTING"))
        val failed = dao.observeFailed("w1", "TESTNET").first()
        assertEquals(1, failed.size)
        assertEquals("0xaa", failed[0].txHash)
    }

    @Test
    fun `getFailedRow returns null for non-FAILED rows`() = runTest {
        dao.insert(row(hash = "0xaa", state = "BROADCAST"))
        assertEquals(null, dao.getFailedRow("0xaa"))
    }

    @Test
    fun `getFailedRow returns the row when FAILED`() = runTest {
        dao.insert(row(hash = "0xaa", state = "FAILED"))
        val r = dao.getFailedRow("0xaa")
        assertEquals("0xaa", r?.txHash)
    }
}
