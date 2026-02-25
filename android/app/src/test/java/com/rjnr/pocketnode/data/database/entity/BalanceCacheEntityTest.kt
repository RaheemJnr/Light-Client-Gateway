package com.rjnr.pocketnode.data.database.entity

import org.junit.Assert.assertEquals
import org.junit.Test

class BalanceCacheEntityTest {

    @Test
    fun `toBalanceResponse maps fields correctly`() {
        val entity = BalanceCacheEntity(
            network = "MAINNET",
            address = "ckb1qz...",
            capacity = "0x174876e800",
            capacityCkb = "1000.0",
            blockNumber = "0x1a4",
            cachedAt = 1700000000000L
        )

        val response = entity.toBalanceResponse()

        assertEquals("ckb1qz...", response.address)
        assertEquals("0x174876e800", response.capacity)
        assertEquals("1000.0", response.capacityCkb)
        assertEquals("0x1a4", response.asOfBlock)
    }

    @Test
    fun `isFresh returns true within TTL`() {
        val entity = BalanceCacheEntity(
            network = "MAINNET",
            address = "ckb1qz...",
            capacity = "0x0",
            capacityCkb = "0.0",
            blockNumber = "0x0",
            cachedAt = System.currentTimeMillis() - 60_000 // 1 minute ago
        )
        assertEquals(true, entity.isFresh())
    }

    @Test
    fun `isFresh returns false after TTL`() {
        val entity = BalanceCacheEntity(
            network = "MAINNET",
            address = "ckb1qz...",
            capacity = "0x0",
            capacityCkb = "0.0",
            blockNumber = "0x0",
            cachedAt = System.currentTimeMillis() - 180_000 // 3 minutes ago
        )
        assertEquals(false, entity.isFresh())
    }
}
