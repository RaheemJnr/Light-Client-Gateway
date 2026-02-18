package com.rjnr.pocketnode.data.gateway.models

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiModelsTest {

    // --- getCheckpoint ---

    @Test
    fun `getCheckpoint returns 18_300_000 for mainnet`() {
        assertEquals(18_300_000L, getCheckpoint(NetworkType.MAINNET))
    }

    @Test
    fun `getCheckpoint returns 0 for testnet`() {
        assertEquals(0L, getCheckpoint(NetworkType.TESTNET))
    }

    // --- NEW_WALLET ---

    @Test
    fun `NEW_WALLET uses real tip when provided`() {
        val fromBlock = SyncMode.NEW_WALLET.toFromBlock(tipHeight = 20_000_000L, network = NetworkType.MAINNET)
        assertEquals("20000000", fromBlock)
    }

    @Test
    fun `NEW_WALLET falls back to mainnet checkpoint when tip is zero`() {
        val fromBlock = SyncMode.NEW_WALLET.toFromBlock(tipHeight = 0L, network = NetworkType.MAINNET)
        assertEquals("18300000", fromBlock)
    }

    @Test
    fun `NEW_WALLET falls back to testnet checkpoint 0 when tip is zero`() {
        val fromBlock = SyncMode.NEW_WALLET.toFromBlock(tipHeight = 0L, network = NetworkType.TESTNET)
        assertEquals("0", fromBlock)
    }

    // --- RECENT ---

    @Test
    fun `RECENT on mainnet subtracts 200_000 from tip`() {
        val fromBlock = SyncMode.RECENT.toFromBlock(tipHeight = 18_500_000L, network = NetworkType.MAINNET)
        assertEquals("18300000", fromBlock)
    }

    @Test
    fun `RECENT on testnet with zero tip clamps to 0`() {
        // bestTip = checkpoint = 0; 0 - 200_000 < 0 → "0"
        val fromBlock = SyncMode.RECENT.toFromBlock(tipHeight = 0L, network = NetworkType.TESTNET)
        assertEquals("0", fromBlock)
    }

    @Test
    fun `RECENT on testnet with real tip subtracts 200_000`() {
        val fromBlock = SyncMode.RECENT.toFromBlock(tipHeight = 500_000L, network = NetworkType.TESTNET)
        assertEquals("300000", fromBlock)
    }

    // --- FULL_HISTORY ---

    @Test
    fun `FULL_HISTORY always returns 0 on mainnet`() {
        assertEquals("0", SyncMode.FULL_HISTORY.toFromBlock(network = NetworkType.MAINNET))
    }

    @Test
    fun `FULL_HISTORY always returns 0 on testnet`() {
        assertEquals("0", SyncMode.FULL_HISTORY.toFromBlock(network = NetworkType.TESTNET))
    }

    // --- CUSTOM ---

    @Test
    fun `CUSTOM returns specified height as string`() {
        val fromBlock = SyncMode.CUSTOM.toFromBlock(customBlockHeight = 5_000_000L, network = NetworkType.MAINNET)
        assertEquals("5000000", fromBlock)
    }

    @Test
    fun `CUSTOM with null height returns 0`() {
        val fromBlock = SyncMode.CUSTOM.toFromBlock(customBlockHeight = null, network = NetworkType.MAINNET)
        assertEquals("0", fromBlock)
    }
}
