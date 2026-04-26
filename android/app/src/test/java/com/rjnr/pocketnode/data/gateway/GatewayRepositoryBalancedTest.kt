package com.rjnr.pocketnode.data.gateway

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.dao.SyncProgressDao
import com.rjnr.pocketnode.data.database.entity.SyncProgressEntity
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for GatewayRepository.applyBalancedFilter — the function is private,
 * so we test the algorithm via a thin test-only wrapper that mirrors production logic.
 *
 * Tests only the algorithm against the DAO. Production wires this to the
 * GatewayRepository's `currentNetwork` and `activeWalletId` fields; this helper
 * substitutes them via parameters. The integration is exercised by manual smoke
 * (Task 15), which is the cheapest way to cover it — full integration would
 * require constructing a fake GatewayRepository with stubbed JNI calls.
 */
@RunWith(RobolectricTestRunner::class)
class GatewayRepositoryBalancedTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: SyncProgressDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.syncProgressDao()
    }

    @After
    fun tearDown() { db.close() }

    private fun wallet(id: String, lastActiveAt: Long = 0L) = WalletEntity(
        walletId = id, name = id, type = "mnemonic",
        derivationPath = "m/44'/309'/0'/0/0", parentWalletId = null,
        accountIndex = 0, mainnetAddress = "ckb1$id", testnetAddress = "ckt1$id",
        isActive = false, createdAt = 0L, lastActiveAt = lastActiveAt
    )

    private suspend fun seedProgress(walletId: String, network: String, block: Long) {
        dao.upsert(SyncProgressEntity(walletId, network, block, block, 0L))
    }

    /**
     * Helper duplicating GatewayRepository.applyBalancedFilter logic for unit testing.
     * MUST stay byte-equivalent to the production implementation.
     */
    private suspend fun filter(
        wallets: List<WalletEntity>,
        activeId: String,
        network: String = "MAINNET",
        threshold: Long = 100_000L
    ): List<WalletEntity> {
        if (wallets.size <= 1) return wallets
        val progress = wallets.associate { it.walletId to (dao.get(it.walletId, network)?.localSavedBlockNumber ?: 0L) }
        val maxProgress = progress.values.max()
        return wallets.filter { wallet ->
            val lag = maxProgress - (progress[wallet.walletId] ?: 0L)
            wallet.walletId == activeId || lag <= threshold
        }
    }

    @Test
    fun `single wallet returns input unchanged`() = runTest {
        val w = listOf(wallet("a"))
        assertEquals(w, filter(w, activeId = "a"))
    }

    @Test
    fun `all wallets within threshold all kept`() = runTest {
        seedProgress("a", "MAINNET", 1_000_000L)
        seedProgress("b", "MAINNET", 950_000L)
        seedProgress("c", "MAINNET", 900_001L)
        val r = filter(listOf(wallet("a"), wallet("b"), wallet("c")), activeId = "a")
        assertEquals(3, r.size)
    }

    @Test
    fun `laggard dropped when active is leader`() = runTest {
        seedProgress("a", "MAINNET", 1_000_000L)
        seedProgress("b", "MAINNET", 500_000L)   // 500k behind
        val r = filter(listOf(wallet("a"), wallet("b")), activeId = "a")
        assertEquals(listOf("a"), r.map { it.walletId })
    }

    @Test
    fun `active wallet kept even when itself is the laggard`() = runTest {
        seedProgress("a", "MAINNET", 1_000_000L)
        seedProgress("b", "MAINNET", 500_000L)
        val r = filter(listOf(wallet("a"), wallet("b")), activeId = "b")
        assertEquals(setOf("a", "b"), r.map { it.walletId }.toSet())
    }

    @Test
    fun `multiple laggards dropped active kept`() = runTest {
        seedProgress("a", "MAINNET", 1_000_000L)
        seedProgress("b", "MAINNET", 500_000L)
        seedProgress("c", "MAINNET", 400_000L)
        val r = filter(listOf(wallet("a"), wallet("b"), wallet("c")), activeId = "a")
        assertEquals(listOf("a"), r.map { it.walletId })
    }

    @Test
    fun `no progress rows present all wallets pass`() = runTest {
        // No upserts to dao
        val r = filter(listOf(wallet("a"), wallet("b"), wallet("c")), activeId = "a")
        assertEquals(3, r.size)
    }

    @Test
    fun `boundary lag equals threshold kept`() = runTest {
        seedProgress("a", "MAINNET", 200_000L)
        seedProgress("b", "MAINNET", 100_000L)   // exactly 100k behind
        val r = filter(listOf(wallet("a"), wallet("b")), activeId = "a")
        assertEquals(2, r.size)
    }

    @Test
    fun `boundary lag exceeds threshold by one dropped`() = runTest {
        seedProgress("a", "MAINNET", 200_000L)
        seedProgress("b", "MAINNET", 99_999L)    // 100_001 behind
        val r = filter(listOf(wallet("a"), wallet("b")), activeId = "a")
        assertEquals(listOf("a"), r.map { it.walletId })
    }
}
