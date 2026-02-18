package com.rjnr.pocketnode.util

import org.junit.Assert.*
import org.junit.Test

class TimeUtilsTest {

    @Test
    fun `formatBlockTimestamp returns dash for null`() {
        assertEquals("—", formatBlockTimestamp(null))
    }

    @Test
    fun `formatBlockTimestamp returns dash for empty string`() {
        assertEquals("—", formatBlockTimestamp(""))
    }

    @Test
    fun `formatBlockTimestamp returns dash for zero hex`() {
        assertEquals("—", formatBlockTimestamp("0x0"))
    }

    @Test
    fun `formatBlockTimestamp parses valid hex millis`() {
        // 0x18c8d0a7a00 = 1_700_000_000_000 ms = Nov 14 2023
        val result = formatBlockTimestamp("0x18c8d0a7a00")
        assertFalse("Should not contain 'Confirmed'", result.contains("Confirmed", ignoreCase = true))
        assertTrue("Should be non-blank", result.isNotBlank())
        assertNotEquals("—", result)
    }

    @Test
    fun `formatBlockTimestamp handles decimal string millis`() {
        val result = formatBlockTimestamp("1700000000000")
        assertNotEquals("—", result)
    }
}
