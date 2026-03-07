package com.rjnr.pocketnode.data.wallet

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.dao.WalletDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WalletRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var walletDao: WalletDao
    private lateinit var keyManager: KeyManager
    private lateinit var walletPreferences: WalletPreferences
    private lateinit var mnemonicManager: MnemonicManager
    private lateinit var repo: WalletRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        walletDao = db.walletDao()
        mnemonicManager = MnemonicManager()
        keyManager = KeyManager(context, mnemonicManager)
        walletPreferences = WalletPreferences(context)
        repo = WalletRepository(walletDao, keyManager, walletPreferences, mnemonicManager)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `createWallet inserts entity and sets active`() = runTest {
        val wallet = repo.createWallet("Test Wallet")

        assertEquals("Test Wallet", wallet.name)
        assertEquals("mnemonic", wallet.type)
        assertTrue(wallet.isActive)
        assertTrue(wallet.mainnetAddress.startsWith("ckb1"))
        assertTrue(wallet.testnetAddress.startsWith("ckt1"))
        assertEquals("m/44'/309'/0'/0/0", wallet.derivationPath)

        // Verify stored in DB
        val fromDb = walletDao.getById(wallet.walletId)
        assertNotNull(fromDb)
        assertEquals(wallet.walletId, fromDb!!.walletId)

        // Verify keys stored in ESP
        val storedKey = keyManager.getPrivateKeyForWallet(wallet.walletId)
        assertNotNull(storedKey)
        assertEquals(32, storedKey!!.size)

        // Verify active wallet pref
        assertEquals(wallet.walletId, walletPreferences.getActiveWalletId())
    }

    @Test
    fun `switchActiveWallet deactivates old and activates new`() = runTest {
        val wallet1 = repo.createWallet("Wallet 1")
        val wallet2 = repo.createWallet("Wallet 2")

        // Wallet 2 is active after creation
        assertFalse(walletDao.getById(wallet1.walletId)!!.isActive)
        assertTrue(walletDao.getById(wallet2.walletId)!!.isActive)

        // Switch back to wallet 1
        repo.switchActiveWallet(wallet1.walletId)
        assertTrue(walletDao.getById(wallet1.walletId)!!.isActive)
        assertFalse(walletDao.getById(wallet2.walletId)!!.isActive)
        assertEquals(wallet1.walletId, walletPreferences.getActiveWalletId())
    }

    @Test
    fun `deleteWallet removes entity and ESP keys`() = runTest {
        val wallet1 = repo.createWallet("Wallet 1")
        val wallet2 = repo.createWallet("Wallet 2")

        // wallet1 is no longer active, so we can delete it
        repo.deleteWallet(wallet1.walletId)

        assertNull(walletDao.getById(wallet1.walletId))
        assertNull(keyManager.getPrivateKeyForWallet(wallet1.walletId))

        // wallet2 should still exist
        assertNotNull(walletDao.getById(wallet2.walletId))
    }

    @Test
    fun `deleteWallet rejects active wallet`() = runTest {
        val wallet = repo.createWallet("Active Wallet")

        try {
            repo.deleteWallet(wallet.walletId)
            fail("Should have thrown")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("active"))
        }
    }

    @Test
    fun `importRawKey creates raw_key wallet`() = runTest {
        // Generate a deterministic key for testing
        val privateKeyHex = "a" .repeat(64) // 32 bytes of 0xaa

        val wallet = repo.importRawKey("Raw Key Wallet", privateKeyHex)

        assertEquals("Raw Key Wallet", wallet.name)
        assertEquals("raw_key", wallet.type)
        assertNull(wallet.derivationPath)
        assertTrue(wallet.isActive)
        assertTrue(wallet.mainnetAddress.startsWith("ckb1"))
    }

    @Test
    fun `createSubAccount derives from parent mnemonic`() = runTest {
        val parent = repo.createWallet("Parent")

        val sub = repo.createSubAccount(parent.walletId, "Sub Account")

        assertEquals("Sub Account", sub.name)
        assertEquals(parent.walletId, sub.parentWalletId)
        assertTrue(sub.accountIndex > 0)
        assertTrue(sub.isActive)
        // Sub-account should have different address than parent
        assertNotEquals(parent.mainnetAddress, sub.mainnetAddress)
    }

    @Test
    fun `only one wallet is active at a time`() = runTest {
        repo.createWallet("W1")
        repo.createWallet("W2")
        repo.createWallet("W3")

        val allWallets = walletDao.getAllWallets().first()
        val activeCount = allWallets.count { it.isActive }
        assertEquals(1, activeCount)
    }
}
