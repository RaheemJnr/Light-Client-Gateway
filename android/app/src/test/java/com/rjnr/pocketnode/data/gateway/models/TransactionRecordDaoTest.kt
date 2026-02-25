package com.rjnr.pocketnode.data.gateway.models

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for TransactionRecord DAO direction helpers and formatted amount display.
 * Verifies that "dao_deposit", "dao_withdraw", "dao_unlock" directions produce
 * correct helper results, formatting, and don't false-match regular directions.
 */
class TransactionRecordDaoTest {

    private fun record(
        direction: String,
        balanceChange: String = "0x174876e800", // 100 CKB
        isDaoRelated: Boolean = false,
        confirmations: Int = 10
    ) = TransactionRecord(
        txHash = "0xabc",
        blockNumber = "0x100",
        blockHash = "0xhash",
        timestamp = 0L,
        balanceChange = balanceChange,
        direction = direction,
        fee = "0x0",
        confirmations = confirmations,
        isDaoRelated = isDaoRelated
    )

    // --- Direction helpers ---

    @Test
    fun `dao_deposit direction is detected correctly`() {
        val tx = record("dao_deposit")
        assertTrue(tx.isDaoDeposit())
        assertFalse(tx.isDaoWithdraw())
        assertFalse(tx.isDaoUnlock())
        assertTrue(tx.isDaoTransaction())
        assertFalse(tx.isIncoming())
        assertFalse(tx.isOutgoing())
        assertFalse(tx.isSelfTransfer())
    }

    @Test
    fun `dao_withdraw direction is detected correctly`() {
        val tx = record("dao_withdraw")
        assertFalse(tx.isDaoDeposit())
        assertTrue(tx.isDaoWithdraw())
        assertFalse(tx.isDaoUnlock())
        assertTrue(tx.isDaoTransaction())
        assertFalse(tx.isIncoming())
        assertFalse(tx.isOutgoing())
    }

    @Test
    fun `dao_unlock direction is detected correctly`() {
        val tx = record("dao_unlock")
        assertFalse(tx.isDaoDeposit())
        assertFalse(tx.isDaoWithdraw())
        assertTrue(tx.isDaoUnlock())
        assertTrue(tx.isDaoTransaction())
        assertFalse(tx.isIncoming())
        assertFalse(tx.isOutgoing())
    }

    @Test
    fun `regular directions are not DAO`() {
        assertFalse(record("in").isDaoTransaction())
        assertFalse(record("out").isDaoTransaction())
        assertFalse(record("self").isDaoTransaction())
    }

    @Test
    fun `isDaoTransaction true from isDaoRelated flag even with regular direction`() {
        // Legacy records that have isDaoRelated=true but old "out" direction
        val tx = record("out", isDaoRelated = true)
        assertTrue(tx.isDaoTransaction())
        assertFalse(tx.isDaoDeposit()) // still not a specific DAO type
    }

    @Test
    fun `isDaoTransaction true from direction prefix without flag`() {
        val tx = record("dao_deposit", isDaoRelated = false)
        assertTrue(tx.isDaoTransaction())
    }

    // --- Formatted amount ---

    @Test
    fun `dao_deposit shows negative sign`() {
        // 1000 CKB = 100_000_000_000 shannons = 0x174876e800
        val tx = record("dao_deposit", balanceChange = "0x174876e800")
        assertEquals("-1000.00 CKB", tx.formattedAmount())
    }

    @Test
    fun `dao_withdraw shows no sign`() {
        val tx = record("dao_withdraw", balanceChange = "0x174876e800")
        assertEquals("1000.00 CKB", tx.formattedAmount())
    }

    @Test
    fun `dao_unlock shows positive sign`() {
        val tx = record("dao_unlock", balanceChange = "0x174876e800")
        assertEquals("+1000.00 CKB", tx.formattedAmount())
    }

    @Test
    fun `regular in shows positive sign`() {
        val tx = record("in", balanceChange = "0x174876e800")
        assertEquals("+1000.00 CKB", tx.formattedAmount())
    }

    @Test
    fun `regular out shows negative sign`() {
        val tx = record("out", balanceChange = "0x174876e800")
        assertEquals("-1000.00 CKB", tx.formattedAmount())
    }

    @Test
    fun `self shows no sign`() {
        val tx = record("self", balanceChange = "0x174876e800")
        assertEquals("1000.00 CKB", tx.formattedAmount())
    }

    @Test
    fun `small DAO deposit uses more decimals`() {
        // 0.0005 CKB = 50_000 shannons = 0xc350
        val tx = record("dao_deposit", balanceChange = "0xc350")
        assertEquals("-0.0005 CKB", tx.formattedAmount())
    }

    @Test
    fun `tiny DAO unlock uses 8 decimals`() {
        // 0.00001 CKB = 1000 shannons = 0x3e8
        val tx = record("dao_unlock", balanceChange = "0x3e8")
        assertEquals("+0.00001000 CKB", tx.formattedAmount())
    }

    // --- balanceChangeAsCkb ---

    @Test
    fun `balanceChangeAsCkb converts 102 CKB correctly`() {
        // 102 CKB = 10_200_000_000 shannons = 0x25ff7a600
        val tx = record("dao_deposit", balanceChange = "0x25ff7a600")
        assertEquals(102.0, tx.balanceChangeAsCkb(), 0.001)
    }
}
