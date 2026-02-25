package com.rjnr.pocketnode.data.database.entity

import org.junit.Assert.*
import org.junit.Test

class DaoCellEntityTest {

    private fun makeDeposited() = DaoCellEntity(
        txHash = "0xdep123",
        index = "0x0",
        capacity = 10_200_000_000L,
        status = "DEPOSITED",
        depositBlockNumber = 100L,
        depositBlockHash = "0xblockhash1",
        depositEpochHex = "0x7080291000032",
        withdrawBlockNumber = null,
        withdrawBlockHash = null,
        withdrawEpochHex = null,
        compensation = 500_000L,
        unlockEpochHex = null,
        depositTimestamp = 1700000000000L,
        network = "MAINNET",
        lastUpdatedAt = System.currentTimeMillis()
    )

    @Test
    fun `deposited entity has correct fields`() {
        val entity = makeDeposited()

        assertEquals("0xdep123", entity.txHash)
        assertEquals("0x0", entity.index)
        assertEquals(10_200_000_000L, entity.capacity)
        assertEquals("DEPOSITED", entity.status)
        assertEquals(100L, entity.depositBlockNumber)
        assertNull(entity.withdrawBlockNumber)
        assertNull(entity.withdrawBlockHash)
        assertEquals(500_000L, entity.compensation)
    }

    @Test
    fun `locked entity has withdraw fields populated`() {
        val entity = makeDeposited().copy(
            status = "LOCKED",
            withdrawBlockNumber = 200L,
            withdrawBlockHash = "0xwithdrawhash",
            withdrawEpochHex = "0x70802d2000064",
            unlockEpochHex = "0x7080500000096"
        )

        assertEquals("LOCKED", entity.status)
        assertEquals(200L, entity.withdrawBlockNumber)
        assertEquals("0xwithdrawhash", entity.withdrawBlockHash)
        assertNotNull(entity.unlockEpochHex)
    }

    @Test
    fun `pending deposit has DEPOSITING status`() {
        val entity = DaoCellEntity(
            txHash = "0xpending",
            index = "0x0",
            capacity = 10_200_000_000L,
            status = "DEPOSITING",
            depositBlockNumber = 0L,
            depositBlockHash = "",
            depositEpochHex = null,
            withdrawBlockNumber = null,
            withdrawBlockHash = null,
            withdrawEpochHex = null,
            compensation = 0L,
            unlockEpochHex = null,
            depositTimestamp = System.currentTimeMillis(),
            network = "MAINNET",
            lastUpdatedAt = System.currentTimeMillis()
        )

        assertEquals("DEPOSITING", entity.status)
        assertEquals(0L, entity.depositBlockNumber)
        assertEquals(0L, entity.compensation)
    }
}
