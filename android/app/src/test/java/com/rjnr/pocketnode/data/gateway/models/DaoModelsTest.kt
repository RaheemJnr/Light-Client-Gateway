package com.rjnr.pocketnode.data.gateway.models

import org.junit.Assert.assertEquals
import org.junit.Test

class DaoModelsTest {

    // --- EpochInfo.fromHex ---

    @Test
    fun `fromHex parses standard epoch correctly`() {
        // epoch 1, index 24 (0x18), length 1800 (0x708)
        val packed = (1800L shl 40) or (24L shl 24) or 1L
        val epoch = EpochInfo.fromHex("0x${packed.toString(16)}")
        assertEquals(1L, epoch.number)
        assertEquals(24L, epoch.index)
        assertEquals(1800L, epoch.length)
    }

    @Test
    fun `fromHex parses zero epoch`() {
        val epoch = EpochInfo.fromHex("0x0")
        assertEquals(0L, epoch.number)
        assertEquals(0L, epoch.index)
        assertEquals(0L, epoch.length)
    }

    @Test
    fun `fromHex parses epoch number only`() {
        // number=100, index=0, length=0
        val epoch = EpochInfo.fromHex("0x64")
        assertEquals(100L, epoch.number)
        assertEquals(0L, epoch.index)
        assertEquals(0L, epoch.length)
    }

    @Test
    fun `fromHex parses large epoch number`() {
        // number=500, index=150, length=1800
        // packed: (1800 shl 40) or (150 shl 24) or 500
        val packed = (1800L shl 40) or (150L shl 24) or 500L
        val epoch = EpochInfo.fromHex("0x${packed.toString(16)}")
        assertEquals(500L, epoch.number)
        assertEquals(150L, epoch.index)
        assertEquals(1800L, epoch.length)
    }

    @Test
    fun `fromHex without 0x prefix works`() {
        val packed = (1800L shl 40) or (0L shl 24) or 10L
        val epoch = EpochInfo.fromHex(packed.toString(16))
        assertEquals(10L, epoch.number)
        assertEquals(0L, epoch.index)
        assertEquals(1800L, epoch.length)
    }

    // --- EpochInfo.value ---

    @Test
    fun `value computes fractional epoch correctly`() {
        val epoch = EpochInfo(number = 100, index = 50, length = 100)
        assertEquals(100.5, epoch.value, 0.001)
    }

    @Test
    fun `value at start of epoch is whole number`() {
        val epoch = EpochInfo(number = 42, index = 0, length = 1800)
        assertEquals(42.0, epoch.value, 0.001)
    }

    @Test
    fun `value near end of epoch`() {
        // index near length
        val epoch = EpochInfo(number = 42, index = 1799, length = 1800)
        assertEquals(42.999, epoch.value, 0.001)
    }

    // --- cyclePhaseFromProgress ---

    @Test
    fun `cyclePhaseFromProgress returns NORMAL for 0`() {
        assertEquals(CyclePhase.NORMAL, cyclePhaseFromProgress(0.0f))
    }

    @Test
    fun `cyclePhaseFromProgress returns NORMAL just below 80 percent`() {
        assertEquals(CyclePhase.NORMAL, cyclePhaseFromProgress(0.79f))
    }

    @Test
    fun `cyclePhaseFromProgress returns SUGGESTED at exactly 80 percent`() {
        assertEquals(CyclePhase.SUGGESTED, cyclePhaseFromProgress(0.80f))
    }

    @Test
    fun `cyclePhaseFromProgress returns SUGGESTED at 94 percent`() {
        assertEquals(CyclePhase.SUGGESTED, cyclePhaseFromProgress(0.94f))
    }

    @Test
    fun `cyclePhaseFromProgress returns ENDING at exactly 95 percent`() {
        assertEquals(CyclePhase.ENDING, cyclePhaseFromProgress(0.95f))
    }

    @Test
    fun `cyclePhaseFromProgress returns ENDING at 100 percent`() {
        assertEquals(CyclePhase.ENDING, cyclePhaseFromProgress(1.0f))
    }

    // --- determineDaoStatus ---

