package com.rjnr.pocketnode.data.migration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.dao.WalletDao
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.MnemonicManager
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WalletMigrationHelperTest {

    private lateinit var db: AppDatabase
    private lateinit var walletDao: WalletDao
    private lateinit var keyManager: KeyManager
    private lateinit var walletPreferences: WalletPreferences
    private lateinit var helper: WalletMigrationHelper

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        walletDao = db.walletDao()
        keyManager = KeyManager(context, MnemonicManager())
        walletPreferences = WalletPreferences(context)
        helper = WalletMigrationHelper(walletDao, keyManager, walletPreferences, db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `no-op when no legacy wallet exists`() = runTest {
        // No wallet in KeyManager
        helper.migrateIfNeeded()
        assertEquals(0, walletDao.count())
    }

    @Test
    fun `migrates existing single wallet`() = runTest {
        // Set up a legacy single wallet in KeyManager
        keyManager.generateWallet()
        val legacyInfo = keyManager.getWalletInfo()

        helper.migrateIfNeeded()

        // Should have created one wallet entity
        assertEquals(1, walletDao.count())

        val activeWalletId = walletPreferences.getActiveWalletId()
        assertNotNull(activeWalletId)

        val wallet = walletDao.getById(activeWalletId!!)
        assertNotNull(wallet)
        assertEquals("Primary Wallet", wallet!!.name)
        assertEquals("raw_key", wallet.type)
        assertTrue(wallet.isActive)
        assertEquals(legacyInfo.mainnetAddress, wallet.mainnetAddress)
        assertEquals(legacyInfo.testnetAddress, wallet.testnetAddress)

        // Keys should be copied to wallet-scoped ESP
        val scopedKey = keyManager.getPrivateKeyForWallet(activeWalletId)
        assertNotNull(scopedKey)
    }

    @Test
    fun `idempotent - no-op when already migrated`() = runTest {
        keyManager.generateWallet()

        helper.migrateIfNeeded()
        val firstWalletId = walletPreferences.getActiveWalletId()
        assertEquals(1, walletDao.count())

        // Run again
        helper.migrateIfNeeded()
        assertEquals(1, walletDao.count())
        assertEquals(firstWalletId, walletPreferences.getActiveWalletId())
    }

    @Test
    fun `migrates mnemonic wallet with derivation path`() = runTest {
        keyManager.generateWalletWithMnemonic()

        helper.migrateIfNeeded()

        val activeWalletId = walletPreferences.getActiveWalletId()!!
        val wallet = walletDao.getById(activeWalletId)!!
        assertEquals("mnemonic", wallet.type)
        assertEquals("m/44'/309'/0'/0/0", wallet.derivationPath)

        // Mnemonic should be copied
        val mnemonic = keyManager.getMnemonicForWallet(activeWalletId)
        assertNotNull(mnemonic)
        assertTrue(mnemonic!!.size >= 12)
    }
}
