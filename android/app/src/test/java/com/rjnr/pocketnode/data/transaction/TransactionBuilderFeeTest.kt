package com.rjnr.pocketnode.data.transaction

import com.rjnr.pocketnode.data.validation.NetworkValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TransactionBuilderFeeTest {

    private val builder = TransactionBuilder(NetworkValidator())

    @Test
    fun `estimateTransferFee returns positive fee for 1 input 1 output`() {
        val fee = builder.estimateTransferFee(1, 1)
        assertTrue("Fee should be positive", fee > 0)
    }

    @Test
    fun `estimateTransferFee returns at least MIN_FEE`() {
        val fee = builder.estimateTransferFee(1, 1)
        assertTrue("Fee should be at least 1000 shannons", fee >= 1_000L)
    }

    @Test
    fun `estimateTransferFee for small tx is clamped to MIN_FEE`() {
        // Small transactions (< ~1000 bytes) get clamped to MIN_FEE = 1000
        val fee = builder.estimateTransferFee(1, 2)
        assertEquals(1_000L, fee)
    }

    @Test
    fun `estimateTransferFee increases with many inputs`() {
        // Use enough inputs that the estimated size exceeds 1000 bytes,
        // so the fee rises above MIN_FEE and starts differentiating
        val feeSmall = builder.estimateTransferFee(10, 2)
        val feeLarge = builder.estimateTransferFee(20, 2)
        assertTrue("More inputs should mean higher fee for large txs", feeLarge > feeSmall)
    }

    @Test
    fun `estimateTransferFee stays below DEFAULT_FEE for typical transactions`() {
        // Typical DAO deposit: 1-3 inputs, 2 outputs
        val fee = builder.estimateTransferFee(2, 2)
        assertTrue(
            "Fee for typical tx should be below default 100k shannons",
            fee < TransactionBuilder.DEFAULT_FEE
        )
    }
}