    @Test
    fun `determineDaoStatus returns UNLOCKING when pending unlock`() {
        val status = determineDaoStatus(
            isWithdrawingCell = true,
            hasPendingWithdraw = false,
            hasPendingUnlock = true,
            hasPendingDeposit = false,
            currentEpoch = EpochInfo(100, 0, 1800),
            unlockEpoch = EpochInfo(90, 0, 1800)
        )
        assertEquals(DaoCellStatus.UNLOCKING, status)
    }

    @Test
    fun `determineDaoStatus returns UNLOCKABLE when epoch reached`() {
        val status = determineDaoStatus(
            isWithdrawingCell = true,
            hasPendingWithdraw = false,
            hasPendingUnlock = false,
            hasPendingDeposit = false,
            currentEpoch = EpochInfo(200, 0, 1800),
            unlockEpoch = EpochInfo(180, 0, 1800)
        )
        assertEquals(DaoCellStatus.UNLOCKABLE, status)
    }

    @Test
    fun `determineDaoStatus returns UNLOCKABLE at exact epoch boundary`() {
        val epoch = EpochInfo(180, 500, 1800)
        val status = determineDaoStatus(
            isWithdrawingCell = true,
            hasPendingWithdraw = false,
            hasPendingUnlock = false,
            hasPendingDeposit = false,
            currentEpoch = epoch,
            unlockEpoch = epoch
        )
        assertEquals(DaoCellStatus.UNLOCKABLE, status)
    }

    @Test
    fun `determineDaoStatus returns LOCKED when epoch not reached`() {
        val status = determineDaoStatus(
            isWithdrawingCell = true,
            hasPendingWithdraw = false,
            hasPendingUnlock = false,
            hasPendingDeposit = false,
            currentEpoch = EpochInfo(100, 0, 1800),
            unlockEpoch = EpochInfo(180, 0, 1800)
        )
        assertEquals(DaoCellStatus.LOCKED, status)
    }

    @Test
    fun `determineDaoStatus returns LOCKED when withdrawing with null epochs`() {
        val status = determineDaoStatus(
            isWithdrawingCell = true,
            hasPendingWithdraw = false,
            hasPendingUnlock = false,
            hasPendingDeposit = false,
            currentEpoch = null,
            unlockEpoch = null
        )
        assertEquals(DaoCellStatus.LOCKED, status)
    }

    @Test
    fun `determineDaoStatus returns WITHDRAWING when pending withdraw`() {
        val status = determineDaoStatus(
            isWithdrawingCell = false,
            hasPendingWithdraw = true,
            hasPendingUnlock = false,
            hasPendingDeposit = false,
            currentEpoch = null,
            unlockEpoch = null
        )
        assertEquals(DaoCellStatus.WITHDRAWING, status)
    }

    @Test
    fun `determineDaoStatus returns DEPOSITING when pending deposit`() {
        val status = determineDaoStatus(
            isWithdrawingCell = false,
            hasPendingWithdraw = false,
            hasPendingUnlock = false,
            hasPendingDeposit = true,
            currentEpoch = null,
            unlockEpoch = null
        )
        assertEquals(DaoCellStatus.DEPOSITING, status)
    }

    @Test
    fun `determineDaoStatus returns DEPOSITED when no flags set`() {
        val status = determineDaoStatus(
            isWithdrawingCell = false,
            hasPendingWithdraw = false,
            hasPendingUnlock = false,
            hasPendingDeposit = false,
            currentEpoch = null,
            unlockEpoch = null
        )
        assertEquals(DaoCellStatus.DEPOSITED, status)
    }

    @Test
    fun `determineDaoStatus UNLOCKING has highest priority`() {
        // Even with withdrawing cell and unlock epoch reached, pending unlock wins
        val status = determineDaoStatus(
            isWithdrawingCell = true,
            hasPendingWithdraw = true,
            hasPendingUnlock = true,
            hasPendingDeposit = true,
            currentEpoch = EpochInfo(200, 0, 1800),
            unlockEpoch = EpochInfo(180, 0, 1800)
        )
        assertEquals(DaoCellStatus.UNLOCKING, status)
    }
}
