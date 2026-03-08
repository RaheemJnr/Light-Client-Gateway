package com.rjnr.pocketnode.data.export

import com.rjnr.pocketnode.data.database.entity.TransactionEntity
import org.junit.Assert.*
import org.junit.Test

class TransactionExporterTest {

    private val exporter = TransactionExporter()

    private fun createTx(
        hash: String = "0xabc123",
        timestamp: Long = 1709337600000L,
        direction: String = "in",
        balanceChange: String = "10000000000",
        fee: String = "100000",
        status: String = "CONFIRMED",
        blockNumber: String = "18500000",
        confirmations: Int = 1500
    ) = TransactionEntity(
        txHash = hash, blockNumber = blockNumber, blockHash = "0x",
        timestamp = timestamp, balanceChange = balanceChange, direction = direction,
        fee = fee, confirmations = confirmations, blockTimestampHex = null,
        network = "MAINNET", status = status, isLocal = false,
        cachedAt = timestamp, walletId = "wallet-1"
    )

    @Test
    fun `empty list produces header only`() {
        val csv = exporter.exportToCsv(emptyList())
        assertEquals("Date,Transaction Hash,Direction,Amount (CKB),Fee (CKB),Status,Block Number,Confirmations", csv)
    }

    @Test
    fun `single transaction formats correctly`() {
        val csv = exporter.exportToCsv(listOf(createTx()))
        val lines = csv.lines()
        assertEquals(2, lines.size)
        assertTrue(lines[1].contains("0xabc123"))
        assertTrue(lines[1].contains("in"))
        assertTrue(lines[1].contains("100.00000000"))
        assertTrue(lines[1].contains("CONFIRMED"))
    }

    @Test
    fun `fee formats as CKB`() {
        val csv = exporter.exportToCsv(listOf(createTx(fee = "100000")))
        assertTrue(csv.contains("0.00100000"))
    }

    @Test
    fun `multiple transactions produce correct row count`() {
        val txs = listOf(createTx(hash = "0x1"), createTx(hash = "0x2"), createTx(hash = "0x3"))
        val lines = exporter.exportToCsv(txs).lines()
        assertEquals(4, lines.size)
    }
}
