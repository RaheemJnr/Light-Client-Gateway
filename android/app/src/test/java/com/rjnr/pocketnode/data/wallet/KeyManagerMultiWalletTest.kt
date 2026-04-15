package com.rjnr.pocketnode.data.wallet

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.crypto.KeystoreEncryptionManager
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.MIGRATION_1_2
import com.rjnr.pocketnode.data.database.MIGRATION_2_3
import com.rjnr.pocketnode.data.database.MIGRATION_3_4
import com.rjnr.pocketnode.data.database.MIGRATION_4_5
import com.rjnr.pocketnode.data.migration.KeyStoreMigrationHelper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KeyManagerMultiWalletTest {

    private lateinit var keyManager: KeyManager
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .allowMainThreadQueries()
            .build()
        val encryptionManager = KeystoreEncryptionManager.createForTest()
        val migrationPrefs = context.getSharedPreferences("test_multi_migration", Context.MODE_PRIVATE)
        migrationPrefs.edit().clear().commit()
        val migrationHelper = KeyStoreMigrationHelper(db.keyMaterialDao(), encryptionManager, migrationPrefs)

        val mnemonicManager = MnemonicManager()
        keyManager = KeyManager(context, mnemonicManager)
        keyManager.keyStoreMigrationHelper = migrationHelper
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun `storeKeysForWallet and getPrivateKeyForWallet are isolated per wallet`() {
        val key1 = ByteArray(32) { 0x01 }
        val key2 = ByteArray(32) { 0x02 }

        keyManager.storeKeysForWallet("wallet-1", key1, null)
        keyManager.storeKeysForWallet("wallet-2", key2, null)

        assertArrayEquals(key1, keyManager.getPrivateKeyForWallet("wallet-1"))
        assertArrayEquals(key2, keyManager.getPrivateKeyForWallet("wallet-2"))
    }

    @Test
    fun `deleteWalletKeys removes only target wallet`() {
        val key1 = ByteArray(32) { 0x01 }
        val key2 = ByteArray(32) { 0x02 }

        keyManager.storeKeysForWallet("wallet-1", key1, null)
        keyManager.storeKeysForWallet("wallet-2", key2, null)

        keyManager.deleteWalletKeys("wallet-1")

        assertNull(keyManager.getPrivateKeyForWallet("wallet-1"))
        assertNotNull(keyManager.getPrivateKeyForWallet("wallet-2"))
    }

    @Test
    fun `getMnemonicForWallet returns stored mnemonic`() {
        val key = ByteArray(32) { 0x01 }
        val mnemonic = listOf("abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
            "abandon", "abandon", "abandon", "abandon", "abandon", "about")

        keyManager.storeKeysForWallet("wallet-1", key, mnemonic)

        assertEquals(mnemonic, keyManager.getMnemonicForWallet("wallet-1"))
    }

    @Test
    fun `getMnemonicForWallet returns null for raw key wallet`() {
        val key = ByteArray(32) { 0x01 }
        keyManager.storeKeysForWallet("wallet-1", key, null)

        assertNull(keyManager.getMnemonicForWallet("wallet-1"))
    }
}
