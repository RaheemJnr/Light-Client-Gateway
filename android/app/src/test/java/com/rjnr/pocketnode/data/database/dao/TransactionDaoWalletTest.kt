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
class TransactionDaoWalletTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: TransactionDao

    private fun createTx(hash: String, walletId: String, network: String = "MAINNET") =
        TransactionEntity(
            txHash = hash, blockNumber = "100", blockHash = "0x", timestamp = System.currentTimeMillis(),
            balanceChange = "100", direction = "in", fee = "0x186a0", confirmations = 10,
            blockTimestampHex = null, network = network, status = "CONFIRMED",
            isLocal = false, cachedAt = System.currentTimeMillis(), walletId = walletId
        )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.transactionDao()
    }

    @After
    fun tearDown() { database.close() }

    @Test
    fun `getByWalletAndNetwork filters by wallet`() = runTest {
        dao.insertAll(listOf(
            createTx("tx1", "wallet-A"),
            createTx("tx2", "wallet-B"),
            createTx("tx3", "wallet-A")
        ))

        val walletATxs = dao.getByWalletAndNetwork("wallet-A", "MAINNET")
        assertEquals(2, walletATxs.size)
        assertTrue(walletATxs.all { it.walletId == "wallet-A" })
    }

    @Test
    fun `getByWalletAndNetwork filters by network`() = runTest {
        dao.insertAll(listOf(
            createTx("tx1", "wallet-A", "MAINNET"),
            createTx("tx2", "wallet-A", "TESTNET")
        ))

        val mainnet = dao.getByWalletAndNetwork("wallet-A", "MAINNET")
        assertEquals(1, mainnet.size)
        assertEquals("tx1", mainnet[0].txHash)
    }

    @Test
    fun `getAllByWalletAndNetwork returns all without limit`() = runTest {
        val txs = (1..60).map { createTx("tx$it", "wallet-A") }
        dao.insertAll(txs)

        val all = dao.getAllByWalletAndNetwork("wallet-A", "MAINNET")
        assertEquals(60, all.size)
    }

    @Test
    fun `deleteByWalletAndNetwork only deletes target wallet`() = runTest {
        dao.insertAll(listOf(
            createTx("tx1", "wallet-A"),
            createTx("tx2", "wallet-B")
        ))

        dao.deleteByWalletAndNetwork("wallet-A", "MAINNET")

        val remaining = dao.getByWalletAndNetwork("wallet-B", "MAINNET")
        assertEquals(1, remaining.size)
        assertEquals("tx2", remaining[0].txHash)
    }
}
