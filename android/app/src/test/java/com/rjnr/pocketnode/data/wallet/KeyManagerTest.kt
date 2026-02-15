package com.rjnr.pocketnode.data.wallet

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class KeyManagerTest {

    private lateinit var keyManager: KeyManager
    private lateinit var mnemonicManager: MnemonicManager

    private val testMnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        .split(" ")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        mnemonicManager = MnemonicManager()
        keyManager = KeyManager(context, mnemonicManager)
        // Use plain SharedPreferences for testing (EncryptedSharedPreferences needs real KeyStore)
        keyManager.testPrefs = context.getSharedPreferences("test_keys", Context.MODE_PRIVATE)
        keyManager.deleteWallet()
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
}
