package com.rjnr.pocketnode.data.database.entity

import org.junit.Assert.*
import org.junit.Test

class WalletEntityTest {

    @Test
    fun `create wallet entity with all fields`() {
        val wallet = WalletEntity(
            walletId = "test-uuid-1234",
            name = "My Wallet",
            type = "mnemonic",
            derivationPath = "m/44'/309'/0'/0/0",
            parentWalletId = null,
            accountIndex = 0,
            mainnetAddress = "ckb1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqdtyq04tvp02wectaumxn0664yw2jd53lqk508kg",
            testnetAddress = "ckt1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqfqyerlanzmnkxtmd6wgr9ylkz0aalst2gq30ehv",
            isActive = true,
            createdAt = 1709337600000L
        )

        assertEquals("test-uuid-1234", wallet.walletId)
        assertEquals("mnemonic", wallet.type)
        assertNull(wallet.parentWalletId)
        assertTrue(wallet.isActive)
    }

    @Test
    fun `create HD sub-account entity`() {
        val subAccount = WalletEntity(
            walletId = "sub-uuid-5678",
            name = "Account #2",
            type = "mnemonic",
            derivationPath = "m/44'/309'/1'/0/0",
            parentWalletId = "parent-uuid-1234",
            accountIndex = 1,
            mainnetAddress = "ckb1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsq2example",
            testnetAddress = "ckt1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsq2example",
            isActive = false,
            createdAt = 1709337700000L
        )

        assertEquals("parent-uuid-1234", subAccount.parentWalletId)
        assertEquals(1, subAccount.accountIndex)
        assertEquals("m/44'/309'/1'/0/0", subAccount.derivationPath)
    }

    @Test
    fun `raw key wallet has no derivation path`() {
        val rawKeyWallet = WalletEntity(
            walletId = "raw-uuid",
            name = "Imported Key",
            type = "raw_key",
            derivationPath = null,
            parentWalletId = null,
            accountIndex = 0,
            mainnetAddress = "ckb1example",
            testnetAddress = "ckt1example",
            isActive = false,
            createdAt = 1709337800000L
        )

        assertNull(rawKeyWallet.derivationPath)
        assertEquals("raw_key", rawKeyWallet.type)
    }
}
