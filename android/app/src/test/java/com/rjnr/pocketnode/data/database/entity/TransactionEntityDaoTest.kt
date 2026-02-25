package com.rjnr.pocketnode.data.database.entity

import org.junit.Assert.*
import org.junit.Test

/**
 * Verifies that TransactionEntity correctly round-trips DAO direction data.
 * When a TransactionRecord with direction "dao_deposit" is stored in Room
 * and loaded back, isDaoRelated must be derived from the direction prefix.
 */
class TransactionEntityDaoTest {

    private fun makeEntity(direction: String) = TransactionEntity(
        txHash = "0xabc",
        blockNumber = "0x100",
        blockHash = "0xhash",
        timestamp = 0L,
        balanceChange = "0x174876e800",
        direction = direction,
        fee = "0x0",
        confirmations = 10,
        blockTimestampHex = "0x18c8d0a7a00",
        network = "TESTNET",
        status = "CONFIRMED",
        isLocal = false,
        cachedAt = System.currentTimeMillis()
    )

    @Test
    fun `toTransactionRecord sets isDaoRelated for dao_deposit`() {
        val record = makeEntity("dao_deposit").toTransactionRecord()
        assertTrue(record.isDaoRelated)
        assertTrue(record.isDaoDeposit())
        assertTrue(record.isDaoTransaction())
    }

    @Test
    fun `toTransactionRecord sets isDaoRelated for dao_withdraw`() {
        val record = makeEntity("dao_withdraw").toTransactionRecord()
        assertTrue(record.isDaoRelated)
        assertTrue(record.isDaoWithdraw())
    }

    @Test
    fun `toTransactionRecord sets isDaoRelated for dao_unlock`() {
        val record = makeEntity("dao_unlock").toTransactionRecord()
        assertTrue(record.isDaoRelated)
        assertTrue(record.isDaoUnlock())
    }

    @Test
    fun `toTransactionRecord clears isDaoRelated for regular directions`() {
        assertFalse(makeEntity("in").toTransactionRecord().isDaoRelated)
        assertFalse(makeEntity("out").toTransactionRecord().isDaoRelated)
        assertFalse(makeEntity("self").toTransactionRecord().isDaoRelated)
    }

    @Test
    fun `fromTransactionRecord preserves dao direction`() {
        val entity = TransactionEntity.fromTransactionRecord(
            txHash = "0xdep123",
            blockNumber = "0x100",
            blockHash = "0xhash",
            timestamp = 0L,
            balanceChange = "0x174876e800",
            direction = "dao_deposit",
            fee = "0x0",
            confirmations = 5,
            blockTimestampHex = null,
            network = "MAINNET"
        )
        assertEquals("dao_deposit", entity.direction)
        // Round-trip
        val record = entity.toTransactionRecord()
        assertTrue(record.isDaoDeposit())
        assertTrue(record.isDaoRelated)
        assertEquals("-1000.00 CKB", record.formattedAmount())
    }
}
