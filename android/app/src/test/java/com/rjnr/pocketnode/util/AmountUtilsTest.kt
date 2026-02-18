package com.rjnr.pocketnode.util

import org.junit.Assert.*
import org.junit.Test

class AmountUtilsTest {

    @Test
    fun `sanitizeAmount passes through valid 8-decimal input`() {
        assertEquals("100.12345678", sanitizeAmount("100.12345678"))
    }

    @Test
    fun `sanitizeAmount truncates beyond 8 decimal places`() {
        assertEquals("100.12345678", sanitizeAmount("100.123456789"))
        assertEquals("0.00000001", sanitizeAmount("0.000000019999"))
    }

    @Test
    fun `sanitizeAmount passes whole numbers`() {
        assertEquals("100", sanitizeAmount("100"))
        assertEquals("61", sanitizeAmount("61"))
    }

    @Test
    fun `sanitizeAmount passes partial decimal input`() {
        assertEquals("100.", sanitizeAmount("100."))
        assertEquals("100.1", sanitizeAmount("100.1"))
    }

    @Test
    fun `sanitizeAmount rejects non-numeric input`() {
        assertNull(sanitizeAmount("abc"))
        assertNull(sanitizeAmount("100.1.2"))
    }

    @Test
    fun `sanitizeAmount accepts empty string`() {
        assertEquals("", sanitizeAmount(""))
    }
}
