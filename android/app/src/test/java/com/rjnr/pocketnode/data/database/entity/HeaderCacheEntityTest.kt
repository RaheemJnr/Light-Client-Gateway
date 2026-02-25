package com.rjnr.pocketnode.data.database.entity

import com.rjnr.pocketnode.data.gateway.models.JniHeaderView
import org.junit.Assert.*
import org.junit.Test

class HeaderCacheEntityTest {

    private fun makeHeader() = JniHeaderView(
        hash = "0xabc123",
        number = "0x1a4",
        epoch = "0x7080291000032",
        timestamp = "0x18c8d0a7a00",
        parentHash = "0xparent",
        transactionsRoot = "0xtxroot",
        proposalsHash = "0xproposals",
        extraHash = "0xextra",
        dao = "0x40b4d9a3ddc9e730bdf4d7c1d303200020a89727c721f600005cda0fe6c50007",
        nonce = "0x0"
    )

    @Test
    fun `from JniHeaderView maps all fields`() {
        val entity = HeaderCacheEntity.from(makeHeader(), "MAINNET")

        assertEquals("0xabc123", entity.blockHash)
        assertEquals("0x1a4", entity.number)
        assertEquals("0x7080291000032", entity.epoch)
        assertEquals("0x18c8d0a7a00", entity.timestamp)
        assertEquals("0x40b4d9a3ddc9e730bdf4d7c1d303200020a89727c721f600005cda0fe6c50007", entity.dao)
        assertEquals("MAINNET", entity.network)
        assertTrue(entity.cachedAt > 0)
    }

    @Test
    fun `from preserves network correctly`() {
        val mainnet = HeaderCacheEntity.from(makeHeader(), "MAINNET")
        val testnet = HeaderCacheEntity.from(makeHeader(), "TESTNET")

        assertEquals("MAINNET", mainnet.network)
        assertEquals("TESTNET", testnet.network)
    }
}
