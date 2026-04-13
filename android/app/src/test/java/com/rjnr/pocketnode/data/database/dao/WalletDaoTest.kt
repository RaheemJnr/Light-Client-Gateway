package com.rjnr.pocketnode.data.database.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WalletDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var walletDao: WalletDao

    private fun createWallet(
        id: String = "wallet-1",
        name: String = "Test Wallet",
        type: String = "mnemonic",
        isActive: Boolean = false,
        parentWalletId: String? = null,
        accountIndex: Int = 0
    ) = WalletEntity(
        walletId = id,
        name = name,
        type = type,
        derivationPath = if (type == "mnemonic") "m/44'/309'/${accountIndex}'/0/0" else null,
        parentWalletId = parentWalletId,
        accountIndex = accountIndex,
        mainnetAddress = "ckb1_$id",
        testnetAddress = "ckt1_$id",
        isActive = isActive,
        createdAt = System.currentTimeMillis()
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        walletDao = database.walletDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `insert and retrieve active wallet`() = runTest {
        val wallet = createWallet(isActive = true)
        walletDao.insert(wallet)

        val active = walletDao.getActiveWallet().first()
        assertNotNull(active)
        assertEquals("wallet-1", active!!.walletId)
        assertTrue(active.isActive)
    }

    @Test
    fun `getActiveWallet returns null when no active wallet`() = runTest {
        val wallet = createWallet(isActive = false)
        walletDao.insert(wallet)

        val active = walletDao.getActiveWallet().first()
        assertNull(active)
    }

    @Test
    fun `getAllWallets returns wallets ordered by createdAt`() = runTest {
        walletDao.insert(createWallet(id = "w1").copy(createdAt = 100))
        walletDao.insert(createWallet(id = "w2").copy(createdAt = 200))
        walletDao.insert(createWallet(id = "w3").copy(createdAt = 50))

        val all = walletDao.getAllWallets().first()
        assertEquals(3, all.size)
        assertEquals("w3", all[0].walletId)
        assertEquals("w1", all[1].walletId)
        assertEquals("w2", all[2].walletId)
    }

    @Test
    fun `deactivateAll and activate switches active wallet`() = runTest {
        walletDao.insert(createWallet(id = "w1", isActive = true))
        walletDao.insert(createWallet(id = "w2", isActive = false))

        walletDao.deactivateAll()
        walletDao.activate("w2")

        val active = walletDao.getActiveWallet().first()
        assertEquals("w2", active!!.walletId)
    }

    @Test
    fun `rename updates wallet name`() = runTest {
        walletDao.insert(createWallet(id = "w1", name = "Old Name"))
        walletDao.rename("w1", "New Name")

        val all = walletDao.getAllWallets().first()
        assertEquals("New Name", all[0].name)
    }

    @Test
    fun `delete removes wallet`() = runTest {
        walletDao.insert(createWallet(id = "w1"))
        walletDao.insert(createWallet(id = "w2"))

        walletDao.delete("w1")

        val count = walletDao.count()
        assertEquals(1, count)
    }

    @Test
    fun `getSubAccounts returns only children of parent`() = runTest {
        walletDao.insert(createWallet(id = "parent"))
        walletDao.insert(createWallet(id = "sub1", parentWalletId = "parent", accountIndex = 1))
        walletDao.insert(createWallet(id = "sub2", parentWalletId = "parent", accountIndex = 2))
        walletDao.insert(createWallet(id = "unrelated"))

        val subs = walletDao.getSubAccounts("parent").first()
        assertEquals(2, subs.size)
        assertEquals(1, subs[0].accountIndex)
        assertEquals(2, subs[1].accountIndex)
    }

    @Test
    fun `count returns total wallet count`() = runTest {
        assertEquals(0, walletDao.count())

        walletDao.insert(createWallet(id = "w1"))
        walletDao.insert(createWallet(id = "w2"))

        assertEquals(2, walletDao.count())
    }
}
