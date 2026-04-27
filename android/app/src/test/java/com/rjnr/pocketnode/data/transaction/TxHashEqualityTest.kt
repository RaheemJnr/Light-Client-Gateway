package com.rjnr.pocketnode.data.transaction

import com.rjnr.pocketnode.data.gateway.models.CellInput
import com.rjnr.pocketnode.data.gateway.models.CellOutput
import com.rjnr.pocketnode.data.gateway.models.OutPoint
import com.rjnr.pocketnode.data.gateway.models.Script
import com.rjnr.pocketnode.data.gateway.models.Transaction
import com.rjnr.pocketnode.data.validation.NetworkValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TxHashEqualityTest {

    private val builder = TransactionBuilder(
        networkValidator = NetworkValidator()
    )

    private fun sampleTx(): Transaction = Transaction(
        cellDeps = emptyList(),
        cellInputs = listOf(
            CellInput(
                previousOutput = OutPoint(
                    txHash = "0x" + "11".repeat(32),
                    index = "0x0"
                )
            )
        ),
        cellOutputs = listOf(
            CellOutput(
                capacity = "0x" + 61_00000000L.toString(16),
                lock = Script(
                    codeHash = Script.SECP256K1_CODE_HASH,
                    hashType = "type",
                    args = "0x" + "22".repeat(20)
                )
            )
        ),
        outputsData = listOf("0x"),
        witnesses = listOf("0x")
    )

    @Test
    fun `computeTxHash returns 0x-prefixed 64-char lowercase hex`() {
        val hash = builder.computeTxHash(sampleTx())
        assertTrue("expected 0x prefix, got $hash", hash.startsWith("0x"))
        assertEquals(66, hash.length)
        assertEquals(hash, hash.lowercase())
    }

    @Test
    fun `computeTxHash is deterministic across calls`() {
        val tx = sampleTx()
        assertEquals(builder.computeTxHash(tx), builder.computeTxHash(tx))
    }

    @Test
    fun `computeTxHash ignores witnesses`() {
        val tx1 = sampleTx().copy(witnesses = listOf("0x"))
        val tx2 = sampleTx().copy(witnesses = listOf("0x" + "ff".repeat(65)))
        assertEquals(builder.computeTxHash(tx1), builder.computeTxHash(tx2))
    }
}
