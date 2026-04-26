package com.rjnr.pocketnode.data.database.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.entity.SyncProgressEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncProgressDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: SyncProgressDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.syncProgressDao()
    }

    @After
    fun tearDown() { db.close() }

    private fun row(
        walletId: String = "w-1",
        network: String = "MAINNET",
        light: Long = 100L,
        local: Long = 100L,
        ts: Long = 1_000L
    ) = SyncProgressEntity(walletId, network, light, local, ts)

    @Test
    fun `upsert then get returns the row`() = runTest {
        dao.upsert(row(local = 500L))
        val r = dao.get("w-1", "MAINNET")
        assertNotNull(r)
        assertEquals(500L, r!!.localSavedBlockNumber)
    }

    @Test
    fun `get returns null when row absent`() = runTest {
        assertNull(dao.get("nonexistent", "MAINNET"))
    }

    @Test
    fun `upsert replaces on conflict (same PK)`() = runTest {
        dao.upsert(row(local = 100L, ts = 1L))
        dao.upsert(row(local = 200L, ts = 2L))
        val r = dao.get("w-1", "MAINNET")!!
        assertEquals(200L, r.localSavedBlockNumber)
        assertEquals(2L, r.updatedAt)
    }

    @Test
    fun `same walletId different network are distinct rows`() = runTest {
        dao.upsert(row(network = "MAINNET", local = 100L))
        dao.upsert(row(network = "TESTNET", local = 200L))
        assertEquals(100L, dao.get("w-1", "MAINNET")!!.localSavedBlockNumber)
        assertEquals(200L, dao.get("w-1", "TESTNET")!!.localSavedBlockNumber)
    }

    @Test
    fun `getAllForNetwork returns only rows for that network`() = runTest {
        dao.upsert(row(walletId = "w-1", network = "MAINNET"))
        dao.upsert(row(walletId = "w-2", network = "MAINNET"))
        dao.upsert(row(walletId = "w-3", network = "TESTNET"))

        val mainnet = dao.getAllForNetwork("MAINNET")
        assertEquals(2, mainnet.size)
        assertTrue(mainnet.all { it.network == "MAINNET" })
    }

    @Test
    fun `updateLocalSaved updates only that field`() = runTest {
        dao.upsert(row(light = 100L, local = 100L, ts = 1L))
        dao.updateLocalSaved("w-1", "MAINNET", 999L, 5L)
        val r = dao.get("w-1", "MAINNET")!!
        assertEquals(100L, r.lightStartBlockNumber)        // unchanged
        assertEquals(999L, r.localSavedBlockNumber)
        assertEquals(5L, r.updatedAt)
    }

    @Test
    fun `updateLightStart returns 0 when row absent`() = runTest {
        val rows = dao.updateLightStart("missing", "MAINNET", 999L, 1L)
        assertEquals(0, rows)
    }

    @Test
    fun `updateLightStart updates only lightStartBlockNumber preserving localSavedBlockNumber`() = runTest {
        dao.upsert(row(light = 100L, local = 500L, ts = 1L))

        val rows = dao.updateLightStart("w-1", "MAINNET", 999L, 5L)
        assertEquals(1, rows)

        val r = dao.get("w-1", "MAINNET")!!
        assertEquals(999L, r.lightStartBlockNumber)
        assertEquals(500L, r.localSavedBlockNumber)   // preserved (closes the race)
        assertEquals(5L, r.updatedAt)
    }

    @Test
    fun `deleteForWallet removes all networks for that walletId`() = runTest {
        dao.upsert(row(network = "MAINNET"))
        dao.upsert(row(network = "TESTNET"))
        dao.upsert(row(walletId = "w-2", network = "MAINNET"))

        dao.deleteForWallet("w-1")

        assertNull(dao.get("w-1", "MAINNET"))
        assertNull(dao.get("w-1", "TESTNET"))
        assertNotNull(dao.get("w-2", "MAINNET"))
    }
}
