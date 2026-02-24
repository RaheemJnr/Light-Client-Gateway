package com.rjnr.pocketnode.ui.screens.dao

import org.junit.Assert.assertEquals
import org.junit.Test

class DaoFormatTest {

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
    fun `formatCkb handles large amounts`() {
        assertEquals("10000.00", formatCkb(1_000_000_000_000L))
    }

    @Test
    fun `formatCkb rounds up at midpoint`() {
        // 1.005 CKB = 100,500,000 shannons → should round to "1.01" (banker rounding may vary)
        // Actually String.format uses half-up rounding
        assertEquals("1.01", formatCkb(100_500_000L))
    }
}
