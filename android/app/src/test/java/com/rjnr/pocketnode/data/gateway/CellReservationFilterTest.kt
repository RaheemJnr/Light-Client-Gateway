package com.rjnr.pocketnode.data.gateway

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.entity.PendingBroadcastEntity
import com.rjnr.pocketnode.data.gateway.models.OutPoint
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies the cell-reservation filter logic that runs inside
 * GatewayRepository.prepareAndSend's sendMutex section. The filter
 * is a pure function over (live cells, reserved OutPoints), so the
 * test exercises that function shape directly rather than instantiating
 * the full Repository — same approach as GatewayRepositorySendTransactionTest.
 */
@RunWith(RobolectricTestRunner::class)
class CellReservationFilterTest {

    private lateinit var db: AppDatabase
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun teardown() = db.close()

    private fun outPoint(hash: String, idx: String = "0x0") = OutPoint(hash, idx)

    private fun row(
        txHash: String,
        inputs: List<OutPoint>,
        state: String = "BROADCAST",
        wallet: String = "w1",
        net: String = "TESTNET"
    ) = PendingBroadcastEntity(
        txHash = txHash,
        walletId = wallet,
        network = net,
        signedTxJson = "{}",
        reservedInputs = json.encodeToString(
            ListSerializer(OutPoint.serializer()),
            inputs
        ),
        state = state,
        submittedAtTipBlock = 0L,
        nullCount = 0,
        createdAt = 0L,
        lastCheckedAt = 0L
    )

    /** Mirrors the filter logic in GatewayRepository.prepareAndSend. */
    private suspend fun filterAvailable(
        live: List<OutPoint>, walletId: String, network: String
    ): List<OutPoint> {
        val pending = db.pendingBroadcastDao().getActive(walletId, network)
        val reserved = pending.flatMap {
            json.decodeFromString(
                ListSerializer(OutPoint.serializer()),
                it.reservedInputs
            )
        }.toSet()
        return live.filter { it !in reserved }
    }

    @Test
    fun `with no pending rows returns all live cells`() = runTest {
        val live = listOf(outPoint("0x01"), outPoint("0x02"), outPoint("0x03"))
        val available = filterAvailable(live, "w1", "TESTNET")
        assertEquals(3, available.size)
    }

    @Test
    fun `excludes inputs reserved by an active row`() = runTest {
        db.pendingBroadcastDao().insert(row("0xaa", listOf(outPoint("0x01"))))
        val live = listOf(outPoint("0x01"), outPoint("0x02"), outPoint("0x03"))
        val available = filterAvailable(live, "w1", "TESTNET")
        assertEquals(2, available.size)
        assertEquals(setOf(outPoint("0x02"), outPoint("0x03")), available.toSet())
    }

    @Test
    fun `excludes union across multiple active rows`() = runTest {
        db.pendingBroadcastDao().insert(row("0xaa", listOf(outPoint("0x01"), outPoint("0x02"))))
        db.pendingBroadcastDao().insert(row("0xbb", listOf(outPoint("0x03"))))
        val live = (1..5).map { outPoint("0x0$it") }
        val available = filterAvailable(live, "w1", "TESTNET")
        assertEquals(setOf(outPoint("0x04"), outPoint("0x05")), available.toSet())
    }

    @Test
    fun `terminal-state rows do not reserve anything`() = runTest {
        db.pendingBroadcastDao().insert(row("0xaa", listOf(outPoint("0x01")), state = "CONFIRMED"))
        db.pendingBroadcastDao().insert(row("0xbb", listOf(outPoint("0x02")), state = "FAILED"))
        val live = listOf(outPoint("0x01"), outPoint("0x02"))
        val available = filterAvailable(live, "w1", "TESTNET")
        assertEquals(2, available.size)
    }

    @Test
    fun `wallet and network scope is honored`() = runTest {
        db.pendingBroadcastDao().insert(row("0xaa", listOf(outPoint("0x01")), wallet = "w2"))
        db.pendingBroadcastDao().insert(row("0xbb", listOf(outPoint("0x02")), net = "MAINNET"))
        val live = listOf(outPoint("0x01"), outPoint("0x02"), outPoint("0x03"))
        val available = filterAvailable(live, "w1", "TESTNET")
        assertEquals(3, available.size)  // neither reservation matches w1+TESTNET
    }
}
