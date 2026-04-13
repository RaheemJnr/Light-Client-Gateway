package com.rjnr.pocketnode.data.wallet

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KeyManagerMultiWalletTest {

    private lateinit var keyManager: KeyManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val mnemonicManager = MnemonicManager()
        keyManager = KeyManager(context, mnemonicManager)
    }

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
