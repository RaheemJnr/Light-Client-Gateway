package com.rjnr.pocketnode.data.database.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.entity.HeaderCacheEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HeaderCacheDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: HeaderCacheDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.headerCacheDao()
    }

    @After
    fun teardown() { db.close() }

    private fun makeHeader(
        hash: String = "0xabc",
        network: String = "MAINNET"
    ) = HeaderCacheEntity(
        blockHash = hash,
        number = "0x100",
        epoch = "0x7080291000032",
        timestamp = "0x18c8d0a7a00",
        dao = "0x40b4d9a3ddc9e730",
        network = network,
        cachedAt = System.currentTimeMillis()
    )

    @Test
    fun `upsert and getByBlockHash`() = runTest {
        dao.upsert(makeHeader("0xabc"))
        val result = dao.getByBlockHash("0xabc")
        assertNotNull(result)
        assertEquals("0x100", result!!.number)
    }

    @Test
    fun `getByBlockHash returns null for missing hash`() = runTest {
        assertNull(dao.getByBlockHash("0xmissing"))
    }

    @Test
    fun `getByNetwork filters correctly`() = runTest {
        dao.upsert(makeHeader("0x1", "MAINNET"))
        dao.upsert(makeHeader("0x2", "TESTNET"))

        val mainnet = dao.getByNetwork("MAINNET")
        assertEquals(1, mainnet.size)
        assertEquals("0x1", mainnet[0].blockHash)
    }

    @Test
    fun `upsert replaces existing entry`() = runTest {
        dao.upsert(makeHeader("0xabc").copy(number = "0x100"))
        dao.upsert(makeHeader("0xabc").copy(number = "0x200"))

        val result = dao.getByBlockHash("0xabc")
        assertEquals("0x200", result!!.number)
    }

    @Test
    fun `deleteByNetwork only removes matching network`() = runTest {
        dao.upsert(makeHeader("0x1", "MAINNET"))
        dao.upsert(makeHeader("0x2", "TESTNET"))

        dao.deleteByNetwork("MAINNET")

        assertTrue(dao.getByNetwork("MAINNET").isEmpty())
        assertEquals(1, dao.getByNetwork("TESTNET").size)
    }
}
