package com.rjnr.pocketnode.util

import org.junit.Assert.*
import org.junit.Test

class ExtensionsTest {

    // --- ByteArray.toHex() ---

    @Test
    fun `toHex converts empty array to empty string`() {
        assertEquals("", byteArrayOf().toHex())
    }

    @Test
    fun `toHex converts single byte`() {
        assertEquals("ff", byteArrayOf(0xFF.toByte()).toHex())
    }

    @Test
    fun `toHex pads single-digit hex with leading zero`() {
        assertEquals("0a", byteArrayOf(0x0A).toHex())
    }

    @Test
    fun `toHex converts multi-byte array`() {
        assertEquals("deadbeef", byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()).toHex())
    }

    // --- String.hexToBytes() ---

    @Test
    fun `hexToBytes converts lowercase hex`() {
        assertArrayEquals(byteArrayOf(0xDE.toByte(), 0xAD.toByte()), "dead".hexToBytes())
    }

    @Test
    fun `hexToBytes strips 0x prefix`() {
        assertArrayEquals(byteArrayOf(0xAB.toByte()), "0xab".hexToBytes())
    }

    @Test
    fun `hexToBytes converts empty string after prefix strip`() {
        assertArrayEquals(byteArrayOf(), "0x".hexToBytes())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `hexToBytes rejects odd-length string`() {
        "abc".hexToBytes()
    }

    @Test
    fun `hexToBytes round-trips with toHex`() {
        val original = byteArrayOf(0x01, 0x23, 0x45, 0x67, 0x89.toByte(), 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte())
        assertArrayEquals(original, original.toHex().hexToBytes())
    }

    // --- Long.toHex() ---

    @Test
    fun `toHex formats zero`() {
        assertEquals("0x0", 0L.toHex())
    }

    @Test
    fun `toHex formats positive number`() {
        assertEquals("0x3e8", 1000L.toHex())
    }

    @Test
    fun `toHex formats large number`() {
        assertEquals("0x2540be400", 10_000_000_000L.toHex())
    }

    // --- Long.toLittleEndianBytes() ---

    @Test
    fun `toLittleEndianBytes encodes zero as 8 bytes`() {
        assertArrayEquals(ByteArray(8), 0L.toLittleEndianBytes())
    }

    @Test
    fun `toLittleEndianBytes encodes 1 correctly`() {
        val expected = byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0)
        assertArrayEquals(expected, 1L.toLittleEndianBytes())
    }

    @Test
    fun `toLittleEndianBytes encodes 256 correctly`() {
        val expected = byteArrayOf(0, 1, 0, 0, 0, 0, 0, 0)
        assertArrayEquals(expected, 256L.toLittleEndianBytes())
    }

    @Test
    fun `toLittleEndianBytes with custom size 4`() {
        val expected = byteArrayOf(0x39, 0x05, 0x00, 0x00)
        assertArrayEquals(expected, 1337L.toLittleEndianBytes(4))
    }

    // --- Int.toLittleEndianBytes() ---

    @Test
    fun `Int toLittleEndianBytes encodes zero`() {
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), 0.toLittleEndianBytes())
    }

    @Test
    fun `Int toLittleEndianBytes encodes 1`() {
        assertArrayEquals(byteArrayOf(1, 0, 0, 0), 1.toLittleEndianBytes())
    }

    @Test
    fun `Int toLittleEndianBytes encodes 0x01020304`() {
        val expected = byteArrayOf(0x04, 0x03, 0x02, 0x01)
        assertArrayEquals(expected, 0x01020304.toLittleEndianBytes())
    }
}
