package com.rjnr.pocketnode.data.database.entity

import org.junit.Assert.*
import org.junit.Test

class TransactionEntityTest {

    @Test
    fun `toTransactionRecord maps all fields correctly`() {
        val entity = TransactionEntity(
            txHash = "0xabc123",
            blockNumber = "0x1a4",
            blockHash = "0xdef456",
            timestamp = 1700000000000L,
            balanceChange = "0x174876e800",
            direction = "out",
            fee = "0x186a0",
            confirmations = 5,
            blockTimestampHex = "0x18c8d0a7a00",
            network = "MAINNET",
            status = "CONFIRMED",
            isLocal = false,
            cachedAt = 1700000000000L
        )

        val record = entity.toTransactionRecord()

        assertEquals("0xabc123", record.txHash)
        assertEquals("0x1a4", record.blockNumber)
        assertEquals("0xdef456", record.blockHash)
        assertEquals(1700000000000L, record.timestamp)
        assertEquals("0x174876e800", record.balanceChange)
        assertEquals("out", record.direction)
        assertEquals("0x186a0", record.fee)
        assertEquals(5, record.confirmations)
        assertEquals("0x18c8d0a7a00", record.blockTimestampHex)
    }

    @Test
    fun `toTransactionRecord handles null blockTimestampHex`() {
        val entity = TransactionEntity(
            txHash = "0xabc",
            blockNumber = "",
            blockHash = "",
            timestamp = 0L,
            balanceChange = "0x0",
            direction = "out",
            fee = "0x0",
            confirmations = 0,
            blockTimestampHex = null,
            network = "MAINNET",
            status = "PENDING",
            isLocal = true,
            cachedAt = 1700000000000L
        )

        val record = entity.toTransactionRecord()
        assertNull(record.blockTimestampHex)
        assertTrue(record.isPending())
    }

    @Test
    fun `fromTransactionRecord creates entity with CONFIRMED status when confirmations gt 0`() {
        val entity = TransactionEntity.fromTransactionRecord(
            txHash = "0xabc123",
            blockNumber = "0x1a4",
            blockHash = "0xdef456",
            timestamp = 1700000000000L,
            balanceChange = "0x174876e800",
            direction = "in",
            fee = "0x0",
            confirmations = 10,
            blockTimestampHex = "0x18c8d0a7a00",
            network = "MAINNET"
        )

        assertEquals("CONFIRMED", entity.status)
        assertFalse(entity.isLocal)
    }

    @Test
    fun `fromTransactionRecord creates entity with PENDING status when confirmations is 0`() {
        val entity = TransactionEntity.fromTransactionRecord(
            txHash = "0xabc123",
            blockNumber = "",
            blockHash = "",
            timestamp = 0L,
            balanceChange = "0x0",
            direction = "out",
            fee = "0x186a0",
            confirmations = 0,
            blockTimestampHex = null,
            network = "TESTNET"
        )

        assertEquals("PENDING", entity.status)
        assertFalse(entity.isLocal) // fromTransactionRecord always sets isLocal=false (JNI source)
    }
}
