package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.data.gateway.models.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for DAO transaction direction detection logic.
 *
 * This mirrors the heuristic in GatewayRepository.getTransactions():
 *   - DAO output + empty headerDeps  → dao_deposit
 *   - DAO output + non-empty headerDeps → dao_withdraw
 *   - No DAO output + headerDeps >= 2  → dao_unlock
 *   - Otherwise → regular direction (in/out/self)
 *
 * Also verifies amount override: DAO deposit/withdraw use the DAO cell
 * capacity, not the net balance change (which would just be the fee).
 */
class DaoDirectionDetectionTest {

    private val userLock = Script(
        codeHash = Script.SECP256K1_CODE_HASH,
        hashType = "type",
        args = "0xabcdef1234567890abcdef1234567890abcdef12"
    )

    private val daoType = Script(
        codeHash = DaoConstants.DAO_CODE_HASH,
        hashType = DaoConstants.DAO_HASH_TYPE,
        args = DaoConstants.DAO_ARGS
    )

    private fun cellOutput(capacityHex: String, type: Script? = null) = CellOutput(
        capacity = capacityHex,
        lock = userLock,
        type = type
    )

    private fun cellInput(txHash: String, index: String = "0x0") = CellInput(
        since = "0x0",
        previousOutput = OutPoint(txHash = txHash, index = index)
    )

    /**
     * Replicates the detection logic from GatewayRepository.getTransactions()
     * so we can test it in isolation without JNI.
     */
    private fun detectDaoDirection(
        outputs: List<CellOutput>,
        headerDeps: List<String>,
        netChangeShannons: Long,
        outputInteractionCapacities: List<Long> = emptyList()
    ): Pair<String, Long> {
        val hasDaoOutput = outputs.any { it.type?.codeHash == DaoConstants.DAO_CODE_HASH }

        val regularDirection = when {
            netChangeShannons > 0 -> "in"
            netChangeShannons < 0 -> "out"
            else -> "self"
        }
        val regularAmount = if (netChangeShannons < 0) -netChangeShannons else netChangeShannons

        return if (hasDaoOutput) {
            val daoOutputCapacity = outputs
                .first { it.type?.codeHash == DaoConstants.DAO_CODE_HASH }
                .capacity.removePrefix("0x").toLong(16)
            if (headerDeps.isEmpty()) {
                "dao_deposit" to daoOutputCapacity
            } else {
                "dao_withdraw" to daoOutputCapacity
            }
        } else if (headerDeps.size >= 2) {
            val totalOutput = outputInteractionCapacities.sum()
            "dao_unlock" to totalOutput
        } else {
            regularDirection to regularAmount
        }
    }

    // ── Deposit detection ────────────────────────────────────────────

    @Test
    fun `deposit - DAO output with no header deps`() {
        // User deposits 1000 CKB: one DAO output + change output
        val outputs = listOf(
            cellOutput("0x174876e800", type = daoType),  // 1000 CKB DAO cell
            cellOutput("0xd18c2e2800")                    // change
        )
        val (direction, amount) = detectDaoDirection(
            outputs = outputs,
            headerDeps = emptyList(),
            netChangeShannons = -100_000L // fee only
        )
        assertEquals("dao_deposit", direction)
        assertEquals(100_000_000_000L, amount) // 1000 CKB, not the fee
    }

    @Test
    fun `deposit - amount is DAO cell capacity not net change`() {
        // 102 CKB minimum deposit
        val outputs = listOf(
            cellOutput("0x25ff7a600", type = daoType), // 102 CKB
            cellOutput("0x100")                         // tiny change
        )
        val (direction, amount) = detectDaoDirection(
            outputs = outputs,
            headerDeps = emptyList(),
            netChangeShannons = -100_000L
        )
        assertEquals("dao_deposit", direction)
        assertEquals(10_200_000_000L, amount) // 102 CKB
    }

    // ── Withdraw (phase 1) detection ─────────────────────────────────

    @Test
    fun `withdraw - DAO output with one header dep`() {
        // Phase 1 withdraw: consumes DAO deposit, creates new DAO cell
        // headerDeps = [deposit_block_hash]
        val outputs = listOf(
            cellOutput("0x174876e800", type = daoType), // same 1000 CKB capacity
            cellOutput("0xd18c2e2800")                   // change
        )
        val (direction, amount) = detectDaoDirection(
            outputs = outputs,
            headerDeps = listOf("0xdeposit_block_hash"),
            netChangeShannons = -100_000L
        )
        assertEquals("dao_withdraw", direction)
        assertEquals(100_000_000_000L, amount) // 1000 CKB
    }

