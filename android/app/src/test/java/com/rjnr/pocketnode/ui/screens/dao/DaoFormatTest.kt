package com.rjnr.pocketnode.ui.screens.dao

import org.junit.Assert.assertEquals
import org.junit.Test

class DaoFormatTest {

    // -- formatCkb (2 decimal places, thousand separators) --

    @Test
    fun `formatCkb returns 0_00 for zero shannons`() {
        assertEquals("0.00", formatCkb(0L))
    }

    @Test
    fun `formatCkb returns 1_00 for one CKB`() {
        assertEquals("1.00", formatCkb(100_000_000L))
    }

    @Test
    fun `formatCkb returns 102_00 for min deposit`() {
        assertEquals("102.00", formatCkb(10_200_000_000L))
    }

    @Test
    fun `formatCkb truncates to 2 decimal places`() {
        // 123,456,789 shannons = 1.23456789 CKB → "1.23"
        assertEquals("1.23", formatCkb(123_456_789L))
    }

    @Test
    fun `formatCkb rounds sub-shannon amounts to zero`() {
        assertEquals("0.00", formatCkb(1L))
    }

    @Test
    fun `formatCkb handles large amounts with thousand separators`() {
        assertEquals("10,000.00", formatCkb(1_000_000_000_000L))
    }

    @Test
    fun `formatCkb rounds up at midpoint`() {
        assertEquals("1.01", formatCkb(100_500_000L))
    }

    // -- formatCkbFull (8 decimal places, thousand separators) --

    @Test
    fun `formatCkbFull returns 0_00000000 for zero shannons`() {
        assertEquals("0.00000000", formatCkbFull(0L))
    }

    @Test
    fun `formatCkbFull returns full precision`() {
        // 19413969478266 shannons = 194,139.69478266 CKB
        assertEquals("194,139.69478266", formatCkbFull(19_413_969_478_266L))
    }

    @Test
    fun `formatCkbFull shows thousand separators`() {
        // 1,000,000,000,000 shannons = 10,000 CKB
        assertEquals("10,000.00000000", formatCkbFull(1_000_000_000_000L))
    }
}
