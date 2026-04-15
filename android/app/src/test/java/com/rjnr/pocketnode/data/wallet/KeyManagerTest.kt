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
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class KeyManagerTest {

    private lateinit var keyManager: KeyManager
    private lateinit var mnemonicManager: MnemonicManager
    private lateinit var backupManager: KeyBackupManager
    private lateinit var db: AppDatabase
    private lateinit var migrationHelper: KeyStoreMigrationHelper

    private val testMnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        .split(" ")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        mnemonicManager = MnemonicManager()
        keyManager = KeyManager(context, mnemonicManager)
        // Use plain SharedPreferences for testing (EncryptedSharedPreferences needs real KeyStore)
        keyManager.testPrefs = context.getSharedPreferences("test_keys", Context.MODE_PRIVATE)
        val backupDir = File(context.cacheDir, "test_key_backups")
        backupDir.deleteRecursively()
        backupManager = KeyBackupManager(backupDir)
        backupManager.kdfIterations = 1000
        keyManager.keyBackupManager = backupManager

        // Set up Room-backed KeyStoreMigrationHelper
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .allowMainThreadQueries()
            .build()
        val encryptionManager = KeystoreEncryptionManager.createForTest()
        val migrationPrefs = context.getSharedPreferences("test_migration", Context.MODE_PRIVATE)
        migrationPrefs.edit().clear().commit()
        migrationHelper = KeyStoreMigrationHelper(db.keyMaterialDao(), encryptionManager, migrationPrefs)
        keyManager.keyStoreMigrationHelper = migrationHelper

        keyManager.deleteWallet()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // -- Mnemonic generation --

    @Test
    fun `generateWalletWithMnemonic returns 12 words and valid wallet info`() {
        val (info, words) = keyManager.generateWalletWithMnemonic()

        assertEquals(12, words.size)
        assertTrue(mnemonicManager.validateMnemonic(words))
        assertNotNull(info.publicKey)
        assertTrue(info.mainnetAddress.startsWith("ckb1"))
        assertTrue(info.testnetAddress.startsWith("ckt1"))
    }

    @Test
    fun `generateWalletWithMnemonic sets wallet type to mnemonic`() {
        keyManager.generateWalletWithMnemonic()
        assertEquals(KeyManager.WALLET_TYPE_MNEMONIC, keyManager.getWalletType())
    }

    @Test
    fun `generateWallet sets wallet type to raw_key`() {
        keyManager.generateWallet()
        assertEquals(KeyManager.WALLET_TYPE_RAW_KEY, keyManager.getWalletType())
    }

    // -- Mnemonic storage and retrieval --

    @Test
    fun `getMnemonic returns stored words after mnemonic generation`() {
        val (_, words) = keyManager.generateWalletWithMnemonic()
        val retrieved = keyManager.getMnemonic()
        assertEquals(words, retrieved)
    }

    @Test
    fun `getMnemonic returns null for raw key wallet`() {
        keyManager.generateWallet()
        assertNull(keyManager.getMnemonic())
    }

    // -- Mnemonic import --

    @Test
    fun `importWalletFromMnemonic produces valid wallet`() {
        val info = keyManager.importWalletFromMnemonic(testMnemonic)

        assertNotNull(info.publicKey)
        assertTrue(info.mainnetAddress.startsWith("ckb1"))
        assertEquals(KeyManager.WALLET_TYPE_MNEMONIC, keyManager.getWalletType())
    }

    @Test
    fun `importWalletFromMnemonic is deterministic`() {
        val info1 = keyManager.importWalletFromMnemonic(testMnemonic)
        keyManager.deleteWallet()
        val info2 = keyManager.importWalletFromMnemonic(testMnemonic)

        assertEquals(info1.publicKey, info2.publicKey)
        assertEquals(info1.mainnetAddress, info2.mainnetAddress)
    }

    @Test
    fun `importWalletFromMnemonic stores mnemonic`() {
        keyManager.importWalletFromMnemonic(testMnemonic)
        assertEquals(testMnemonic, keyManager.getMnemonic())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `importWalletFromMnemonic rejects invalid mnemonic`() {
        keyManager.importWalletFromMnemonic(listOf("invalid", "words", "here"))
    }

    // -- Backup status --

    @Test
    fun `hasMnemonicBackup is false after generation`() {
        keyManager.generateWalletWithMnemonic()
        assertFalse(keyManager.hasMnemonicBackup())
    }

    @Test
    fun `setMnemonicBackedUp updates status`() {
        keyManager.generateWalletWithMnemonic()
        assertFalse(keyManager.hasMnemonicBackup())

        keyManager.setMnemonicBackedUp(true)
        assertTrue(keyManager.hasMnemonicBackup())
    }

    // -- Delete --

    @Test
    fun `deleteWallet clears all stored data`() {
        keyManager.generateWalletWithMnemonic()
        assertTrue(keyManager.hasWallet())

        keyManager.deleteWallet()
        assertFalse(keyManager.hasWallet())
        assertNull(keyManager.getMnemonic())
        assertEquals(KeyManager.WALLET_TYPE_RAW_KEY, keyManager.getWalletType())
    }

    // -- Backward compatibility --

    @Test
    fun `existing generateWallet still works`() {
        val info = keyManager.generateWallet()
        assertTrue(keyManager.hasWallet())
        assertNotNull(info.publicKey)
        assertEquals(KeyManager.WALLET_TYPE_RAW_KEY, keyManager.getWalletType())
    }

    @Test
    fun `existing importWallet still works`() {
        val hex = "a".repeat(64)
        val info = keyManager.importWallet(hex)
        assertTrue(keyManager.hasWallet())
        assertNotNull(info.publicKey)
        assertEquals(KeyManager.WALLET_TYPE_RAW_KEY, keyManager.getWalletType())
    }

    // -- Backup dual-write --

    @Test
    fun `generateWalletWithMnemonic writes backup when session PIN available`() {
        keyManager.setSessionPin("123456".toCharArray())
        keyManager.generateWalletWithMnemonic()
        assertTrue(backupManager.hasAnyBackups())
    }

    @Test
    fun `generateWalletWithMnemonic skips backup when no session PIN`() {
        keyManager.generateWalletWithMnemonic()
        assertFalse(backupManager.hasAnyBackups())
    }

    @Test
    fun `deleteWallet removes backup`() {
        keyManager.setSessionPin("123456".toCharArray())
        keyManager.generateWalletWithMnemonic()
        assertTrue(backupManager.hasBackup("default"))
        keyManager.deleteWallet()
        assertFalse(backupManager.hasBackup("default"))
    }

    // -- Room storage verification --

    @Test
    fun `writes go to Room and are readable`() {
        keyManager.generateWalletWithMnemonic()
        // Verify data is in Room via the migration helper
        val data = kotlinx.coroutines.runBlocking { migrationHelper.readDecryptedKey("default") }
        assertNotNull(data)
        assertEquals(KeyManager.WALLET_TYPE_MNEMONIC, data!!.walletType)
        assertNotNull(data.mnemonic)
    }

    @Test
    fun `ESP fallback works when Room has no data`() {
        // Simulate a pre-migration user: keys exist only in ESP, not Room.
        // Write directly to the testPrefs (standing in for EncryptedSharedPreferences).
        val context = ApplicationProvider.getApplicationContext<Context>()
        val espPrefs = context.getSharedPreferences("test_keys", Context.MODE_PRIVATE)
        espPrefs.edit()
            .putString("private_key", "ab".repeat(32))
            .putString("wallet_type", KeyManager.WALLET_TYPE_MNEMONIC)
            .putString("mnemonic_words", testMnemonic.joinToString(" "))
            .putBoolean("mnemonic_backed_up", false)
            .commit()

        // Room is empty — reads should fall back to ESP
        assertTrue(keyManager.hasWallet())
        assertEquals(testMnemonic, keyManager.getMnemonic())
        assertEquals(KeyManager.WALLET_TYPE_MNEMONIC, keyManager.getWalletType())
        assertFalse(keyManager.hasMnemonicBackup())
    }
}
