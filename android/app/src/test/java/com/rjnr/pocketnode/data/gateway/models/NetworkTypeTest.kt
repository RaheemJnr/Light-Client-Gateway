package com.rjnr.pocketnode.data.gateway.models

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkTypeTest {

    @Test
    fun `MAINNET hrp is ckb`() {
        assertEquals("ckb", NetworkType.MAINNET.hrp)
    }

    @Test
    fun `TESTNET hrp is ckt`() {
        assertEquals("ckt", NetworkType.TESTNET.hrp)
    }

    @Test
    fun `MAINNET displayName is Mainnet`() {
        assertEquals("Mainnet", NetworkType.MAINNET.displayName)
    }

    @Test
    fun `TESTNET displayName is Testnet`() {
        assertEquals("Testnet", NetworkType.TESTNET.displayName)
    }
}