    @Test
    fun `withdraw - multiple header deps still detected`() {
        val outputs = listOf(
            cellOutput("0x174876e800", type = daoType)
        )
        val (direction, _) = detectDaoDirection(
            outputs = outputs,
            headerDeps = listOf("0xhash1", "0xhash2"),
            netChangeShannons = -100_000L
        )
        // Has DAO output + headerDeps → withdraw (not unlock, because DAO output exists)
        assertEquals("dao_withdraw", direction)
    }

    // ── Unlock (phase 2) detection ───────────────────────────────────

    @Test
    fun `unlock - no DAO output with 2 header deps`() {
        // Phase 2 unlock: consumes DAO withdrawal cell, returns normal CKB
        // headerDeps = [deposit_block_hash, withdraw_block_hash]
        val outputs = listOf(
            cellOutput("0x17595fd400") // 1001 CKB (original + compensation)
        )
        // outputInteractionCapacities = what the user receives
        val (direction, amount) = detectDaoDirection(
            outputs = outputs,
            headerDeps = listOf("0xdeposit_hash", "0xwithdraw_hash"),
            netChangeShannons = 100_000_000L, // compensation
            outputInteractionCapacities = listOf(100_100_000_000L) // 1001 CKB
        )
        assertEquals("dao_unlock", direction)
        assertEquals(100_100_000_000L, amount) // total returned, not just compensation
    }

    @Test
    fun `unlock - amount is total output not net change`() {
        val outputs = listOf(
            cellOutput("0x100") // doesn't matter, detection uses interaction caps
        )
        val (direction, amount) = detectDaoDirection(
            outputs = outputs,
            headerDeps = listOf("0xh1", "0xh2"),
            netChangeShannons = 500_000L, // small compensation
            outputInteractionCapacities = listOf(100_000_000_000L, 500_000L) // 1000 CKB + dust
        )
        assertEquals("dao_unlock", direction)
        assertEquals(100_000_500_000L, amount) // total of all outputs to user
    }

    // ── Regular transactions (no DAO) ────────────────────────────────

    @Test
    fun `regular incoming - no DAO output no header deps`() {
        val outputs = listOf(cellOutput("0x174876e800"))
        val (direction, amount) = detectDaoDirection(
            outputs = outputs,
            headerDeps = emptyList(),
            netChangeShannons = 100_000_000_000L
        )
        assertEquals("in", direction)
        assertEquals(100_000_000_000L, amount)
    }

    @Test
    fun `regular outgoing - no DAO output no header deps`() {
        val outputs = listOf(cellOutput("0x100"))
        val (direction, amount) = detectDaoDirection(
            outputs = outputs,
            headerDeps = emptyList(),
            netChangeShannons = -50_000_000_000L
        )
        assertEquals("out", direction)
        assertEquals(50_000_000_000L, amount)
    }

    @Test
    fun `regular self transfer - net zero`() {
        val outputs = listOf(cellOutput("0x100"))
        val (direction, _) = detectDaoDirection(
            outputs = outputs,
            headerDeps = emptyList(),
            netChangeShannons = 0L
        )
        assertEquals("self", direction)
    }

    @Test
    fun `single header dep without DAO output is regular tx`() {
        // Edge case: a non-DAO transaction that happens to have 1 header dep
        // (unlikely but possible in theory)
        val outputs = listOf(cellOutput("0x100"))
        val (direction, _) = detectDaoDirection(
            outputs = outputs,
            headerDeps = listOf("0xsomehash"),
            netChangeShannons = 100_000L
        )
        // Only 1 headerDep + no DAO output → regular, not unlock
        assertEquals("in", direction)
    }

    // ── isDaoRelated flag ────────────────────────────────────────────

    @Test
    fun `isDaoRelated is true for deposit`() {
        val outputs = listOf(cellOutput("0x100", type = daoType))
        val hasDaoOutput = outputs.any { it.type?.codeHash == DaoConstants.DAO_CODE_HASH }
        val headerDeps = emptyList<String>()
        assertTrue(hasDaoOutput || headerDeps.size >= 2)
    }

    @Test
    fun `isDaoRelated is true for unlock`() {
        val outputs = listOf(cellOutput("0x100"))
        val hasDaoOutput = outputs.any { it.type?.codeHash == DaoConstants.DAO_CODE_HASH }
        val headerDeps = listOf("0xh1", "0xh2")
        assertTrue(hasDaoOutput || headerDeps.size >= 2)
    }

    @Test
    fun `isDaoRelated is false for regular tx`() {
        val outputs = listOf(cellOutput("0x100"))
        val hasDaoOutput = outputs.any { it.type?.codeHash == DaoConstants.DAO_CODE_HASH }
        val headerDeps = emptyList<String>()
        assertFalse(hasDaoOutput || headerDeps.size >= 2)
    }
}
